package com.example.safeguardassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * API 34+: captura de ecrã exige um serviço em primeiro plano com tipo [mediaProjection].
 * Mantém a projeção activa enquanto o inspetor usa o copiloto com visão.
 */
class MediaProjectionCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
    private var callbackRegistered = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ScreenCaptureHolder.clear()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getMediaProjectionResultIntent()
                if (code == 0 || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithType()
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(code, data)
                registerStopCallbackOnce(projection)
                ScreenCaptureHolder.setProjection(projection)
            }
            else -> {
                // Reinício do sistema: não temos Intent válido
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ScreenCaptureHolder.clear()
        super.onDestroy()
    }

    private fun registerStopCallbackOnce(projection: MediaProjection?) {
        if (projection == null || callbackRegistered) return
        callbackRegistered = true
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Sistema revogou permissão / stopScreenCapture.
                    ScreenCaptureHolder.clear()
                    stopForegroundCompat()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun startForegroundWithType() {
        createChannelIfNeeded()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.media_projection_notif_title))
            .setContentText(getString(R.string.media_projection_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.media_projection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    private fun Intent.getMediaProjectionResultIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_DATA)
        }

    companion object {
        const val ACTION_START = "com.example.safeguardassistant.mediaproj.START"
        const val ACTION_STOP = "com.example.safeguardassistant.mediaproj.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "safeguard_media_projection"
        private const val NOTIFICATION_ID = 7101

        fun stop(context: Context) {
            context.startService(Intent(context, MediaProjectionCaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
