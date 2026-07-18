package com.duanxinzhuanfa.xinxi.utils

import com.duanxinzhuanfa.xinxi.entity.CloneInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端工具：通过 OkHttp 实现配置文件的远程备份和同步。
 *
 * 文件命名规范：{deviceName}_{yyyyMMdd_HHmmss}.json
 * 目录结构：WebDAV 根路径下的文件夹，所有设备的配置文件混放，按设备名+时间戳区分。
 *
 * 用法：
 * - backupToWebDav(url, username, password): 上传当前配置
 * - pullLatestFromWebDav(url, deviceName): 拉取当前设备的最新配置
 * - listConfigFiles(url): 列出目录下所有配置文件
 */
@Suppress("PrivatePropertyName", "DEPRECATION", "unused")
object WebDavUtils {

    private val TAG = "WebDavUtils"
    private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 构建带认证的 WebDAV URL（追加用户名密码到 URL 中）。
     * 格式：https://user:pass@host:port/path/
     */
    private fun buildAuthUrl(baseUrl: String, username: String?, password: String?): String {
        if (username.isNullOrBlank()) return baseUrl.trimEnd('/')
        val url = baseUrl.trimEnd('/')
        val userInfo = Credentials.basic(username, password ?: "")
        // OkHttp 会自动处理 Authorization header
        return url
    }

    /** 获取 Authorization header 值 */
    private fun authHeader(username: String?, password: String?): String? {
        if (username.isNullOrBlank()) return null
        return Credentials.basic(username, password ?: "")
    }

    // ==================== PROPFIND：列出文件 ====================

