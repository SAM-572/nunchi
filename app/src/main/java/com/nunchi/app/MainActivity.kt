package com.nunchi.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nunchi.app.alert.Alert
import com.nunchi.app.alert.AlertHistory
import com.nunchi.app.alert.AlertRecord
import com.nunchi.app.alert.Watch
import com.nunchi.app.settings.NunchiSettings
import com.nunchi.app.ui.EyeView
import com.nunchi.app.ui.SoundIndicatorView
import com.nunchi.app.vehicle.TurnSignal
import com.nunchi.app.vehicle.VehicleState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 눈치의 화면. 차량 신호를 지켜보는 일은 [NunchiService]가 하고,
 * 여기서는 [NunchiHub]에 올라온 상태를 그리기만 한다.
 */
class MainActivity : Activity() {
    private lateinit var settings: NunchiSettings
    private lateinit var history: AlertHistory

    private lateinit var eyes: EyeView
    private lateinit var message: TextView
    private lateinit var detail: TextView
    private lateinit var drivingChip: TextView
    private lateinit var leftIndicator: SoundIndicatorView
    private lateinit var hazardIndicator: SoundIndicatorView
    private lateinit var rightIndicator: SoundIndicatorView
    private lateinit var scrim: View
    private lateinit var panels: List<View>
    private lateinit var settingsPanel: View
    private lateinit var historyPanel: View
    private lateinit var logPanel: View
    private lateinit var onboarding: View

    /** 주행 중에 설정을 누르면 잠깐 보여 주는 안내가 끝나는 시각 */
    private var lockedHintUntilMs = 0L

