package com.nunchi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nunchi.app.settings.NunchiSettings

/** 시동을 걸면 앱을 열지 않아도 눈치가 지켜보기 시작한다. 첫 실행 안내를 마친 뒤부터 동작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!NunchiSettings(context).onboardingDone) return
        runCatching { NunchiService.start(context) }
            .onFailure { NunchiHub.log("부팅 후 자동 시작 실패: ${it.message}") }
    }
}
