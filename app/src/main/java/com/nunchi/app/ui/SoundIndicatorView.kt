package com.nunchi.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 똑딱 소리를 눈으로 보여 주는 표시기.
 *
 * 깜빡이·비상등이 켜지면 차 안에서는 "똑, 딱" 소리가 난다. 그 박자에 맞춰 아이콘이 켜졌다 꺼지고,
 * 소리가 퍼지듯 고리가 번지며, 글자가 "똑"과 "딱"으로 바뀐다.
 * 여러 표시기가 같은 박자로 움직이도록 시계 기준으로 켜짐/꺼짐을 정한다.
 */
class SoundIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Kind { LEFT, HAZARD, RIGHT }

    var kind = Kind.HAZARD
        set(value) {
            field = value
            invalidate()
        }

    var label = ""
        set(value) {
            field = value
            invalidate()
        }

    var activeColor = Color.parseColor("#3DDC84")

    var active = false
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(ticker)
            if (value) post(ticker) else invalidate()
        }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (active) postOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val beat = now / TICK_MS
        val lit = active && beat % 2 == 0L
        val progress = (now % TICK_MS) / TICK_MS.toFloat()

        cardPaint.color = if (active) CARD_ACTIVE else CARD_IDLE
        canvas.drawRoundRect(0f, 0f, w, h, h * 0.22f, h * 0.22f, cardPaint)

        val iconSize = min(w, h) * 0.34f
        val cx = w / 2
        val cy = h * 0.40f

        // 소리가 퍼지는 고리: 똑·딱 할 때마다 한 번씩 번진다
        if (active) {
            ringPaint.color = activeColor
            ringPaint.alpha = ((1f - progress) * 150).toInt()
            ringPaint.strokeWidth = iconSize * 0.07f
            canvas.drawCircle(cx, cy, iconSize * (0.75f + 0.55f * progress), ringPaint)
        }

        iconPaint.color = when {
            lit -> activeColor
            active -> dim(activeColor)
            else -> ICON_IDLE
        }
        drawIcon(canvas, cx, cy, iconSize)

        textPaint.textSize = h * 0.19f
        textPaint.color = if (active) Color.WHITE else TEXT_IDLE
        val text = if (active) (if (beat % 2 == 0L) "똑" else "딱") else label
        canvas.drawText(text, cx, h * 0.86f, textPaint)
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        path.reset()
        val half = size / 2
        when (kind) {
            Kind.HAZARD -> {
                // 비상등 버튼의 삼각형
                path.moveTo(cx, cy - half)
                path.lineTo(cx + half * 1.1f, cy + half * 0.9f)
                path.lineTo(cx - half * 1.1f, cy + half * 0.9f)
                path.close()
            }
            Kind.LEFT, Kind.RIGHT -> {
                // 계기판의 방향지시 화살표
                val d = if (kind == Kind.LEFT) -1f else 1f
                path.moveTo(cx + d * half * 1.2f, cy)
                path.lineTo(cx, cy - half)
                path.lineTo(cx, cy - half * 0.42f)
                path.lineTo(cx - d * half * 1.1f, cy - half * 0.42f)
                path.lineTo(cx - d * half * 1.1f, cy + half * 0.42f)
                path.lineTo(cx, cy + half * 0.42f)
                path.lineTo(cx, cy + half)
                path.close()
            }
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun dim(color: Int): Int =
        Color.argb(255, Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4)

    private companion object {
        /** 방향지시등은 1분에 60~120번 깜빡인다 (약 85번 = 0.35초마다 켜짐/꺼짐) */
        const val TICK_MS = 350L
        val CARD_IDLE = Color.parseColor("#15151B")
        val CARD_ACTIVE = Color.parseColor("#22222C")
        val ICON_IDLE = Color.parseColor("#3A3A46")
        val TEXT_IDLE = Color.parseColor("#6E6E7C")
    }
}
