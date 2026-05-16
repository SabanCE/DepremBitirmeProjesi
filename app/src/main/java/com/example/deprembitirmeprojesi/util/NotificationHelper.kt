package com.example.deprembitirmeprojesi.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.ui.MainActivity

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // LOW Level - Silent
            val lowChannel = NotificationChannel(
                Constants.CHANNEL_LOW,
                "Düşük Riskli Sarsıntılar",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hafif sarsıntılar için sessiz bildirimler"
                enableVibration(false)
            }

            // MEDIUM Level - Vibration & High Priority
            val mediumChannel = NotificationChannel(
                Constants.CHANNEL_MEDIUM,
                "Orta Riskli Sarsıntılar",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Hissedilebilir sarsıntılar için titreşimli bildirimler"
                enableVibration(true)
            }

            // HIGH Level - Loud Sound & Full Screen
            val highChannel = NotificationChannel(
                Constants.CHANNEL_HIGH,
                "KRİTİK DEPREM UYARISI",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Yüksek riskli deprem alarmları"
                enableVibration(true)
                // Özel acil durum sesi (res/raw/alarm_sound.mp3)
                val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}")
                setSound(soundUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }

            notificationManager.createNotificationChannels(listOf(lowChannel, mediumChannel, highChannel))
        }
    }

    fun sendNotification(score: Double) {
        // Bu metod artık sadece yerel sarsıntı analizi devam ederken bilgi vermek için kullanılacak
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_LOW)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sarsıntı Analiz Ediliyor")
            .setContentText("Cihazınızda bir sarsıntı algılandı, doğrulanıyor...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(1001, builder.build())
    }

    fun sendConfirmedNotification(magnitude: Float, nearby: Int) {
        val formattedMagnitude = String.format("%.1f", magnitude)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("ALERT_TYPE", "CONFIRMED_EARTHQUAKE")
            putExtra("MAGNITUDE", magnitude)
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_HIGH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 BÖLGESEL SARSINTI UYARISI!")
            .setContentText("Çevrenizdeki cihazlar sarsıntı bildirdi. Lütfen tedbirli olun!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(1002, builder.build())
    }
}
