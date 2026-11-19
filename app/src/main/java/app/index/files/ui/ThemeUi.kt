package app.index.files.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R

/** Seletor de tema (claro ou escuro) com prévia desenhada de cada um. */
object ThemeUi {

    /** Cores de um tema, lidas do estilo (sem aplicá-lo). */
    class Palette(ctx: Context, style: Int) {
        private val th = ContextThemeWrapper(ctx, style).theme
        private fun c(attr: Int): Int {
            val tv = TypedValue()
            th.resolveAttribute(attr, tv, true)
            return tv.data
        }
        val bg = c(R.attr.idxBg)
        val surface = c(R.attr.idxSurface)
        val toolbar = c(R.attr.idxToolbar)
        val navBar = c(R.attr.idxNavBar)
        val text = c(R.attr.idxText)
        val text2 = c(R.attr.idxTextSecondary)
        val accent = c(R.attr.idxAccent)
        val onAccent = c(R.attr.idxOnAccent)
        val selection = c(R.attr.idxSelection)
        val warning = c(R.attr.idxWarning)
    }

    /** Miniatura do app no tema: barra superior, grade com um item selecionado, botão e barra de navegação. */
    class Preview(ctx: Context, private val p: Palette) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val clip = Path()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 1.15f).toInt())
        }

        private fun fill(color: Int, l: Float, t: Float, rr: Float, b: Float, radius: Float = 0f) {
            paint.style = Paint.Style.FILL
            paint.color = color
            r.set(l, t, rr, b)
            if (radius > 0) canvasRef.drawRoundRect(r, radius, radius, paint) else canvasRef.drawRect(r, paint)
        }

        private lateinit var canvasRef: Canvas

        override fun onDraw(canvas: Canvas) {
            canvasRef = canvas
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = 10 * d
            clip.reset()
            clip.addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)

            fill(p.bg, 0f, 0f, w, h)
            // Barra superior com título.
            val tb = h * 0.15f
            fill(p.toolbar, 0f, 0f, w, tb)
            fill(p.text, w * 0.08f, tb * 0.42f, w * 0.48f, tb * 0.58f, 2 * d)
            fill(p.text2, w * 0.82f, tb * 0.36f, w * 0.90f, tb * 0.64f, 2 * d)

            // Grade 3×2 de miniaturas simuladas; a segunda está selecionada.
            val pad = w * 0.06f
            val gap = w * 0.035f
            val cols = 3
            val tile = (w - 2 * pad - (cols - 1) * gap) / cols
            val top0 = tb + pad
            for (row in 0 until 2) for (col in 0 until cols) {
                val l = pad + col * (tile + gap)
                val t = top0 + row * (tile + gap * 2.6f)
                fill(p.surface, l, t, l + tile, t + tile, 4 * d)
                // "Imagem": sol e morro em tom secundário.
                paint.color = p.text2
                paint.alpha = 110
                canvas.drawCircle(l + tile * 0.32f, t + tile * 0.34f, tile * 0.1f, paint)
                r.set(l + tile * 0.12f, t + tile * 0.62f, l + tile * 0.88f, t + tile * 0.8f)
                canvas.drawRoundRect(r, 2 * d, 2 * d, paint)
                paint.alpha = 255
                if (row == 0 && col == 1) {
                    fill(p.selection, l, t, l + tile, t + tile, 4 * d)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2 * d
                    paint.color = p.accent
                    r.set(l + d, t + d, l + tile - d, t + tile - d)
                    canvas.drawRoundRect(r, 4 * d, 4 * d, paint)
                }
                // Nome do arquivo.
                fill(if (row == 1 && col == 2) p.warning else p.text, l, t + tile + gap * 0.6f, l + tile * 0.75f, t + tile + gap * 0.6f + 3 * d, 1.5f * d)
            }

            // Barra de navegação e botão de destaque.
            val nb = h * 0.07f
            fill(p.navBar, 0f, h - nb, w, h)
            fill(p.text2, w * 0.4f, h - nb * 0.55f, w * 0.6f, h - nb * 0.4f, 2 * d)
            val bh = h * 0.1f
            val bt = h - nb - bh - pad * 0.6f
            fill(p.accent, w * 0.52f, bt, w - pad, bt + bh, bh / 2)
            fill(p.onAccent, w * 0.6f, bt + bh * 0.44f, w - pad - w * 0.08f, bt + bh * 0.56f, 2 * d)

            canvas.restore()
            // Contorno discreto (temas claros sobre diálogo claro).
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d
            paint.color = p.text2
            paint.alpha = 80
            r.set(d / 2, d / 2, w - d / 2, h - d / 2)
            canvas.drawRoundRect(r, rad, rad, paint)
            paint.alpha = 255
        }
    }

    /** Diálogo com a grade de temas; [onPick] recebe o id escolhido. */
    fun pick(ctx: Context, title: Int, themes: List<Themes.Theme>, current: String, onPick: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val accent = TypedValue().also { ctx.theme.resolveAttribute(R.attr.idxAccent, it, true) }.data
        val list = RecyclerView(ctx)
        val span = if (ctx.resources.configuration.screenWidthDp >= 600) 4 else 2
        list.layoutManager = GridLayoutManager(ctx, span)
        list.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        list.clipToPadding = false
        lateinit var dialog: AlertDialog

        class Holder(v: View) : RecyclerView.ViewHolder(v)
        list.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = themes.size

            @SuppressLint("SetTextI18n")
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                val card = LinearLayout(ctx)
                card.orientation = LinearLayout.VERTICAL
                val m = (6 * d).toInt()
                card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(m, m, m, m) }
                card.setPadding(m, m, m, m)
                return Holder(card)
            }

            override fun onBindViewHolder(h: Holder, pos: Int) {
                val t = themes[pos]
                val card = h.itemView as LinearLayout
                card.removeAllViews()
                card.addView(Preview(ctx, Palette(ctx, t.style)))
                card.addView(TextView(ctx).apply {
                    text = t.name
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setPadding(0, (6 * d).toInt(), 0, 0)
                    setTextColor(ctx.getColor(if (t.id == current) R.color.accent else R.color.text))
                    if (t.id == current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                card.background = if (t.id == current) {
                    GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setStroke((3 * d).toInt(), accent)
                    }
                } else {
                    null
                }
                card.contentDescription = t.name
                card.setOnClickListener {
                    dialog.dismiss()
                    if (t.id != current) onPick(t.id)
                }
            }
        }
        dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(list)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        themes.indexOfFirst { it.id == current }.takeIf { it > 0 }?.let { list.scrollToPosition(it) }
    }
}
