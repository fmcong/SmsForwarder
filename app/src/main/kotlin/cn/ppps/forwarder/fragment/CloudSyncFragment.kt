package cn.ppps.forwarder.fragment

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentCloudSyncBinding
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.utils.CloudSyncEngine
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log
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
import java.security.MessageDigest
import java.util.Date

//云端同步中心：申请访问（/pair/apply 换取 SM4 密钥与配置）
//推送/拉取已全自动：CloudSyncEngine 双向同步（约 30 分钟周期或切换前后台触发）
//协议与 CloneFragment / CloudSyncWorker 保持一致；显式 UA 避免 Cloudflare 1010 拦截
@Suppress("PrivatePropertyName", "DEPRECATION")
@Page(name = "云端同步中心")
class CloudSyncFragment : BaseFragment<FragmentCloudSyncBinding?>(), View.OnClickListener {

    private val TAG: String = CloudSyncFragment::class.java.simpleName

    private var mPairingCountDownHelper: CountDownButtonHelper? = null

    companion object {
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

    override fun initViews() {
        //申请访问按钮增加倒计时，避免重复点击
        mPairingCountDownHelper = CountDownButtonHelper(binding!!.btnPairing, SettingUtils.requestTimeout)
        mPairingCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPairing.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPairing.text = getString(R.string.pairing)
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
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_pairing -> doPairing()
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

    //申请访问：用「配置代号 + 配对码」向服务端换取 SM4 密钥与全量配置（POST /pair/apply，明文 JSON，由 HTTPS 保护）
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

                        //restoreSettings 会清空全部 SP，应用后重新写入配对凭证，避免本地信息被云端配置覆盖；
                        //拉取应用期间的 SP 写入不触发状态上报（engine 的 suppressPushDuring 防回环）
                        CloudSyncEngine.suppressPushDuring {
                            //服务器下发的 settings 可能是纯 JSON，先转换格式并并入本地凭证，再 restore（阶段13 根因修复）
                            CloudSyncEngine.sanitizeSettingsForRestore(cloneInfo)

                            val userCode = HttpServerUtils.clientUserCode
                            val pairingCode = HttpServerUtils.pairingCode
                            HttpServerUtils.restoreSettings(cloneInfo)
                            HttpServerUtils.clientSignKey = sm4Key
                            HttpServerUtils.clientSafetyMeasures = 3
                            HttpServerUtils.serverAddress = address
                            HttpServerUtils.clientUserCode = userCode
                            HttpServerUtils.pairingCode = pairingCode

                            //记录同步标记（远端已应用 + 推送基准），避免引擎下一轮重复应用/回推
                            CloudSyncEngine.recordApplied(sha256(configElement.toString()))
                        }

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

    private fun sha256(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroyView() {
        if (mPairingCountDownHelper != null) mPairingCountDownHelper!!.recycle()
        super.onDestroyView()
    }

}
