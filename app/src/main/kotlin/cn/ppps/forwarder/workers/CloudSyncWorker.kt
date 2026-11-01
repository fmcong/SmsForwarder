package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SM4Crypt
import cn.ppps.forwarder.utils.SP_LAST_CLOUD_SYNC_HASH
import cn.ppps.forwarder.utils.SP_LAST_CLOUD_SYNC_TIME
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SharedPreference
import com.xuexiang.xutil.data.ConvertTools
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date

//云端配置自动同步：每小时检查一次，云端配置内容有变化才应用（SHA-256 变更检测）
@Suppress("DEPRECATION")
class CloudSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val TAG: String = CloudSyncWorker::class.java.simpleName

    companion object {
        private var lastCloudSyncHash: String by SharedPreference(SP_LAST_CLOUD_SYNC_HASH, "")
        private var lastCloudSyncTime: String by SharedPreference(SP_LAST_CLOUD_SYNC_TIME, "")
    }

    override fun doWork(): Result {
        try {
            //守卫：读值先 trim，避免脏数据导致 checkUrl 误判
            val serverAddress = HttpServerUtils.serverAddress.trim().trimEnd('/')
            val clientSignKey = HttpServerUtils.clientSignKey.trim()
            val clientUserCode = HttpServerUtils.clientUserCode.trim()
            if (serverAddress.isNullOrEmpty() || !CommonUtils.checkUrl(serverAddress)
                || clientSignKey.isNullOrEmpty() || clientUserCode.isNullOrEmpty()
            ) {
                Log.d(TAG, "配置不齐全，跳过云端同步检查")
                return Result.success()
            }
            if (HttpServerUtils.clientSafetyMeasures == 2) {
                Log.d(TAG, "RSA 模式不支持自动同步，跳过")
                return Result.success()
            }

            //心跳：只记录结果，失败不影响后续拉取
            sendHeartbeat(serverAddress, clientSignKey, clientUserCode)

            val responseJson = pullFromServer(serverAddress, clientSignKey, clientUserCode) ?: return Result.success()
            applyIfChanged(responseJson)
        } catch (e: Exception) {
            //周期任务下一轮自然会重试，不返回 retry 以免密集重试
            Log.w(TAG, "doWork: ${e.message}")
        }
        return Result.success()
    }

    //心跳上报：POST {serverAddress}/heartbeat/{userCode}，X-Clone-Key 鉴权；任何失败仅记日志
    private fun sendHeartbeat(serverAddress: String, clientSignKey: String, clientUserCode: String) {
        try {
            val body = Gson().toJson(
                mapOf(
                    "device_mark" to SettingUtils.extraDeviceMark,
                    "version_code" to AppUtils.getAppVersionCode(),
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

    //拉取云端配置，返回解密后的响应 JSON（守卫不通过或请求失败返回 null）
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

        //同步请求（Worker 运行在后台线程）
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

    //变更检测：对响应中的 data 部分算 SHA-256，与上次一致则不应用（timestamp 等字段每次请求都变，不能参与哈希）
    private fun applyIfChanged(responseJson: String) {
        val root = Gson().fromJson(responseJson, JsonObject::class.java)
            ?: throw IOException("响应 JSON 解析失败")
        val dataElement = root.get("data") ?: throw IOException("响应缺少 data 字段")

        val digest = MessageDigest.getInstance("SHA-256").digest(dataElement.toString().toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        if (hash == lastCloudSyncHash) {
            Log.d(TAG, "云端配置未变化")
            return
        }

        //解析 BaseResponse<CloneInfo>（Date 字段替换为当前时间，与 CloneFragment 一致）
        val builder = GsonBuilder()
        builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
        val gson = builder.create()
        val resp: BaseResponse<CloneInfo> = gson.fromJson(responseJson, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
        if (resp.code != 200) {
            Log.w(TAG, "applyIfChanged: code=${resp.code}, msg=${resp.msg}")
            return
        }
        val cloneInfo = resp.data ?: throw IOException("响应 data 为空")

        //判断版本是否一致（版本不兼容会抛异常，由 doWork 捕获，不更新 hash）
        HttpServerUtils.compareVersion(cloneInfo)

        if (HttpServerUtils.restoreSettings(cloneInfo)) {
            //restoreSettings 会清空全部 SP，必须在应用成功之后再记录 hash 与时间
            lastCloudSyncHash = hash
            lastCloudSyncTime = App.DateFormat.format(Date())
            Log.i(TAG, "云端配置已更新: $lastCloudSyncTime")
        }
    }

}
