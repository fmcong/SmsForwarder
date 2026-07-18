package com.duanxinzhuanfa.xinxi.entity.action

import com.duanxinzhuanfa.xinxi.database.entity.Rule
import java.io.Serializable

data class RuleSetting(
    var description: String = "", //描述
    var status: Int = 1, //状态：0-禁用；1-启用
    var ruleList: List<Rule>, //转发规则列表
) : Serializable
