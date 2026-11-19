package app.index.files.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import app.index.files.Bg
import app.index.files.R
import app.index.files.security.AppLock
import app.index.files.security.Secret

/**
 * Autenticação com APIs da plataforma, sem androidx.biometric:
 * - bloqueio do celular: API 30+ BiometricPrompt (BIOMETRIC_STRONG | DEVICE_CREDENTIAL);
 *   API 29 BiometricPrompt.setDeviceCredentialAllowed; API 26–28 tela do KeyguardManager;
 * - senha própria: diálogo + PBKDF2 ([AppLock.verify]), com biometria opcional (API 28+).
 *
 * Criado como propriedade da Activity (registra o launcher da tela do Keyguard).
 */
class Auth(private val activity: MainActivity) {

    private var keyguardResult: ((Boolean) -> Unit)? = null
    private val keyguardLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            AppLock.promptActive = false
            val cb = keyguardResult
            keyguardResult = null
            cb?.invoke(r.resultCode == Activity.RESULT_OK)
        }

    /** Bloqueio do celular (PIN/padrão/senha do aparelho ou biometria). */
    fun device(onResult0: (Boolean) -> Unit) {
        if (AppLock.promptActive) return // já há um aberto
        AppLock.promptActive = true
        val onResult = { ok: Boolean ->
            AppLock.promptActive = false
            onResult0(ok)
        }
        val title = activity.getString(R.string.lock_prompt_title)
        when {
            Build.VERSION.SDK_INT >= 30 -> BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(CancellationSignal(), activity.mainExecutor, callback(onResult))
            Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION")
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setDeviceCredentialAllowed(true)
                .build()
                .authenticate(CancellationSignal(), activity.mainExecutor, callback(onResult))
            else -> {
                @Suppress("DEPRECATION")
                val intent = activity.getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent(title, null)
                if (intent == null) {
                    onResult(false)
                    return
                }
                keyguardResult = onResult
                AppLock.beginOwnFlow()
                keyguardLauncher.launch(intent)
            }
        }
    }

    /** Biometria para a senha própria (API 28+). [onFallback]: usar a senha. */
    fun biometric(onOk: () -> Unit, onFallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28 || AppLock.promptActive) {
            onFallback()
            return
        }
        AppLock.promptActive = true
        val b = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setNegativeButton(activity.getString(R.string.lock_use_password), activity.mainExecutor) { _, _ ->
                AppLock.promptActive = false
                onFallback()
            }
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
        b.build().authenticate(CancellationSignal(), activity.mainExecutor, callback { ok ->
            AppLock.promptActive = false
            if (ok) onOk() else onFallback()
        })
    }

    private fun callback(onResult: (Boolean) -> Unit) = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)

        // Cancelado, muitas tentativas, sem hardware… Erros pontuais (dedo não reconhecido)
        // são tratados pelo próprio prompt, que continua aberto.
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
    }

    /**
     * Confirma a identidade com o método atual (antes de alterar ou desativar a proteção).
     * Se o bloqueio do celular foi removido, exige criar uma senha própria.
     */
    fun confirmCurrent(onOk: () -> Unit) {
        if (!AppLock.enabled(activity)) {
            onOk()
            return
        }
        if (AppLock.method(activity) == AppLock.METHOD_DEVICE) {
            if (!AppLock.deviceSecure(activity)) {
                deviceLockRemoved(onOk)
                return
            }
            device { ok -> if (ok) onOk() }
            return
        }
        unlockWith(AppLock.secret(activity), onOk)
    }

    /** Senha própria (com biometria, se ativada para ela). */
    fun unlockWith(s: Secret, onOk: () -> Unit) {
        if (s.biometric && AppLock.biometricAvailable(activity)) {
            biometric(onOk) { passwordDialog(s, onOk) }
        } else {
            passwordDialog(s, onOk)
        }
    }

    /** O bloqueio do celular sumiu: a proteção não desliga, passa para senha própria. */
    fun deviceLockRemoved(onDone: () -> Unit) {
        setupPassword(
            note = activity.getString(R.string.lock_device_removed),
            cancelable = false,
            onDone = onDone,
        )
    }

    /** Diálogo "digite a senha atual". */
    fun passwordDialog(s: Secret, onOk: () -> Unit) {
        val input = EditText(activity).apply {
            inputType = inputTypeFor(s.kind)
            imeOptions = EditorInfo.IME_ACTION_DONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val box = FrameLayout(activity).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        val d = AlertDialog.Builder(activity)
            .setTitle(if (s.kind == AppLock.KIND_PIN) R.string.lock_current_pin else R.string.lock_current_password)
            .setView(box)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val submit = {
                val pw = input.text.toString().toCharArray()
                input.text.clear()
                ok.isEnabled = false
                val app = activity.applicationContext
                Bg.run({ s.verify(pw) }) { r ->
                    ok.isEnabled = true
                    if (r.getOrDefault(false)) {
                        d.dismiss()
                        onOk()
                    } else {
                        Toast.makeText(app, wrongMessage(s), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ok.setOnClickListener { submit() }
            input.setOnEditorActionListener { _, _, _ -> submit(); true }
            input.requestFocus()
        }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    /** "Senha incorreta" ou o tempo de espera, se as tentativas acabaram. */
    fun wrongMessage(s: Secret = AppLock.secret(activity)): String {
        val wait = s.waitRemainingMs()
        return if (wait > 0) activity.getString(R.string.lock_wait, formatWait(wait))
        else activity.getString(R.string.lock_wrong)
    }

    /**
     * Criar (ou trocar) a senha própria: tipo, senha e confirmação; depois pergunta se
     * quer usar a biometria também (se houver).
     */
    fun setupPassword(
        note: String? = null,
        cancelable: Boolean = true,
        onDone: () -> Unit,
        onCancel: () -> Unit = {},
        /** Grava a senha (fora da main). Padrão: senha do app. */
        save: (CharArray, Int) -> Unit = { pw, k -> AppLock.enableOwn(activity.applicationContext, pw, k) },
        /** Guarda a escolha da biometria. Padrão: senha do app. */
        setBiometric: (Boolean) -> Unit = { AppLock.setBiometric(activity, it) },
    ) {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_password_setup, null)
        val kindGroup = v.findViewById<RadioGroup>(R.id.setup_kind)
        val input = v.findViewById<EditText>(R.id.setup_input)
        val confirm = v.findViewById<EditText>(R.id.setup_confirm)
        val error = v.findViewById<TextView>(R.id.setup_error)
        v.findViewById<TextView>(R.id.setup_note).apply {
            text = note
            visibility = if (note == null) View.GONE else View.VISIBLE
        }
        fun kind() = if (kindGroup.checkedRadioButtonId == R.id.setup_password) AppLock.KIND_PASSWORD else AppLock.KIND_PIN
        kindGroup.setOnCheckedChangeListener { _, _ ->
            val k = kind()
            input.inputType = inputTypeFor(k)
            confirm.inputType = inputTypeFor(k)
            input.setHint(if (k == AppLock.KIND_PIN) R.string.lock_new_pin else R.string.lock_new_password)
            confirm.setHint(if (k == AppLock.KIND_PIN) R.string.lock_confirm_pin else R.string.lock_confirm_password)
            error.visibility = View.GONE
        }
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.lock_create_title)
            .setView(v)
            .setCancelable(cancelable)
            .setPositiveButton(R.string.save, null)
            .apply { if (cancelable) setNegativeButton(R.string.cancel) { _, _ -> onCancel() } }
            .setOnCancelListener { onCancel() }
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setOnShowListener {
            val save = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val submit = submit@{
                val k = kind()
                val a = input.text.toString()
                val b = confirm.text.toString()
                val problem = when {
                    k == AppLock.KIND_PIN && (a.length !in AppLock.PIN_MIN..AppLock.PIN_MAX || !a.all { it in '0'..'9' }) ->
                        activity.getString(R.string.lock_pin_rule, AppLock.PIN_MIN, AppLock.PIN_MAX)
                    k == AppLock.KIND_PASSWORD && a.length !in AppLock.PASSWORD_MIN..AppLock.PASSWORD_MAX ->
                        activity.getString(R.string.lock_password_rule, AppLock.PASSWORD_MIN)
                    a != b -> activity.getString(R.string.lock_mismatch)
                    else -> null
                }
                if (problem != null) {
                    error.text = problem
                    error.visibility = View.VISIBLE
                    return@submit
                }
                save.isEnabled = false
                val app = activity.applicationContext
                val pw = a.toCharArray()
                input.text.clear()
                confirm.text.clear()
                Bg.run({ save(pw, k) }) { r ->
                    d.dismiss()
                    if (r.isFailure) {
                        Toast.makeText(app, R.string.lock_save_failed, Toast.LENGTH_LONG).show()
                        onCancel()
                    } else {
                        askBiometric(setBiometric, onDone)
                    }
                }
            }
            save.setOnClickListener { submit() }
            confirm.setOnEditorActionListener { _, _, _ -> submit(); true }
            input.requestFocus()
        }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun askBiometric(setBiometric: (Boolean) -> Unit, onDone: () -> Unit) {
        if (!AppLock.biometricAvailable(activity)) {
            setBiometric(false)
            onDone()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.lock_biometric_title)
            .setMessage(R.string.lock_biometric_ask)
            .setCancelable(false)
            .setPositiveButton(R.string.lock_biometric_yes) { _, _ ->
                setBiometric(true)
                onDone()
            }
            .setNegativeButton(R.string.lock_biometric_no) { _, _ ->
                setBiometric(false)
                onDone()
            }
            .show()
    }

    companion object {
        fun inputTypeFor(kind: Int) = if (kind == AppLock.KIND_PIN) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        fun formatWait(ms: Long): String {
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
