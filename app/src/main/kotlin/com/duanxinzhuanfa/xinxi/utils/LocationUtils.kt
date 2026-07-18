package com.duanxinzhuanfa.xinxi.utils

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
object LocationUtils {

    private const val LOCATION_MODE_OFF = 0
    private const val LOCATION_MODE_SENSORS_ONLY = 1 // GPS only
    private const val LOCATION_MODE_BATTERY_SAVING = 2 // Network only
    private const val LOCATION_MODE_HIGH_ACCURACY = 3 // GPS + Network

    private fun hasLocationPermission(context: Context): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d("LocationUtils", "hasLocationPermission: $hasPermission")
        return hasPermission
    }

    /**
     * 检测屏幕是否处于息屏状态。
     * 静默操作（如开关GPS）只应在息屏时执行，避免被用户发现。
     */
    fun isScreenOff(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            !pm.isInteractive
        } else {
            @Suppress("Deprecation")
            !pm.isScreenOn
        }
    }

    fun isLocationEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            Log.d("LocationUtils", "isLocationEnabled: ${locationManager?.isLocationEnabled}")
            locationManager?.isLocationEnabled == true
        } else {
            try {
                val locationMode = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE
                )
                Log.d("LocationUtils", "isLocationEnabled: locationMode=$locationMode")
                locationMode != LOCATION_MODE_OFF
            } catch (e: Settings.SettingNotFoundException) {
                false
            }
        }
    }

    /**
     * 获取当前定位模式（用于恢复）
     * @return 当前定位模式值，失败返回 -1
     */
    fun getCurrentLocationMode(context: Context): Int {
        return try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
    }

    /**
     * 静默启用系统定位（Best-effort，非 root 设备可能失败）
     * 仅在屏幕关闭时执行，避免用户察觉。
     * 使用高精度模式（GPS + 网络），获取到位置后可调用 restoreLocationState 恢复。
     * @return 是否成功切换
     */
    fun enableLocationSilently(context: Context): Boolean {
        // 安全检查：只在息屏时操作
        if (!isScreenOff(context)) {
            Log.d("LocationUtils", "enableLocationSilently: 屏幕亮着，跳过静默操作以免被用户发现")
            return false
        }
        return try {
            val currentMode = getCurrentLocationMode(context)
            if (currentMode != LOCATION_MODE_OFF) {
                Log.d("LocationUtils", "enableLocationSilently: 定位已开启 (mode=$currentMode)，无需操作")
                return true // 已经开了
            }
            Log.d("LocationUtils", "enableLocationSilently: 息屏状态，静默开启定位（高精度模式）")
            val result = Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, LOCATION_MODE_HIGH_ACCURACY)
            true
        } catch (e: SecurityException) {
            Log.w("LocationUtils", "enableLocationSilently: 权限不足（非系统应用），无法静默开启定位")
            false
        } catch (e: Exception) {
            Log.e("LocationUtils", "enableLocationSilently: ${e.message}")
            false
        }
    }

    /**
     * 恢复定位状态到之前的值（仅当当前值与之前不同且屏幕关闭时）
     * @param previousMode 之前 getCurrentLocationMode() 的返回值
     */
    fun restoreLocationState(context: Context, previousMode: Int) {
        if (previousMode < 0) return // 获取失败，不操作
        // 只在息屏时恢复，避免亮屏时改变状态被用户发现
        if (!isScreenOff(context)) {
            Log.d("LocationUtils", "restoreLocationState: 屏幕亮着，延迟恢复（等待息屏）")
            return
        }
        try {
            val currentMode = getCurrentLocationMode(context)
            if (currentMode != previousMode) {
                Log.d("LocationUtils", "restoreLocationState: 息屏状态，恢复定位模式 $previousMode")
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, previousMode)
            }
        } catch (e: SecurityException) {
            Log.w("LocationUtils", "restoreLocationState: 权限不足，无法恢复定位状态")
        } catch (e: Exception) {
            Log.e("LocationUtils", "restoreLocationState: ${e.message}")
        }
    }

    /**
     * 判断当前是否只有网络定位可用（GPS 未开启但定位系统是开的）
     * 这种情况适用于不需要 GPS 的静默定位场景
     */
    fun isOnlyNetworkLocation(context: Context): Boolean {
        if (!isLocationEnabled(context)) return false
        val mode = getCurrentLocationMode(context)
        return mode == LOCATION_MODE_BATTERY_SAVING // 仅网络定位模式
    }

    fun hasLocationCapability(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?

        // 检查是否有位置权限
        if (!hasLocationPermission(context)) {
            Log.e("LocationUtils", "hasLocationCapability: no location permission")
            return false
        }

        // 检查是否有定位能力（GPS 或 网络 任一个可用即可）
        val hasGpsProvider = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val hasNetworkProvider = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        val hasPassiveProvider = locationManager?.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) == true

        Log.d("LocationUtils", "hasLocationCapability: hasGpsProvider=$hasGpsProvider, hasNetworkProvider=$hasNetworkProvider, hasPassiveProvider=$hasPassiveProvider")
        return hasGpsProvider || hasNetworkProvider || hasPassiveProvider
    }
}
