package com.duanxinzhuanfa.xinxi.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.activity.MainActivity
import com.duanxinzhuanfa.xinxi.core.Core
import com.duanxinzhuanfa.xinxi.entity.action.AlarmSetting
import com.duanxinzhuanfa.xinxi.utils.ACTION_START
import com.duanxinzhuanfa.xinxi.utils.ACTION_STOP
import com.duanxinzhuanfa.xinxi.utils.ACTION_STOP_ALARM
import com.duanxinzhuanfa.xinxi.utils.ACTION_UPDATE_NOTIFICATION
import com.duanxinzhuanfa.xinxi.utils.CommonUtils
import com.duanxinzhuanfa.xinxi.utils.EVENT_ALARM_ACTION
import com.duanxinzhuanfa.xinxi.utils.EVENT_FRPC_RUNNING_ERROR
import com.duanxinzhuanfa.xinxi.utils.EVENT_FRPC_RUNNING_SUCCESS
import com.duanxinzhuanfa.xinxi.utils.EXTRA_UPDATE_NOTIFICATION
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_ID
import com.duanxinzhuanfa.xinxi.utils.FRONT_CHANNEL_NAME
import com.duanxinzhuanfa.xinxi.utils.FRONT_NOTIFY_ID
import com.duanxinzhuanfa.xinxi.utils.FlashUtils
import com.duanxinzhuanfa.xinxi.utils.INTENT_FRPC_APPLY_FILE
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_CRON
import com.duanxinzhuanfa.xinxi.utils.VibrationUtils
import com.duanxinzhuanfa.xinxi.utils.task.CronJobScheduler
import com.duanxinzhuanfa.xinxi.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xutil.XUtil
import frpclib.Frpclib
import io.reactivex.Single
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName", "DeferredResultUnused", "OPT_IN_USAGE", "DEPRECATION")
class ForegroundService : Service() {

    private val TAG: String = ForegroundService::class.java.simpleName
    private var notificationManager: NotificationManager? = null

