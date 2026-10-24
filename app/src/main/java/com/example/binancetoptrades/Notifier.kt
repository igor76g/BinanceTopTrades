package com.example.binancetoptrades

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

object Notifier {
    private const val CHANNEL_SERVICE = "monitor_service"
    private const val CHANNEL_LIST = "top_trades_list_v1"
    const val FOREGROUND_ID = 1
    private const val LIST_ID = 2
    private const val WARNING_ID = 3

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIST, "Top coins by trades", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "List of the coins with the most trades, sent every few minutes"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Persistent notification required for the foreground service. */
    fun buildServiceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Binance Top Trades")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()

    @SuppressLint("MissingPermission")
    fun updateServiceNotification(context: Context, text: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context)
            .notify(FOREGROUND_ID, buildServiceNotification(context, text))
    }

    /** One line per coin, e.g. "1. BTCUSDT   45,210 trades   NATR 1.23%". */
    fun formatLines(coins: List<TopCoin>): List<String> =
        coins.mapIndexed { i, c ->
            String.format(Locale.US, "%d. %s   %,d trades   NATR %.2f%%", i + 1, c.symbol, c.trades, c.natr)
        }

    /** The list of coins, sent as one notification (a new one replaces the previous). */
    @SuppressLint("MissingPermission")
    fun sendTopList(context: Context, coins: List<TopCoin>, windowMinutes: Int, sound: Boolean) {
        if (!canNotify(context)) return
        val title = if (coins.isEmpty()) {
            "No coin matches the conditions"
        } else {
            "Top ${coins.size} by trades (last $windowMinutes min)"
        }
        val lines = formatLines(coins)
        val summary = if (lines.isEmpty()) "Nothing to show right now" else lines.joinToString("\n")

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.forEach { style.addLine(it) }

        val notification = NotificationCompat.Builder(context, CHANNEL_LIST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (coins.isEmpty()) summary else coins.joinToString("  ") { it.symbol })
            .setStyle(if (coins.isEmpty()) NotificationCompat.BigTextStyle().bigText(summary) else style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(!sound)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(LIST_ID, notification)
    }

    /** Problem notice (e.g. connection lost) or its resolution. */
    @SuppressLint("MissingPermission")
    fun sendWarning(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_LIST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(WARNING_ID, notification)
    }
}
