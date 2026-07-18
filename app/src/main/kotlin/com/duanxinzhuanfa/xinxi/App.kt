package com.duanxinzhuanfa.xinxi

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.MutableLiveData
import androidx.multidex.MultiDex
import androidx.work.Configuration
import androidx.work.WorkManager
import com.duanxinzhuanfa.xinxi.activity.MainActivity
import com.duanxinzhuanfa.xinxi.core.Core
import com.duanxinzhuanfa.xinxi.database.AppDatabase
import com.duanxinzhuanfa.xinxi.database.repository.FrpcRepository
import com.duanxinzhuanfa.xinxi.database.repository.LogsRepository
import com.duanxinzhuanfa.xinxi.database.repository.MsgRepository
import com.duanxinzhuanfa.xinxi.database.repository.RuleRepository
import com.duanxinzhuanfa.xinxi.database.repository.SenderRepository
import com.duanxinzhuanfa.xinxi.database.repository.TaskRepository
import com.duanxinzhuanfa.xinxi.entity.SimInfo
import com.duanxinzhuanfa.xinxi.receiver.BatteryReceiver
import com.duanxinzhuanfa.xinxi.receiver.BluetoothReceiver
import com.duanxinzhuanfa.xinxi.receiver.CactusReceiver
import com.duanxinzhuanfa.xinxi.receiver.LockScreenReceiver
import com.duanxinzhuanfa.xinxi.receiver.NetworkChangeReceiver
import com.duanxinzhuanfa.xinxi.service.BluetoothScanService
import com.duanxinzhuanfa.xinxi.service.ForegroundService
import com.duanxinzhuanfa.xinxi.service.HttpServerService
import com.duanxinzhuanfa.xinxi.service.LocationService
import com.duanxinzhuanfa.xinxi.utils.ACTION_START
import com.duanxinzhuanfa.xinxi.utils.AppInfo
import com.duanxinzhuanfa.xinxi.utils.CactusSave
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_ID
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_NAME
import com.duanxinzhuanfa.xinxi.utils.FRONT_NOTIFY_ID
import com.duanxinzhuanfa.xinxi.utils.FRPC_LIB_DOWNLOAD_URL
import com.duanxinzhuanfa.xinxi.utils.FRPC_LIB_VERSION
import com.duanxinzhuanfa.xinxi.utils.PhoneUtils
import com.duanxinzhuanfa.xinxi.utils.HistoryUtils
import com.duanxinzhuanfa.xinxi.utils.HttpServerUtils
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.ProximitySensorScreenHelper
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.SharedPreference
import com.duanxinzhuanfa.xinxi.utils.sdkinit.UMengInit
import com.duanxinzhuanfa.xinxi.utils.sdkinit.XBasicLibInit
import com.duanxinzhuanfa.xinxi.utils.sdkinit.XUpdateInit
import com.duanxinzhuanfa.xinxi.utils.tinker.TinkerLoadLibrary
import com.gyf.cactus.Cactus
import com.gyf.cactus.callback.CactusCallback
import com.gyf.cactus.ext.cactus
import com.hjq.language.MultiLanguages
import com.hjq.language.OnLanguageListener
import com.king.location.LocationClient
import com.xuexiang.xutil.file.FileUtils
import frpclib.Frpclib
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class App : Application(), CactusCallback, Configuration.Provider by Core {

    val applicationScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.getInstance(this) }
    val frpcRepository by lazy { FrpcRepository(database.frpcDao()) }
    val msgRepository by lazy { MsgRepository(database.msgDao()) }
    val logsRepository by lazy { LogsRepository(database.logsDao()) }
    val ruleRepository by lazy { RuleRepository(database.ruleDao()) }
    val senderRepository by lazy { SenderRepository(database.senderDao()) }
    val taskRepository by lazy { TaskRepository(database.taskDao()) }

    companion object {
        const val TAG: String = "SmsForwarder"

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

        //自定义模板可用变量标签
        var COMMON_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var SMS_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var CALL_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var APP_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var LOCATION_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var BATTERY_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var NETWORK_TAG_MAP: MutableMap<String, String> = mutableMapOf()

        //通话类型：1.来电挂机 2.去电挂机 3.未接来电 4.来电提醒 5.来电接通 6.去电拨出
        var CALL_TYPE_MAP: MutableMap<String, String> = mutableMapOf()
        var FILED_MAP: MutableMap<String, String> = mutableMapOf()
        var CHECK_MAP: MutableMap<String, String> = mutableMapOf()
        var SIM_SLOT_MAP: MutableMap<String, String> = mutableMapOf()
        var FORWARD_STATUS_MAP: MutableMap<Int, String> = mutableMapOf()
        var BARK_LEVEL_MAP: MutableMap<String, String> = mutableMapOf()
        var BARK_ENCRYPTION_ALGORITHM_MAP: MutableMap<String, String> = mutableMapOf()

        //已插入SIM卡信息（使用线程安全的 ConcurrentHashMap，避免广播/WorkManager 并发读写导致的 ConcurrentModificationException）
        var SimInfoList: MutableMap<Int, SimInfo> = ConcurrentHashMap()

        //已安装App信息
        var LoadingAppList = false
        var UserAppList: MutableList<AppInfo> = mutableListOf()
        var SystemAppList: MutableList<AppInfo> = mutableListOf()

        /**
         * @return 当前app是否是调试开发模式
         */
        var isDebug: Boolean = BuildConfig.DEBUG

        //Cactus相关
        val mEndDate = MutableLiveData<String>() //结束时间
        val mLastTimer = MutableLiveData<String>() //上次存活时间
        val mTimer = MutableLiveData<String>() //存活时间
        val mStatus = MutableLiveData<Boolean>().apply { value = true } //运行状态
        var mDisposable: Disposable? = null

        //Location相关
        val LocationClient by lazy { LocationClient(context) }
        val Geocoder by lazy { Geocoder(context) }
        val DateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

        //Frpclib是否已经初始化
        var FrpclibInited = false

        //是否需要在拼接字符串时添加空格
        var isNeedSpaceBetweenWords = false
    }

    override fun attachBaseContext(base: Context) {
        //super.attachBaseContext(base)
        // 绑定语种
        super.attachBaseContext(MultiLanguages.attach(base))
        //解决4.x运行崩溃的问题
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        // 设置全局异常捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()
            try {
                val logPath = this.cacheDir.absolutePath + "/logs"
                val logDir = File(logPath)
                if (!logDir.exists()) logDir.mkdirs()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                val logFile = File(logPath, "crash_$currentDateTime.txt")
                BufferedWriter(FileWriter(logFile, true)).use { writer ->
                    writer.append("$throwable\n")
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            //使用默认的处理方式让APP停止运行
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            context = applicationContext
            initLibs()

            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //初始化WorkManager
            WorkManager.initialize(this, Configuration.Builder().build())

            //========== 自动刷新初始化 ==========

            //设备名称空白时自动获取
            if (SettingUtils.extraDeviceMark.isBlank()) {
                SettingUtils.extraDeviceMark = PhoneUtils.getDeviceName()
                Log.d(TAG, "设备名称已自动刷新: ${SettingUtils.extraDeviceMark}")
            }

            //SIM主键/备注空白时自动刷新
            if (SettingUtils.subidSim1 <= 0 || SettingUtils.extraSim1.isBlank()) {
                try {
                    val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        val infoList = subscriptionManager.activeSubscriptionInfoList
                        if (infoList != null) {
                            for (info in infoList) {
                                val slotIndex = info.simSlotIndex
                                val subId = info.subscriptionId
                                val displayName = info.displayName?.toString() ?: ""
                                if (slotIndex == 0) {
                                    if (SettingUtils.subidSim1 <= 0) SettingUtils.subidSim1 = subId
                                    if (SettingUtils.extraSim1.isBlank()) SettingUtils.extraSim1 = displayName
                                } else if (slotIndex == 1) {
                                    if (SettingUtils.subidSim2 <= 0) SettingUtils.subidSim2 = subId
                                    if (SettingUtils.extraSim2.isBlank()) SettingUtils.extraSim2 = displayName
                                }
                            }
                            Log.d(TAG, "SIM信息已自动刷新: SIM1=${SettingUtils.extraSim1}, SIM2=${SettingUtils.extraSim2}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SIM自动刷新失败: ${e.message}")
                }
            }

            //========== 动态加载FrpcLib ==========

            val libPath = filesDir.absolutePath + "/libs"
            val soFile = File(libPath)
            if (soFile.exists()) {
                try {
                    TinkerLoadLibrary.installNativeLibraryPath(classLoader, soFile)
                    FrpclibInited = FileUtils.isFileExists(filesDir.absolutePath + "/libs/libgojni.so") && FRPC_LIB_VERSION == Frpclib.getVersion()
                } catch (throwable: Throwable) {
                    Log.e("APP", throwable.message.toString())
                }
            }

            //若 FrpcLib 未初始化，在后台静默下载（无弹窗）
            if (!FrpclibInited) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    silentDownloadFrpcLib(libPath)
                }
            }

            //启动前台服务
            val foregroundServiceIntent = Intent(this, ForegroundService::class.java)
            foregroundServiceIntent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(foregroundServiceIntent)
            } else {
                startService(foregroundServiceIntent)
            }

            //启动HttpServer
            if (HttpServerUtils.enableServerAutorun) {
                Intent(this, HttpServerService::class.java).also {
                    startService(it)
                }
            }

            //启动LocationService
            if (SettingUtils.enableLocation) {
                val locationServiceIntent = Intent(this, LocationService::class.java)
                locationServiceIntent.action = ACTION_START
                startService(locationServiceIntent)
            }

            //监听电量&充电状态变化
            val batteryReceiver = BatteryReceiver()
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, batteryFilter)

            //监听蓝牙状态变化
            val bluetoothReceiver = BluetoothReceiver()
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            registerReceiver(bluetoothReceiver, filter)
            if (SettingUtils.enableBluetooth) {
                val bluetoothScanServiceIntent = Intent(this, BluetoothScanService::class.java)
                bluetoothScanServiceIntent.action = ACTION_START
                startService(bluetoothScanServiceIntent)
            }

            //监听网络变化
            val networkReceiver = NetworkChangeReceiver()
            val networkFilter = IntentFilter().apply {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                //addAction("android.intent.action.DATA_CONNECTION_STATE_CHANGED")
            }
            registerReceiver(networkReceiver, networkFilter)

            //监听锁屏&解锁
            val lockScreenReceiver = LockScreenReceiver()
            val lockScreenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(lockScreenReceiver, lockScreenFilter)
            //靠近听筒关屏
            ProximitySensorScreenHelper.refresh(this)
            //Cactus 集成双进程前台服务，JobScheduler，onePix(一像素)，WorkManager，无声音乐
            if (SettingUtils.enableCactus) {
                //注册广播监听器
                registerReceiver(CactusReceiver(), IntentFilter().apply {
                    addAction(Cactus.CACTUS_WORK)
                    addAction(Cactus.CACTUS_STOP)
                    addAction(Cactus.CACTUS_BACKGROUND)
                    addAction(Cactus.CACTUS_FOREGROUND)
                })
                //设置通知栏点击事件
                val activityIntent = Intent(this, MainActivity::class.java)
                val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
                val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, flags)
                cactus {
                    setServiceId(FRONT_NOTIFY_ID) //服务Id
                    setChannelId(FRONT_CHANNEL_ID) //渠道Id
                    setChannelName(FRONT_CHANNEL_NAME) //渠道名
                    setTitle(getString(R.string.app_name))
                    setContent(SettingUtils.notifyContent)
                    setSmallIcon(R.drawable.ic_forwarder)
                    setLargeIcon(R.mipmap.ic_launcher)
                    setPendingIntent(pendingIntent)
                    //无声音乐
                    if (SettingUtils.enablePlaySilenceMusic) {
                        setMusicEnabled(true)
                        setBackgroundMusicEnabled(true)
                        setMusicId(R.raw.silence)
                        //设置音乐间隔时间，时间间隔越长，越省电
                        setMusicInterval(SettingUtils.musicInterval.toLong())
                        isDebug(true)
                    }
                    //是否可以使用一像素，默认可以使用，只有在android p以下可以使用
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && SettingUtils.enableOnePixelActivity) {
                        setOnePixEnabled(true)
                    }
                    //崩溃是否可以重启用户界面
                    setCrashRestartUIEnabled(true)
                    addCallback({
                        Log.d(TAG, "Cactus保活：onStop回调")
                    }) {
                        Log.d(TAG, "Cactus保活：doWork回调")
                    }
                    //切后台切换回调
                    addBackgroundCallback {
                        Log.d(TAG, if (it) "SmsForwarder 切换到后台运行" else "SmsForwarder 切换到前台运行")
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "onCreate: $e")
        }
    }

    /**
     * 初始化基础库
     */
    /**
     * 静默下载 FrpcLib（无弹窗），在后台线程使用 OkHttp 下载 libgojni.so
     */
    @Suppress("DEPRECATION")
    private fun silentDownloadFrpcLib(libPath: String) {
        try {
            @Suppress("DEPRECATION")
            val cpuAbi = when (Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI) {
                "x86" -> "x86"
                "x86_64" -> "x86_64"
                "arm64-v8a" -> "arm64-v8a"
                else -> "armeabi-v7a"
            }
            val downloadUrl = String.format(FRPC_LIB_DOWNLOAD_URL, FRPC_LIB_VERSION, cpuAbi)
            Log.d(TAG, "silentDownloadFrpcLib: url=$downloadUrl")

            val request = okhttp3.Request.Builder().url(downloadUrl).build()
            val response = okhttp3.OkHttpClient.Builder()
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()

            @Suppress("DEPRECATION")
            if (response.isSuccessful() && response.body() != null) {
                val libDir = File(libPath)
                if (!libDir.exists()) libDir.mkdirs()
                val destFile = File(libPath, "libgojni.so")
                val bytes = response.body()!!.bytes()
                destFile.writeBytes(bytes)
                Log.d(TAG, "silentDownloadFrpcLib: success (${bytes.size} bytes)")
            } else {
                Log.w(TAG, "silentDownloadFrpcLib: HTTP ${response.code()}, download skipped")
            }
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "silentDownloadFrpcLib: ${e.message}")
        }
    }

    private fun initLibs() {
        Core.init(this)
        // 配置文件初始化
        SharedPreference.init(applicationContext)
        // 清理旧 fork 源残留的 SharedPreferences key（如 com.idormy.*, cn.ppps.forwarder.widget.*）
        SharedPreference.cleanOldForkKeys()
        // X系列基础库初始化
        XBasicLibInit.init(this)
        // 初始化日志打印
        isDebug = SettingUtils.enableDebugMode
        Log.init(applicationContext)
        // 转发历史工具类初始化
        HistoryUtils.init(applicationContext)
        // 版本更新初始化
        XUpdateInit.init(this)
        // 运营统计数据
        UMengInit.init(this)
        // 初始化语种切换框架
        MultiLanguages.init(this)
        // 设置语种变化监听器
        MultiLanguages.setOnLanguageListener(object : OnLanguageListener {
            override fun onAppLocaleChange(oldLocale: Locale, newLocale: Locale) {
                // 注意：只有setAppLanguage时触发，clearAppLanguage时不触发
                Log.i(TAG, "监听到应用切换了语种，旧语种：$oldLocale，新语种：$newLocale")
                switchLanguage(newLocale)
            }

            override fun onSystemLocaleChange(oldLocale: Locale, newLocale: Locale) {
                Log.i(TAG, "监听到系统切换了语种，旧语种：$oldLocale，新语种：$newLocale")
                switchLanguage(newLocale)
                /*val isFlowSystem = SettingUtils.isFlowSystemLanguage //MultiLanguages.isSystemLanguage(context)取值不对，一直是false
                Log.i(TAG, "监听到系统切换了语种，旧语种：$oldLocale，新语种：$newLocale，是否跟随系统：$isFlowSystem")
                if (isFlowSystem) {
                    CommonUtils.switchLanguage(oldLocale, newLocale)
                }*/
            }
        })
        switchLanguage(MultiLanguages.getAppLanguage(this))
    }

    @SuppressLint("CheckResult")
    override fun doWork(times: Int) {
        Log.d(TAG, "doWork:$times")
        mStatus.postValue(true)
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("GMT+00:00")
        var oldTimer = CactusSave.timer
        if (times == 1) {
            CactusSave.lastTimer = oldTimer
            CactusSave.endDate = CactusSave.date
            oldTimer = 0L
        }
        mLastTimer.postValue(dateFormat.format(Date(CactusSave.lastTimer * 1000)))
        mEndDate.postValue(CactusSave.endDate)
        mDisposable = Observable.interval(1, TimeUnit.SECONDS).map {
            oldTimer + it
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe { aLong ->
            CactusSave.timer = aLong
            CactusSave.date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).run {
                format(Date())
            }
            mTimer.value = dateFormat.format(Date(aLong * 1000))
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        mStatus.postValue(false)
        mDisposable?.apply {
            if (!isDisposed) {
                dispose()
            }
        }
    }

    //多语言切换时枚举常量自动切换语言
    private fun switchLanguage(newLocale: Locale) {
        isNeedSpaceBetweenWords = !newLocale.language.contains("zh")

        //自定义模板可用变量标签
        COMMON_TAG_MAP.clear()
        COMMON_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_receive_time) to getString(R.string.insert_tag_receive_time),
                getString(R.string.tag_current_time) to getString(R.string.insert_tag_current_time),
                getString(R.string.tag_device_name) to getString(R.string.insert_tag_device_name),
                getString(R.string.tag_app_version) to getString(R.string.insert_tag_app_version),
            )
        )
        SMS_TAG_MAP.clear()
        SMS_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_from) to getString(R.string.insert_tag_from),
                getString(R.string.tag_sms) to getString(R.string.insert_tag_sms),
                getString(R.string.tag_card_slot) to getString(R.string.insert_tag_card_slot),
                getString(R.string.tag_card_subid) to getString(R.string.insert_tag_card_subid),
                getString(R.string.tag_contact_name) to getString(R.string.insert_tag_contact_name),
                getString(R.string.tag_phone_area) to getString(R.string.insert_tag_phone_area),
            )
        )
        CALL_TAG_MAP.clear()
        CALL_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_from) to getString(R.string.insert_tag_from),
                getString(R.string.tag_sms) to getString(R.string.insert_tag_msg),
                getString(R.string.tag_card_slot) to getString(R.string.insert_tag_card_slot),
                getString(R.string.tag_card_subid) to getString(R.string.insert_tag_card_subid),
                getString(R.string.tag_call_type) to getString(R.string.insert_tag_call_type),
                getString(R.string.tag_contact_name) to getString(R.string.insert_tag_contact_name),
                getString(R.string.tag_phone_area) to getString(R.string.insert_tag_phone_area),
            )
        )
        APP_TAG_MAP.clear()
        APP_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_uid) to getString(R.string.insert_tag_uid),
                getString(R.string.tag_package_name) to getString(R.string.insert_tag_package_name),
                getString(R.string.tag_app_name) to getString(R.string.insert_tag_app_name),
                getString(R.string.tag_title) to getString(R.string.insert_tag_title),
                getString(R.string.tag_msg) to getString(R.string.insert_tag_msg),
            )
        )
        LOCATION_TAG_MAP.clear()
        LOCATION_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_location) to getString(R.string.insert_tag_location),
                getString(R.string.tag_location_longitude) to getString(R.string.insert_tag_location_longitude),
                getString(R.string.tag_location_latitude) to getString(R.string.insert_tag_location_latitude),
                getString(R.string.tag_location_address) to getString(R.string.insert_tag_location_address),
            )
        )
        BATTERY_TAG_MAP.clear()
        BATTERY_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_battery_pct) to getString(R.string.insert_tag_battery_pct),
                getString(R.string.tag_battery_status) to getString(R.string.insert_tag_battery_status),
                getString(R.string.tag_battery_plugged) to getString(R.string.insert_tag_battery_plugged),
                getString(R.string.tag_battery_info) to getString(R.string.insert_tag_battery_info),
                getString(R.string.tag_battery_info_simple) to getString(R.string.insert_tag_battery_info_simple),
            )
        )
        NETWORK_TAG_MAP.clear()
        NETWORK_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_ipv4) to getString(R.string.insert_tag_ipv4),
                getString(R.string.tag_ipv6) to getString(R.string.insert_tag_ipv6),
                getString(R.string.tag_ip_list) to getString(R.string.insert_tag_ip_list),
                getString(R.string.tag_net_type) to getString(R.string.insert_tag_net_type),
            )
        )

        CALL_TYPE_MAP.clear()
        CALL_TYPE_MAP.putAll(
            mapOf(
                //"0" to getString(R.string.unknown_call),
                "1" to getString(R.string.incoming_call_ended),
                "2" to getString(R.string.outgoing_call_ended),
                "3" to getString(R.string.missed_call),
                "4" to getString(R.string.incoming_call_received),
                "5" to getString(R.string.incoming_call_answered),
                "6" to getString(R.string.outgoing_call_started),
            )
        )

        FILED_MAP.clear()
        FILED_MAP.putAll(
            mapOf(
                "transpond_all" to getString(R.string.rule_transpond_all),
                "phone_num" to getString(R.string.rule_phone_num),
                "msg_content" to getString(R.string.rule_msg_content),
                "multi_match" to getString(R.string.rule_multi_match),
                "package_name" to getString(R.string.rule_package_name),
                "inform_content" to getString(R.string.rule_inform_content),
                "call_type" to getString(R.string.rule_call_type),
                "uid" to getString(R.string.rule_uid),
            )
        )

        CHECK_MAP.clear()
        CHECK_MAP.putAll(
            mapOf(
                "is" to getString(R.string.rule_is),
                "notis" to getString(R.string.rule_notis),
                "contain" to getString(R.string.rule_contain),
                "startwith" to getString(R.string.rule_startwith),
                "endwith" to getString(R.string.rule_endwith),
                "notcontain" to getString(R.string.rule_notcontain),
                "regex" to getString(R.string.rule_regex),
            )
        )

        SIM_SLOT_MAP.clear()
        SIM_SLOT_MAP.putAll(
            mapOf(
                "ALL" to getString(R.string.rule_any),
                "SIM1" to "SIM1",
                "SIM2" to "SIM2",
            )
        )

        FORWARD_STATUS_MAP.clear()
        FORWARD_STATUS_MAP.putAll(
            mapOf(
                0 to getString(R.string.failed),
                1 to getString(R.string.processing),
                2 to getString(R.string.success),
            )
        )

        BARK_LEVEL_MAP.clear()
        BARK_LEVEL_MAP.putAll(
            mapOf(
                "critical" to getString(R.string.bark_level_critical),
                "active" to getString(R.string.bark_level_active),
                "timeSensitive" to getString(R.string.bark_level_timeSensitive),
                "passive" to getString(R.string.bark_level_passive)
            )
        )

        BARK_ENCRYPTION_ALGORITHM_MAP.clear()
        BARK_ENCRYPTION_ALGORITHM_MAP.putAll(
            mapOf(
                "none" to getString(R.string.bark_encryption_algorithm_none),
                "AES128/CBC/PKCS7Padding" to "AES128/CBC/PKCS7Padding",
                "AES128/ECB/PKCS7Padding" to "AES128/ECB/PKCS7Padding",
                "AES192/CBC/PKCS7Padding" to "AES192/CBC/PKCS7Padding",
                "AES192/ECB/PKCS7Padding" to "AES192/ECB/PKCS7Padding",
                "AES256/CBC/PKCS7Padding" to "AES256/CBC/PKCS7Padding",
                "AES256/ECB/PKCS7Padding" to "AES256/ECB/PKCS7Padding",
                "AES256/GCM/NoPadding" to "AES256/GCM/NoPadding",
            )
        )
    }

}
