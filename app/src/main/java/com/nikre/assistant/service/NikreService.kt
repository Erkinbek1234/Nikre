package com.nikre.assistant.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nikre.assistant.MainActivity
import com.nikre.assistant.R

/**
 * Nikre'ning yuragi: fonda doim ishlaydi, wake word'ni tinglaydi,
 * so'z eshitilgach STT -> Intent tushunish -> javob berish zanjirini boshqaradi.
 *
 * Hozircha bu faqat skelet. Keyingi bosqichlarda shu yerga:
 *  - wakeword/PorcupineListener
 *  - stt/VoskRecognizer
 *  - ai/OnlineAiClient (internet bo'lsa) yoki commands/OfflineCommandHandler (internet bo'lmasa)
 * ulanadi.
 */
class NikreService : Service() {

    companion object {
        private const val CHANNEL_ID = "nikre_foreground_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // TODO (keyingi bosqich): wake word tinglashni shu yerda boshlash
        // wakeWordListener.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY -> tizim xizmatni o'chirib qo'ysa, qayta ishga tushiradi
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: wakeWordListener.stop(), sttRecognizer.close() va h.k.
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nikre xizmati",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nikre fonda ovozingizni tinglab turadi"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nikre")
            .setContentText("Tinglab turibman...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
