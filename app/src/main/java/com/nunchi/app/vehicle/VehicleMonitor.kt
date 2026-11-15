package com.nunchi.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * 차량 신호를 모아 [VehicleState]로 전달한다.
 *
 * - Pleos Vehicle SDK: 방향지시등, 핸들 각도, 브레이크, 경고등(비상등 판별)
 * - AAOS CarPropertyManager: 속도, 기어. SDK의 Gear API는 deprecated되면서
 *   Google GEAR_SELECTION 속성을 쓰라고 안내한다.
 *
 * 비상등을 직접 알려주는 AAOS HAZARD_LIGHTS_STATE는 signature 권한이라 일반 앱이 읽을 수 없다.
 *
 * 모든 콜백은 메인 스레드에서 전달한다.
 */
class VehicleMonitor(
    private val context: Context,
    private val onState: (VehicleState) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var current = VehicleState()
    private var lastTelltaleLog: String? = null

    val light: Light get() = vehicle.getLight()

    /**
     * 비상등 판별용 경고등 조회. 경고등 구독(registerTelltales)은 PRIVATE 권한이라
     * 일반 권한(CAR_SAFETY)으로 되는 조회를 주기적으로 반복한다.
     */
    private val telltalePoll = object : Runnable {
        override fun run() {
            vehicle.getSafety().getTelltalesInfo(
                onComplete = { telltales -> post { onTelltales(telltales) } },
                onFailed = { e -> post { logTelltales("조회 실패: ${e.message}") } },
            )
            main.postDelayed(this, TELLTALE_POLL_MS)
        }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = post {
            // 비상등을 켰을 때 이 값이 어떻게 오는지 에뮬레이터에서 확인하기 위해 원본 그대로 기록
            onLog("Pleos TurnSignal = $state")
            update { copy(turnSignal = state.toTurnSignal()) }
        }

        override fun onFailed(e: Exception) = logFailure("Pleos TurnSignal", e)
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = post {
            update { copy(steeringAngleDeg = angle) }
        }

        override fun onFailed(e: Exception) = logFailure("Pleos SteeringWheelAngle", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = post {
            onLog("Pleos BrakePedal = $status")
            update { copy(brakePressed = status) }
        }

        override fun onFailed(e: Exception) = logFailure("Pleos BrakePedal", e)
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = post {
            val raw = value.value
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val kmh = (raw as? Float ?: 0f) * 3.6f
                    update { copy(speedKmh = kmh, speedSensorAlive = speedSensorAlive || kmh > 0f) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    onLog("AAOS GEAR_SELECTION = $raw")
                    update { copy(gear = raw as? Int) }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) =
            logFailure(VehiclePropertyIds.toString(propertyId), null)
    }

    fun start() {
        vehicle.initialize()
        vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        main.post(telltalePoll)

        car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { car, ready ->
            if (ready) {
                subscribeCarProperties(car)
            } else {
                propertyManager = null
                onLog("Car 서비스 연결 끊김")
            }
        }
    }

    fun stop() {
        main.removeCallbacks(telltalePoll)
        runCatching {
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
        }
        vehicle.release()
        propertyManager?.unregisterCallback(propertyCallback)
        car?.disconnect()
    }

    private fun subscribeCarProperties(car: Car) {
        val manager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        propertyManager = manager
        for (property in CAR_PROPERTIES) {
            val name = VehiclePropertyIds.toString(property.id)
            if (context.checkSelfPermission(property.permission) != PackageManager.PERMISSION_GRANTED) {
                onLog("$name: 권한 없음 (${property.permission})")
                continue
            }
            val subscribed = runCatching { manager.registerCallback(propertyCallback, property.id, property.rateHz) }
                .onFailure { onLog("$name 구독 실패: ${it.message}") }
                .getOrDefault(false)
            onLog("$name 구독 ${if (subscribed) "성공" else "실패"}")
        }
    }

    /** 비상등은 좌·우 방향지시 경고등이 동시에 켜진다. 한쪽만 켜지면 깜빡이다. */
    private fun onTelltales(telltales: Telltales) {
        val active = telltales.telltales.filter { it.state != TelltaleState.OFF }
        logTelltales(active.joinToString { "${it.type}=${it.state}" }.ifEmpty { "모두 꺼짐" })
        val types = active.map { it.type }
        val hazardOn = TelltaleType.TURN_SIGNAL_LEFT in types && TelltaleType.TURN_SIGNAL_RIGHT in types
        if (hazardOn != current.hazardOn) update { copy(hazardOn = hazardOn) }
    }

    private fun logTelltales(line: String) {
        if (line == lastTelltaleLog) return
        lastTelltaleLog = line
        onLog("Pleos Telltales: $line")
    }

    private inline fun update(change: VehicleState.() -> VehicleState) {
        current = current.change()
        onState(current)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun logFailure(source: String, e: Exception?) = post {
        onLog("$source 오류: ${e?.message ?: "error event"}")
    }

    private fun TurnSignalValue?.toTurnSignal() = when (this) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        TurnSignalValue.OFF, null -> TurnSignal.OFF
    }

    private class CarProperty(val id: Int, val permission: String, val rateHz: Float)

    private companion object {
        const val TELLTALE_POLL_MS = 500L

        val CAR_PROPERTIES = listOf(
            CarProperty(
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                "android.car.permission.CAR_SPEED",
                CarPropertyManager.SENSOR_RATE_NORMAL,
            ),
            CarProperty(
                VehiclePropertyIds.GEAR_SELECTION,
                "android.car.permission.CAR_POWERTRAIN",
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            ),
        )
    }
}
