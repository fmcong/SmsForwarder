package com.duanxinzhuanfa.xinxi.entity.result

data class PushplusResult(
    var code: Long,
    var msg: String,
    var data: String?,
    var count: Long?,
)