package com.nunchi.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 눈치의 얼굴. 차량 상황에 따라 눈 표정이 바뀐다.
 *
 * - 평소: 가끔 깜빡이고 주변을 살핀다. 정차 중에는 눈꺼풀을 반쯤 내리고 쉰다.
 * - 깜빡이가 켜지면 그쪽을 본다.
 * - 알림: 눈이 커지고 테두리가 빛난다. 계속 무시하면 좌우로 떨린다.
 * - 해결되면 웃는다.
 */
class EyeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Expression { IDLE, ALERT, URGENT, HAPPY }

    var expression = Expression.IDLE
        set(value) {
            if (field == value) return
            field = value
            val alerting = value == Expression.ALERT || value == Expression.URGENT
            alertAnimator?.cancel()
            alertAnimator = tween(alertLevel, if (alerting) 1f else 0f, 220) { alertLevel = it }
            pulse.cancel()
            pulseLevel = 0f
            if (alerting) {
                pulse.duration = if (value == Expression.URGENT) 260 else 600
                pulse.start()
            }
            invalidate()
        }

    /** 눈동자가 보는 방향. -1(왼쪽) ~ 1(오른쪽) */
    var gaze = 0f
        set(value) {
            if (field == value) return
            field = value
            lookAnimator?.cancel()
            lookAnimator = tween(lookX, value, 250) { lookX = it }
        }

    /** 정차 중처럼 할 일이 없을 때 눈꺼풀을 반쯤 내린다. */
    var relaxed = false
        set(value) {
            if (field == value) return
            field = value
            lidAnimator?.cancel()
            lidAnimator = tween(lidDroop, if (value) RELAXED_DROOP else 0f, 500) { lidDroop = it }
        }

    var accentColor = Color.parseColor("#FFB020")

    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B1B1F") }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val happyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private var lookX = 0f
    private var glanceX = 0f
    private var lidOpen = 1f
    private var lidDroop = 0f
    private var alertLevel = 0f
    private var pulseLevel = 0f
    private var lookAnimator: ValueAnimator? = null
    private var lidAnimator: ValueAnimator? = null
    private var alertAnimator: ValueAnimator? = null

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseLevel = it.animatedValue as Float
            invalidate()
        }
    }

    /** 살아 있는 느낌: 몇 초마다 눈을 깜빡이거나 옆을 슬쩍 본다. */
    private val idleLoop = object : Runnable {
        override fun run() {
            if (expression == Expression.IDLE) {
                if (gaze == 0f && Random.nextInt(3) == 0) glance() else blink()
            }
            postDelayed(this, Random.nextLong(2_500, 5_000))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(idleLoop, 2_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idleLoop)
        pulse.cancel()
        lookAnimator?.cancel()
        lidAnimator?.cancel()
        alertAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val eyeW = min(width * 0.2f, height * 0.42f)
        val eyeH = eyeW * 1.35f
        val gap = eyeW * 0.6f
        val cy = height * 0.5f
        // 계속 무시하면 좌우로 떨면서 재촉한다
        val shake = if (expression == Expression.URGENT) eyeW * 0.05f * sin(pulseLevel * 2 * PI).toFloat() else 0f
        for (side in intArrayOf(-1, 1)) {
            val cx = width / 2f + side * (eyeW + gap) / 2f + shake
            if (expression == Expression.HAPPY) {
                drawHappyEye(canvas, cx, cy, eyeW)
            } else {
                drawEye(canvas, cx, cy, eyeW, eyeH)
            }
        }
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, eyeW: Float, eyeH: Float) {
        val scale = 1f + alertLevel * (0.15f + 0.05f * pulseLevel)
        val w = eyeW * scale
        val h = eyeH * scale * lidOpen

        if (alertLevel > 0f) {
            val pad = eyeW * 0.12f
            glowPaint.color = accentColor
            glowPaint.alpha = (alertLevel * (120 + 135 * pulseLevel)).toInt()
            glowPaint.strokeWidth = eyeW * if (expression == Expression.URGENT) 0.12f else 0.08f
            canvas.drawOval(cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad, glowPaint)
        }

        // 눈꺼풀: 위쪽을 잘라내서 반쯤 감은 눈을 만든다 (알림 중에는 번쩍 뜬다)
        val droop = lidDroop * (1f - alertLevel)
        canvas.save()
        canvas.clipRect(cx - w, cy - h / 2 + h * droop, cx + w, cy + h)
        canvas.drawOval(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, scleraPaint)
        if (lidOpen >= 0.3f) {
            val pupilR = w * (0.28f - 0.10f * alertLevel)
            val look = (lookX + glanceX).coerceIn(-1f, 1f)
            val px = cx + look * (w / 2 - pupilR * 1.2f)
            val py = cy + h * (0.05f + 0.12f * droop)
            canvas.drawCircle(px, py, pupilR, pupilPaint)
            canvas.drawCircle(px - pupilR * 0.35f, py - pupilR * 0.35f, pupilR * 0.25f, scleraPaint)
        }
        canvas.restore()
    }

    /** ^ ^ 모양으로 웃는 눈 */
    private fun drawHappyEye(canvas: Canvas, cx: Float, cy: Float, eyeW: Float) {
        val r = eyeW * 0.42f
        happyPaint.strokeWidth = eyeW * 0.16f
        canvas.drawArc(cx - r, cy - r * 0.4f, cx + r, cy + r * 1.6f, 200f, 140f, false, happyPaint)
    }

    private fun blink() {
        ValueAnimator.ofFloat(1f, 0.05f, 1f).apply {
            duration = 180
            addUpdateListener {
                lidOpen = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun glance() {
        val target = if (Random.nextBoolean()) 0.45f else -0.45f
        ValueAnimator.ofFloat(0f, target, target, 0f).apply {
            duration = 1_100
            addUpdateListener {
                glanceX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun tween(from: Float, to: Float, durationMs: Long, apply: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                apply(it.animatedValue as Float)
                invalidate()
            }
            start()
        }

    private companion object {
        const val RELAXED_DROOP = 0.26f
    }
}
