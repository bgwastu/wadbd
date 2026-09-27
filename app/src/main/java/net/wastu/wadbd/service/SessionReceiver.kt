package net.wastu.wadbd.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.wastu.wadbd.MainActivity
import net.wastu.wadbd.R
import net.wastu.wadbd.data.WadbdRepository

class SessionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SESSION_UPDATE = "net.wastu.wadbd.ACTION_SESSION_UPDATE"
        const val ACTION_DISCONNECT = "net.wastu.wadbd.ACTION_DISCONNECT"
        const val CHANNEL_ID = "wadbd_active_session"
        const val NOTIFICATION_ID = 1337
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel(context, notifManager)

        when (action) {
            ACTION_SESSION_UPDATE -> {
                val count = intent.getIntExtra("count", 0)
                val client = intent.getStringExtra("client") ?: ""

                if (count > 0) {
                    val contentIntent = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val disconnectIntent = PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent(context, SessionReceiver::class.java).apply {
                            this.action = ACTION_DISCONNECT
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val title = "Wireless ADB Active"
                    val text = if (count == 1) "Connected: $client" else "$count active clients: $client"

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setOngoing(true)
                        .setContentIntent(contentIntent)
                        .addAction(R.drawable.ic_launcher_monochrome, "Disconnect", disconnectIntent)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()

                    notifManager.notify(NOTIFICATION_ID, notification)
                } else {
                    notifManager.cancel(NOTIFICATION_ID)
                }
            }

            ACTION_DISCONNECT -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = WadbdRepository()
                        repo.kickAllSessions()
                        notifManager.cancel(NOTIFICATION_ID)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun createNotificationChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
