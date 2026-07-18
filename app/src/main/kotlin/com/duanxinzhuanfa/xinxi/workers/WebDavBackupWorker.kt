package com.duanxinzhuanfa.xinxi.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.PhoneUtils
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.WebDavUtils
import java.util.concurrent.TimeUnit

/**
 * WebDAV 云备份 Worker：每隔 N 小时自动备份配置到 WebDAV。
 *
 * 触发条件：WebDAV URL 已配置 + 非纯客户端模式。
 * 备份文件命名：{deviceName}_{yyyyMMdd_HHmmss}.json
 */
@Suppress("PrivatePropertyName")
class WebDavBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val TAG = "WebDavBackupWorker"

    companion object {
        /** 默认备份间隔（小时） */
        private const val BACKUP_INTERVAL_HOURS = 6L

        /** 启动定期云备份任务 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(5, TimeUnit.MINUTES) // 首次启动后 5 分钟执行
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "webdav_backup",
                ExistingPeriodicWorkPolicy.KEEP, // 已有任务则保留
                request
            )
        }

        /** 取消定期云备份任务 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("webdav_backup")
        }
    }

    override suspend fun doWork(): Result {
        val url = SettingUtils.webdavUrl
        if (url.isBlank()) {
            Log.d(TAG, "WebDAV URL not configured, skipping")
            return Result.success()
        }
        if (SettingUtils.enablePureClientMode) {
            Log.d(TAG, "Pure client mode, skipping")
            return Result.success()
        }

        val deviceName = SettingUtils.extraDeviceMark.ifBlank { PhoneUtils.getDeviceName() }
        val username = SettingUtils.webdavUsername.ifBlank { null }
        val password = SettingUtils.webdavPassword.ifBlank { null }

        return try {
            // 智能备份：内容未变化自动跳过，省电省流量
            val result = WebDavUtils.smartBackupToWebDav(url, deviceName, username, password)
            when (result) {
                "uploaded" -> {
                    Log.d(TAG, "Auto backup: uploaded")
                    Result.success()
                }
                "skipped" -> {
                    Log.d(TAG, "Auto backup: skipped (unchanged)")
                    Result.success()
                }
                else -> {
                    Log.w(TAG, "Auto backup failed, will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup error: ${e.message}")
            Result.retry()
        }
    }
}