    private val render: (NunchiHub.Snapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = NunchiSettings(this)
        history = AlertHistory(NunchiService.historyPrefs(this))
        setContentView(R.layout.activity_main)

        eyes = findViewById(R.id.eyes)
        message = findViewById(R.id.message)
        detail = findViewById(R.id.detail)
        drivingChip = findViewById(R.id.drivingChip)
        leftIndicator = indicator(R.id.leftIndicator, SoundIndicatorView.Kind.LEFT, R.string.indicator_left, R.color.alert_signal)
        hazardIndicator = indicator(R.id.hazardIndicator, SoundIndicatorView.Kind.HAZARD, R.string.indicator_hazard, R.color.alert_hazard)
        rightIndicator = indicator(R.id.rightIndicator, SoundIndicatorView.Kind.RIGHT, R.string.indicator_right, R.color.alert_signal)
        scrim = findViewById(R.id.scrim)
        settingsPanel = findViewById(R.id.settingsPanel)
        historyPanel = findViewById(R.id.historyPanel)
        logPanel = findViewById(R.id.logPanel)
        panels = listOf(settingsPanel, historyPanel, logPanel)
        onboarding = findViewById(R.id.onboarding)

        findViewById<View>(R.id.settingsButton).setOnClickListener { openPanel(settingsPanel) }
        findViewById<View>(R.id.historyButton).setOnClickListener { openPanel(historyPanel) }
        scrim.setOnClickListener { closePanels() }
        for (id in intArrayOf(R.id.settingsClose, R.id.historyClose, R.id.logClose)) {
            findViewById<View>(id).setOnClickListener { closePanels() }
        }
        findViewById<View>(R.id.previewButton).setOnClickListener {
            closePanels()
            NunchiService.start(this, NunchiService.ACTION_PREVIEW)
        }
        findViewById<View>(R.id.logButton).setOnClickListener { openPanel(logPanel) }
        findViewById<View>(R.id.historyClear).setOnClickListener {
            history.clear()
            showHistory()
        }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        setUpSettings()

        if (settings.onboardingDone) {
            requestPermissionsThenStart()
        } else {
            onboarding.visibility = View.VISIBLE
            findViewById<View>(R.id.onboardingStart).setOnClickListener {
                settings.onboardingDone = true
                onboarding.visibility = View.GONE
                requestPermissionsThenStart()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NunchiHub.addListener(render)
    }

    override fun onStop() {
        NunchiHub.removeListener(render)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panels.any { it.visibility == View.VISIBLE }) closePanels() else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        permissions.zip(grantResults.toTypedArray()).forEach { (permission, result) ->
            NunchiHub.log("권한 ${permission.substringAfterLast('.')}: ${if (result == PackageManager.PERMISSION_GRANTED) "허용" else "거부"}")
        }
        // 거부해도 동작한다: 속도 대신 기어·브레이크로 주행을 판단하고, 알림 대신 무드등·음성으로 알린다
        NunchiService.start(this)
    }

    private fun requestPermissionsThenStart() {
        val wanted = buildList {
            add("android.car.permission.CAR_SPEED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add("android.permission.POST_NOTIFICATIONS")
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) NunchiService.start(this) else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    // ───────────── 상태 그리기 ─────────────

    private fun render(snapshot: NunchiHub.Snapshot) {
        val vehicle = snapshot.vehicle
        val watch = snapshot.watch
        val fired = watch?.takeIf { it.fired }
        val now = SystemClock.elapsedRealtime()
        val justCleared = snapshot.clearedAtMs != 0L && now - snapshot.clearedAtMs < HAPPY_MS

        // 똑딱 표시기: 비상등은 실제 차처럼 양쪽 화살표도 함께 깜빡인다
        val hazard = vehicle.hazardOn || (snapshot.preview && fired != null)
        hazardIndicator.active = hazard
        leftIndicator.active = hazard || vehicle.turnSignal == TurnSignal.LEFT
        rightIndicator.active = hazard || vehicle.turnSignal == TurnSignal.RIGHT
        drivingChip.text = describeDriving(vehicle)

        eyes.gaze = when {
            hazard -> 0f
            vehicle.turnSignal == TurnSignal.LEFT -> -1f
            vehicle.turnSignal == TurnSignal.RIGHT -> 1f
            else -> 0f
        }
        eyes.relaxed = !vehicle.isMoving && watch == null && !justCleared
        if (fired != null) eyes.accentColor = getColor(colorOf(fired.alert))
        eyes.expression = when {
            fired?.urgent == true -> EyeView.Expression.URGENT
            fired != null -> EyeView.Expression.ALERT
            justCleared -> EyeView.Expression.HAPPY
            else -> EyeView.Expression.IDLE
        }

        when {
            fired != null -> {
                message.setText(titleOf(fired))
                detail.text = when {
                    snapshot.preview -> getString(R.string.detail_preview)
                    fired.alert == Alert.HazardForgotten -> getString(R.string.detail_hazard_fired, seconds(fired))
                    else -> getString(R.string.detail_signal_fired, seconds(fired))
                }
                detail.setTextColor(getColor(colorOf(fired.alert)))
            }
            justCleared -> {
                message.setText(R.string.alert_cleared)
                detail.text = ""
            }
            else -> {
                message.text = ""
                detail.text = if (now < lockedHintUntilMs) getString(R.string.locked_while_driving) else describeIdle(vehicle, watch)
                detail.setTextColor(getColor(R.color.text_muted))
            }
        }

        // 운전에 집중: 달리기 시작하면 설정·기록을 닫는다
        if (vehicle.isMoving && panels.any { it.visibility == View.VISIBLE }) closePanels()
        if (logPanel.visibility == View.VISIBLE) showLog(vehicle)
    }

    private fun describeIdle(vehicle: VehicleState, watch: Watch?): String = when {
        watch != null && watch.alert == Alert.HazardForgotten -> getString(R.string.watching_hazard, seconds(watch))
        watch != null && watch.elapsedMs == 0L -> getString(R.string.watching_signal_turning)
        watch != null -> getString(
            if (vehicle.turnSignal == TurnSignal.LEFT) R.string.watching_signal_left else R.string.watching_signal_right,
            seconds(watch),
        )
        vehicle.isMoving -> getString(R.string.idle_moving)
        vehicle.hazardOn || vehicle.turnSignal != TurnSignal.OFF -> getString(R.string.idle_parked_signal)
        else -> getString(R.string.idle_parked)
    }

    private fun describeDriving(vehicle: VehicleState): String {
        val gear = gearName(vehicle.gear)
        return when {
            !vehicle.isMoving -> getString(R.string.chip_parked, gear)
            vehicle.speedSensorAlive -> getString(R.string.chip_moving, gear, (vehicle.speedKmh ?: 0f).toInt())
            else -> getString(R.string.chip_moving_no_speed, gear)
        }
    }

    private fun gearName(gear: Int?): String = when (gear) {
        VehicleState.GEAR_PARK -> "P"
        VehicleState.GEAR_REVERSE -> "R"
        VehicleState.GEAR_NEUTRAL -> "N"
        VehicleState.GEAR_DRIVE -> "D"
        else -> "–"
    }

    private fun titleOf(watch: Watch): Int = when (val alert = watch.alert) {
        Alert.HazardForgotten -> if (watch.urgent) R.string.alert_hazard_urgent else R.string.alert_hazard
        is Alert.TurnSignalForgotten -> when {
            alert.direction == TurnSignal.LEFT && watch.urgent -> R.string.alert_signal_left_urgent
            alert.direction == TurnSignal.LEFT -> R.string.alert_signal_left
            watch.urgent -> R.string.alert_signal_right_urgent
            else -> R.string.alert_signal_right
        }
    }

    private fun colorOf(alert: Alert): Int =
        if (alert == Alert.HazardForgotten) R.color.alert_hazard else R.color.alert_signal

    private fun seconds(watch: Watch): Int = (watch.elapsedMs / 1000).toInt()

    private fun indicator(id: Int, kind: SoundIndicatorView.Kind, label: Int, color: Int): SoundIndicatorView =
        findViewById<SoundIndicatorView>(id).also {
            it.kind = kind
            it.label = getString(label)
            it.activeColor = getColor(color)
        }

    // ───────────── 패널 ─────────────

    private fun openPanel(panel: View) {
        if (NunchiHub.snapshot.vehicle.isMoving) {
            // 주행 중에는 조작이 필요한 화면을 열지 않는다
            lockedHintUntilMs = SystemClock.elapsedRealtime() + LOCKED_HINT_MS
            render(NunchiHub.snapshot)
            return
        }
        panels.forEach { it.visibility = if (it == panel) View.VISIBLE else View.GONE }
        scrim.visibility = View.VISIBLE
        when (panel) {
            historyPanel -> showHistory()
            logPanel -> showLog(NunchiHub.snapshot.vehicle)
        }
    }

    private fun closePanels() {
        panels.forEach { it.visibility = View.GONE }
        scrim.visibility = View.GONE
    }

    private fun setUpSettings() {
        val seconds = { value: Int -> getString(R.string.settings_seconds, value) }
        val onOff = { value: Boolean -> getString(if (value) R.string.settings_on else R.string.settings_off) }
        segments(R.id.hazardSegments, NunchiSettings.HAZARD_OPTIONS, settings.hazardSeconds, seconds) { settings.hazardSeconds = it }
        segments(R.id.signalSegments, NunchiSettings.SIGNAL_OPTIONS, settings.signalSeconds, seconds) { settings.signalSeconds = it }
        segments(R.id.ambientSegments, listOf(true, false), settings.ambientEnabled, onOff) { settings.ambientEnabled = it }
        segments(R.id.voiceSegments, listOf(true, false), settings.voiceEnabled, onOff) { settings.voiceEnabled = it }
    }

    /** 여러 값 중 하나를 고르는 버튼 줄. 고르면 바로 저장되고 서비스가 다음 판단부터 반영한다. */
    private fun <T> segments(rowId: Int, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        val row = findViewById<LinearLayout>(rowId)
        val buttons = options.map { option ->
            TextView(this).apply {
                text = label(option)
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = if (option == options.last()) 0 else dp(12)
                }
            }
        }
        fun mark(current: T) = buttons.forEachIndexed { index, button ->
            button.setBackgroundResource(if (options[index] == current) R.drawable.bg_pill_selected else R.drawable.bg_pill)
        }
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                onSelect(options[index])
                mark(options[index])
            }
            row.addView(button)
        }
        mark(selected)
    }

