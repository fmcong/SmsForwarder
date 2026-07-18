package com.duanxinzhuanfa.xinxi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.activity.MainActivity
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_ID
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_NAME
import com.duanxinzhuanfa.xinxi.utils.FRONT_NOTIFY_ID

/**
 * 辅助服务：利用 Android 双服务通知隐藏技巧，彻底消除前台通知栏痕迹。
 *
 * 原理：
 * 1. ForegroundService 先用 startForeground(id, notification) 注册前台通知
 * 2. HideNotificationService 也用 startForeground(同一个 id, notification) 注册
 * 3. HideNotificationService 立即调用 stopForeground(true) 移除通知
 * 4. 由于两个服务共享同一个通知 ID，通知被移除后 ForegroundService 仍以前台优先级运行
 *
 * 配合 IMPORTANCE_NONE 通道，通知本身在出现瞬间也是完全不可见的。
 */
class HideNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 确保通知通道存在（和 ForegroundService 用同一个）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE)
                channel.description = ""
                channel.enableLights(false)
                channel.enableVibration(false)
                channel.setSound(null, null)
                nm.createNotificationChannel(channel)
            }

            // 创建最精简的通知（空标题、空内容）
            val flagsImmutable = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flagsImmutable)
            val notification = NotificationCompat.Builder(this, FRONT_CHANNEL_ID)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(R.drawable.ic_forwarder)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            // 用同一个 ID 注册前台服务
            startForeground(FRONT_NOTIFY_ID, notification)
            // 立即移除通知（关键步骤：去掉通知栏展示）
            stopForeground(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 延迟停止自身，让 ForegroundService 有时间接管前台状态
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 500)
        return START_NOT_STICKY
    }
}
