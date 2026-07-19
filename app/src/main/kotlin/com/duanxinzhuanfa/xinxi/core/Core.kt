package com.duanxinzhuanfa.xinxi.core

import android.app.Application
import androidx.work.Configuration
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.BuildConfig
import com.duanxinzhuanfa.xinxi.database.repository.FrpcRepository
import com.duanxinzhuanfa.xinxi.database.repository.LogsRepository
import com.duanxinzhuanfa.xinxi.database.repository.MsgRepository
import com.duanxinzhuanfa.xinxi.database.repository.RuleRepository
import com.duanxinzhuanfa.xinxi.database.repository.SenderRepository
import com.duanxinzhuanfa.xinxi.database.repository.TaskRepository
import com.duanxinzhuanfa.xinxi.utils.Log
import kotlinx.coroutines.launch

object Core : Configuration.Provider {
    lateinit var app: Application
    val frpc: FrpcRepository by lazy { (app as App).frpcRepository }
    val msg: MsgRepository by lazy { (app as App).msgRepository }
    val logs: LogsRepository by lazy { (app as App).logsRepository }
    val rule: RuleRepository by lazy { (app as App).ruleRepository }
    val sender: SenderRepository by lazy { (app as App).senderRepository }
    val task: TaskRepository by lazy { (app as App).taskRepository }

    fun init(app: Application) {
        this.app = app
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().apply {
            setDefaultProcessName(app.packageName + ":bg")
            setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO)
            setExecutor { (app as App).applicationScope.launch { it.run() } }
            setTaskExecutor { (app as App).applicationScope.launch { it.run() } }
        }.build()
}
