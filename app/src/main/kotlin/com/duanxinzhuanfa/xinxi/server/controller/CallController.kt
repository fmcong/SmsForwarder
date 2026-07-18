package com.duanxinzhuanfa.xinxi.server.controller

import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.entity.CallInfo
import com.duanxinzhuanfa.xinxi.server.model.BaseRequest
import com.duanxinzhuanfa.xinxi.server.model.CallQueryData
import com.duanxinzhuanfa.xinxi.utils.PhoneUtils
import com.yanzhenjie.andserver.annotation.*

@Suppress("PrivatePropertyName")
@RestController
@RequestMapping(path = ["/call"])
class CallController {

    private val TAG: String = CallController::class.java.simpleName

    //远程查通话
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/query")
    fun query(@RequestBody bean: BaseRequest<CallQueryData>): List<CallInfo> {
        val callQueryData = bean.data
        Log.d(TAG, callQueryData.toString())

        val limit = callQueryData.pageSize
        val offset = (callQueryData.pageNum - 1) * limit
        return PhoneUtils.getCallInfoList(callQueryData.type, limit, offset, callQueryData.phoneNumber)
    }

}