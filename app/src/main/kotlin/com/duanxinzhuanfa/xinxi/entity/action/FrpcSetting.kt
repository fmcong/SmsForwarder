package com.duanxinzhuanfa.xinxi.entity.action

import com.duanxinzhuanfa.xinxi.database.entity.Frpc
import java.io.Serializable

data class FrpcSetting(
    var description: String = "", //描述
    var action: String = "start", //动作: start=启动, stop=停止
    var frpcList: List<Frpc>, //Frpc列表
) : Serializable
