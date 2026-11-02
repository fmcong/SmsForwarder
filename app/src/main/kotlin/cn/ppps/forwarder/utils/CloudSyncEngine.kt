package cn.ppps.forwarder.utils

import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.server.model.BaseResponse
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.xuexiang.xutil.data.ConvertTools
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

//自动双向同步引擎（手动推送模式）：
//①拉取：/clone/check 发现 updated_ts 变化 → /clone/pull → 应用（后台「下发配置」自动生效）
//②推送：仅当 check 返回 need_push=true（后台点「读取配置」）→ 强制 /clone/push 一次
//全程静默失败（同步失败不影响 App 使用）；同步执行，调用方须在后台线程调用（内部有单飞锁防并发）
@Suppress("PrivatePropertyName", "DEPRECATION")
object CloudSyncEngine {

    private val TAG: String = CloudSyncEngine::class.java.simpleName

    private val syncing = AtomicBoolean(false)

    //check 结果：无动作 / 云端有更新需拉取应用 / 后台请求回传当前配置
    enum class CheckResult { NONE, CHANGED, NEED_PUSH }

    private var lastAppliedHash: String by SharedPreference(SP_LAST_CLOUD_SYNC_HASH, "")
    private var lastPushedHash: String by SharedPreference(SP_LAST_PUSHED_HASH, "")
    private var lastCloudSyncTime: String by SharedPreference(SP_LAST_CLOUD_SYNC_TIME, "")
    private var lastStoreTs: String by SharedPreference(SP_LAST_STORE_TS, "")

    //轻量检查时拿到的云端 store 时间戳，全量拉取应用成功后才落盘（避免应用失败却记已同步）
    @Volatile
    private var pendingStoreTs: String = ""

