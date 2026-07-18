package com.duanxinzhuanfa.xinxi.entity.setting

import java.io.Serializable

data class GotifySetting(
    var webServer: String = "",
    val title: String = "",
    val priority: String = "",
) : Serializable