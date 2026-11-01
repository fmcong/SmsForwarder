package cn.ppps.forwarder.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentCloudSyncBinding
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.IS_AGREE_PRIVACY_KEY
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SM4Crypt
import cn.ppps.forwarder.utils.SP_ENABLE_APP_NOTIFY
import cn.ppps.forwarder.utils.SP_ENABLE_PHONE
import cn.ppps.forwarder.utils.SP_ENABLE_SMS
import cn.ppps.forwarder.utils.SP_LAST_CLOUD_SYNC_HASH
import cn.ppps.forwarder.utils.SP_LAST_CLOUD_SYNC_TIME
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SharedPreference
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xutil.data.ConvertTools
import java.security.MessageDigest
import java.util.Date

//云端同步中心：配对（/pair/apply）、推送备份（/clone/push）、拉取恢复（/clone/pull）
//协议与 CloneFragment / CloudSyncWorker 保持一致；显式 UA 避免 Cloudflare 1010 拦截
@Suppress("PrivatePropertyName", "DEPRECATION")
@Page(name = "云端同步中心")
class CloudSyncFragment : BaseFragment<FragmentCloudSyncBinding?>(), View.OnClickListener {

    private val TAG: String = CloudSyncFragment::class.java.simpleName

    private var mPairingCountDownHelper: CountDownButtonHelper? = null
    private var mPushCountDownHelper: CountDownButtonHelper? = null
    private var mPullCountDownHelper: CountDownButtonHelper? = null

    companion object {
        private var lastCloudSyncHash: String by SharedPreference(SP_LAST_CLOUD_SYNC_HASH, "")
        private var lastCloudSyncTime: String by SharedPreference(SP_LAST_CLOUD_SYNC_TIME, "")
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.cloud_sync)
        return titleBar
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentCloudSyncBinding {
        return FragmentCloudSyncBinding.inflate(inflater, container, false)
    }

