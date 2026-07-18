package com.duanxinzhuanfa.xinxi.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 飞牛 NAS 云同步工具
 *
 * 功能：
 * 1. 解析飞牛分享链接 (https://subdomain.fnnas.net/s/shareId)
 * 2. 列出分享文件夹中的文件
 * 3. 按设备名和时间戳匹配最新的配置文件
 * 4. 下载配置文件
 *
 * 文件命名规范：{设备名}_{yyyyMMdd_HHmmss}_SmsForwarder.json
 * 或简化版：{设备名}_SmsForwarder.json
 */
@Suppress("PrivatePropertyName", "BlockingMethodInNonBlockingContext")
object NasSyncUtils {

    private val TAG = "NasSyncUtils"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** 飞牛 NAS 分享链接正则 */
    private val SHARE_URL_PATTERN = Pattern.compile(
        "https?://([a-zA-Z0-9.-]+\\.)?fnnas\\.net/s/([a-zA-Z0-9]+)"
    )

    /**
     * 解析飞牛分享链接，提取 baseUrl 和 shareId
     * @return Pair<baseUrl, shareId> 或 null
     */
    fun parseShareUrl(url: String): Pair<String, String>? {
        val matcher = SHARE_URL_PATTERN.matcher(url.trim())
        return if (matcher.find()) {
            val fullHost = matcher.group(0).substringBefore("/s/")
            val shareId = matcher.group(2)
            Log.d(TAG, "Parsed share URL: host=$fullHost, shareId=$shareId")
            Pair(fullHost, shareId)
        } else {
            Log.w(TAG, "Invalid share URL: $url")
            null
        }
    }

    /**
     * 获取分享文件夹中的文件列表
     * 飞牛NAS 的分享 API 返回 JSON 格式的文件列表
     */
    suspend fun listShareFiles(baseUrl: String, shareId: String): List<NasFileInfo> = withContext(Dispatchers.IO) {
        try {
            // 飞牛NAS API: GET /s/{shareId}/api/list
            val apiUrl = "$baseUrl/s/$shareId/api/list"
            Log.d(TAG, "Fetching file list from: $apiUrl")

            val json = httpGet(apiUrl)
            parseFileListJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list share files: ${e.message}")
            // 尝试备用方式：解析 HTML 页面
            try {
                val htmlUrl = "$baseUrl/s/$shareId"
                val html = httpGet(htmlUrl)
                parseFileListHtml(html)
            } catch (e2: Exception) {
                Log.e(TAG, "HTML parsing also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    /**
     * 根据设备名查找匹配的配置文件，优先取最新的
     * @param deviceName 设备标识名（如 SettingUtils.extraDeviceMark）
     */
    suspend fun findConfigFile(
        baseUrl: String, shareId: String, deviceName: String
    ): NasFileInfo? = withContext(Dispatchers.IO) {
        if (deviceName.isBlank()) return@withContext null

        val allFiles = listShareFiles(baseUrl, shareId)
        if (allFiles.isEmpty()) {
            Log.w(TAG, "No files found in share")
            return@withContext null
        }

        // 匹配设备名的文件（支持模糊匹配：文件名包含设备名 或 设备名包含在文件名中）
        val matchedFiles = allFiles.filter { file ->
            val name = file.name.lowercase()
            val dev = deviceName.lowercase()
            name.contains(dev) || dev.contains(name) || name.contains(deviceName.replace(" ", "").lowercase())
        }.filter { it.name.endsWith(".json") }

        if (matchedFiles.isEmpty()) {
            Log.w(TAG, "No config file found for device '$deviceName'")
            // 列出所有 JSON 文件供参考
            val jsonFiles = allFiles.filter { it.name.endsWith(".json") }
            Log.d(TAG, "Available JSON files: ${jsonFiles.map { it.name }}")
            return@withContext null
        }

        // 取最新修改时间的文件
        val latest = matchedFiles.maxByOrNull { it.modifiedTime }
        Log.d(TAG, "Found config for '$deviceName': ${latest?.name}")
        latest
    }

    /**
     * 下载文件到本地
     */
    suspend fun downloadFile(
        baseUrl: String, shareId: String, fileInfo: NasFileInfo, saveDir: File
    ): File? = withContext(Dispatchers.IO) {
        try {
            val downloadUrl = "$baseUrl/s/$shareId/download${fileInfo.path}"
            Log.d(TAG, "Downloading: $downloadUrl")

            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            // 飞牛 NAS 可能需要 Referer 头
            connection.setRequestProperty("Referer", "$baseUrl/s/$shareId")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed: HTTP ${connection.responseCode}")
                return@withContext null
            }

            if (!saveDir.exists()) saveDir.mkdirs()
            val saveFile = File(saveDir, fileInfo.name)
            connection.inputStream.use { input ->
                FileOutputStream(saveFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Downloaded to: ${saveFile.absolutePath}")
            saveFile
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }

    /**
     * 一键拉取：解析链接 → 找匹配文件 → 下载
     * @return 下载的配置文件 File，失败返回 null
     */
    suspend fun pullConfig(shareUrl: String, deviceName: String, saveDir: File): File? {
        val (baseUrl, shareId) = parseShareUrl(shareUrl) ?: return null
        val fileInfo = findConfigFile(baseUrl, shareId, deviceName) ?: return null
        return downloadFile(baseUrl, shareId, fileInfo, saveDir)
    }

    // ================== Private Helpers ==================

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "SmsForwarder/4.0")
        connection.setRequestProperty("Accept", "application/json, text/html")

        return if (connection.responseCode in 200..299) {
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
        } else {
            throw Exception("HTTP ${connection.responseCode}")
        }
    }

    /** 解析飞牛NAS JSON API 返回的文件列表 */
    private fun parseFileListJson(json: String): List<NasFileInfo> {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data")
            val files = data?.optJSONArray("files") ?: root.optJSONArray("files") ?: JSONArray()
            (0 until files.length()).mapNotNull { i ->
                val file = files.getJSONObject(i)
                NasFileInfo(
                    name = file.optString("name", file.optString("fileName", "")),
                    path = file.optString("path", file.optString("filePath", "/${file.optString("name")}")),
                    size = file.optLong("size", file.optLong("fileSize", 0)),
                    modifiedTime = file.optLong("mtime", file.optLong("modifiedTime", 0))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            emptyList()
        }
    }

    /** 解析飞牛NAS HTML 分享页面的文件列表（备用方案） */
    private fun parseFileListHtml(html: String): List<NasFileInfo> {
        val files = mutableListOf<NasFileInfo>()
        // 匹配 <a> 标签中的文件名模式
        val filePattern = Pattern.compile(
            """<a[^>]*href=["']([^"']*download[^"']*)["'][^>]*>([^<]+\.json)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = filePattern.matcher(html)
        while (matcher.find()) {
            val path = matcher.group(1) ?: continue
            val name = matcher.group(2) ?: continue
            files.add(NasFileInfo(name = name, path = path, size = 0, modifiedTime = 0))
        }
        Log.d(TAG, "HTML parsed: ${files.size} files found")
        return files
    }
}

/**
 * NAS 文件信息
 */
data class NasFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedTime: Long
)
