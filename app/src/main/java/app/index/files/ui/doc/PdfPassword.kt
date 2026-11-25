package app.index.files.ui.doc

import android.annotation.SuppressLint
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions

/**
 * PDF com senha pelo renderizador do próprio Android: Android 15 ou mais novo, ou Android 11+
 * com o módulo do sistema atualizado (extensão S 13). Antes disso não há como passar a senha.
 */
object PdfPassword {
    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= 35 ||
            (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

    /** SecurityException = senha errada (ou cifra não suportada). */
    @SuppressLint("NewApi")
    fun open(fd: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
}
