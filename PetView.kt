package com.bichinho.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Desenha o painel preto com os olhos verdes e as linhas de status. */
class PetView(context: Context) : View(context) {

    companion object {
        const val W_DP = 180f
        const val H_DP = 150f
        private const val GREEN = 0xFF00FF66.toInt()
        private const val GREEN_DIM = 0xFF1FD18A.toInt()
        private const val RED = 0xFFFF4B2B.toInt()
    }

    var mood = Mood.NORMAL
    var l1 = ""
    var l2 = ""
    var l3 = ""
    var l4 = ""
    var speech: String? = null

    /** Nível da sua voz (0..1) e se o microfone está ligado. */
    var level = 0f
    var listening = false
    /** 0..1 durante a sessão "ensinar minha voz"; negativo = fora dela. */
    var teachProgress = -1f

    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN_DIM }

    private var lookX = 0f
    private var lookY = 0f
    private var tX = 0f
    private var tY = 0f
    private var blinkStart = 0L
    private var nextBlink = 0L

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050805.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9FFD3.toInt() }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val lidPath = Path()

    fun look(x: Float, y: Float) {
        tX = x.coerceIn(-1f, 1f)
        tY = y.coerceIn(-1f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(W_DP).toInt(), dp(H_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now >= nextBlink) {
            blinkStart = now
            nextBlink = now + Random.nextLong(2500L, 6000L)
        }
        val bt = now - blinkStart
        val blink = if (bt < 160) sin(PI * bt / 160.0).toFloat() else 0f

        lookX += (tX - lookX) * 0.15f
        lookY += (tY - lookY) * 0.15f

        val w = width.toFloat()
        val rr = RectF(dp(1f), dp(1f), w - dp(1f), height - dp(1f))
        canvas.drawRoundRect(rr, dp(14f), dp(14f), bgPaint)
        borderPaint.color = if (mood == Mood.GREVE || mood == Mood.IRRITADO) RED else GREEN
        canvas.drawRoundRect(rr, dp(14f), dp(14f), borderPaint)

        val baseOpen = when (mood) {
            Mood.SONOLENTO -> 0.35f
            Mood.ENTEDIADO -> 0.65f
            else -> 1f
        }
        val open = (baseOpen * (1f - blink)).coerceAtLeast(0.06f)
        val cy = dp(40f)
        drawEye(canvas, w / 2f - dp(30f), cy, true, open)
        drawEye(canvas, w / 2f + dp(30f), cy, false, open)

        drawMouth(canvas, w / 2f, dp(66f))

        // Ondas de voz (microfone ligado) ou progresso do aprendizado
        if (listening) {
            for (i in 0 until 5) {
                val bh = dp(3f) + level * dp(14f) * kotlin.math.abs(sin(now / 90.0 + i * 1.3)).toFloat()
                val bx = w - dp(14f) - i * dp(5f)
                canvas.drawRoundRect(RectF(bx - dp(2f), dp(26f) - bh, bx, dp(26f)), dp(1f), dp(1f), barPaint)
            }
        }
        if (teachProgress >= 0f) {
            val bar = RectF(dp(16f), height - dp(8f), w - dp(16f), height - dp(5f))
            barPaint.alpha = 90
            canvas.drawRoundRect(bar, dp(2f), dp(2f), barPaint)
            barPaint.alpha = 255
            canvas.drawRoundRect(
                RectF(bar.left, bar.top, bar.left + bar.width() * teachProgress, bar.bottom),
                dp(2f), dp(2f), barPaint
            )
        }

        // Linhas de status
        textPaint.color = GREEN
        textPaint.isFakeBoldText = true
        textPaint.textSize = dp(13f)
        canvas.drawText(l1, w / 2f, dp(82f), textPaint)

        textPaint.color = 0xFF5CFFD1.toInt()
        textPaint.textSize = dp(11f)
        canvas.drawText(l2, w / 2f, dp(97f), textPaint)

        textPaint.isFakeBoldText = false
        textPaint.color = GREEN_DIM
        textPaint.textSize = dp(7.5f)
        canvas.drawText(l3, w / 2f, dp(110f), textPaint)

        textPaint.textSize = dp(8.5f)
        canvas.drawText(l4, w / 2f, dp(123f), textPaint)

        speech?.let {
            textPaint.color = 0xFFFFD27A.toInt()
            textPaint.textSize = dp(9f)
            val n = textPaint.breakText(it, true, w - dp(16f), null)
            canvas.drawText(it.substring(0, n), w / 2f, dp(140f), textPaint)
        }

        if (mood == Mood.GREVE) {
            val b = RectF(dp(8f), dp(6f), dp(78f), dp(24f))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
            canvas.drawRoundRect(b, dp(3f), dp(3f), p)
            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.isFakeBoldText = true
            textPaint.textSize = dp(10f)
            canvas.drawText("EM GREVE", b.centerX(), b.centerY() + dp(3.5f), textPaint)
        }

        postInvalidateDelayed(40)
    }

    /** Boca: sorri, faz bico, fica reta ou abre conforme o volume da sua voz. */
    private fun drawMouth(c: Canvas, cx: Float, cy: Float) {
        val mw = dp(14f)
        mouthPaint.strokeWidth = dp(2.5f)
        if (level > 0.25f) {
            // "ouvindo" você falar: boca aberta em ovo, tamanho acompanha o volume
            val r = dp(2f) + level * dp(4f)
            c.drawOval(RectF(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.9f), mouthPaint)
            return
        }
        when (mood) {
            Mood.FELIZ ->
                c.drawArc(RectF(cx - mw, cy - dp(7f), cx + mw, cy + dp(5f)), 20f, 140f, false, mouthPaint)
            Mood.TRISTE, Mood.IRRITADO ->
                c.drawArc(RectF(cx - mw, cy - dp(1f), cx + mw, cy + dp(11f)), 200f, 140f, false, mouthPaint)
            Mood.GREVE -> {
                val p = Path()
                p.moveTo(cx - mw, cy + dp(2f))
                for (i in 1..6) p.lineTo(cx - mw + i * (2 * mw / 6f), cy + if (i % 2 == 1) dp(-1f) else dp(3f))
                c.drawPath(p, mouthPaint)
            }
            else -> c.drawLine(cx - dp(7f), cy + dp(2f), cx + dp(7f), cy + dp(2f), mouthPaint)
        }
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, left: Boolean, open: Float) {
        val ew = dp(40f)
        val eh = dp(32f) * open
        val px = cx + lookX * dp(5f)
        val py = cy + lookY * dp(4f)

        if (mood == Mood.FELIZ && open > 0.5f) {
            // olhos "^ ^"
            val r = RectF(px - ew / 2f, py - dp(8f), px + ew / 2f, py + dp(16f))
            c.drawArc(r, 200f, 140f, false, arcPaint)
            return
        }

        val rect = RectF(px - ew / 2f, py - eh / 2f, px + ew / 2f, py + eh / 2f)
        c.drawRoundRect(rect, dp(9f), dp(9f), eyePaint)
        if (open > 0.5f) c.drawCircle(px - ew / 4f, py - eh / 4f, dp(3f), glintPaint)

        // Pálpebras diagonais por cima do olho
        val pad = 1f
        val drop = eh * 0.5f
        when (mood) {
            Mood.IRRITADO, Mood.GREVE -> {
                lidPath.reset()
                lidPath.moveTo(rect.left - pad, rect.top - pad)
                lidPath.lineTo(rect.right + pad, rect.top - pad)
                if (left) lidPath.lineTo(rect.right + pad, rect.top + drop)
                else lidPath.lineTo(rect.left - pad, rect.top + drop)
                lidPath.close()
                c.drawPath(lidPath, bgPaint)
            }
            Mood.TRISTE -> {
                lidPath.reset()
                lidPath.moveTo(rect.left - pad, rect.top - pad)
                lidPath.lineTo(rect.right + pad, rect.top - pad)
                if (left) lidPath.lineTo(rect.left - pad, rect.top + drop)
                else lidPath.lineTo(rect.right + pad, rect.top + drop)
                lidPath.close()
                c.drawPath(lidPath, bgPaint)
            }
            else -> Unit
        }
    }
}
