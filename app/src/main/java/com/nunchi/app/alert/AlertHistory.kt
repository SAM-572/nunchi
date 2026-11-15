package com.nunchi.app.alert

import android.content.SharedPreferences
import com.nunchi.app.vehicle.TurnSignal

/** 눈치가 알려준 일 한 건. */
data class AlertRecord(
    val kind: Kind,
    /** 알림이 끝난 시각 (System.currentTimeMillis) */
    val endedAtMs: Long,
    /** 주행 중에 켜져 있던 시간 */
    val durationMs: Long,
) {
    enum class Kind { HAZARD, SIGNAL_LEFT, SIGNAL_RIGHT }
}

/** 최근 알림 기록. 기기에만 저장하고 밖으로 보내지 않는다. */
class AlertHistory(private val prefs: SharedPreferences) {

    /** 최근 것이 앞에 온다. */
    fun records(): List<AlertRecord> = decode(prefs.getString(KEY, "").orEmpty())

    fun add(record: AlertRecord) {
        prefs.edit().putString(KEY, encode((listOf(record) + records()).take(MAX_RECORDS))).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val MAX_RECORDS = 50
        private const val KEY = "records"

        fun encode(records: List<AlertRecord>): String =
            records.joinToString(";") { "${it.kind.name},${it.endedAtMs},${it.durationMs}" }

        /** 형식이 깨진 항목은 버린다 (앱 업데이트로 종류가 바뀌어도 죽지 않게). */
        fun decode(text: String): List<AlertRecord> =
            text.split(";").mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size != 3) return@mapNotNull null
                val kind = AlertRecord.Kind.entries.find { it.name == parts[0] } ?: return@mapNotNull null
                val endedAt = parts[1].toLongOrNull() ?: return@mapNotNull null
                val duration = parts[2].toLongOrNull() ?: return@mapNotNull null
                AlertRecord(kind, endedAt, duration)
            }

        fun countSince(records: List<AlertRecord>, sinceMs: Long): Int = records.count { it.endedAtMs >= sinceMs }

        fun kindOf(alert: Alert): AlertRecord.Kind = when (alert) {
            Alert.HazardForgotten -> AlertRecord.Kind.HAZARD
            is Alert.TurnSignalForgotten ->
                if (alert.direction == TurnSignal.LEFT) {
                    AlertRecord.Kind.SIGNAL_LEFT
                } else {
                    AlertRecord.Kind.SIGNAL_RIGHT
                }
        }
    }
}
