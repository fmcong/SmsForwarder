package com.duanxinzhuanfa.xinxi.server.controller

import android.content.Intent
import android.content.IntentFilter
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.entity.BatteryInfo
import com.duanxinzhuanfa.xinxi.server.model.BaseRequest
import com.duanxinzhuanfa.xinxi.server.model.EmptyData
import com.duanxinzhuanfa.xinxi.utils.BatteryUtils
import com.yanzhenjie.andserver.annotation.*

@Suppress("PrivatePropertyName")
@RestController
@RequestMapping(path = ["/battery"])
class BatteryController {

    private val TAG: String = BatteryController::class.java.simpleName

    //远程查电量
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/query")
    fun query(@RequestBody bean: BaseRequest<EmptyData>): BatteryInfo {
        Log.d(TAG, bean.data.toString())

        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = App.context.registerReceiver(null, intentFilter)
        return BatteryUtils.getBatteryInfo(intent)
    }

}