package com.nunchi.app.output

import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import android.os.Handler
import android.os.Looper

/**
 * 알림 중에 무드등을 깜빡여서, 화면을 보지 않아도 시야 가장자리로 알 수 있게 한다.
 * 알림이 끝나면 원래 색으로 되돌린다.
 *
 * 무드등 영역은 차량마다 다르므로 SDK가 알려주는 지원 영역을 모두 쓴다.
 * SDK 콜백은 백그라운드 스레드로 오기 때문에 상태는 메인 스레드에서만 바꾼다.
 */
class AmbientLightController(
    private val light: Light,
    private val onLog: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var areas: List<SeatArea> = emptyList()
    private var originalColor: Int? = null
    private var blinkColor: Int? = null
    private var intervalMs = BLINK_INTERVAL_MS
    private var lit = false

    private val blink = object : Runnable {
        override fun run() {
            val color = blinkColor ?: return
            lit = !lit
            setColor(if (lit) color else OFF_COLOR)
            main.postDelayed(this, intervalMs)
        }
    }

    init {
        findAreas(attemptsLeft = FIND_AREAS_ATTEMPTS)
    }

    /** 앱 시작 직후에는 SDK 서비스가 아직 연결 전이라 실패할 수 있어서 몇 번 다시 시도한다. */
    private fun findAreas(attemptsLeft: Int) {
        light.checkAmbientLightBrightnessColorCapabilities(
            onComplete = { config, supported ->
                main.post {
                    areas = supported
                    onLog("무드등 지원 영역 ${supported.size}개 (${config.accessType})")
                    readOriginalColor()
                }
            },
            onFailed = { e ->
                if (attemptsLeft > 1) {
                    main.postDelayed({ findAreas(attemptsLeft - 1) }, FIND_AREAS_RETRY_MS)
                } else {
                    onLog("무드등 지원 여부 확인 실패: ${e.message}")
                }
            },
        )
    }

    /** [urgent]이면 두 배 빠르게 깜빡인다. */
    fun start(color: Int, urgent: Boolean = false) {
        val interval = if (urgent) URGENT_INTERVAL_MS else BLINK_INTERVAL_MS
        if (blinkColor == color && intervalMs == interval) return
        if (blinkColor == null) readOriginalColor()
        blinkColor = color
        intervalMs = interval
        lit = false
        main.removeCallbacks(blink)
        main.post(blink)
    }

    fun stop() {
        if (blinkColor == null) return
        blinkColor = null
        main.removeCallbacks(blink)
        setColor(originalColor ?: DEFAULT_COLOR)
    }

    /** 깜빡이는 중에 읽으면 알림 색을 원래 색으로 착각하므로, 깜빡이지 않을 때만 읽는다. */
    private fun readOriginalColor() {
        val area = areas.firstOrNull() ?: return
        if (blinkColor != null) return
        light.getAmbientLightBrightnessColor(
            area,
            onComplete = { color -> main.post { if (blinkColor == null && color != null) originalColor = color } },
            onFailed = { onLog("무드등 색 읽기 실패: ${it.message}") },
        )
    }

    private fun setColor(argb: Int) {
        for (area in areas) {
            light.setAmbientLightBrightnessColor(
                area,
                argb,
                onComplete = { _, _ -> },
                onFailed = { onLog("무드등 색 변경 실패: ${it.message}") },
            )
        }
    }

    private companion object {
        const val BLINK_INTERVAL_MS = 500L
        const val URGENT_INTERVAL_MS = 250L
        const val FIND_AREAS_ATTEMPTS = 10
        const val FIND_AREAS_RETRY_MS = 1_000L
        const val OFF_COLOR = 0xFF000000.toInt()
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    }
}
