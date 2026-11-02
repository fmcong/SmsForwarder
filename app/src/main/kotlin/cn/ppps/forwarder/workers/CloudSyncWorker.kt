package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import cn.ppps.forwarder.utils.CloudSyncEngine
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log

//云端同步周期任务：心跳上报（CloudSyncEngine.sendHeartbeat）+ 轻量检查（有更新才拉取应用；推送仅由后台 need_push 触发）
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

            //心跳：只记录结果，失败不影响后续同步（engine 内部还有 RSA/配置守卫）
            CloudSyncEngine.sendHeartbeat(serverAddress, clientSignKey, clientUserCode)

            //轻量检查：云端有更新才拉取应用（engine 内部静默失败）
            CloudSyncEngine.syncIfRemoteChanged()
        } catch (e: Exception) {
            //周期任务下一轮自然会重试，不返回 retry 以免密集重试
            Log.w(TAG, "doWork: ${e.message}")
        }
        return Result.success()
    }

}
