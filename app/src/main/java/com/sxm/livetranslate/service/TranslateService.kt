package com.sxm.livetranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sxm.livetranslate.MainActivity
import com.sxm.livetranslate.R

/** 前台服务：保证翻译会话在后台/锁屏时持续运行，通知栏可停止。 */
class TranslateService : Service() {

    companion object {
        private const val CHANNEL_ID = "translate_session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.sxm.livetranslate.action.START"
        const val ACTION_STOP = "com.sxm.livetranslate.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TranslateService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslateService::class.java))
        }
    }

    private lateinit var manager: TranslateManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = TranslateManager.get(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在实时翻译"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manager.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 会话资源由 Manager 单例持有，Service 销毁只结束会话不销毁单例，
        // 保证"Activity 先死、Service 后死"期间会话仍可被 stop。
        if (manager.isRunning) {
            manager.stop()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "实时翻译",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop_translate), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
