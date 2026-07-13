package es.personal.avisosairef.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import es.personal.avisosairef.R
import es.personal.avisosairef.data.parser.Publicacion

class AirefNotificationManager(private val context: Context) {
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyNewPublications(monitorName: String, publications: List<Publicacion>) {
        if (publications.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val first = publications.first()
        val intent = Intent(Intent.ACTION_VIEW, first.url.toUri())
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = NotificationCompat.InboxStyle()
        publications.take(6).forEach { style.addLine(it.title.uppercase()) }
        if (publications.size > 1) style.setSummaryText("${publications.size} CAMBIOS NUEVOS")

        val body = if (publications.size == 1) first.title.uppercase() else "${publications.size} CAMBIOS NUEVOS DETECTADOS"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CAMBIO EN ${monitorName.uppercase()}")
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setColor("#063347".toColorInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (publications.size > 1) builder.setStyle(style)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + monitorName.hashCode(), builder.build())
    }

    companion object {
        const val CHANNEL_ID = "web_refresh_changes"
        const val NOTIFICATION_ID = 20251022
    }
}
