package com.nunchi.app.alert

import com.nunchi.app.vehicle.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertHistoryTest {
    private val records = listOf(
        AlertRecord(AlertRecord.Kind.HAZARD, endedAtMs = 3_000, durationMs = 42_000),
        AlertRecord(AlertRecord.Kind.SIGNAL_LEFT, endedAtMs = 2_000, durationMs = 17_500),
        AlertRecord(AlertRecord.Kind.SIGNAL_RIGHT, endedAtMs = 1_000, durationMs = 15_000),
    )

    @Test
    fun `records survive saving and loading`() {
        assertEquals(records, AlertHistory.decode(AlertHistory.encode(records)))
    }

    @Test
    fun `empty storage has no records`() {
        assertEquals(emptyList<AlertRecord>(), AlertHistory.decode(""))
    }

    @Test
    fun `broken entries are skipped`() {
        val text = "HAZARD,3000,42000;UNKNOWN_KIND,1,1;garbage;SIGNAL_LEFT,abc,1"
        assertEquals(listOf(records[0]), AlertHistory.decode(text))
    }

    @Test
    fun `counts only records since the given time`() {
        assertEquals(2, AlertHistory.countSince(records, sinceMs = 2_000))
        assertEquals(0, AlertHistory.countSince(records, sinceMs = 9_000))
    }

    @Test
    fun `alerts map to record kinds`() {
        assertEquals(AlertRecord.Kind.HAZARD, AlertHistory.kindOf(Alert.HazardForgotten))
        assertEquals(AlertRecord.Kind.SIGNAL_LEFT, AlertHistory.kindOf(Alert.TurnSignalForgotten(TurnSignal.LEFT)))
        assertEquals(AlertRecord.Kind.SIGNAL_RIGHT, AlertHistory.kindOf(Alert.TurnSignalForgotten(TurnSignal.RIGHT)))
    }
}
