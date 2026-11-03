package com.missingtable.scorer.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.missingtable.scorer.MainActivity
import com.missingtable.scorer.MtApp
import com.missingtable.scorer.R
import com.missingtable.scorer.domain.NotificationIntent

/**
 * FCM entry point (SB-1464).
 *
 * Live-score pushes are notification messages, so in the background or when
 * killed the system tray shows them and a tap launches MainActivity with the
 * data map as extras — this class is not involved. In the foreground FCM
 * shows nothing and hands the message here, so we post it ourselves, the same
 * way: same channel, same tag, same extras.
 */
class MtMessagingService : FirebaseMessagingService() {

    /** FCM (re)registered this installation; keep the server's row current. */
    override fun onRegistered(registration: String) {
        (application as MtApp).container.pushRegistrar.launchRegister()
    }

    // Deprecated by the FID-based register() flow, but still where token
    // rotation lands if FCM falls back to it — same response either way.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        (application as MtApp).container.pushRegistrar.launchRegister()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val n = message.notification
        val title = n?.title ?: return
        val matchId = NotificationIntent.matchIdFrom(data)
        val tag = NotificationIntent.tagOrFallback(
            serverTag = n.tag,
            matchId = matchId,
            eventType = data[NotificationIntent.EXTRA_EVENT_TYPE],
            messageId = message.messageId,
            title = title,
        )

        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data.forEach { (k, v) -> putExtra(k, v) }
        }
        val pending = PendingIntent.getActivity(
            this,
            NotificationIntent.requestCodeFor(tag),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, NotificationIntent.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(tag, 0, notification)
    }
}
