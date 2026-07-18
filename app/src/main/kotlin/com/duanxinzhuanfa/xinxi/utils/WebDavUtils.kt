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
     * 备份当前配置到 WebDAV。
     * @return 上传的文件名，失败返回 null
     */
    fun backupToWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        if (baseUrl.isBlank()) {
            Log.w(TAG, "backupToWebDav: baseUrl is blank")
            return null
        }
        try {
            val fileName = "${sanitizeFileName(deviceName)}_${DATE_FORMAT.format(Date())}.json"
            val cloneInfo = HttpServerUtils.exportSettings()
            val jsonStr = Gson().toJson(cloneInfo)
            val success = uploadFile(baseUrl, fileName, jsonStr, username, password)
            if (success) {
                Log.d(TAG, "backupToWebDav: uploaded $fileName")
                return fileName
            }
        } catch (e: Exception) {
            Log.e(TAG, "backupToWebDav error: ${e.message}")
        }
        return null
    }

    /**
     * 从 WebDAV 拉取当前设备最新的配置文件并恢复。
     * @return 恢复成功返回文件名，失败返回 null
     */
    fun pullLatestFromWebDav(
        baseUrl: String, deviceName: String,
        username: String? = null, password: String? = null
    ): String? {
        if (baseUrl.isBlank()) return null
        try {
            val files = listConfigFiles(baseUrl, username, password)
            // 找到匹配当前设备名的文件（前缀匹配），取最新的（已按时间降序排列）
            val matchFile = files.firstOrNull { (name, _) ->
                name.startsWith(sanitizeFileName(deviceName)) && name.endsWith(".json")
            } ?: files.firstOrNull()

            if (matchFile == null) {
                Log.w(TAG, "pullLatestFromWebDav: no config file found for device '$deviceName'")
                return null
            }

            val (fileName, _) = matchFile
            val jsonStr = downloadFile(baseUrl, fileName, username, password)
            if (jsonStr.isNullOrBlank()) return null

            val builder = GsonBuilder()
            builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            val gson = builder.create()
            val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java) ?: return null

            HttpServerUtils.compareVersion(cloneInfo)
            val restored = HttpServerUtils.restoreSettings(cloneInfo)
            if (restored) {
                Log.d(TAG, "pullLatestFromWebDav: restored from $fileName")
                return fileName
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullLatestFromWebDav error: ${e.message}")
        }
        return null
    }

    /** 文件名安全化：去除路径分隔符和特殊字符 */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
    }
}
