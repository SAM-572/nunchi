package com.nunchi.app

import android.os.Handler
import android.os.Looper
import com.nunchi.app.alert.Watch
import com.nunchi.app.vehicle.VehicleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 서비스(차량 신호를 지켜보는 쪽)와 화면(보여주는 쪽)을 잇는 앱 안의 게시판.
 * 화면이 닫혀 있어도 서비스는 계속 돌고, 화면은 열릴 때 마지막 상태부터 이어서 그린다.
 * 상태는 메인 스레드에서만 바꾼다.
 */
object NunchiHub {
    data class Snapshot(
        val vehicle: VehicleState = VehicleState(),
        /** 지금 지켜보고 있는 신호. 알림 전이면 fired가 false다. */
        val watch: Watch? = null,
        /** 마지막으로 알림이 풀린 시각 (elapsedRealtime). 한 번도 없으면 0 */
        val clearedAtMs: Long = 0L,
        /** 설정의 "알림 미리보기"로 만든 가짜 알림인지 */
        val preview: Boolean = false,
        val serviceRunning: Boolean = false,
    )

    var snapshot = Snapshot()
        private set

    val logLines = ArrayDeque<String>()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Snapshot) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun publish(next: Snapshot) {
        snapshot = next
        listeners.toList().forEach { it(next) }
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }

    /** SDK 콜백은 백그라운드 스레드에서도 오므로 메인 스레드로 넘겨서 기록한다. */
    fun log(line: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { log(line) }
            return
        }
        android.util.Log.d(TAG, line)
        logLines.addFirst("${timeFormat.format(Date())}  $line")
        while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
    }

    private const val TAG = "Nunchi"
    private const val MAX_LOG_LINES = 200
}