    // 缓存通知大图标，避免每次更新通知都重新解码 bitmap 造成内存抖动
    private val cachedLargeIcon: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(resources, R.drawable.ic_menu_frpc)
        } catch (e: Exception) {
            null
        }
    }

    private val compositeDisposable = CompositeDisposable()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frpcObserver = Observer { uid: String ->
        if (!App.FrpclibInited || Frpclib.isRunning(uid)) return@Observer

        Core.frpc.get(uid).flatMap { (uid1, _, config) ->
            val error = Frpclib.runContent(uid1, config)
            Single.just(error)
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(object : SingleObserver<String> {
            override fun onSubscribe(d: Disposable) {
                compositeDisposable.add(d)
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                Log.e(TAG, "onError: ${e.message}")
                LiveEventBus.get(EVENT_FRPC_RUNNING_ERROR, String::class.java).post(uid)
            }

            override fun onSuccess(msg: String) {
                if (!TextUtils.isEmpty(msg)) {
                    Log.e(TAG, msg)
                    LiveEventBus.get(EVENT_FRPC_RUNNING_ERROR, String::class.java).post(uid)
                } else {
                    LiveEventBus.get(EVENT_FRPC_RUNNING_SUCCESS, String::class.java).post(uid)
                }
            }
        })
    }

    // 振动控制
    private lateinit var vibrationUtils: VibrationUtils
    private var isVibrating = false

    // 闪光灯控制
    private lateinit var flashUtils: FlashUtils
    private var isFlash = false

    // 音乐播放器
    private var alarmPlayer: MediaPlayer? = null
    private var alarmPlayTimes = 0
    private val alarmObserver = Observer<AlarmSetting> { alarm ->
        Log.d(TAG, "Received alarm: $alarm")
        //停止振动
        if (vibrationUtils.isVibrating) {
            vibrationUtils.stopVibration()
        }
        //停止闪光灯
        if (flashUtils.isFlashing) {
            flashUtils.stopFlashing()
        }
        //停止播放音乐
        alarmPlayer?.release()
        alarmPlayer = null
        if (alarm.action == "start") {
            //播放音乐
            if (alarm.playTimes >= 0) {
                //获取音量
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "maxVolume=$maxVolume, currentVolume=$currentVolume")
                //设置音量
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVolume * alarm.volume / 100), 0)
                //播放音乐
                alarmPlayer = MediaPlayer().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                        setAudioAttributes(audioAttributes)
                    } else {
                        // 对于 Android 5.0 之前的版本，使用 setAudioStreamType
                        val audioStreamType = AudioManager.STREAM_ALARM
                        setAudioStreamType(audioStreamType)
                    }

                    try {
                        if (alarm.music.isEmpty() || !File(alarm.music).exists()) {
                            val fd = resources.openRawResourceFd(R.raw.alarm)
                            setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        } else {
                            setDataSource(alarm.music)
                        }

                        setOnPreparedListener {
                            Log.d(TAG, "MediaPlayer prepared")
                            start()
                            alarmPlayTimes++
                            //更新通知栏
                            updateNotification(alarm.description, R.drawable.auto_task_icon_alarm, true)
                        }

                        setOnCompletionListener {
                            Log.d(TAG, "MediaPlayer completed")
                            if (alarm.playTimes == 0 || alarmPlayTimes < alarm.playTimes) {
                                start()
                                alarmPlayTimes++
                            } else {
                                stop()
                                reset()
                                release()
                                alarmPlayer = null
                                alarmPlayTimes = 0
                                //恢复音量
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                //恢复通知栏
                                updateNotification(SettingUtils.notifyContent)
                            }
                        }

                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                            release()
                            return@setOnErrorListener true
                        }

                        setVolume(alarm.volume / 100F, alarm.volume / 100F)
                        prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaPlayer Exception: ${e.message}")
                    }
                }
            }
            //振动提醒
            if (alarm.repeatTimes >= 0) {
                isVibrating = true
                vibrationUtils.startVibration(alarm.vibrate, alarm.repeatTimes)
            }
            //闪光灯提醒
            if (alarm.flashTimes >= 0) {
                isFlash = true
                flashUtils.startFlashing(alarm.flash, alarm.flashTimes)
            }
        }
    }

    companion object {
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()

        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return

        // 降低整个进程/服务的优先级，不抢系统资源，最大化省电
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }

        //创建通知渠道
        createNotificationChannel()

        //初始化振动
        vibrationUtils = VibrationUtils(this)

        //初始化闪光灯
        flashUtils = FlashUtils(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return START_NOT_STICKY

        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    startForegroundService()
                }

                ACTION_STOP -> {
                    stopForegroundService()
                }

                ACTION_UPDATE_NOTIFICATION -> {
                    val updatedContent = intent.getStringExtra(EXTRA_UPDATE_NOTIFICATION)
                    updateNotification(updatedContent ?: "")
                }

                ACTION_STOP_ALARM -> {
                    alarmPlayer?.release()
                    alarmPlayer = null
                    updateNotification(SettingUtils.notifyContent)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        //移除 LiveEventBus 观察者，避免泄漏与重复注册
        LiveEventBus.get(INTENT_FRPC_APPLY_FILE, String::class.java).removeObserver(frpcObserver)
        LiveEventBus.get<AlarmSetting>(EVENT_ALARM_ACTION).removeObserver(alarmObserver)
        compositeDisposable.clear()
        serviceScope.cancel()
        //非纯客户端模式
        if (!SettingUtils.enablePureClientMode) stopForegroundService()
        flashUtils.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun startForegroundService() {
        if (isRunning) return
        isRunning = true

        val notification = createNotification(SettingUtils.notifyContent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FRONT_NOTIFY_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FRONT_NOTIFY_ID, notification)
        }
        // 双服务技巧：延迟 300ms 后隐藏通知栏条目
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ hideForegroundNotification() }, 300)

        try {
            // 降低服务线程优先级，不抢前台资源，最大化省电
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }

            //开关通知监听服务
            if (SettingUtils.enableAppNotify && CommonUtils.isNotificationListenerServiceEnabled(this)) {
                CommonUtils.toggleNotificationListenerService(this)
            }

            //启动剪切板监控服务（省电的被动监听，零额外功耗）
            if (SettingUtils.enableClipboardMonitor) {
                try {
                    ClipboardService.start(this)
                    Log.d(TAG, "剪切板监控已启动")
                } catch (e: Exception) {
                    Log.w(TAG, "剪切板监控启动失败: ${e.message}")
                }
            }

            //启动定时任务
            serviceScope.launch {
                val taskList = Core.task.getByType(TASK_CONDITION_CRON)
                taskList.forEach { task ->
                    Log.d(TAG, "task = $task")
                    CronJobScheduler.cancelTask(task.id)
                    CronJobScheduler.scheduleTask(task)
                }
            }

            //异步获取所有已安装 App 信息
            if (SettingUtils.enableLoadAppList) {
                val request = OneTimeWorkRequestBuilder<LoadAppListWorker>().build()
                WorkManager.getInstance(XUtil.getContext()).enqueue(request)
            }

            //启动 Frpc
            if (App.FrpclibInited) {
                //监听Frpc启动指令
                LiveEventBus.get(INTENT_FRPC_APPLY_FILE, String::class.java).observeForever(frpcObserver)
                //自启动的Frpc
                serviceScope.launch {
                    val frpcList = Core.frpc.getAutorun()

                    if (frpcList.isEmpty()) {
                        Log.d(TAG, "没有自启动的Frpc")
                        return@launch
                    }

                    for (frpc in frpcList) {
                        Log.d(TAG, "自启动的Frpc: $frpc")
                        serviceScope.launch {
                            val error = Frpclib.runContent(frpc.uid, frpc.config)
                            Log.d(TAG, "自启动的Frpc: uid=${frpc.uid}, error=$error")
                            if (!TextUtils.isEmpty(error)) {
                                Log.e(TAG, error)
                            }
                        }
                    }
                }
            }

            //播放警报
            LiveEventBus.get<AlarmSetting>(EVENT_ALARM_ACTION).observeForever(alarmObserver)

        } catch (e: Exception) {
            handleException(e, "startForegroundService")
        }

    }

    private fun stopForegroundService() {
        try {
            stopForeground(true)
            stopSelf()
            compositeDisposable.dispose()
            isRunning = false
            alarmPlayer?.release()
            alarmPlayer = null
            //停止振动
            if (vibrationUtils.isVibrating) {
                vibrationUtils.stopVibration()
            }
            //停止闪光灯
            if (flashUtils.isFlashing) {
                flashUtils.stopFlashing()
            }
        } catch (e: Exception) {
            handleException(e, "stopForegroundService")
        }
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_NONE：完全零打扰，通知栏无任何显示（需配合双服务隐藏技巧彻底消除前台通知痕迹）
            val importance = NotificationManager.IMPORTANCE_NONE
            val notificationChannel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, importance)
            notificationChannel.description = ""
            notificationChannel.enableLights(false)
            notificationChannel.enableVibration(false)
            notificationChannel.setSound(null, null)
            if (notificationManager != null) {
                notificationManager!!.createNotificationChannel(notificationChannel)
            }
        }
    }

    /**
     * 双服务通知隐藏技巧：用 HideNotificationService 消除前台通知栏条目。
     * ForegroundService 先注册前台通知，HideNotificationService 用同一 ID 接管后立即移除，
     * ForegroundService 继续以前台优先级运行，但通知栏无痕迹。
     */
    private fun hideForegroundNotification() {
        try {
            val intent = Intent(this, HideNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "hideForegroundNotification failed: ${e.message}")
        }
    }

    private fun createNotification(content: String, largeIconResId: Int? = null, showStopButton: Boolean = false): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        val builder = NotificationCompat.Builder(this, FRONT_CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(content).setSmallIcon(R.drawable.ic_forwarder).setContentIntent(pendingIntent).setWhen(System.currentTimeMillis())

        // 设置大图标（可选）
        if (largeIconResId != null) {
            builder.setLargeIcon(BitmapFactory.decodeResource(resources, largeIconResId))
        } else {
            cachedLargeIcon?.let { builder.setLargeIcon(it) }
        }

        // 添加停止按钮（可选）
        if (showStopButton) {
            val stopIntent = Intent(this, ForegroundService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
            builder.addAction(R.drawable.ic_stop, getString(R.string.stop), stopPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(updatedContent: String, largeIconResId: Int? = null, showStopButton: Boolean = false) {
        try {
            val notification = createNotification(updatedContent, largeIconResId, showStopButton)
            notificationManager?.notify(FRONT_NOTIFY_ID, notification)
        } catch (e: Exception) {
            handleException(e, "updateNotification")
        }
    }

    private fun handleException(e: Exception, methodName: String) {
        e.printStackTrace()
        Log.e(TAG, "$methodName: $e")
        isRunning = false
    }

}
