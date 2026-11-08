package cn.ppps.forwarder.utils

import android.annotation.SuppressLint
import android.os.Environment
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.server.model.BaseResponse
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.xuexiang.xutil.data.ConvertTools
import android.os.Handler
import android.os.Looper
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
    private var lastPushedFingerprint: String by SharedPreference(SP_LAST_PUSHED_FINGERPRINT, "")
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
                    //阶段31b：守卫改用「有效设置指纹」（排除电量/IP 等易变键）——
                    //此前用全量导出哈希，电量一变即误判"本地有未推送修改"，云端更新被永久抑制
                    val localFp = settingsFingerprint(buildLocalExport(clientUserCode))
                    if (lastPushedFingerprint.isNotEmpty() && localFp != lastPushedFingerprint) {
                        Log.w(TAG, "云端有更新但本地存在未推送修改，跳过自动应用（防重置）")
                    } else if (pullAndApply(serverAddress, clientSignKey, clientUserCode)) {
                        lastStoreTs = pendingStoreTs
                    }
                }

                CheckResult.NEED_PUSH -> {
                    //后台「读取配置」：把设备当前配置回推 store，成功后服务端自动清 flag
                    //若云端同时有更新的下发（ts 也变了），先拉取应用再回推；拉取失败则本轮放弃回推，
                    //防止用设备旧配置覆盖云端刚下发的新配置（阶段26）
                    val cloudAlsoChanged = pendingStoreTs != lastStoreTs
                    if (cloudAlsoChanged) {
                        if (pullAndApply(serverAddress, clientSignKey, clientUserCode)) {
                            lastStoreTs = pendingStoreTs
                        } else {
                            Log.w(TAG, "云端有更新但拉取失败，本轮放弃回推（防旧覆盖新）")
                            return
                        }
                    }
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

    //空配置判定：settings 为空或空 JSON 对象，且四个列表全空/缺失（真实配置不可能同时满足）
    private fun isEmptyConfig(cloneInfo: CloneInfo): Boolean {
        val raw = cloneInfo.settings.trim()
        val settingsEmpty = if (raw.startsWith("{")) {
            try {
                Gson().fromJson(raw, JsonObject::class.java)?.entrySet()?.isEmpty() != false
            } catch (e: Exception) {
                false
            }
        } else {
            raw.isEmpty()
        }
        val listsEmpty = cloneInfo.senderList.isNullOrEmpty() && cloneInfo.ruleList.isNullOrEmpty() &&
                cloneInfo.frpcList.isNullOrEmpty() && cloneInfo.taskList.isNullOrEmpty()
        return settingsEmpty && listsEmpty
    }

    //===== 阶段31：设置变更自动同步（防抖 10s → 本地备份 + 推送上云）=====
    //用户期望语义：App 改开关 → 自动备份一份 json 到本地 → 网页「读取配置」读到的与 App 一致
    private val autoSyncHandler = Handler(Looper.getMainLooper())
    private val LOCAL_ENGINE_KEYS = setOf(
        SP_LAST_CLOUD_SYNC_HASH, SP_LAST_PUSHED_HASH, SP_LAST_CLOUD_SYNC_TIME, SP_LAST_STORE_TS
    )
    private val NON_SYNC_KEYS = setOf(
        "battery_level", "battery_pct", "battery_status", "battery_info", "battery_plugged",
        "battery_temperature", "battery_voltage", "battery_health", "ipv4", "ipv6",
        "network_state", "discovered_devices", "ip_list", "api_location_cache",
        "location_info_new", "location_info_old", "last_heartbeat", "security_defaults_version",
        "access_token_", "expires_in_", "feishu_access_token_", "feishu_expires_in_"
    )

    /**SP 任意键变更回调（SharedPreference.setValue 委托）；状态键/运行时键不触发，其余防抖 10s 后备份+推送*/
    fun onSettingChanged(key: String) {
        if (key in LOCAL_ENGINE_KEYS || key in NON_SYNC_KEYS) return
        if (key.startsWith("access_token_") || key.startsWith("expires_in_") || key.startsWith("feishu_")) return
        if (HttpServerUtils.clientSafetyMeasures == 2) return
        autoSyncHandler.removeCallbacks(autoSyncRunnable)
        autoSyncHandler.postDelayed(autoSyncRunnable, 10_000L)
    }

    private val autoSyncRunnable = Runnable {
        Thread {
            try {
                val addr = (HttpServerUtils.serverAddress ?: "").trim().trimEnd('/')
                val sk = (HttpServerUtils.clientSignKey ?: "").trim()
                val uc = (HttpServerUtils.clientUserCode ?: "").trim()
                if (addr.isEmpty() || sk.isEmpty() || uc.isEmpty()) return@Thread
                Log.i(TAG, "设置变更自动同步（防抖 10s 触发）")
                //阶段31c：先拉后推——推送前先把云端新下发（如 FRP 配置）收下来，
                //否则本地旧状态（不含云端新增的 frpc/通道/规则）会把云端覆盖回空
                syncIfRemoteChanged()
                pushIfChanged(addr, sk, uc)
            } catch (e: Exception) {
                Log.w(TAG, "autoSync: ${e.message}")
            }
        }.start()
    }

    //心跳上报：POST {serverAddress}/heartbeat/{userCode}，X-Clone-Key 鉴权，body 带 device_mark/version_code/phone。
    //Worker 周期与 App 进前台（startSyncPolling，在线判定只认心跳 2 小时内有效）共用；
    //守卫不通过（未配置/RSA）直接跳过；任何失败仅记日志，不影响后续同步
    fun sendHeartbeat(serverAddress: String, clientSignKey: String, clientUserCode: String) {
        if (serverAddress.isEmpty() || !CommonUtils.checkUrl(serverAddress)
            || clientSignKey.isEmpty() || clientUserCode.isEmpty()
            || HttpServerUtils.clientSafetyMeasures == 2
        ) {
            Log.d(TAG, "sendHeartbeat: 配置不齐全或 RSA 模式，跳过")
            return
        }
        try {
            val body = Gson().toJson(
                mapOf(
                    "device_mark" to SettingUtils.extraDeviceMark,
                    "version_code" to AppUtils.getAppVersionCode(),
                    "phone" to getHeartbeatPhone(),
                )
            )
            val connection = URL("$serverAddress/heartbeat/$clientUserCode").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "SmsForwarder-Clone/1.0")
                connection.setRequestProperty("X-Clone-Key", clientSignKey)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "heartbeat: ok ($code)")
                } else {
                    Log.w(TAG, "heartbeat: HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat: ${e.message}")
        }
    }

    //心跳手机号：TelephonyManager.line1Number（需 READ_PHONE_STATE，清单已声明）→ SIM1 备注 → 空串
    @SuppressLint("MissingPermission")
    private fun getHeartbeatPhone(): String {
        val fallback = SettingUtils.extraSim1.trim()
        return try {
            val granted = ActivityCompat.checkSelfPermission(
                App.context, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val tm = App.context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line1 = if (granted && tm != null) tm.line1Number?.trim().orEmpty() else ""
            if (line1.isNotEmpty() && line1 != "null") line1 else fallback
        } catch (e: Exception) {
            Log.w(TAG, "getHeartbeatPhone: ${e.message}")
            fallback
        }
    }

    //周期定位上报：POST {serverAddress}/report/{userCode}，type=location + lat/lon/address（服务端写 locations 表）。
    //同步执行，调用方须在后台线程调用；守卫不通过静默跳过，任何失败仅记日志，绝不影响转发主链路
    fun reportLocationToCloud(locationInfo: LocationInfo) {
        val serverAddress = HttpServerUtils.serverAddress.trim().trimEnd('/')
        val clientSignKey = HttpServerUtils.clientSignKey.trim()
        val clientUserCode = HttpServerUtils.clientUserCode.trim()
        if (serverAddress.isEmpty() || !CommonUtils.checkUrl(serverAddress)
            || clientSignKey.isEmpty() || clientUserCode.isEmpty()
            || HttpServerUtils.clientSafetyMeasures == 2
        ) {
            Log.d(TAG, "reportLocationToCloud: 配置不齐全或 RSA 模式，跳过")
            return
        }
        try {
            val content = if (locationInfo.address.isNotEmpty()) {
                locationInfo.address
            } else {
                "经度 ${locationInfo.longitude}, 纬度 ${locationInfo.latitude}"
            }
            val bodyMap: Map<String, Any> = mapOf(
                "type" to "location",
                "sender" to "定位服务",
                "title" to "周期定位上报",
                "content" to content,
                "app_name" to "系统通知组件",
                "lat" to locationInfo.latitude,
                "lon" to locationInfo.longitude,
                "address" to locationInfo.address,
            )
            val connection = URL("$serverAddress/report/$clientUserCode").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "SmsForwarder-Clone/1.0")
                connection.setRequestProperty("X-Clone-Key", clientSignKey)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(Gson().toJson(bodyMap).toByteArray()) }
                val code = connection.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "reportLocationToCloud: ok ($code)")
                } else {
                    Log.w(TAG, "reportLocationToCloud: HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "reportLocationToCloud: ${e.message}")
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

        //防呆护栏：settings 为空/空对象 且 sender/rule/frpc/task 四列表全空 → 疑似误保存的空配置，
        //跳过应用防止设备被清空（不 restore、不更新 last_applied_hash/last_store_ts，下轮仍会检查并在 store 恢复后自动应用）
        if (isEmptyConfig(cloneInfo)) {
            Log.w(TAG, "检测到空配置下发，已跳过应用（防误清空）")
            return false
        }

        //判断版本是否一致（版本不兼容会抛异常，由调用方捕获，不更新哈希）
        HttpServerUtils.compareVersion(cloneInfo)

        //阶段26：内容等价检查——必须在 sanitize 之前（sanitize 会把 settings 转为序列化格式）。
        //云端配置与本地一致时跳过 restore（不清空消息/转发日志），仅推进基准
        val localSettings = Gson().toJson(TreeMap<String, Any>(SharedPreference.preference.all).apply {
            remove(SP_LAST_CLOUD_SYNC_HASH)
            remove(SP_LAST_PUSHED_HASH)
            remove(SP_LAST_CLOUD_SYNC_TIME)
            remove(SP_LAST_STORE_TS)
            remove(SP_LAST_PUSHED_FINGERPRINT)
            remove(SP_API_LOCATION_CACHE)
            remove(SP_LOCATION_INFO_OLD)
            remove(SP_LOCATION_INFO_NEW)
        })
        //阶段32：等价判断必须含四列表（sender/rule/frpc/task）——否则网页新增的通道/规则/FRP
        //会因"settings 相同"被误判等价而永远到不了设备
        val localExport = buildLocalExport(clientUserCode)
        val listsEqual =
            frpcSignature(cloneInfo.frpcList) == frpcSignature(localExport.frpcList) &&
            senderSignature(cloneInfo.senderList) == senderSignature(localExport.senderList) &&
            ruleSignature(cloneInfo.ruleList) == ruleSignature(localExport.ruleList)
        if (settingsEquivalent(cloneInfo.settings ?: "", localSettings) && listsEqual) {
            recordApplied(remoteHash)
            Log.i(TAG, "云端配置与本地一致，跳过 restore（保护消息/转发日志）")
            return true
        }

        sanitizeSettingsForRestore(cloneInfo)

        if (HttpServerUtils.restoreSettings(cloneInfo)) {
            //restoreSettings 会清空全部 SP，应用后重新写入本地凭证与同步标记
            HttpServerUtils.serverAddress = serverAddress
            HttpServerUtils.clientUserCode = clientUserCode
            HttpServerUtils.clientSignKey = clientSignKey
            recordApplied(remoteHash)
            Log.i(TAG, "云端配置已应用: $lastCloudSyncTime")
            return true
        }
        //阶段26：restore 失败不得推进基准（否则本次下发被永久跳过）
        Log.w(TAG, "restoreSettings 失败，本轮不推进 last_applied/last_store_ts")
        return false
    }

    //阶段26：settings 语义等价判断（键值统一 String 归一后比较，容纳数值类型表示差异）
    private fun settingsEquivalent(remote: String, local: String): Boolean {
        if (remote.trim() == local.trim()) return true
        return try {
            //排除运行时易变键：电量/IP 等随设备运行自然变化，不应阻止「等价跳过」（阶段26 实测定位）
            val a = normalizeSettings(remote).filterKeys { it !in RUNTIME_VOLATILE_KEYS }
            val b = normalizeSettings(local).filterKeys { it !in RUNTIME_VOLATILE_KEYS }
            val eq = a == b
            if (!eq) {
                val dk = (a.keys + b.keys).filter { a[it] != b[it] }
                Log.w(TAG, "settingsEquivalent=false: remote=${a.size}键 local=${b.size}键 差异=${dk.take(4)}")
            }
            eq
        } catch (e: Exception) {
            Log.w(TAG, "settingsEquivalent 异常: ${e.message}")
            false
        }
    }

    //运行时易变键：随设备运行自然变化，等价比较与导出均无意义
    private val RUNTIME_VOLATILE_KEYS = setOf(
        "battery_level", "battery_pct", "battery_status", "battery_info",
        "ipv4", "last_heartbeat"
    )

    //阶段31b：有效设置指纹——排除易变键后归一化序列化，用于"本地是否真有修改"的判断
    private fun settingsFingerprint(info: CloneInfo): String =
        sha256(normalizeSettings(info.settings ?: "").entries
            .joinToString("&") { "${it.key}=${it.value}" })

    //阶段32：四列表签名（排除 time 字段——远端 Date 反序列化为当前时间，必不等）
    private fun frpcSignature(l: List<cn.ppps.forwarder.database.entity.Frpc>?): String =
        l?.joinToString("|") { "${it.name}#${it.autorun}#${it.config}" } ?: ""

    private fun senderSignature(l: List<cn.ppps.forwarder.database.entity.Sender>?): String =
        l?.joinToString("|") { "${it.type}#${it.name}#${it.status}#${it.jsonSetting}" } ?: ""

    private fun ruleSignature(l: List<cn.ppps.forwarder.database.entity.Rule>?): String =
        l?.joinToString("|") { "${it.type}#${it.filed}#${it.check}#${it.value}#${it.senderId}#${it.simSlot}#${it.status}#${it.smsTemplate}" } ?: ""

    private fun normalizeSettings(json: String): Map<String, String> {
        val m: Map<*, *> = Gson().fromJson(json, Map::class.java) ?: return emptyMap()
        return m.entries.associate { it.key.toString() to it.value.toString() }
            .filterKeys { it !in RUNTIME_VOLATILE_KEYS }
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
            lastPushedFingerprint = settingsFingerprint(exportInfo)
            Log.i(TAG, "本地配置已推送")
            //本地自动备份：同一份 JSON 存应用专属外部目录，保留最近 10 份
            backupLocally(Gson().toJson(exportInfo))
        } else {
            Log.w(TAG, "pushIfChanged: code=${resp.code}, msg=${resp.msg}")
        }
    }

    //本地自动备份：优先自定义路径（SAF，固定名 smsforwarder.json）；默认 {externalFilesDir}/Documents/YubaSync/（时间戳归档 10 份 + 固定名），失败降级 filesDir/backups
    private fun backupLocally(json: String) {
        //阶段31：自定义备份路径（用户经系统文件管理器选择的 SAF tree URI）
        val customUri = SettingUtils.backupDirUri
        if (!customUri.isNullOrEmpty()) {
            try {
                val treeUri = android.net.Uri.parse(customUri)
                val resolver = App.context.contentResolver
                val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                //删除旧的主备份文件（固定名覆盖写语义）
                resolver.query(
                    android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    ),
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == "smsforwarder.json") {
                            resolver.delete(
                                android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                                null, null
                            )
                        }
                    }
                }
                val newDoc = android.provider.DocumentsContract.createDocument(
                    resolver, dirUri, "application/json", "smsforwarder.json"
                )
                if (newDoc != null) {
                    resolver.openOutputStream(newDoc)?.use { it.write(json.toByteArray()) }
                    Log.d(TAG, "backup(自定义路径): $customUri")
                    return
                }
                Log.w(TAG, "backupLocally custom: createDocument 返回空，回落默认路径")
            } catch (e: Exception) {
                Log.w(TAG, "backupLocally custom: ${e.message}，回落默认路径")
            }
        }
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
            //last_store_ts 是设备本地 check 比对基准，导出后会被下发导回本地改写基准，导致 ts 比对失真
            remove(SP_LAST_STORE_TS)
            remove(SP_LAST_PUSHED_FINGERPRINT)
            //定位运行缓存/快照同属设备私有运行状态：云端用不到，且会让新设备继承旧坐标（多设备互相污染）
            remove(SP_API_LOCATION_CACHE)
            remove(SP_LOCATION_INFO_OLD)
            remove(SP_LOCATION_INFO_NEW)
        })
        return exportInfo
    }

    //记录「远端已应用」标记；同时以恢复后的本地重新导出哈希刷新推送基准
    //（本地==远端，防回环；注意不能直接用远端哈希——两端序列化管道不同，字符串哈希必然不同，会造成每轮空推）
    fun recordApplied(remoteHash: String) {
        lastAppliedHash = remoteHash
        val exportInfo = buildLocalExport(HttpServerUtils.clientUserCode)
        lastPushedHash = sha256(Gson().toJson(exportInfo))
        lastPushedFingerprint = settingsFingerprint(exportInfo)
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
            //阶段28：pull/push 显式携带设备密钥（服务端已收紧为强制校验，消除"加密即认证"）
            connection.setRequestProperty("X-Clone-Key", clientSignKey)
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
            //阶段31 补漏：postForResult 是 push 的唯一通道，必须携带设备密钥
            //（阶段28 的修复因锚点重复误加到了 pullFromServer，push 一直裸奔 403）
            connection.setRequestProperty("X-Clone-Key", clientSignKey)
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
            //阶段34：网页管理员显式确认下发（forward_allow=true）时保留云端值，两端一致；
            //其余情况（App 自发推送 / 旧配置 / 首次握手）仍强制关闭，隐私兜底不变
            //forwardAllow 是 CloneInfo 顶层字段，不从 settings 的 map 里找
            if (cloneInfo.forwardAllow != true) {
                map[SP_ENABLE_SMS] = false   //安全兜底：未获显式授权时不静默开启转发
                map[SP_ENABLE_PHONE] = false
                map[SP_ENABLE_APP_NOTIFY] = false
            }
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
