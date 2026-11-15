package com.nunchi.app.output

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import java.util.Locale

/**
 * 알림을 음성으로도 읽어 준다 (Pleos TextToSpeech SDK).
 *
 * 똑딱 소리는 작고 높아서 놓치기 쉽지만 또렷한 말소리는 들을 수 있는 난청 운전자가 많고,
 * 동승자도 함께 알아챌 수 있다. 차 안에서 바로 합성하는 ON_DEVICE 모드라 통신이 필요 없다.
 */
class VoiceAnnouncer(context: Context, private val onLog: (String) -> Unit) {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onError(errorType: ErrorType) = onLog("음성 안내 오류: $errorType")
    }

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(events)
                tts.setLocale(Locale.KOREA)
                ready = true
                onLog("음성 안내 준비됨 (Pleos TTS, ON_DEVICE)")
            }

            override fun onFailure(errorType: ErrorType) = onLog("음성 안내 초기화 실패: $errorType")
        })
    }

    fun speak(text: String) {
        if (ready) tts.speak(text)
    }

    fun release() {
        if (ready) tts.removeEventListener(events)
        tts.release()
    }
}
