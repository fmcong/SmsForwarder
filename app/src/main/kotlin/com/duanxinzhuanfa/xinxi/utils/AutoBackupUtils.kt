package com.duanxinzhuanfa.xinxi.utils

import android.content.Context
import android.os.Environment
import com.duanxinzhuanfa.xinxi.core.Core
import com.duanxinzhuanfa.xinxi.entity.CloneInfo
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置文件自动备份工具
 *
 * 备份策略：
 * - 备份目录：{Download}/SmsForwarder/backups/
 * - 文件命名：{设备名}_{yyyyMMdd_HHmmss}.json
 * - 自动清理：保留最近 N 份备份，删除旧文件
 */
object AutoBackupUtils {

    private val TAG = "AutoBackupUtils"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 最大保留备份数量 */
    private const val MAX_BACKUP_COUNT = 10

    /** 获取备份目录 */
    fun getBackupDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SmsForwarder/backups"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 执行一次配置备份
     * @param deviceName 设备标识名
     * @return 备份文件，失败返回 null
     */
    suspend fun backupNow(deviceName: String): File? = withContext(Dispatchers.IO) {
        try {
            val backupDir = getBackupDir()
            val timestamp = dateFormat.format(Date())
            val safeDeviceName = deviceName.ifBlank { "unknown" }
                .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val fileName = "${safeDeviceName}_${timestamp}.json"
            val backupFile = File(backupDir, fileName)

            // 导出当前配置
            val cloneInfo = CloneInfo(
                versionCode = AppUtils.getAppVersionCode(),
                versionName = AppUtils.getAppVersionName(),
                settings = SharedPreference.exportPreference(),
                senderList = Core.sender.getAllNonCache(),
                ruleList = Core.rule.getAllNonCache(),
                frpcList = Core.frpc.getAllNonCache(),
                taskList = Core.task.getAllNonCache(),
            )

            backupFile.writeText(gson.toJson(cloneInfo))
            Log.d(TAG, "Backup saved: ${backupFile.absolutePath}")

            // 清理旧备份
            cleanOldBackups(backupDir, safeDeviceName)

            backupFile
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
            null
        }
    }

    /**
     * 获取指定设备的最新备份文件
     */
    fun getLatestBackup(deviceName: String): File? {
        val backupDir = getBackupDir()
        val safeDeviceName = deviceName.ifBlank { "unknown" }
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")

        return backupDir.listFiles()
            ?.filter { it.name.startsWith(safeDeviceName) && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * 列出所有本地备份
     */
    fun listBackups(deviceName: String? = null): List<File> {
        val backupDir = getBackupDir()
        val allFiles = backupDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        return if (deviceName != null) {
            val safeName = deviceName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            allFiles.filter { it.name.startsWith(safeName) }.sortedByDescending { it.lastModified() }
        } else {
            allFiles.sortedByDescending { it.lastModified() }
        }
    }

    /** 清理旧备份，保留最近 MAX_BACKUP_COUNT 份 */
    private fun cleanOldBackups(backupDir: File, deviceName: String) {
        try {
            val backups = backupDir.listFiles()
                ?.filter { it.name.startsWith(deviceName) && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (backups.size > MAX_BACKUP_COUNT) {
                backups.drop(MAX_BACKUP_COUNT).forEach { oldFile ->
                    oldFile.delete()
                    Log.d(TAG, "Cleaned old backup: ${oldFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clean old backups failed: ${e.message}")
        }
    }
}
