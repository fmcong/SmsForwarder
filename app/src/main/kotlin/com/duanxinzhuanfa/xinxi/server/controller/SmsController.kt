package com.duanxinzhuanfa.xinxi.server.controller

import android.Manifest
import android.content.pm.PackageManager
import com.duanxinzhuanfa.xinxi.utils.Log
import androidx.core.app.ActivityCompat
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.entity.SmsInfo
import com.duanxinzhuanfa.xinxi.server.model.BaseRequest
import com.duanxinzhuanfa.xinxi.server.model.SmsQueryData
import com.duanxinzhuanfa.xinxi.server.model.SmsSendData
import com.duanxinzhuanfa.xinxi.utils.PhoneUtils
import com.xuexiang.xutil.XUtil
import com.xuexiang.xutil.resource.ResUtils.getString
import com.yanzhenjie.andserver.annotation.*

@Suppress("PrivatePropertyName")
@RestController
@RequestMapping(path = ["/sms"])
class SmsController {

    private val TAG: String = SmsController::class.java.simpleName

    //发送短信
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/send")
    fun send(@RequestBody bean: BaseRequest<SmsSendData>): String {
        val smsSendData = bean.data
        Log.d(TAG, smsSendData.toString())

        //获取卡槽信息
        if (App.SimInfoList.isEmpty()) {
            App.SimInfoList = PhoneUtils.getSimMultiInfo()
        }
        Log.d(TAG, App.SimInfoList.toString())

        //发送卡槽: 1=SIM1, 2=SIM2
        val simSlotIndex = smsSendData.simSlot - 1
        //TODO：取不到卡槽信息时，采用默认卡槽发送
        val mSubscriptionId: Int = App.SimInfoList[simSlotIndex]?.mSubscriptionId ?: -1

        if (ActivityCompat.checkSelfPermission(XUtil.getContext(), Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return getString(R.string.no_sms_sending_permission)
        }

        return PhoneUtils.sendSms(mSubscriptionId, smsSendData.phoneNumbers, smsSendData.msgContent) ?: "success"
    }

    //查询短信
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/query")
    fun query(@RequestBody bean: BaseRequest<SmsQueryData>): List<SmsInfo> {
        val smsQueryData = bean.data
        Log.d(TAG, smsQueryData.toString())

        val limit = smsQueryData.pageSize
        val offset = (smsQueryData.pageNum - 1) * limit
        return PhoneUtils.getSmsInfoList(smsQueryData.type, limit, offset, smsQueryData.keyword)
    }
}