    //轻量检查 + 按需动作：App 在前台时每 60s 调一次（只取 store 时间戳，几十字节；后台靠 Worker 30 分钟兜底）
    fun syncIfRemoteChanged() {
        if (!syncing.compareAndSet(false, true)) {
            Log.d(TAG, "syncIfRemoteChanged: 上一轮同步尚未结束，跳过")
            return
        }
        try {
            val serverAddress = HttpServerUtils.serverAddress.trim().trimEnd('/')
            val clientSignKey = HttpServerUtils.clientSignKey.trim()
            val clientUserCode = HttpServerUtils.clientUserCode.trim()
            if (serverAddress.isNullOrEmpty() || !CommonUtils.checkUrl(serverAddress)
                || clientSignKey.isNullOrEmpty() || clientUserCode.isNullOrEmpty()
                || HttpServerUtils.clientSafetyMeasures == 2
            ) {
                return
            }

            when (checkForUpdates(serverAddress, clientSignKey, clientUserCode)) {
                CheckResult.NONE -> Log.d(TAG, "云端 store 未变化，跳过")

                CheckResult.CHANGED -> {
                    if (pullAndApply(serverAddress, clientSignKey, clientUserCode)) {
                        lastStoreTs = pendingStoreTs
                    }
                }

                CheckResult.NEED_PUSH -> {
                    //后台「读取配置」：强制推一次当前配置（绕过哈希观察），成功后服务端自动清 flag
                    Log.i(TAG, "服务端请求回传配置（need_push）")
                    pushIfChanged(serverAddress, clientSignKey, clientUserCode, force = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncIfRemoteChanged: ${e.message}")
        } finally {
            syncing.set(false)
        }
    }

    //GET /clone/check?uc=xxx（X-Clone-Key 鉴权，User-Agent 显式）→ 解析 need_push / updated_ts
    private fun checkForUpdates(serverAddress: String, clientSignKey: String, clientUserCode: String): CheckResult {
        val connection = URL("$serverAddress/clone/check?uc=$clientUserCode").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SmsForwarder-Clone/1.0")
            connection.setRequestProperty("X-Clone-Key", clientSignKey)
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "checkForUpdates: HTTP $code")
                return CheckResult.NONE
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = Gson().fromJson(body, JsonObject::class.java) ?: return CheckResult.NONE
            if (root.get("code")?.asInt != 200) return CheckResult.NONE
            val data = root.getAsJsonObject("data") ?: return CheckResult.NONE
            if (data.get("exists")?.asBoolean == false) return CheckResult.NONE
            val ts = data.get("updated_ts")?.asString ?: return CheckResult.NONE
            if (ts.isEmpty()) return CheckResult.NONE
            pendingStoreTs = ts
            //后台点「读取配置」：优先响应强制回传（不受 ts 比对影响）
            if (data.get("need_push")?.asBoolean == true) return CheckResult.NEED_PUSH
            return if (ts != lastStoreTs) CheckResult.CHANGED else CheckResult.NONE
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdates: ${e.message}")
            return CheckResult.NONE
        } finally {
            connection.disconnect()
        }
    }

    //①拉取：内容有变化才应用；返回 true=已应用或确认无变化，false=请求/解析失败
    private fun pullAndApply(serverAddress: String, clientSignKey: String, clientUserCode: String): Boolean {
        val responseJson = pullFromServer(serverAddress, clientSignKey, clientUserCode) ?: return false
        val root = Gson().fromJson(responseJson, JsonObject::class.java)
            ?: throw IOException("响应 JSON 解析失败")
        val dataElement = root.get("data") ?: throw IOException("响应缺少 data 字段")
        val remoteHash = sha256(dataElement.toString())
        if (remoteHash == lastAppliedHash) {
            Log.d(TAG, "云端配置未变化")
            return true
        }

        //解析 BaseResponse<CloneInfo>（Date 字段替换为当前时间，与 CloneFragment 一致）
        val builder = GsonBuilder()
        builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
        val resp: BaseResponse<CloneInfo> = builder.create().fromJson(responseJson, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
        if (resp.code != 200) {
            Log.w(TAG, "pullAndApply: code=${resp.code}, msg=${resp.msg}")
            return false
        }
        val cloneInfo = resp.data ?: throw IOException("响应 data 为空")

        //判断版本是否一致（版本不兼容会抛异常，由调用方捕获，不更新哈希）
        HttpServerUtils.compareVersion(cloneInfo)

        sanitizeSettingsForRestore(cloneInfo)

        if (HttpServerUtils.restoreSettings(cloneInfo)) {
            //restoreSettings 会清空全部 SP，应用后重新写入本地凭证与同步标记
            HttpServerUtils.serverAddress = serverAddress
            HttpServerUtils.clientUserCode = clientUserCode
            HttpServerUtils.clientSignKey = clientSignKey
            recordApplied(remoteHash)
            Log.i(TAG, "云端配置已应用: $lastCloudSyncTime")
        }
        return true
    }

    //②推送：默认内容有变化才推送（force=true 强制推一次，用于后台 need_push 请求），成功后记录本地哈希
    private fun pushIfChanged(serverAddress: String, clientSignKey: String, clientUserCode: String, force: Boolean = false) {
        val exportInfo = buildLocalExport(clientUserCode)
        val localHash = sha256(Gson().toJson(exportInfo))
        if (!force && localHash == lastPushedHash) {
            Log.d(TAG, "本地配置未变化，无需推送")
            return
        }

        //构造请求体（与 CloneFragment.doPushData 一致）
        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        msgMap["data"] = exportInfo

        var requestMsg: String = Gson().toJson(msgMap)
        if (HttpServerUtils.clientSafetyMeasures == 3) {
            val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
            requestMsg = ConvertTools.bytes2HexString(SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key))
        }

        val (code, response) = postForResult(serverAddress + "/clone/push", clientSignKey, requestMsg)
        if (code !in 200..299) {
            Log.w(TAG, "pushIfChanged: HTTP $code")
            return
        }
        //解析响应（协议与 CloneFragment.doPushData 一致：measures==3 时密文 hex → SM4 解密 → BaseResponse<String>）
        var json = response
        if (HttpServerUtils.clientSafetyMeasures == 3) {
            val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
            json = String(SM4Crypt.decrypt(ConvertTools.hexStringToByteArray(json), sm4Key))
        }
        val resp: BaseResponse<String> = Gson().fromJson(json, object : TypeToken<BaseResponse<String>>() {}.type)
        if (resp.code == 200) {
            lastPushedHash = localHash
            Log.i(TAG, "本地配置已推送")
            //本地自动备份：同一份 JSON 存应用专属外部目录，保留最近 10 份
            backupLocally(Gson().toJson(exportInfo))
        } else {
            Log.w(TAG, "pushIfChanged: code=${resp.code}, msg=${resp.msg}")
        }
    }

    //本地自动备份：{externalFilesDir}/Documents/YubaSync/backup_yyyyMMdd_HHmmss.json，失败降级到 filesDir/backups，保留最近 10 份
    private fun backupLocally(json: String) {
        try {
            val baseDir = App.context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (writeBackup(File(baseDir ?: App.context.filesDir, "YubaSync"), json)) return
        } catch (e: Exception) {
            Log.w(TAG, "backupLocally external: ${e.message}")
        }
        try {
            if (!writeBackup(File(App.context.filesDir, "backups"), json)) {
                Log.w(TAG, "backupLocally: 写入失败")
            }
        } catch (e: Exception) {
            Log.w(TAG, "backupLocally internal: ${e.message}")
        }
    }

    private fun writeBackup(dir: File, json: String): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val fileName = "backup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
            File(dir, fileName).writeText(json)
            //固定名覆盖写：与时间戳归档同内容，便于用户直接识别（对齐原项目 smsforwarder.json）
            File(dir, "smsforwarder.json").writeText(json)
            //保留最近 10 份（文件名含时间戳，按名称排序即时间序；smsforwarder.json 不参与轮换）
            val backups = dir.listFiles { f -> f.isFile && f.name.startsWith("backup_") && f.name.endsWith(".json") }
                ?.sortedBy { it.name } ?: return true
            if (backups.size > 10) {
                backups.take(backups.size - 10).forEach { it.delete() }
            }
            Log.d(TAG, "backup: ${File(dir, fileName).absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeBackup: ${e.message}")
            false
        }
    }

    //构建本地导出对象（推送与推送基准共用同一管道，保证哈希可比）
    //settings 用纯 JSON（键排序）：服务端 store 只存 JSON，后台可视化配置表单按 JSON 键值渲染，
    //exportPreference() 的 Java 序列化串表单无法解析；拉取路径由 sanitizeSettingsForRestore 兼容两种格式，不受影响
    private fun buildLocalExport(userCode: String): CloneInfo {
        val exportInfo = HttpServerUtils.exportSettings()
        exportInfo.userCode = userCode
        exportInfo.settings = Gson().toJson(TreeMap<String, Any>(SharedPreference.preference.all).apply {
            //剔除同步引擎自身状态键：它们每次 recordApplied 都会变，导出它们会导致
            //「哈希更新→导出必变→永远推送」的自我喂养死循环（实测每 13s 一次）
            remove(SP_LAST_CLOUD_SYNC_HASH)
            remove(SP_LAST_PUSHED_HASH)
            remove(SP_LAST_CLOUD_SYNC_TIME)
        })
        return exportInfo
    }

    //记录「远端已应用」标记；同时以恢复后的本地重新导出哈希刷新推送基准
    //（本地==远端，防回环；注意不能直接用远端哈希——两端序列化管道不同，字符串哈希必然不同，会造成每轮空推）
    fun recordApplied(remoteHash: String) {
        lastAppliedHash = remoteHash
        val exportInfo = buildLocalExport(HttpServerUtils.clientUserCode)
        lastPushedHash = sha256(Gson().toJson(exportInfo))
        lastCloudSyncTime = now()
    }

    //拉取云端配置，返回解密后的响应 JSON（请求失败返回 null）
    private fun pullFromServer(serverAddress: String, clientSignKey: String, clientUserCode: String): String? {
        //构造请求体（与 CloneFragment.doPullData 一致）
        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        msgMap["data"] = mutableMapOf<String, Any>(
            "version_code" to AppUtils.getAppVersionCode(),
            "user_code" to clientUserCode,
        )
        var requestMsg: String = Gson().toJson(msgMap)
        if (HttpServerUtils.clientSafetyMeasures == 3) {
            val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
            requestMsg = ConvertTools.bytes2HexString(SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key))
        }

        //同步请求（调用方保证在后台线程）
        val connection = URL(serverAddress + "/clone/pull").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SettingUtils.requestTimeout * 1000
            connection.readTimeout = SettingUtils.requestTimeout * 1000
            connection.requestMethod = "POST"
            connection.doOutput = true
            //显式 User-Agent，避免被 Cloudflare 等按 UA 拦截（1010）
            connection.setRequestProperty("User-Agent", "SmsForwarder-Clone/1.0")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestMsg.toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                Log.w(TAG, "pullFromServer: HTTP $code, $error")
                return null
            }
            var response = connection.inputStream.bufferedReader().use { it.readText() }
            if (HttpServerUtils.clientSafetyMeasures == 3) {
                val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
                response = String(SM4Crypt.decrypt(ConvertTools.hexStringToByteArray(response), sm4Key))
            }
            Log.d(TAG, "pullFromServer: $response")
            return response
        } finally {
            connection.disconnect()
        }
    }

    //POST 请求，返回 HTTP 状态码与响应体（推送用）
    private fun postForResult(url: String, clientSignKey: String, requestMsg: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SettingUtils.requestTimeout * 1000
            connection.readTimeout = SettingUtils.requestTimeout * 1000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "SmsForwarder-Clone/1.0")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestMsg.toByteArray()) }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                } ?: ""
            }
            return Pair(code, body)
        } finally {
            connection.disconnect()
        }
    }

    //服务器下发的 settings 若为纯 JSON（默认骨架/直接下发），SharedPreference.importPreference（Java 反序列化）
    //会解析失败，导致 clearPreference 之后 restoreSettings 中途异常、本地凭证全部丢失（阶段13 根因）。
    //在 restoreSettings 之前统一转换为导出格式，并并入本地凭证与安全兜底项。
    fun sanitizeSettingsForRestore(cloneInfo: CloneInfo) {
        val raw = cloneInfo.settings.trim()
        if (raw.isEmpty() || raw.startsWith("{")) {
            val map = LinkedHashMap<String, Any>()
            if (raw.isNotEmpty()) {
                val obj = Gson().fromJson(raw, JsonObject::class.java)
                for ((k, v) in obj.entrySet()) {
                    when {
                        v.isJsonPrimitive -> {
                            val p = v.asJsonPrimitive
                            when {
                                p.isBoolean -> map[k] = p.asBoolean
                                p.isNumber -> {
                                    //按 SP 实际存储类型还原：整数优先 Int，超出 Int 范围用 Long，小数用 Float
                                    val d = p.asDouble
                                    map[k] = when {
                                        d == kotlin.math.floor(d) && !d.isInfinite() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE -> d.toInt()
                                        d == kotlin.math.floor(d) && !d.isInfinite() -> d.toLong()
                                        else -> d.toFloat()
                                    }
                                }
                                else -> map[k] = p.asString
                            }
                        }
                        else -> map[k] = v.asString
                    }
                }
            }
            //本地凭证并入，restore 清空后随 importPreference 一起恢复（含隐私同意标记，避免弹窗重现）
            map["server_address"] = HttpServerUtils.serverAddress.trim().trimEnd('/')
            map["client_user_code"] = HttpServerUtils.clientUserCode.trim()
            map["client_pairing_code"] = HttpServerUtils.pairingCode
            map["client_sign_key"] = HttpServerUtils.clientSignKey.trim()
            map[IS_AGREE_PRIVACY_KEY] = true
            map[SP_CLIENT_SAFETY_MEASURES] = 3   //恢复后保持 SM4 加密，否则推送/拉取回落明文分支
            map[SP_ENABLE_SMS] = false   //安全兜底：配置下发不静默开启转发
            map[SP_ENABLE_PHONE] = false
            map[SP_ENABLE_APP_NOTIFY] = false
            cloneInfo.settings = serializePrefs(map)
        }
    }

    //与 SharedPreference.exportPreference() 相同的导出格式（Java 序列化 + URLEncode）
    private fun serializePrefs(map: Map<String, Any>): String {
        val baos = ByteArrayOutputStream()
        java.io.ObjectOutputStream(baos).use { it.writeObject(map) }
        return URLEncoder.encode(baos.toString("ISO-8859-1"), "UTF-8")
    }

    private fun sha256(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

}
