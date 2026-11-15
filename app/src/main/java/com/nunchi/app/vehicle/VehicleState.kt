package com.nunchi.app.vehicle

enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * 알림 판단에 쓰는 차량 상태 스냅샷.
 * 값이 null이면 아직 못 받았거나 이 차량(또는 권한)이 지원하지 않는 신호다.
 */
data class VehicleState(
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val hazardOn: Boolean = false,
    val steeringAngleDeg: Float? = null,
    val brakePressed: Boolean? = null,
    val speedKmh: Float? = null,
    val gear: Int? = null,
    /** 속도가 한 번이라도 0보다 크게 들어왔으면 속도 센서를 믿는다. */
    val speedSensorAlive: Boolean = false,
) {
    val isMoving: Boolean
        get() = if (speedSensorAlive) {
            (speedKmh ?: 0f) > MOVING_SPEED_KMH
        } else {
            // 속도 권한이 거부됐거나 속도가 아직 안 들어온 경우 → 기어 D + 브레이크 해제를 주행으로 본다
            gear == GEAR_DRIVE && brakePressed == false
        }

    companion object {
        const val MOVING_SPEED_KMH = 5f

        /** AAOS VehicleGear 값 */
        const val GEAR_NEUTRAL = 0x0001
        const val GEAR_REVERSE = 0x0002
        const val GEAR_PARK = 0x0004
        const val GEAR_DRIVE = 0x0008
    }
}
