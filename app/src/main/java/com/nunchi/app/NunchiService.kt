package com.nunchi.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.nunchi.app.alert.Alert
import com.nunchi.app.alert.AlertEngine
import com.nunchi.app.alert.AlertHistory
import com.nunchi.app.alert.AlertRecord
import com.nunchi.app.alert.Watch
import com.nunchi.app.output.AlertNotifier
import com.nunchi.app.output.AmbientLightController
import com.nunchi.app.output.VoiceAnnouncer
import com.nunchi.app.settings.NunchiSettings
import com.nunchi.app.vehicle.TurnSignal
import com.nunchi.app.vehicle.VehicleMonitor
import com.nunchi.app.vehicle.VehicleState

/**
 * 눈치의 본체. 화면이 닫혀 있어도(내비를 보고 있어도) 차량 신호를 지켜보다가
 * 무드등, 음성, 시스템 알림으로 알려 준다. 화면은 [NunchiHub]로 상태를 받아 그리기만 한다.
 */
class NunchiService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: NunchiSettings
    private lateinit var history: AlertHistory
    private lateinit var notifier: AlertNotifier
    private lateinit var monitor: VehicleMonitor
    private lateinit var ambient: AmbientLightController
    private lateinit var voice: VoiceAnnouncer
    private val engine = AlertEngine(SystemClock::elapsedRealtime) { settings.thresholds }

    private var vehicleState = VehicleState()

    /** 지금 울리고 있는 알림 */
    private var active: Watch? = null
    private var activeUrgent = false
    private var activeIsPreview = false
    private var clearedAtMs = 0L
    private var previewUntilMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = NunchiSettings(this)
        history = AlertHistory(historyPrefs(this))
        notifier = AlertNotifier(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlertNotifier.STATUS_ID, notifier.status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AlertNotifier.STATUS_ID, notifier.status())
        }

        monitor = VehicleMonitor(
            context = this,
            onState = {
                vehicleState = it
                evaluate()
            },
            onLog = NunchiHub::log,
        )
        monitor.start()
        ambient = AmbientLightController(monitor.light, NunchiHub::log)
        voice = VoiceAnnouncer(this, NunchiHub::log)
        main.post(tick)
        NunchiHub.log("눈치 서비스 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREVIEW) {
            previewUntilMs = SystemClock.elapsedRealtime() + PREVIEW_MS
            evaluate()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (active != null) finish()
        voice.release()
        monitor.stop()
        NunchiHub.publish(NunchiHub.snapshot.copy(watch = null, serviceRunning = false))
        super.onDestroy()
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val real = engine.update(vehicleState)
        val previewing = now < previewUntilMs
        val watch = if (previewing) previewWatch(now) else real
        val fired = watch?.takeIf { it.fired }

        when {
            fired == null -> if (active != null) finish()
            active?.alert != fired.alert -> {
                if (active != null) finish()
                begin(fired, previewing)
            }
            fired.urgent && !activeUrgent -> escalate(fired)
        }
        if (fired != null) active = fired

        NunchiHub.publish(NunchiHub.Snapshot(vehicleState, watch, clearedAtMs, previewing, serviceRunning = true))
    }

    private fun begin(watch: Watch, preview: Boolean) {
        active = watch
        activeUrgent = false
        activeIsPreview = preview
        val title = getString(titleOf(watch.alert))
        if (settings.ambientEnabled) ambient.start(colorOf(watch.alert))
        if (settings.voiceEnabled) voice.speak(title)
        notifier.showAlert(title, colorOf(watch.alert))
        NunchiHub.log("알림: ${watch.alert}${if (preview) " (미리보기)" else ""}")
    }

    /** 알렸는데도 계속 켜져 있다 → 무드등을 빠르게, 한 번 더 말해 준다. */
    private fun escalate(watch: Watch) {
        activeUrgent = true
        if (settings.ambientEnabled) ambient.start(colorOf(watch.alert), urgent = true)
        if (settings.voiceEnabled) voice.speak(getString(urgentTitleOf(watch.alert)))
        NunchiHub.log("알림 강조: ${watch.alert}")
    }

    private fun finish() {
        val ended = active ?: return
        ambient.stop()
        notifier.cancelAlert()
        if (!activeIsPreview) {
            history.add(AlertRecord(AlertHistory.kindOf(ended.alert), System.currentTimeMillis(), ended.elapsedMs))
        }
        // 신호를 실제로 껐을 때만 "꺼졌어요"를 보여 준다. 신호를 켠 채 차를 세워서 알림이 멈춘 것이라면
        // (정차 중 비상등은 정상) 웃지 않고 조용히 물러난다.
        val signalOff = !vehicleState.hazardOn && vehicleState.turnSignal == TurnSignal.OFF
        if (signalOff || activeIsPreview) clearedAtMs = SystemClock.elapsedRealtime()
        active = null
        NunchiHub.log("알림: ${if (signalOff) "해제" else "정차로 멈춤"} (${ended.elapsedMs / 1000}초 동안 켜져 있었음)")
    }

    /** 차량 신호 없이도 알림이 어떻게 보이는지 체험할 수 있게, 방금 울린 비상등 알림을 흉내 낸다. */
    private fun previewWatch(now: Long): Watch {
        val threshold = settings.thresholds.hazardMs
        val shownFor = PREVIEW_MS - (previewUntilMs - now)
        return Watch(Alert.HazardForgotten, threshold + shownFor, threshold)
    }

    private fun titleOf(alert: Alert): Int = when (alert) {
        Alert.HazardForgotten -> R.string.alert_hazard
        is Alert.TurnSignalForgotten ->
            if (alert.direction == TurnSignal.LEFT) R.string.alert_signal_left else R.string.alert_signal_right
    }

    private fun urgentTitleOf(alert: Alert): Int = when (alert) {
        Alert.HazardForgotten -> R.string.alert_hazard_urgent
        is Alert.TurnSignalForgotten ->
            if (alert.direction == TurnSignal.LEFT) R.string.alert_signal_left_urgent else R.string.alert_signal_right_urgent
    }

    private fun colorOf(alert: Alert): Int = when (alert) {
        Alert.HazardForgotten -> getColor(R.color.alert_hazard)
        is Alert.TurnSignalForgotten -> getColor(R.color.alert_signal)
    }

    companion object {
        const val ACTION_PREVIEW = "com.nunchi.app.action.PREVIEW"
        private const val TICK_MS = 500L
        private const val PREVIEW_MS = 7_000L

        fun historyPrefs(context: Context) = context.getSharedPreferences("nunchi_history", Context.MODE_PRIVATE)

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, NunchiService::class.java).setAction(action))
        }
    }
}
