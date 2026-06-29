package com.actionverite.live.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.actionverite.live.R
import com.actionverite.live.domain.repository.NotificationRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives FCM pushes (friend invites, "your turn", game-start, etc.) and posts
 * notifications. Token refreshes are persisted via [NotificationRepository].
 */
@AndroidEntryPoint
class AvFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationRepository: NotificationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { notificationRepository.registerDeviceToken() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, getString(R.string.notif_channel_default))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
