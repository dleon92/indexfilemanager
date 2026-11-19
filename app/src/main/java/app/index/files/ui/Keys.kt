package app.index.files.ui

import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Atalhos de teclado comuns às grades (fase 12). */
object Keys {

    /** Ctrl + "+"/"=" aumenta, Ctrl + "−" diminui as miniaturas. */
    fun zoom(e: KeyEvent, zoom: GridZoom): Boolean {
        if (!e.isCtrlPressed) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoom.step(+1)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoom.step(-1)
            else -> return false
        }
        return true
    }

    /**
     * Primeira seta com a lista sem foco: foca o primeiro item visível (dali em diante
     * as setas e o Enter são a navegação por foco normal do Android).
     */
    fun focusList(e: KeyEvent, rv: RecyclerView): Boolean {
        val arrow = e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!arrow || rv.hasFocus()) return false
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition()
        val v = lm.findViewByPosition(first) ?: return false
        return v.requestFocus()
    }
}
