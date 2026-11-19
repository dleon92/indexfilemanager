package app.index.files.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileOps
import app.index.files.data.Loc
import app.index.files.data.NotEditable
import app.index.files.data.TransferService
import app.index.files.data.drive.AuthRequired
import app.index.files.data.drive.DriveHttpError
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Itens copiados ou recortados esperando "Colar aqui". Só na memória (some ao fechar o app). */
object Clip {
    var from: Loc? = null
        private set
    var items: List<FileItem> = emptyList()
        private set
    var move = false
        private set

    val active get() = from != null && items.isNotEmpty()

    fun set(from: Loc, items: List<FileItem>, move: Boolean) {
        this.from = from
        this.items = items
        this.move = move
    }

    fun clear() {
        from = null
        items = emptyList()
    }
}

/**
 * Executa uma operação de [FileOps] numa thread própria com um diálogo de progresso
 * (cancelável) e a tela acesa. Ao terminar, mostra o resultado e chama [onDone] na main.
 */
object OpRunner {

    /** Diálogo de progresso aberto (escondido enquanto o app está bloqueado). */
    private var current: AlertDialog? = null
    private var locked = false

    /** Chamado pela tela de bloqueio: o diálogo é outra janela e ficaria por cima dela. */
    fun setLocked(on: Boolean) {
        locked = on
        current?.let { if (on) it.hide() else if (!it.isShowing) it.show() }
    }

    fun run(
        activity: MainActivity,
        title: Int,
        work: (FileOps) -> FileOps.Result,
        onDone: (FileOps.Result) -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null)
        val name = view.findViewById<TextView>(R.id.op_name)
        val bar = view.findViewById<ProgressBar>(R.id.op_bar)
        val detail = view.findViewById<TextView>(R.id.op_detail)

        val cur = java.util.concurrent.atomic.AtomicReference<Array<Any>?>(null)
        val ui = Runnable {
            val p = cur.get() ?: return@Runnable
            val n = p[0] as String
            val i = p[1] as Int
            val count = p[2] as Int
            val bytes = p[3] as Long
            val size = p[4] as Long
            name.text = n
            if (size > 0) {
                bar.isIndeterminate = false
                bar.progress = (bytes * 1000 / size).toInt().coerceIn(0, 1000)
                detail.text = activity.getString(
                    R.string.op_item_of_bytes, i + 1, count,
                    Formatter.formatShortFileSize(activity, bytes), Formatter.formatShortFileSize(activity, size),
                )
            } else {
                bar.isIndeterminate = true
                detail.text = activity.getString(R.string.op_item_of, i + 1, count)
            }
        }
        var lastPost = 0L
        val app = activity.applicationContext
        val ops = FileOps(activity) { n, i, count, bytes, size ->
            cur.set(arrayOf(n, i, count, bytes, size))
            // No máximo ~10 atualizações por segundo na tela.
            val now = System.currentTimeMillis()
            if (now - lastPost >= 100 || bytes == 0L) {
                lastPost = now
                Bg.main.post(ui)
            }
            // Notificação: só o andamento, sem nomes de arquivos.
            val pct = if (size > 0) (bytes * 100 / size).toInt() else -1
            TransferService.progress(app, app.getString(R.string.op_item_of, i + 1, count), pct)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        current = dialog
        if (locked) dialog.hide()
        // O botão não fecha o diálogo: só pede o cancelamento (o item atual termina ou é desfeito).
        val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        cancelBtn.setOnClickListener {
            ops.cancelled.set(true)
            it.isEnabled = false
        }
        // Serviço em primeiro plano: a operação continua com o app em segundo plano ou a tela apagada.
        TransferService.start(app, activity.getString(title)) {
            ops.cancelled.set(true)
            Bg.main.post { cancelBtn.isEnabled = false }
        }

        Thread({
            val res = work(ops)
            Bg.main.post {
                // App fora da tela: o resultado vai para uma notificação.
                val visible = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                TransferService.stop(app, if (visible) null else summary(app, res))
                if (current === dialog) current = null
                if (activity.isDestroyed) return@post
                runCatching { dialog.dismiss() }
                report(activity, res)
                onDone(res)
            }
        }, "file-op").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun summary(ctx: android.content.Context, res: FileOps.Result) = ctx.getString(
        when {
            res.cancelled -> R.string.transfer_cancelled
            res.failed > 0 -> R.string.transfer_failed
            else -> R.string.transfer_done
        },
    )

    private fun report(activity: MainActivity, res: FileOps.Result) {
        val e = res.firstError
        when {
            e is AuthRequired -> activity.askConnect()
            res.cancelled -> Toast.makeText(activity, R.string.op_cancelled, Toast.LENGTH_LONG).show()
            res.failed > 0 -> AlertDialog.Builder(activity)
                .setMessage(activity.resources.getQuantityString(R.plurals.op_failed, res.failed, res.failed, reason(activity, e)))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    fun reason(activity: MainActivity, e: Throwable?): String = activity.getString(
        when {
            e is NotEditable && e.message?.startsWith("atalho") == true -> R.string.err_shortcut
            e is NotEditable && e.message?.startsWith("documento") == true -> R.string.err_google_doc
            e is NotEditable && e.message?.startsWith("destino") == true -> R.string.err_into_itself
            e is SecurityException -> R.string.err_no_permission
            e is DriveHttpError && (e.code == 403 || e.code == 404) -> R.string.err_no_permission
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException -> R.string.err_network
            e is FileNotFoundException -> R.string.err_generic
            else -> R.string.err_generic
        },
    )

    /** Pede um nome (nova pasta / renomear). Para arquivo, já seleciona o nome sem a extensão. */
    fun askName(activity: MainActivity, title: Int, initial: String, isFile: Boolean, onName: (String) -> Unit) {
        val input = EditText(activity).apply {
            setSingleLine()
            hint = activity.getString(R.string.name_hint)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(initial)
            val dot = initial.lastIndexOf('.')
            if (isFile && dot > 0) setSelection(0, dot) else selectAll()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val d = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        val ok = {
            val name = input.text.toString().trim()
            if (!FileOps.validName(name)) {
                input.error = activity.getString(R.string.name_invalid)
            } else {
                d.dismiss()
                if (name != initial) onName(name)
            }
        }
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { ok() }
        input.setOnEditorActionListener { _, _, _ -> ok(); true }
        input.requestFocus()
    }
}
