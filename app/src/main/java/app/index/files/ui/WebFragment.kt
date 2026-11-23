package app.index.files.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Roots
import app.index.files.security.AppLock
import app.index.files.web.WebService

/** "Acesso pelo computador": navegador do PC abre o armazenamento do celular pela rede Wi-Fi. */
class WebFragment : Fragment(R.layout.fragment_web) {

    private val listener: () -> Unit = { if (view != null) render() }

    private val lanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ctx = context ?: return@registerForActivityResult
        if (ok) toggle() else Toast.makeText(ctx, R.string.web_needs_lan, Toast.LENGTH_LONG).show()
    }

    /** Notificação do IndexWeb (com o botão Desligar): pedida uma vez ao ligar; recusar não impede de ligar. */
    private var notifAsked = false
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifAsked = true
        if (context != null) toggle()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.web_toggle).setOnClickListener { toggle() }
        view.findViewById<View>(R.id.row_web_password).setOnClickListener { askPassword(null) }
        view.findViewById<View>(R.id.row_web_all_files).setOnClickListener { openAllFiles() }
        WebService.addListener(listener)
        // Aberta pelo botão flutuante do IndexWeb: já segue o fluxo de ligar (pede o que faltar).
        if (savedInstanceState == null && arguments?.getBoolean(ARG_AUTO) == true && !WebService.running) {
            view.post { if (isAdded && !WebService.running) toggle() }
        }
    }

    override fun onDestroyView() {
        WebService.removeListener(listener)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.web_title)
        render()
    }

    private fun render() {
        val v = view ?: return
        val on = WebService.running
        val addrs = WebService.addresses()
        val status = v.findViewById<TextView>(R.id.web_status)
        val address = v.findViewById<TextView>(R.id.web_address)
        val hint = v.findViewById<TextView>(R.id.web_hint)
        status.setText(
            when {
                WebService.error -> R.string.web_status_error
                on && addrs.isEmpty() -> R.string.web_no_network
                on -> R.string.web_status_on
                else -> R.string.web_status_off
            },
        )
        if (on && addrs.isNotEmpty()) {
            address.text = (listOf(WebService.fixedHost) + addrs).joinToString("\n") { "http://$it:${WebService.port}" }
            address.visibility = View.VISIBLE
            hint.setText(R.string.web_hint_on)
        } else {
            address.visibility = View.GONE
            hint.setText(R.string.web_hint_off)
        }
        v.findViewById<Button>(R.id.web_toggle).setText(if (on) R.string.web_turn_off else R.string.web_turn_on)

        val pw = v.findViewById<View>(R.id.row_web_password)
        pw.findViewById<TextView>(R.id.title).setText(R.string.web_password)
        pw.findViewById<TextView>(R.id.summary).setText(if (WebService.hasPassword(requireContext())) R.string.web_password_set else R.string.web_password_none)

        val af = v.findViewById<View>(R.id.row_web_all_files)
        af.visibility = if (Roots.hasAllFiles()) View.GONE else View.VISIBLE
        af.findViewById<TextView>(R.id.title).setText(R.string.all_files_title)
        af.findViewById<TextView>(R.id.summary).setText(R.string.web_needs_all_files)
    }

    private fun toggle() {
        val ctx = requireContext()
        if (WebService.running) {
            WebService.stop(ctx)
            return
        }
        if (Build.VERSION.SDK_INT < 30 || !Roots.hasAllFiles()) {
            openAllFiles()
            return
        }
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(ctx, LAN) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLock.beginOwnFlow()
            lanPermission.launch(LAN)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            ContextCompat.checkSelfPermission(ctx, NOTIF) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLock.beginOwnFlow()
            notifPermission.launch(NOTIF)
            return
        }
        if (!WebService.hasPassword(ctx)) {
            askPassword { WebService.start(ctx) }
            return
        }
        WebService.start(ctx)
    }

    private fun openAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(requireContext(), R.string.web_needs_all_files, Toast.LENGTH_LONG).show()
            return
        }
        AppLock.beginOwnFlow()
        val pkg = requireContext().packageName
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    /** Define/troca a senha do acesso pelo navegador. [then] depois de salva. */
    private fun askPassword(then: (() -> Unit)?) {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(hint: Int) = EditText(ctx).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val a = field(R.string.web_password_new)
        val b = field(R.string.web_password_repeat)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(a)
            addView(b)
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.web_password)
            .setMessage(R.string.web_password_msg)
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = a.text.toString()
            val p2 = b.text.toString()
            when {
                p1.length < MIN -> a.error = getString(R.string.web_password_short, MIN)
                p1 != p2 -> b.error = getString(R.string.web_password_mismatch)
                else -> {
                    dlg.dismiss()
                    val app = ctx.applicationContext
                    Bg.run({ WebService.setPassword(app, p1.toCharArray()) }) {
                        if (view != null) render()
                        context?.let { Toast.makeText(it, R.string.web_password_saved, Toast.LENGTH_SHORT).show() }
                        then?.invoke()
                    }
                }
            }
        }
    }

    companion object {
        private const val MIN = 6
        private const val ARG_AUTO = "auto_start"

        fun newInstance(autoStart: Boolean) = WebFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_AUTO, autoStart) }
        }
        private const val LAN = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val NOTIF = "android.permission.POST_NOTIFICATIONS"
    }
}
