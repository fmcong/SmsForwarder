package com.duanxinzhuanfa.xinxi.fragment.client

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.activity.MainActivity
import com.duanxinzhuanfa.xinxi.core.BaseFragment
import com.duanxinzhuanfa.xinxi.databinding.FragmentClientCloneBinding
import com.duanxinzhuanfa.xinxi.entity.CloneInfo
import com.duanxinzhuanfa.xinxi.server.model.BaseResponse
import com.duanxinzhuanfa.xinxi.utils.AppUtils
import com.duanxinzhuanfa.xinxi.utils.Base64
import com.duanxinzhuanfa.xinxi.utils.CommonUtils
import com.duanxinzhuanfa.xinxi.utils.HttpServerUtils
import com.duanxinzhuanfa.xinxi.utils.KEY_DEFAULT_SELECTION
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.RSACrypt
import com.duanxinzhuanfa.xinxi.utils.SM4Crypt
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.AutoBackupUtils
import com.duanxinzhuanfa.xinxi.utils.NasSyncUtils
import com.duanxinzhuanfa.xinxi.utils.XToastUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.cache.model.CacheMode
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.data.ConvertTools
import com.xuexiang.xutil.file.FileIOUtils
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.resource.ResUtils.getStringArray
import java.io.File
import java.util.Date

@Suppress("PrivatePropertyName")
@Page(name = "一键换新机")
class CloneFragment : BaseFragment<FragmentClientCloneBinding?>(), View.OnClickListener {

    private val TAG: String = CloneFragment::class.java.simpleName
    private var backupPath: String? = null
    private val backupFile = "SmsForwarder.json"
    private var pushCountDownHelper: CountDownButtonHelper? = null
    private var pullCountDownHelper: CountDownButtonHelper? = null
    private var exportCountDownHelper: CountDownButtonHelper? = null
    private var importCountDownHelper: CountDownButtonHelper? = null

