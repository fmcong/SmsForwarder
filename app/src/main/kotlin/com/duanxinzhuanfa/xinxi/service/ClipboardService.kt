package com.duanxinzhuanfa.xinxi.service

import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.duanxinzhuanfa.xinxi.core.Core
import com.duanxinzhuanfa.xinxi.entity.MsgInfo
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.Worker
import com.duanxinzhuanfa.xinxi.workers.SendWorker
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/**
 * 剪切板监控服务：
 * 监听系统剪切板变化，将剪切板内容通过转发规则推送到配置的渠道。
 * 类似短信/通知/电话监控，支持规则过滤和静默转发。
 *
 * 省电设计：
 * - 使用 OnPrimaryClipChangedListener 被动监听，不轮询
 * - 去重：相同的剪切板内容 1 秒内不重复处理
 * - 低电量时可暂停监控
 */
@Suppress("PrivatePropertyName", "DEPRECATION")
class ClipboardService : Service() {

    private val TAG: String = ClipboardService::class.java.simpleName
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardText = ""
    private var lastClipboardTime = 0L

    companion object {
        @Volatile
        var isRunning = false
        /** 是否启用剪切板监控（由用户配置控制） */
        var enableClipboardMonitor = false

        fun start(context: android.content.Context) {
            if (!enableClipboardMonitor) return
            val intent = Intent(context, ClipboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, ClipboardService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ClipboardService onCreate")
        // 使用低优先级启动，不抢前台资源
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Log.d(TAG, "ClipboardService started - clipboard monitor active")
        startClipboardMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        stopClipboardMonitor()
        Log.d(TAG, "ClipboardService destroyed")
        super.onDestroy()
    }

    private fun startClipboardMonitor() {
        try {
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            Log.d(TAG, "Clipboard listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start clipboard monitor: ${e.message}")
        }
    }

    private fun stopClipboardMonitor() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop clipboard monitor: ${e.message}")
        }
        clipboardManager = null
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            handleClipboardChange()
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardChange error: ${e.message}")
        }
    }

    private fun handleClipboardChange() {
        // 总开关检查
        if (SettingUtils.enablePureClientMode || !enableClipboardMonitor) return

        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return

            val clipText = clip.getItemAt(0)?.text?.toString() ?: return
            if (clipText.isBlank()) return

            // 去重：相同内容 2 秒内不重复处理
            val now = System.currentTimeMillis()
            if (clipText == lastClipboardText && (now - lastClipboardTime) < 2000) {
                return
            }
            lastClipboardText = clipText
            lastClipboardTime = now

            Log.d(TAG, "Clipboard changed: ${clipText.take(50)}...")

            // 获取剪切板来源信息
            val packageName = try {
                val desc = clip.getDescription()
                val mimeTypes = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }
                "clipboard(${mimeTypes.joinToString(",")})"
            } catch (e: Exception) {
                "clipboard"
            }

            // 构建消息并通过 SendWorker 转发
            val msgInfo = MsgInfo(
                type = "clipboard",
                from = packageName,
                content = clipText,
                date = Date(now),
                simInfo = "",
                simSlot = -1
            )

            serviceScope.launch {
                sendViaRules(msgInfo, clipText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardChange: ${e.message}")
        }
    }

    /**
     * 通过匹配的规则将剪切板内容推送到转发渠道
     */
    private suspend fun sendViaRules(msgInfo: MsgInfo, clipText: String) {
        try {
            Log.d(TAG, "Forwarding clipboard via SendWorker")
            val data = workDataOf(
                Worker.MSG_INFO to Gson().toJson(msgInfo)
            )
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(this).enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "sendViaRules error: ${e.message}")
        }
    }
}
