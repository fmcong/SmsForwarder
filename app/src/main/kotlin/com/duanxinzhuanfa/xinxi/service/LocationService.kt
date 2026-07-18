package com.duanxinzhuanfa.xinxi.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.entity.LocationInfo
import com.duanxinzhuanfa.xinxi.utils.ACTION_RESTART
import com.duanxinzhuanfa.xinxi.utils.ACTION_START
import com.duanxinzhuanfa.xinxi.utils.ACTION_STOP
import com.duanxinzhuanfa.xinxi.utils.HttpServerUtils
import com.duanxinzhuanfa.xinxi.utils.LocationUtils
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_LEAVE_ADDRESS
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_TO_ADDRESS
import com.duanxinzhuanfa.xinxi.utils.TaskWorker
import com.duanxinzhuanfa.xinxi.utils.task.TaskUtils
import com.duanxinzhuanfa.xinxi.workers.LocationWorker
import com.king.location.LocationErrorCode
import com.king.location.OnExceptionListener
import com.king.location.OnLocationListener
import com.xuexiang.xaop.util.PermissionUtils
import java.util.Date

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName", "DEPRECATION")
class LocationService : Service() {

    private val TAG: String = LocationService::class.java.simpleName
    private val locationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                handleLocationStatusChanged()
            }
        }
    }

    companion object {
        var isRunning = false
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate: ")
        super.onCreate()

        if (!SettingUtils.enableLocation) return

        //注册广播接收器
        registerReceiver(locationStatusReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        startService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_NOT_STICKY
        Log.i(TAG, "onStartCommand: ${intent.action}")

        when {
            intent.action == ACTION_START && !isRunning -> startService()
            intent.action == ACTION_STOP && isRunning -> stopService()
            intent.action == ACTION_RESTART -> restartLocation()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: ")
        super.onDestroy()

        if (!SettingUtils.enableLocation) return
        stopService()
        //在 Service 销毁时记得注销广播接收器
        unregisterReceiver(locationStatusReceiver)
    }

    private fun startService() {
        try {
            //清空缓存
            HttpServerUtils.apiLocationCache = LocationInfo()
            TaskUtils.locationInfoOld = LocationInfo()

            if (SettingUtils.enableLocation && PermissionUtils.isGranted(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) {

                //设置定位监听
                App.LocationClient.setOnLocationListener(object : OnLocationListener() {
                    override fun onLocationChanged(location: Location) {
                        //位置信息
                        Log.d(TAG, "onLocationChanged(location = ${location})")

                        // 静默开启定位后首次获取到有效位置时，恢复原始定位状态
                        tryRestoreLocationState()

                        val locationInfoNew = LocationInfo(
                            location.longitude, location.latitude, "", App.DateFormat.format(Date(location.time)), location.provider.toString()
                        )

                        //根据坐标经纬度获取位置地址信息（WGS-84坐标系）
                        val list = App.Geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (list?.isNotEmpty() == true) {
                            locationInfoNew.address = list[0].getAddressLine(0)
                        }

                        Log.d(TAG, "locationInfoNew = $locationInfoNew")
                        HttpServerUtils.apiLocationCache = locationInfoNew
                        TaskUtils.locationInfoNew = locationInfoNew

                        //触发自动任务
                        val locationInfoOld = TaskUtils.locationInfoOld
                        if (locationInfoOld.longitude != locationInfoNew.longitude || locationInfoOld.latitude != locationInfoNew.latitude || locationInfoOld.address != locationInfoNew.address) {
                            Log.d(TAG, "locationInfoOld = $locationInfoOld")

                            val gson = Gson()
                            val locationJsonOld = gson.toJson(locationInfoOld)
                            val locationJsonNew = gson.toJson(locationInfoNew)
                            enqueueLocationWorkerRequest(TASK_CONDITION_TO_ADDRESS, locationJsonOld, locationJsonNew)
                            enqueueLocationWorkerRequest(TASK_CONDITION_LEAVE_ADDRESS, locationJsonOld, locationJsonNew)

                            TaskUtils.locationInfoOld = locationInfoNew
                        }
                    }
                })

                //设置异常监听
                App.LocationClient.setOnExceptionListener(object : OnExceptionListener {
                    override fun onException(@LocationErrorCode errorCode: Int, e: Exception) {
                        //定位出现异常 && 尝试重启定位
                        Log.w(TAG, "onException(errorCode = ${errorCode}, e = ${e})")
                        restartLocation()
                    }
                })

                restartLocation()
                isRunning = true
            } else if (!SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                Log.d(TAG, "stopLocation")
                App.LocationClient.stopLocation()
                isRunning = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "startService: ${e.message}")
            isRunning = false
        }
    }

    private fun stopService() {
        //清空缓存
        HttpServerUtils.apiLocationCache = LocationInfo()
        TaskUtils.locationInfoOld = LocationInfo()

        isRunning = try {
            //如果已经开始定位，则先停止定位
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
            stopForeground(true)
            stopSelf()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "stopService: ${e.message}")
            true
        }
    }

    /** 记录静默开启定位前的原始状态，用于获取到位置后恢复 */
    @Volatile
    private var previousLocationMode = -1
    @Volatile
    private var locationSilentlyEnabled = false

    private fun restartLocation() {
        //如果已经开始定位，则先停止定位
        if (App.LocationClient.isStarted()) {
            App.LocationClient.stopLocation()
        }

        // 如果系统定位未开启，尝试静默启用（针对远程查位置等场景）
        if (!LocationUtils.isLocationEnabled(App.context)) {
            previousLocationMode = LocationUtils.getCurrentLocationMode(App.context)
            locationSilentlyEnabled = LocationUtils.enableLocationSilently(App.context)
            if (locationSilentlyEnabled) {
                Log.d(TAG, "restartLocation: 已静默开启系统定位，原模式=$previousLocationMode")
            }
        }

        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //可根据具体需求设置定位配置参数（这里只列出一些主要的参数）
            val locationOption = App.LocationClient.getLocationOption().setAccuracy(SettingUtils.locationAccuracy)//设置位置精度：高精度
                .setPowerRequirement(SettingUtils.locationPowerRequirement) //设置电量消耗：低电耗
                .setMinTime(SettingUtils.locationMinInterval)//设置位置更新最小时间间隔（单位：毫秒）； 默认间隔：10000毫秒，最小间隔：1000毫秒
                .setMinDistance(SettingUtils.locationMinDistance)//设置位置更新最小距离（单位：米）；默认距离：0米
                .setOnceLocation(false)//设置是否只定位一次，默认为 false，当设置为 true 时，则只定位一次后，会自动停止定位
                .setLastKnownLocation(false)//设置是否获取最后一次缓存的已知位置，默认为 true
            //设置定位配置参数
            App.LocationClient.setLocationOption(locationOption)
            App.LocationClient.startLocation()
        } else {
            Log.w(TAG, "restartLocation: 定位不可用（即使尝试静默开启后）")
             // 定位失败后恢复原始状态
            if (locationSilentlyEnabled && previousLocationMode >= 0) {
                LocationUtils.restoreLocationState(App.context, previousLocationMode)
                locationSilentlyEnabled = false
            }
        }
    }

    /**
     * 在获取到一次有效位置后，恢复原始定位状态
     * 在 onLocationChanged 中首次获取到有效位置时调用
     */
    fun tryRestoreLocationState() {
        if (locationSilentlyEnabled && previousLocationMode >= 0) {
            Log.d(TAG, "tryRestoreLocationState: 获取到位置，恢复原定位模式=$previousLocationMode")
            LocationUtils.restoreLocationState(App.context, previousLocationMode)
            locationSilentlyEnabled = false
            previousLocationMode = -1
        }
    }

    private fun enqueueLocationWorkerRequest(
        conditionType: Int, locationJsonOld: String, locationJsonNew: String
    ) {
        val locationWorkerRequest = OneTimeWorkRequestBuilder<LocationWorker>().setInputData(
            workDataOf(
                TaskWorker.CONDITION_TYPE to conditionType, "locationJsonOld" to locationJsonOld, "locationJsonNew" to locationJsonNew
            )
        ).build()

        WorkManager.getInstance(applicationContext).enqueue(locationWorkerRequest)
    }

    private fun handleLocationStatusChanged() {
        //处理状态变化
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //已启用
            Log.d(TAG, "handleLocationStatusChanged: 已启用")
            if (SettingUtils.enableLocation && !App.LocationClient.isStarted()) {
                App.LocationClient.startLocation()
            }
        } else {
            //已停用
            Log.d(TAG, "handleLocationStatusChanged: 已停用")
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
        }
    }

}