    /**
     * 列出 WebDAV 目录下的配置文件，返回 (文件名, 最后修改时间) 列表。
     */
    fun listConfigFiles(baseUrl: String, username: String? = null, password: String? = null): List<Pair<String, String>> {
        val url = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url(url)
            .apply { authHeader(username, password)?.let { header("Authorization", it) } }
            .method("PROPFIND", null)
            .header("Depth", "1")
            .header("Content-Type", "application/xml")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "PROPFIND failed: ${response.code} ${response.message}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            response.close()
            parsePropfindResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "listConfigFiles error: ${e.message}")
            emptyList()
        }
    }

    /** 解析 PROPFIND XML 响应，提取 .json 文件名和修改时间 */
    private fun parsePropfindResponse(xml: String): List<Pair<String, String>> {
        val files = mutableListOf<Pair<String, String>>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var currentHref: String? = null
            var currentModified: String? = null
            var inResponse = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> inResponse = true
                            "href" -> currentHref = parser.nextText()
                            "getlastmodified" -> currentModified = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response" && inResponse) {
                            val href = currentHref?.trimEnd('/')?.substringAfterLast('/') ?: ""
                            if (href.endsWith(".json") && href != "") {
                                files.add(href to (currentModified ?: ""))
                            }
                            currentHref = null
                            currentModified = null
                            inResponse = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePropfindResponse error: ${e.message}")
        }
        // 按修改时间降序排列（最新的在前）
        return files.sortedByDescending { it.second }
    }

    // ==================== GET：下载文件 ====================

    /**
     * 从 WebDAV 下载指定文件内容。
     */
    fun downloadFile(baseUrl: String, fileName: String, username: String? = null, password: String? = null): String? {
        val url = "${baseUrl.trimEnd('/')}/$fileName"
        val request = Request.Builder()
            .url(url)
            .apply { authHeader(username, password)?.let { header("Authorization", it) } }
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GET failed: ${response.code}")
                return null
            }
            val body = response.body?.string()
            response.close()
            body
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile error: ${e.message}")
            null
        }
    }

    // ==================== PUT：上传文件 ====================

    /**
     * 上传配置文件到 WebDAV。
     */
    fun uploadFile(
        baseUrl: String, fileName: String, content: String,
        username: String? = null, password: String? = null
    ): Boolean {
        val url = "${baseUrl.trimEnd('/')}/$fileName"
        @Suppress("DEPRECATION")
        val requestBody = RequestBody.create(MediaType.parse("application/json"), content)
        val request = Request.Builder()
            .url(url)
            .apply { authHeader(username, password)?.let { header("Authorization", it) } }
            .put(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.w(TAG, "PUT failed: ${response.code} ${response.message}")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile error: ${e.message}")
            false
        }
    }

    // ==================== 高层业务 API ====================

    /**
     * 计算当前配置的 MD5 哈希（用于去重比较）。
     */
    fun computeConfigHash(): String {
        return try {
            val cloneInfo = HttpServerUtils.exportSettings()
            val jsonStr = Gson().toJson(cloneInfo)
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(jsonStr.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "computeConfigHash error: ${e.message}")
            ""
        }
    }

    /**
     * 智能备份：仅当配置内容发生变化时才上传。
     * 比较当前配置哈希与上次上传的哈希，相同则跳过以节省流量和电量。
     *
     * @return "uploaded"=已上传, "skipped"=内容相同跳过, null=失败
     */
    fun smartBackupToWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        if (baseUrl.isBlank()) return null
        try {
            val currentHash = computeConfigHash()
            if (currentHash.isEmpty()) return null

            // 内容未变化，跳过上传
            if (currentHash == SettingUtils.lastWebdavUploadHash) {
                Log.d(TAG, "smartBackup: config unchanged (hash=$currentHash), skipped")
                return "skipped"
            }

            val fileName = "${sanitizeFileName(deviceName)}_${DATE_FORMAT.format(Date())}.json"
            val cloneInfo = HttpServerUtils.exportSettings()
            val jsonStr = Gson().toJson(cloneInfo)
            val success = uploadFile(baseUrl, fileName, jsonStr, username, password)
            if (success) {
                SettingUtils.lastWebdavUploadHash = currentHash
                SettingUtils.lastWebdavUploadTime = System.currentTimeMillis()
                Log.d(TAG, "smartBackup: uploaded $fileName (hash=$currentHash)")
                return "uploaded"
            }
        } catch (e: Exception) {
            Log.e(TAG, "smartBackup error: ${e.message}")
        }
        return null
    }

    /**
     * 智能拉取：仅当远程配置文件比本地更新时才下载同步。
     * 通过 PROPFIND 获取最新文件的修改时间，与上次同步时间比较。
     *
     * @return "restored"=已恢复, "skipped"=远程未更新, null=失败
     */
    fun smartPullFromWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        if (baseUrl.isBlank()) return null
        try {
            val files = listConfigFiles(baseUrl, username, password)
            val matchFile = files.firstOrNull { (name, _) ->
                name.startsWith(sanitizeFileName(deviceName)) && name.endsWith(".json")
            } ?: files.firstOrNull()

            if (matchFile == null) {
                Log.w(TAG, "smartPull: no config file for '$deviceName'")
                return null
            }

            val (fileName, fileTime) = matchFile

            // 解析远程文件时间并与上次同步时间比较
            val remoteTime = parseWebDavTime(fileTime)
            val lastSync = SettingUtils.lastWebdavSyncTime
            if (remoteTime > 0 && lastSync > 0 && remoteTime <= lastSync) {
                Log.d(TAG, "smartPull: remote not newer (remote=$fileTime, lastSync=$lastSync), skipped")
                return "skipped"
            }

            // 远程更新了，下载并恢复
            val jsonStr = downloadFile(baseUrl, fileName, username, password)
            if (jsonStr.isNullOrBlank()) return null

            val builder = GsonBuilder()
            builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            val gson = builder.create()
            val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java) ?: return null

            HttpServerUtils.compareVersion(cloneInfo)
            val restored = HttpServerUtils.restoreSettings(cloneInfo)
            if (restored) {
                SettingUtils.lastWebdavSyncTime = System.currentTimeMillis()
                Log.d(TAG, "smartPull: restored from $fileName")
                return "restored"
            }
        } catch (e: Exception) {
            Log.e(TAG, "smartPull error: ${e.message}")
        }
        return null
    }

    /** 解析 WebDAV 返回的时间字符串为毫秒时间戳 */
    private fun parseWebDavTime(timeStr: String): Long {
        if (timeStr.isBlank()) return 0
        return try {
            // WebDAV 返回格式: "Sat, 19 Jul 2026 12:00:00 GMT"
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            format.parse(timeStr)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 备份当前配置到 WebDAV（兼容旧接口，内部调用智能版本）。
     * @return 上传的文件名，"skipped"=跳过，null=失败
     */
    fun backupToWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        return smartBackupToWebDav(baseUrl, deviceName, username, password)
    }

    /**
     * 从 WebDAV 拉取当前设备最新的配置文件并恢复（兼容旧接口）。
     * @return 文件名，"skipped"=跳过，"restored"=已恢复，null=失败
     */
    fun pullLatestFromWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        return smartPullFromWebDav(baseUrl, deviceName, username, password)
    }

    /** 文件名安全化：去除路径分隔符和特殊字符 */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
    }
}
