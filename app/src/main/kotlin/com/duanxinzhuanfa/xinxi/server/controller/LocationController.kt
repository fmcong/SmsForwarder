package com.duanxinzhuanfa.xinxi.server.controller

import android.annotation.SuppressLint
import com.duanxinzhuanfa.xinxi.entity.LocationInfo
import com.duanxinzhuanfa.xinxi.server.model.BaseRequest
import com.duanxinzhuanfa.xinxi.server.model.EmptyData
import com.duanxinzhuanfa.xinxi.utils.HttpServerUtils
import com.duanxinzhuanfa.xinxi.utils.Log
import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.PostMapping
import com.yanzhenjie.andserver.annotation.RequestBody
import com.yanzhenjie.andserver.annotation.RequestMapping
import com.yanzhenjie.andserver.annotation.RequestMethod
import com.yanzhenjie.andserver.annotation.RestController

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName")
@RestController
@RequestMapping(path = ["/location"])
class LocationController {

    private val TAG: String = LocationController::class.java.simpleName

    //远程找手机
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/query")
    fun query(@RequestBody bean: BaseRequest<EmptyData>): LocationInfo {
        Log.d(TAG, bean.data.toString())
        return HttpServerUtils.apiLocationCache
    }

}