    private fun showHistory() {
        val records = history.records()
        findViewById<TextView>(R.id.historyCount).text =
            getString(R.string.history_count, AlertHistory.countSince(records, startOfToday()))
        val list = findViewById<LinearLayout>(R.id.historyList)
        list.removeAllViews()
        if (records.isEmpty()) {
            list.addView(historyText(getString(R.string.history_empty), R.color.text_muted, 22f))
            return
        }
        val timeFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        for (record in records.take(MAX_HISTORY_ROWS)) {
            val kind = getString(
                when (record.kind) {
                    AlertRecord.Kind.HAZARD -> R.string.history_hazard
                    AlertRecord.Kind.SIGNAL_LEFT -> R.string.history_signal_left
                    AlertRecord.Kind.SIGNAL_RIGHT -> R.string.history_signal_right
                }
            )
            list.addView(historyText(timeFormat.format(Date(record.endedAtMs)), R.color.text_muted, 20f))
            list.addView(
                historyText(getString(R.string.history_row, kind, (record.durationMs / 1000).toInt()), R.color.text_primary, 24f)
                    .apply { setPadding(0, 0, 0, dp(8)) }
            )
        }
    }

    private fun historyText(text: String, color: Int, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(color))
        setPadding(0, dp(16), 0, 0)
    }

    private fun showLog(vehicle: VehicleState) {
        findViewById<TextView>(R.id.logState).text =
            "깜빡이 ${vehicle.turnSignal} · 비상등 ${if (vehicle.hazardOn) "ON" else "OFF"} · 기어 ${gearName(vehicle.gear)}\n" +
            "속도 ${vehicle.speedKmh?.let { "%.0f km/h".format(it) } ?: "?"} · 브레이크 ${vehicle.brakePressed ?: "?"} · " +
            "핸들 ${vehicle.steeringAngleDeg?.let { "%.0f°".format(it) } ?: "?"} · ${if (vehicle.isMoving) "주행 중" else "정지"}"
        findViewById<TextView>(R.id.logText).text = NunchiHub.logLines.joinToString("\n")
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val HAPPY_MS = 1_500L
        const val LOCKED_HINT_MS = 3_000L
        const val MAX_HISTORY_ROWS = 20
        const val REQUEST_PERMISSIONS = 1
    }
}