    @JvmField
    @AutoWired(name = KEY_DEFAULT_SELECTION)
    var defaultSelection: Int = 0

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientCloneBinding {
        return FragmentClientCloneBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_clone)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        // 申请储存权限
        XXPermissions.with(this)
            .permission(PermissionLists.getManageExternalStoragePermission())
            .request(object : OnPermissionCallback {
                @SuppressLint("SetTextI18n")
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        // 判断请求失败的权限是否被用户勾选了不再询问的选项
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        // 处理权限请求失败的逻辑
                        binding!!.tvBackupPath.text = getString(R.string.storage_permission_tips)
                        return
                    }
                    // 处理权限请求成功的逻辑
                    backupPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                    binding!!.tvBackupPath.text = backupPath + File.separator + backupFile

                }
            })

        binding!!.tabBar.setTabTitles(getStringArray(R.array.clone_type_option))
        binding!!.tabBar.setOnTabClickListener { _, position ->
            when (position) {
                0 -> {
                    binding!!.layoutNetwork.visibility = View.VISIBLE
                    binding!!.layoutOffline.visibility = View.GONE
                    binding!!.layoutCloud.visibility = View.GONE
                }
                1 -> {
                    binding!!.layoutNetwork.visibility = View.GONE
                    binding!!.layoutOffline.visibility = View.VISIBLE
                    binding!!.layoutCloud.visibility = View.GONE
                }
                2 -> {
                    binding!!.layoutNetwork.visibility = View.GONE
                    binding!!.layoutOffline.visibility = View.GONE
                    binding!!.layoutCloud.visibility = View.VISIBLE
                    val dn = SettingUtils.extraDeviceMark
                    if (dn.isNotBlank() && binding!!.etNasDeviceName.text.isNullOrBlank()) {
                        binding!!.etNasDeviceName.setText(dn)
                    }
                }
            }
        }
        binding!!.swAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            SettingUtils.enableAutoBackup = isChecked
            if (isChecked) XToastUtils.info(getString(R.string.nas_auto_backup_enabled))
        }
        binding!!.swAutoBackup.isChecked = SettingUtils.enableAutoBackup

        if (defaultSelection == 1) {
            binding!!.tabBar.visibility = View.GONE
            binding!!.layoutNetwork.visibility = View.GONE
            binding!!.layoutOffline.visibility = View.VISIBLE
        }

        //按钮增加倒计时，避免重复点击
        pushCountDownHelper = CountDownButtonHelper(binding!!.btnPush, SettingUtils.requestTimeout)
        pushCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPush.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPush.text = getString(R.string.push)
            }
        })
        pullCountDownHelper = CountDownButtonHelper(binding!!.btnPull, SettingUtils.requestTimeout)
        pullCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPull.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPull.text = getString(R.string.pull)
            }
        })
        exportCountDownHelper = CountDownButtonHelper(binding!!.btnExport, 3)
        exportCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnExport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnExport.text = getString(R.string.export)
            }
        })
        importCountDownHelper = CountDownButtonHelper(binding!!.btnImport, 3)
        importCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnImport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnImport.text = getString(R.string.imports)
            }
        })
    }

    override fun initListeners() {
        binding!!.btnPush.setOnClickListener(this)
        binding!!.btnPull.setOnClickListener(this)
        binding!!.btnExport.setOnClickListener(this)
        binding!!.btnImport.setOnClickListener(this)
        binding!!.btnNasPull.setOnClickListener(this)
        binding!!.btnNasBackup.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            //推送配置
            R.id.btn_push -> pushData()
            //拉取配置
            R.id.btn_pull -> pullData()
            //导出配置
            R.id.btn_export -> {
                try {
                    exportCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    //判断文件是否存在，存在则在创建之前删除
                    FileUtils.createFileByDeleteOldFile(file)
                    val cloneInfo = HttpServerUtils.exportSettings()
                    val jsonStr = Gson().toJson(cloneInfo)
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (FileIOUtils.writeFileFromString(file, jsonStr)) {
                        XToastUtils.success(getString(R.string.export_succeeded))
                    } else {
                        binding!!.tvExport.text = getString(R.string.export_failed)
                        XToastUtils.error(getString(R.string.export_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(String.format(getString(R.string.export_failed_tips), e.message))
                }
            }
            //导入配置
            R.id.btn_import -> {
                try {
                    importCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    //判断文件是否存在
                    if (!FileUtils.isFileExists(file)) {
                        XToastUtils.error(getString(R.string.import_failed_file_not_exist))
                        return
                    }

                    val jsonStr = FileIOUtils.readFile2String(file)
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (TextUtils.isEmpty(jsonStr)) {
                        XToastUtils.error(getString(R.string.import_failed))
                        return
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java)
                    Log.d(TAG, "cloneInfo = $cloneInfo")

                    //判断版本是否一致
                    HttpServerUtils.compareVersion(cloneInfo)

                    if (HttpServerUtils.restoreSettings(cloneInfo)) {
                        MaterialDialog.Builder(requireContext())
                            .iconRes(R.drawable.icon_api_clone)
                            .title(R.string.clone)
                            .content(R.string.import_succeeded)
                            .cancelable(false)
                            .positiveText(R.string.confirm)
                            .onPositive { _: MaterialDialog?, _: DialogAction? ->
                                val intent = Intent(App.context, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                            }
                            .show()
                    } else {
                        XToastUtils.error(getString(R.string.import_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(String.format(getString(R.string.import_failed_tips), e.message))
                }
            }
            // 云同步：从 NAS 拉取配置
            R.id.btn_nas_pull -> {
                val url = binding!!.etNasUrl.text.toString().trim()
                val dn = binding!!.etNasDeviceName.text.toString().trim()
                if (url.isBlank()) { XToastUtils.warning(getString(R.string.nas_url_required)); return }
                if (dn.isBlank()) { XToastUtils.warning(getString(R.string.nas_device_name_required)); return }
                pullFromNas(url, dn)
            }
            // 云同步：备份当前配置
            R.id.btn_nas_backup -> {
                val dn = binding!!.etNasDeviceName.text.toString().trim().ifBlank { SettingUtils.extraDeviceMark }
                if (dn.isBlank()) { XToastUtils.warning(getString(R.string.nas_device_name_required)); return }
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        showCloudStatus(getString(R.string.nas_backing_up))
                        val f = AutoBackupUtils.backupNow(dn)
                        if (f != null) {
                            showCloudStatus(getString(R.string.nas_backup_done, f.name))
                            XToastUtils.success(getString(R.string.nas_backup_success))
                        } else {
                            showCloudStatus(getString(R.string.nas_backup_failed))
                            XToastUtils.error(getString(R.string.nas_backup_failed))
                        }
                    } catch (e: Exception) {
                        showCloudStatus("Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showCloudStatus(msg: String) {
        binding!!.tvCloudStatus.visibility = View.VISIBLE
        binding!!.tvCloudStatus.text = msg
    }

    private fun pullFromNas(url: String, deviceName: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                showCloudStatus(getString(R.string.nas_connecting))
                val dir = File(backupPath ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                val f = NasSyncUtils.pullConfig(url, deviceName, dir)
                if (f != null) {
                    showCloudStatus(getString(R.string.nas_pull_done, f.name))
                    val info = Gson().fromJson(f.readText(), CloneInfo::class.java)
                    if (info != null) { HttpServerUtils.compareVersion(info); HttpServerUtils.restoreSettings(info); XToastUtils.success(getString(R.string.nas_pull_success)) }
                    else XToastUtils.error(getString(R.string.import_failed))
                } else {
                    showCloudStatus(getString(R.string.nas_no_config_found, deviceName))
                    XToastUtils.warning(getString(R.string.nas_no_config_found, deviceName))
                }
            } catch (e: Exception) {
                showCloudStatus("Error: ${e.message}")
            }
        }
    }

    //推送配置
    private fun pushData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        pushCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/push"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }
        msgMap["data"] = HttpServerUtils.exportSettings()

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeOut((SettingUtils.requestTimeout * 1000).toLong()) //超时时间10s
            .cacheMode(CacheMode.NO_CACHE).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                pushCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }
                    val resp: BaseResponse<String> = Gson().fromJson(json, object : TypeToken<BaseResponse<String>>() {}.type)
                    if (resp.code == 200) {
                        XToastUtils.success(getString(R.string.request_succeeded))
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                pushCountDownHelper?.finish()
            }
        })

    }

    //拉取配置
    private fun pullData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        exportCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/pull"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }

        val dataMap: MutableMap<String, Any> = mutableMapOf()
        dataMap["version_code"] = AppUtils.getAppVersionCode()
        msgMap["data"] = dataMap

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                exportCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val resp: BaseResponse<CloneInfo> = gson.fromJson(json, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
                    if (resp.code == 200) {
                        val cloneInfo = resp.data
                        Log.d(TAG, "cloneInfo = $cloneInfo")

                        if (cloneInfo == null) {
                            XToastUtils.error(getString(R.string.request_failed))
                            return
                        }

                        //判断版本是否一致
                        HttpServerUtils.compareVersion(cloneInfo)

                        if (HttpServerUtils.restoreSettings(cloneInfo)) {
                            XToastUtils.success(getString(R.string.import_succeeded))
                        }
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                exportCountDownHelper?.finish()
            }
        })

    }

    override fun onDestroyView() {
        if (pushCountDownHelper != null) pushCountDownHelper!!.recycle()
        if (pullCountDownHelper != null) pullCountDownHelper!!.recycle()
        if (exportCountDownHelper != null) exportCountDownHelper!!.recycle()
        if (importCountDownHelper != null) importCountDownHelper!!.recycle()
        super.onDestroyView()
    }
}