package com.nunchi.app.output

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nunchi.app.MainActivity
import com.nunchi.app.R

/**
 * 시스템 알림 두 가지.
 * - 상태 알림: 눈치가 뒤에서 지켜보고 있다는 표시 (포그라운드 서비스에 필요)
 * - 경고 알림: 알림 센터에 남는 알림. Pleos Connect는 화면 위 팝업(HUN)을 시스템 앱에만 허용하므로,
 *   다른 앱을 보고 있을 때는 무드등과 음성이 주된 알림 수단이다.
 */
class AlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, context.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun status(): Notification = Notification.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_eyes)
        .setContentTitle(context.getString(R.string.status_title))
        .setContentText(context.getString(R.string.status_text))
        .setContentIntent(openApp())
        .setOngoing(true)
        .build()

    fun showAlert(title: String, color: Int) {
        val notification = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_eyes)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alert_notification_text))
            .setColor(color)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(false)
            .build()
        // 알림 권한을 거부한 경우 시스템이 조용히 무시한다. 무드등과 음성은 그대로 동작한다.
        manager.notify(ALERT_ID, notification)
    }

    fun cancelAlert() = manager.cancel(ALERT_ID)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val STATUS_ID = 1
        private const val ALERT_ID = 2
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERT = "alert"
    }
}
