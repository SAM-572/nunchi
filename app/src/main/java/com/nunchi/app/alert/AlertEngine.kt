package com.nunchi.app.alert

import com.nunchi.app.vehicle.TurnSignal
import com.nunchi.app.vehicle.VehicleState
import kotlin.math.abs

sealed interface Alert {
    /** 비상등을 켠 채 달리고 있다 (똑딱 소리를 못 들어서 모름). */
    data object HazardForgotten : Alert

    /** 핸들은 직진으로 돌아왔는데 깜빡이가 계속 켜져 있다. */
    data class TurnSignalForgotten(val direction: TurnSignal) : Alert
}

/** 사용자가 설정에서 고르는 알림 시점. */
data class AlertThresholds(
    /** 감사 표시용 비상등은 보통 2~3초. 이 시간을 넘기면 끄는 걸 잊은 것으로 본다. */
    val hazardMs: Long = 10_000L,
    /** 차선 변경 깜빡이는 보통 10초 안에 끝난다. */
    val signalMs: Long = 15_000L,
) {
    /** 핸들 각도를 못 받는 차량은 직진 여부를 모르므로 두 배로 넉넉하게 기다린다. */
    val signalFallbackMs: Long get() = signalMs * 2
}

/**
 * 지금 지켜보고 있는 신호 하나.
 * 주행 중에 켜져 있는 동안 시간이 쌓이고, [thresholdMs]를 넘기면 알림이 된다.
 */
data class Watch(val alert: Alert, val elapsedMs: Long, val thresholdMs: Long) {
    val fired: Boolean get() = elapsedMs >= thresholdMs

    /** 알렸는데도 계속 켜져 있으면 더 강하게 알린다. */
    val urgent: Boolean get() = elapsedMs >= thresholdMs + URGENT_AFTER_MS

    companion object {
        const val URGENT_AFTER_MS = 20_000L
    }
}

/**
 * 소리를 못 들어서 놓치는 상황을 차량 신호로 판단한다.
 *
 * 시간이 흐르는 것만으로 결과가 바뀌므로, 상태가 그대로여도 주기적으로 [update]를 호출해야 한다.
 */
class AlertEngine(
    private val now: () -> Long,
    private val thresholds: () -> AlertThresholds = { AlertThresholds() },
) {
    private var hazardMovingSince: Long? = null
    private var signalMovingSince: Long? = null
    private var signalStraightSince: Long? = null

    /** 주행 중에 켜져 있는 신호가 없으면 null. */
    fun update(state: VehicleState): Watch? {
        val t = now()
        val limits = thresholds()
        val moving = state.isMoving
        // 비상등이 켜지면 방향지시등 값은 차량마다 제각각이라 무시한다
        val signalOn = state.turnSignal != TurnSignal.OFF && !state.hazardOn
        val straight = state.steeringAngleDeg?.let { abs(it) < STRAIGHT_ANGLE_DEG } ?: false

        hazardMovingSince = startedAt(state.hazardOn && moving, hazardMovingSince, t)
        signalMovingSince = startedAt(signalOn && moving, signalMovingSince, t)
        signalStraightSince = startedAt(signalOn && moving && straight, signalStraightSince, t)

        hazardMovingSince?.let { return Watch(Alert.HazardForgotten, t - it, limits.hazardMs) }

        val movingSince = signalMovingSince ?: return null
        val alert = Alert.TurnSignalForgotten(state.turnSignal)
        return if (state.steeringAngleDeg != null) {
            // 핸들을 돌리고 있는 동안(회전·차선 변경 중)은 시간이 쌓이지 않는다
            Watch(alert, signalStraightSince?.let { t - it } ?: 0L, limits.signalMs)
        } else {
            Watch(alert, t - movingSince, limits.signalFallbackMs)
        }
    }

    private fun startedAt(condition: Boolean, since: Long?, t: Long): Long? =
        if (condition) since ?: t else null

    companion object {
        const val STRAIGHT_ANGLE_DEG = 10f
    }
}
