package com.duanxinzhuanfa.xinxi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.duanxinzhuanfa.xinxi.utils.BatteryUtils
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_BATTERY
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_CHARGE
import com.duanxinzhuanfa.xinxi.utils.TaskWorker
import com.duanxinzhuanfa.xinxi.utils.task.TaskUtils
import com.duanxinzhuanfa.xinxi.workers.BatteryWorker

@Suppress("PrivatePropertyName")
class BatteryReceiver : BroadcastReceiver() {

    private val TAG: String = BatteryReceiver::class.java.simpleName

    override fun onReceive(context: Context?, intent: Intent?) {

        if (context == null || intent?.action != Intent.ACTION_BATTERY_CHANGED) return

        val batteryInfo = BatteryUtils.getBatteryInfo(intent).toString()
        TaskUtils.batteryInfo = batteryInfo

        val levelNew = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val levelOld = TaskUtils.batteryLevel
        val isLevelChanged = levelNew != levelOld
        TaskUtils.batteryLevel = levelNew

        val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        TaskUtils.batteryPct = levelNew.toFloat() / scale.toFloat() * 100

        val pluggedNew: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val pluggedOld = TaskUtils.batteryPlugged
        val isPluggedChanged = pluggedNew != pluggedOld
        TaskUtils.batteryPlugged = pluggedNew

        val statusNew: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val statusOld = TaskUtils.batteryStatus
        val isStatusChanged = statusNew != statusOld
        TaskUtils.batteryStatus = statusNew

        val voltage: Int = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        TaskUtils.batteryVoltage = voltage

        //EXTRA_TEMPERATURE 单位为 0.1℃，换算为 ℃
        val temperature: Int = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
        TaskUtils.batteryTemperature = temperature

        val healthNew: Int = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val healthOld = TaskUtils.batteryHealth
        val isHealthChanged = healthNew != healthOld
        TaskUtils.batteryHealth = healthNew

        //电量改变
        if (isLevelChanged) {
            Log.d(TAG, "电量改变")
            val request = OneTimeWorkRequestBuilder<BatteryWorker>().setInputData(
                workDataOf(
                    TaskWorker.CONDITION_TYPE to TASK_CONDITION_BATTERY,
                    "status" to statusNew,
                    "level_new" to levelNew,
                    "level_old" to levelOld,
                )
            ).build()
            WorkManager.getInstance(context).enqueue(request)
        }

        //充电状态改变（含电池健康度变化）
        if (isPluggedChanged || isStatusChanged || isHealthChanged) {
            Log.d(TAG, "充电状态改变")
            val inputData = workDataOf(
                TaskWorker.CONDITION_TYPE to TASK_CONDITION_CHARGE,
                "status_new" to statusNew,
                "status_old" to statusOld,
                "plugged_new" to pluggedNew,
                "plugged_old" to pluggedOld,
                "health_new" to healthNew,
                "health_old" to healthOld,
                "voltage" to voltage,
                "temperature" to temperature,
            )
            // 使用 hashcode 生成唯一的标识符
            val inputDataHash = inputData.hashCode().toString()
            // 检查是否已经存在具有相同输入数据的工作
            val existingWorkPolicy = if (WorkManager.getInstance(context).getWorkInfosByTag(inputDataHash).get().isEmpty()) {
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
            val request = OneTimeWorkRequestBuilder<BatteryWorker>()
                .setInputData(inputData)
                .addTag(inputDataHash)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(inputDataHash, existingWorkPolicy, request)
        }

    }

}
