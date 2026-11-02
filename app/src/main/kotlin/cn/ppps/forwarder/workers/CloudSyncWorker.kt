package cn.ppps.forwarder.workers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import cn.ppps.forwarder.App
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.CloudSyncEngine
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import java.net.HttpURLConnection
import java.net.URL

//云端同步周期任务：心跳上报 + CloudSyncEngine 轻量检查（有更新才拉取应用；推送仅由后台 need_push 触发）
@Suppress("DEPRECATION")
class CloudSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val TAG: String = CloudSyncWorker::class.java.simpleName

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

            //心跳：只记录结果，失败不影响后续同步
            sendHeartbeat(serverAddress, clientSignKey, clientUserCode)

            //双向同步：拉取比对应用 + 导出比对推送（CloudSyncEngine 内部静默失败）
            CloudSyncEngine.syncIfRemoteChanged()
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
            val granted = ActivityCompat.checkSelfPermission(App.context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val tm = App.context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line1 = if (granted && tm != null) tm.line1Number?.trim().orEmpty() else ""
            if (line1.isNotEmpty() && line1 != "null") line1 else fallback
        } catch (e: Exception) {
            Log.w(TAG, "getHeartbeatPhone: ${e.message}")
            fallback
        }
    }

}
