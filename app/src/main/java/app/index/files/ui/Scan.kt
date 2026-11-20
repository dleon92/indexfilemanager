package app.index.files.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import app.index.files.R
import app.index.files.data.Prefs
import app.index.files.security.AppLock
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Digitalizar documento com o scanner do Google Play services: detecta as bordas e corta, filtros
 * (inclusive tirar sombra), flash, várias páginas, importar da galeria. A câmera é dele: o Index
 * não pede permissão. Usado pelo menu da pasta e pelo atalho do ícone.
 */
object Scan {
    private const val TAG = "Index.Scan"

    fun start(act: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { sender ->
                if (act.isFinishing || act.isDestroyed) return@addOnSuccessListener
                AppLock.beginOwnFlow()
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "digitalizador indisponível", e)
                Toast.makeText(act.applicationContext, R.string.scan_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    /** Resultado do scanner, ou null se cancelou ou veio vazio. */
    fun result(code: Int, data: android.content.Intent?): GmsDocumentScanningResult? {
        if (code != Activity.RESULT_OK) return null
        val r = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null
        return r.takeIf { !it.pages.isNullOrEmpty() || it.pdf != null }
    }

    /**
     * Pergunta o formato (lembra a última escolha) e entrega os arquivos já com nome e tipo:
     * PDF com todas as páginas, JPG por página ou os dois.
     */
    fun askFormat(ctx: Context, r: GmsDocumentScanningResult, then: (List<Uri>, List<Pair<String, String>>) -> Unit) {
        val prefs = Prefs.get(ctx)
        val labels = arrayOf(ctx.getString(R.string.scan_as_pdf), ctx.getString(R.string.scan_as_images), ctx.getString(R.string.scan_as_both))
        var choice = prefs.scanFormat.coerceIn(0, 2)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.scan_save_title)
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.scanFormat = choice
                val (uris, named) = files(ctx, r, choice)
                then(uris, named)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun files(ctx: Context, r: GmsDocumentScanningResult, format: Int): Pair<List<Uri>, List<Pair<String, String>>> {
        val pages = r.pages.orEmpty().map { it.imageUri }
        val pdf = r.pdf?.uri
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        val base = ctx.getString(R.string.scan_name, stamp)
        val uris = ArrayList<Uri>()
        val named = ArrayList<Pair<String, String>>()
        if (format != 1 && pdf != null) {
            uris.add(pdf)
            named.add("$base.pdf" to "application/pdf")
        }
        if (format != 0 || pdf == null) {
            pages.forEachIndexed { i, u ->
                uris.add(u)
                val suffix = if (pages.size > 1) " - " + ctx.getString(R.string.scan_page, i + 1) else ""
                named.add("$base$suffix.jpg" to "image/jpeg")
            }
        }
        return uris to named
    }
}
