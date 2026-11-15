package com.nunchi.app.settings

import android.content.Context
import com.nunchi.app.alert.AlertThresholds

/** 사용자가 고른 설정. 기기에만 저장한다. */
class NunchiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nunchi_settings", Context.MODE_PRIVATE)

    /** 비상등을 켠 채 달린 지 몇 초 뒤에 알릴지 */
    var hazardSeconds: Int
        get() = prefs.getInt(KEY_HAZARD_SECONDS, 10)
        set(value) = prefs.edit().putInt(KEY_HAZARD_SECONDS, value).apply()

    /** 깜빡이를 켠 채 직진한 지 몇 초 뒤에 알릴지 */
    var signalSeconds: Int
        get() = prefs.getInt(KEY_SIGNAL_SECONDS, 15)
        set(value) = prefs.edit().putInt(KEY_SIGNAL_SECONDS, value).apply()

    var ambientEnabled: Boolean
        get() = prefs.getBoolean(KEY_AMBIENT, true)
        set(value) = prefs.edit().putBoolean(KEY_AMBIENT, value).apply()

    /** 잔존 청력이 있는 운전자와 동승자를 위한 음성 안내 */
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    val thresholds: AlertThresholds
        get() = AlertThresholds(hazardMs = hazardSeconds * 1_000L, signalMs = signalSeconds * 1_000L)

    companion object {
        val HAZARD_OPTIONS = listOf(5, 10, 20)
        val SIGNAL_OPTIONS = listOf(10, 15, 30)

        private const val KEY_HAZARD_SECONDS = "hazard_seconds"
        private const val KEY_SIGNAL_SECONDS = "signal_seconds"
        private const val KEY_AMBIENT = "ambient"
        private const val KEY_VOICE = "voice"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