    @SuppressLint("SetTextI18n")
    override fun initViews() {
        //三个操作按钮增加倒计时，避免重复点击
        mPairingCountDownHelper = CountDownButtonHelper(binding!!.btnPairing, SettingUtils.requestTimeout)
        mPairingCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPairing.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPairing.text = getString(R.string.pairing)
            }
        })
        mPushCountDownHelper = CountDownButtonHelper(binding!!.btnPush, SettingUtils.requestTimeout)
        mPushCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPush.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPush.text = getString(R.string.push)
            }
        })
        mPullCountDownHelper = CountDownButtonHelper(binding!!.btnPull, SettingUtils.requestTimeout)
        mPullCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPull.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPull.text = getString(R.string.pull)
            }
        })

        refreshStatus()
    }

    //刷新状态卡与输入框回显
    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        binding!!.etSyncAddress.setText(HttpServerUtils.serverAddress)
        binding!!.etSyncUserCode.setText(HttpServerUtils.clientUserCode)
        binding!!.etSyncPairingCode.setText(HttpServerUtils.pairingCode)
        binding!!.etSyncSignKey.setText(HttpServerUtils.clientSignKey)
        binding!!.tvCurrentUserCode.text = HttpServerUtils.clientUserCode
        binding!!.tvLastSyncTime.text = lastCloudSyncTime
    }

    override fun initListeners() {
        binding!!.etSyncAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                HttpServerUtils.serverAddress = binding!!.etSyncAddress.text.toString().trim().trimEnd('/')
            }
        })
        binding!!.etSyncUserCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                HttpServerUtils.clientUserCode = binding!!.etSyncUserCode.text.toString().trim()
            }
        })
        binding!!.etSyncPairingCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                HttpServerUtils.pairingCode = binding!!.etSyncPairingCode.text.toString().trim()
            }
        })
        binding!!.etSyncSignKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                HttpServerUtils.clientSignKey = binding!!.etSyncSignKey.text.toString().trim()
            }
        })

        binding!!.btnPairing.setOnClickListener(this)
        binding!!.btnPush.setOnClickListener(this)
        binding!!.btnPull.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_pairing -> doPairing()
            R.id.btn_push -> doPushData()
            R.id.btn_pull -> doPullData()
        }
    }

    /**
     * 守卫：读取地址后先 trim，再校验；失败时 toast 带上实际值便于诊断。
     * 返回归一化后的地址，校验失败返回 null。
     */
    private fun checkServerAddress(): String? {
        val address = HttpServerUtils.serverAddress.trim().trimEnd('/')
        if (!CommonUtils.checkUrl(address)) {
            XToastUtils.error(String.format(getString(R.string.invalid_address_detail), address))
            return null
        }
        //写回归一化后的地址，避免脏数据残留
        if (address != HttpServerUtils.serverAddress) {
            HttpServerUtils.serverAddress = address
            binding!!.etSyncAddress.setText(address)
        }
        return address
    }

    //配对：用「配置代号 + 配对码」向服务端换取 SM4 密钥与全量配置（POST /pair/apply，明文 JSON，由 HTTPS 保护）
    private fun doPairing() {
        val address = checkServerAddress() ?: return
        if (TextUtils.isEmpty(HttpServerUtils.clientUserCode) || TextUtils.isEmpty(HttpServerUtils.pairingCode)) {
            XToastUtils.error(getString(R.string.pairing_code_required))
            return
        }

        mPairingCountDownHelper?.start()

        val requestUrl: String = address + "/pair/apply"
        Log.i(TAG, "requestUrl:$requestUrl")

        val bodyMap: MutableMap<String, Any> = mutableMapOf()
        bodyMap["code"] = HttpServerUtils.clientUserCode
        bodyMap["access_code"] = HttpServerUtils.pairingCode
        bodyMap["device_mark"] = SettingUtils.extraDeviceMark

        XHttp.post(requestUrl).keepJson(true).timeStamp(true)
            .headers("User-Agent", "SmsForwarder-Clone/1.0")
            .upJson(Gson().toJson(bodyMap))
            .execute(object : SimpleCallBack<String>() {
                override fun onError(e: ApiException) {
                    XToastUtils.error(e.displayMessage)
                    mPairingCountDownHelper?.finish()
                }

                override fun onSuccess(response: String) {
                    Log.i(TAG, response)
                    try {
                        val resp = Gson().fromJson(response, JsonObject::class.java)
                        val code = resp?.get("code")?.asInt ?: -1
                        val msg = resp?.get("msg")?.asString ?: ""
                        if (code != 200) {
                            XToastUtils.error(getString(R.string.request_failed) + msg)
                            return
                        }
                        val data = resp?.getAsJsonObject("data")
                        val sm4Key = data?.get("sm4_key")?.asString ?: ""
                        val configElement = data?.get("config")
                        if (TextUtils.isEmpty(sm4Key) || configElement == null) {
                            XToastUtils.error(getString(R.string.request_failed) + msg)
                            return
                        }

                        val cloneInfo = buildCloneInfo(configElement)
                        if (cloneInfo == null) {
                            XToastUtils.error(getString(R.string.request_failed) + msg)
                            return
                        }

                        //判断版本是否一致（不兼容会抛异常，走 catch 分支）
                        HttpServerUtils.compareVersion(cloneInfo)

                        //服务器下发的 settings 可能是纯 JSON，先转换格式并并入本地凭证，再 restore（阶段13 根因修复）
                        sanitizeSettingsForRestore(cloneInfo)

                        //restoreSettings 会清空全部 SP，应用后重新写入配对凭证，避免本地信息被云端配置覆盖
                        val userCode = HttpServerUtils.clientUserCode
                        val pairingCode = HttpServerUtils.pairingCode
                        HttpServerUtils.restoreSettings(cloneInfo)
                        HttpServerUtils.clientSignKey = sm4Key
                        HttpServerUtils.clientSafetyMeasures = 3
                        HttpServerUtils.serverAddress = address
                        HttpServerUtils.clientUserCode = userCode
                        HttpServerUtils.pairingCode = pairingCode

                        refreshStatus()
                        XToastUtils.success(getString(R.string.pairing_succeeded))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e(TAG, "doPairing error: ${e.message}")
                        XToastUtils.error(getString(R.string.request_failed) + e.message)
                    }
                    mPairingCountDownHelper?.finish()
                }
            })
    }

    //推送配置（POST /clone/push，协议与 CloneFragment.doPushData 一致）
    private fun doPushData() {
        val address = checkServerAddress() ?: return
        val clientSignKey = HttpServerUtils.clientSignKey
        if (HttpServerUtils.clientSafetyMeasures == 2) {
            XToastUtils.error(getString(R.string.cloud_sync_need_key))
            return
        }
        if (TextUtils.isEmpty(clientSignKey)) {
            XToastUtils.error(getString(R.string.cloud_sync_need_key))
            return
        }

        mPushCountDownHelper?.start()

        val requestUrl: String = address + "/clone/push"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        val exportInfo = HttpServerUtils.exportSettings()
        exportInfo.userCode = HttpServerUtils.clientUserCode
        msgMap["data"] = exportInfo

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeStamp(true)
            .headers("User-Agent", "SmsForwarder-Clone/1.0")

        if (HttpServerUtils.clientSafetyMeasures == 3) {
            try {
                val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
                requestMsg = ConvertTools.bytes2HexString(SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key))
            } catch (e: Exception) {
                XToastUtils.error(getString(R.string.request_failed) + e.message)
                Log.e(TAG, "SM4Crypt error: ${e.message}")
                mPushCountDownHelper?.finish()
                return
            }
            postRequest.upString(requestMsg)
        } else {
            postRequest.upJson(requestMsg)
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                mPushCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
                        json = String(SM4Crypt.decrypt(ConvertTools.hexStringToByteArray(json), sm4Key))
                    }
                    val resp: BaseResponse<String> = Gson().fromJson(json, object : TypeToken<BaseResponse<String>>() {}.type)
                    if (resp.code == 200) {
                        XToastUtils.success(getString(R.string.request_succeeded))
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, "doPushData error: ${e.message}")
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                mPushCountDownHelper?.finish()
            }
        })
    }

    //拉取配置（POST /clone/pull，协议与 CloneFragment.doPullData / CloudSyncWorker 一致）
    private fun doPullData() {
        val address = checkServerAddress() ?: return
        val clientSignKey = HttpServerUtils.clientSignKey
        if (HttpServerUtils.clientSafetyMeasures == 2 || TextUtils.isEmpty(clientSignKey)) {
            XToastUtils.error(getString(R.string.cloud_sync_need_key))
            return
        }

        mPullCountDownHelper?.start()

        val requestUrl: String = address + "/clone/pull"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        msgMap["data"] = mutableMapOf<String, Any>(
            "version_code" to AppUtils.getAppVersionCode(),
            "user_code" to HttpServerUtils.clientUserCode,
        )

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeStamp(true)
            .headers("User-Agent", "SmsForwarder-Clone/1.0")

        if (HttpServerUtils.clientSafetyMeasures == 3) {
            try {
                val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
                requestMsg = ConvertTools.bytes2HexString(SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key))
            } catch (e: Exception) {
                XToastUtils.error(getString(R.string.request_failed) + e.message)
                Log.e(TAG, "SM4Crypt error: ${e.message}")
                mPullCountDownHelper?.finish()
                return
            }
            postRequest.upString(requestMsg)
        } else {
            postRequest.upJson(requestMsg)
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                mPullCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(clientSignKey)
                        json = String(SM4Crypt.decrypt(ConvertTools.hexStringToByteArray(json), sm4Key))
                    }

                    //替换Date字段为当前时间（与 CloneFragment 一致）
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val resp: BaseResponse<CloneInfo> = builder.create().fromJson(json, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
                    if (resp.code != 200) {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                        return
                    }
                    val cloneInfo = resp.data ?: throw Exception("data is null")

                    //判断版本是否一致（不兼容会抛异常，走 catch 分支）
                    HttpServerUtils.compareVersion(cloneInfo)

                    //服务器下发的 settings 可能是纯 JSON，先转换格式并并入本地凭证，再 restore（阶段13 根因修复）
                    sanitizeSettingsForRestore(cloneInfo)

                    //restoreSettings 会清空全部 SP，应用后重新写入本地凭证与同步标记
                    val serverAddress = address
                    val userCode = HttpServerUtils.clientUserCode
                    val pairingCode = HttpServerUtils.pairingCode
                    if (HttpServerUtils.restoreSettings(cloneInfo)) {
                        HttpServerUtils.serverAddress = serverAddress
                        HttpServerUtils.clientUserCode = userCode
                        HttpServerUtils.pairingCode = pairingCode
                        HttpServerUtils.clientSignKey = clientSignKey
                        lastCloudSyncHash = sha256OfData(json)
                        lastCloudSyncTime = now()
                        refreshStatus()
                        XToastUtils.success(getString(R.string.import_succeeded))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, "doPullData error: ${e.message}")
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                mPullCountDownHelper?.finish()
            }
        })
    }

    private fun buildCloneInfo(configElement: com.google.gson.JsonElement?): CloneInfo? {
        return try {
            val builder = GsonBuilder()
            builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            builder.create().fromJson(configElement, CloneInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "buildCloneInfo error: ${e.message}")
            null
        }
    }

    //服务器下发的 settings 若为纯 JSON（默认骨架/直接下发），SharedPreference.importPreference（Java 反序列化）
    //会解析失败，导致 clearPreference 之后 restoreSettings 中途异常、本地凭证全部丢失（阶段13 根因）。
    //在 restoreSettings 之前统一转换为导出格式，并并入本地凭证与安全兜底项。
    private fun sanitizeSettingsForRestore(cloneInfo: CloneInfo) {
        val raw = cloneInfo.settings.trim()
        if (raw.isEmpty() || raw.startsWith("{")) {
            val map = LinkedHashMap<String, Any>()
            if (raw.isNotEmpty()) {
                val obj = Gson().fromJson(raw, JsonObject::class.java)
                for ((k, v) in obj.entrySet()) {
                    when {
                        v.isJsonPrimitive -> {
                            val p = v.asJsonPrimitive
                            when {
                                p.isBoolean -> map[k] = p.asBoolean
                                p.isNumber -> {
                                    //按 SP 实际存储类型还原：整数优先 Int，超出 Int 范围用 Long，小数用 Float
                                    val d = p.asDouble
                                    map[k] = when {
                                        d == kotlin.math.floor(d) && !d.isInfinite() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE -> d.toInt()
                                        d == kotlin.math.floor(d) && !d.isInfinite() -> d.toLong()
                                        else -> d.toFloat()
                                    }
                                }
                                else -> map[k] = p.asString
                            }
                        }
                        else -> map[k] = v.asString
                    }
                }
            }
            //本地凭证并入，restore 清空后随 importPreference 一起恢复（含隐私同意标记，避免弹窗重现）
            map["server_address"] = HttpServerUtils.serverAddress
            map["client_user_code"] = HttpServerUtils.clientUserCode
            map["client_pairing_code"] = HttpServerUtils.pairingCode
            map["client_sign_key"] = HttpServerUtils.clientSignKey
            map[IS_AGREE_PRIVACY_KEY] = true
            map[SP_ENABLE_SMS] = false   //安全兜底：配置下发不静默开启转发
            map[SP_ENABLE_PHONE] = false
            map[SP_ENABLE_APP_NOTIFY] = false
            cloneInfo.settings = serializePrefs(map)
        }
    }

    //与 SharedPreference.exportPreference() 相同的导出格式（Java 序列化 + URLEncode）
    private fun serializePrefs(map: Map<String, Any>): String {
        val baos = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(baos).use { it.writeObject(map) }
        return java.net.URLEncoder.encode(baos.toString("ISO-8859-1"), "UTF-8")
    }

    //对响应 JSON 的 data 子树计算 SHA-256（与 CloudSyncWorker 的变更检测口径一致）
    private fun sha256OfData(json: String): String {
        return try {
            val dataElement = Gson().fromJson(json, JsonObject::class.java)?.get("data")
            val bytes = MessageDigest.getInstance("SHA-256").digest((dataElement?.toString() ?: "").toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

    override fun onDestroyView() {
        if (mPairingCountDownHelper != null) mPairingCountDownHelper!!.recycle()
        if (mPushCountDownHelper != null) mPushCountDownHelper!!.recycle()
        if (mPullCountDownHelper != null) mPullCountDownHelper!!.recycle()
        super.onDestroyView()
    }

}
