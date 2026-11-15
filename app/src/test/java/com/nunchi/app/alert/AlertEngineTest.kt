package com.nunchi.app.alert

import com.nunchi.app.vehicle.TurnSignal
import com.nunchi.app.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private var now = 0L
    private var thresholds = AlertThresholds()
    private val engine = AlertEngine({ now }, { thresholds })

    /** 기어 D + 브레이크 해제 + 핸들 직진 = 직진 주행 중 */
    private val cruising = VehicleState(
        gear = VehicleState.GEAR_DRIVE,
        brakePressed = false,
        steeringAngleDeg = 0f,
    )

    /** 그 시각에 지켜보고 있는 신호 (알림 전 포함) */
    private fun watch(timeMs: Long, state: VehicleState): Watch? {
        now = timeMs
        return engine.update(state)
    }

    /** 그 시각에 울리고 있는 알림 */
    private fun at(timeMs: Long, state: VehicleState): Alert? =
        watch(timeMs, state)?.takeIf { it.fired }?.alert

    @Test
    fun `short thank-you hazard does not alert`() {
        val hazard = cruising.copy(hazardOn = true)
        assertNull(at(0, hazard))
        assertNull(at(3_000, hazard))
        assertNull(at(4_000, cruising))
        assertNull(at(20_000, cruising))
    }

    @Test
    fun `hazard left on while moving alerts after 10 seconds`() {
        val hazard = cruising.copy(hazardOn = true)
        assertNull(at(0, hazard))
        assertNull(at(9_999, hazard))
        assertEquals(Alert.HazardForgotten, at(10_000, hazard))
    }

    @Test
    fun `hazard while stopped never alerts`() {
        val parkedWithHazard = cruising.copy(brakePressed = true, hazardOn = true)
        assertNull(at(0, parkedWithHazard))
        assertNull(at(60_000, parkedWithHazard))
    }

    @Test
    fun `stopping resets the hazard timer`() {
        val hazard = cruising.copy(hazardOn = true)
        assertNull(at(0, hazard))
        assertNull(at(8_000, hazard.copy(brakePressed = true)))
        assertNull(at(9_000, hazard))
        assertNull(at(18_999, hazard))
        assertEquals(Alert.HazardForgotten, at(19_000, hazard))
    }

    @Test
    fun `turn signal left on with straight wheel alerts after 15 seconds`() {
        val signal = cruising.copy(turnSignal = TurnSignal.LEFT)
        assertNull(at(0, signal))
        assertNull(at(14_999, signal))
        assertEquals(Alert.TurnSignalForgotten(TurnSignal.LEFT), at(15_000, signal))
    }

    @Test
    fun `turning the wheel restarts the straight timer`() {
        val signal = cruising.copy(turnSignal = TurnSignal.RIGHT)
        assertNull(at(0, signal))
        assertNull(at(10_000, signal.copy(steeringAngleDeg = 90f)))
        assertNull(at(12_000, signal))
        assertNull(at(26_999, signal))
        assertEquals(Alert.TurnSignalForgotten(TurnSignal.RIGHT), at(27_000, signal))
    }

    @Test
    fun `without steering angle falls back to signal duration`() {
        val signal = cruising.copy(turnSignal = TurnSignal.LEFT, steeringAngleDeg = null)
        assertNull(at(0, signal))
        assertNull(at(29_999, signal))
        assertEquals(Alert.TurnSignalForgotten(TurnSignal.LEFT), at(30_000, signal))
    }

    @Test
    fun `hazard takes priority over turn signal`() {
        val both = cruising.copy(turnSignal = TurnSignal.LEFT, hazardOn = true)
        assertNull(at(0, both))
        assertEquals(Alert.HazardForgotten, at(15_000, both))
    }

    @Test
    fun `live speed sensor overrides gear and brake`() {
        val stoppedAtLight = cruising.copy(hazardOn = true, speedSensorAlive = true, speedKmh = 0f)
        assertNull(at(0, stoppedAtLight))
        assertNull(at(30_000, stoppedAtLight))

        val driving = stoppedAtLight.copy(speedKmh = 40f, brakePressed = true)
        assertNull(at(31_000, driving))
        assertEquals(Alert.HazardForgotten, at(41_000, driving))
    }

    @Test
    fun `counts up while watching before the alert fires`() {
        val hazard = cruising.copy(hazardOn = true)
        watch(0, hazard)
        val watching = watch(4_000, hazard)!!
        assertEquals(Alert.HazardForgotten, watching.alert)
        assertEquals(4_000L, watching.elapsedMs)
        assertFalse(watching.fired)
    }

    @Test
    fun `nothing is watched while signals are off`() {
        assertNull(watch(0, cruising))
        assertNull(watch(60_000, cruising))
    }

    @Test
    fun `turn signal time does not build up while the wheel is turned`() {
        val turning = cruising.copy(turnSignal = TurnSignal.LEFT, steeringAngleDeg = 120f)
        watch(0, turning)
        val watching = watch(40_000, turning)!!
        assertEquals(0L, watching.elapsedMs)
        assertFalse(watching.fired)
    }

    @Test
    fun `alert time follows the user's setting`() {
        thresholds = AlertThresholds(hazardMs = 5_000, signalMs = 30_000)
        val hazard = cruising.copy(hazardOn = true)
        assertNull(at(0, hazard))
        assertEquals(Alert.HazardForgotten, at(5_000, hazard))

        val signal = cruising.copy(turnSignal = TurnSignal.RIGHT)
        assertNull(at(100_000, signal))
        assertNull(at(129_999, signal))
        assertEquals(Alert.TurnSignalForgotten(TurnSignal.RIGHT), at(130_000, signal))
    }

    @Test
    fun `becomes urgent when left on 20 seconds after the alert`() {
        val hazard = cruising.copy(hazardOn = true)
        watch(0, hazard)
        assertFalse(watch(29_999, hazard)!!.urgent)
        assertTrue(watch(30_000, hazard)!!.urgent)
    }
}
