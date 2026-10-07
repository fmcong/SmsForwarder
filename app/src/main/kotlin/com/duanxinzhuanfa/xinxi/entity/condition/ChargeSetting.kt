package com.duanxinzhuanfa.xinxi.entity.condition

import android.os.BatteryManager
import com.duanxinzhuanfa.xinxi.R
import com.xuexiang.xutil.resource.ResUtils.getString
import java.io.Serializable

data class ChargeSetting(
    var description: String = "",
    var statusList: List<Int> = emptyList(),  // 多选状态列表
    var pluggedList: List<Int> = emptyList(),  // 多选充电方式列表，空=不限
    var healthList: List<Int> = emptyList(),  // 多选健康度列表，空=不限
    var voltageMin: Int = 0,  // 最低电压（mV），0=不限
    var voltageMax: Int = 0,  // 最高电压（mV），0=不限
    var temperatureLimited: Boolean = false,  // 是否启用温度条件（温度可为0/负值，需显式标记）
    var temperatureMin: Int = 0,  // 最低温度（℃）
    var temperatureMax: Int = 0,  // 最高温度（℃）
    var matchType: Int = 0,  // 条件组合方式：0=全部满足(AND)，1=任一满足(OR)
    // 旧版字段，保留以兼容已存储的历史数据
    var status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    var plugged: Int = BatteryManager.BATTERY_PLUGGED_AC,
) : Serializable {

    constructor(
        statusCheckIds: List<Int>,
        pluggedCheckIds: List<Int>,
        healthCheckIds: List<Int> = emptyList(),
        voltageMin: Int = 0,
        voltageMax: Int = 0,
        temperatureLimited: Boolean = false,
        temperatureMin: Int = 0,
        temperatureMax: Int = 0,
        matchType: Int = 0,
    ) : this() {
        statusList = statusCheckIds.mapNotNull { id ->
            when (id) {
                R.id.cb_battery_charging -> BatteryManager.BATTERY_STATUS_CHARGING
                R.id.cb_battery_discharging -> BatteryManager.BATTERY_STATUS_DISCHARGING
                R.id.cb_battery_not_charging -> BatteryManager.BATTERY_STATUS_NOT_CHARGING
                R.id.cb_battery_full -> BatteryManager.BATTERY_STATUS_FULL
                R.id.cb_battery_unknown -> BatteryManager.BATTERY_STATUS_UNKNOWN
                else -> null
            }
        }
        pluggedList = if (pluggedCheckIds.contains(R.id.cb_plugged_unlimited)) {
            emptyList() // 不限 = 任意充电方式
        } else {
            pluggedCheckIds.mapNotNull { id ->
                when (id) {
                    R.id.cb_plugged_ac -> BatteryManager.BATTERY_PLUGGED_AC
                    R.id.cb_plugged_usb -> BatteryManager.BATTERY_PLUGGED_USB
                    R.id.cb_plugged_wireless -> BatteryManager.BATTERY_PLUGGED_WIRELESS
                    else -> null
                }
            }
        }
        healthList = if (healthCheckIds.contains(R.id.cb_health_unlimited)) {
            emptyList() // 不限 = 任意健康度
        } else {
            healthCheckIds.mapNotNull { id ->
                when (id) {
                    R.id.cb_health_good -> BatteryManager.BATTERY_HEALTH_GOOD
                    R.id.cb_health_overheat -> BatteryManager.BATTERY_HEALTH_OVERHEAT
                    R.id.cb_health_cold -> BatteryManager.BATTERY_HEALTH_COLD
                    R.id.cb_health_dead -> BatteryManager.BATTERY_HEALTH_DEAD
                    R.id.cb_health_over_voltage -> BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE
                    R.id.cb_health_unspecified_failure -> BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE
                    R.id.cb_health_unknown -> BatteryManager.BATTERY_HEALTH_UNKNOWN
                    else -> null
                }
            }
        }
        this.voltageMin = voltageMin
        this.voltageMax = voltageMax
        this.temperatureLimited = temperatureLimited
        this.temperatureMin = temperatureMin
        this.temperatureMax = temperatureMax
        this.matchType = matchType
        description = buildDescription()
    }

    // 兼容旧数据：statusList 为空时回退到旧字段 status
    private fun getEffectiveStatusList(): List<Int> =
        statusList.ifEmpty { listOf(status) }

    // 兼容旧数据：statusList 为空时代表旧格式，回退到旧字段 plugged
    private fun getEffectivePluggedList(): List<Int> {
        if (statusList.isEmpty()) return if (plugged != 0) listOf(plugged) else emptyList()
        return pluggedList // 新格式：空列表 = 任意充电方式
    }

    // 电压条件是否启用（voltageMax > 0 视为已设置范围）
    private fun isVoltageLimited(): Boolean = voltageMax > 0

    /**
     * 判断当前电池状态是否满足条件
     * matchType = 0：所有已设置（非不限）的条件组都满足才为 true
     * matchType = 1：任一已设置的条件组满足即为 true（全部不限时恒为 true）
     */
    fun isMatch(status: Int, plugged: Int, voltage: Int, health: Int, temperature: Int): Boolean {
        val results = mutableListOf<Boolean>()
        val effectiveStatusList = getEffectiveStatusList()
        if (effectiveStatusList.isNotEmpty()) results.add(status in effectiveStatusList)
        val effectivePluggedList = getEffectivePluggedList()
        if (effectivePluggedList.isNotEmpty()) results.add(plugged in effectivePluggedList)
        if (isVoltageLimited()) results.add(voltage in voltageMin..voltageMax)
        if (healthList.isNotEmpty()) results.add(health in healthList)
        if (temperatureLimited) results.add(temperature in temperatureMin..temperatureMax)

        if (results.isEmpty()) return true // 全部不限
        return if (matchType == 1) results.any { it } else results.all { it }
    }

    private fun buildDescription(): String {
        val statusStr = getEffectiveStatusList().joinToString("/") { getStatusStr(it) }
        val effectivePlugged = getEffectivePluggedList()
        val pluggedStr = if (effectivePlugged.isEmpty()) getString(R.string.battery_unlimited)
                         else effectivePlugged.joinToString("/") { getPluggedStr(it) }
        val voltageStr = if (!isVoltageLimited()) getString(R.string.battery_unlimited)
                         else "${voltageMin}mV~${voltageMax}mV"
        val temperatureStr = if (!temperatureLimited) getString(R.string.battery_unlimited)
                             else "$temperatureMin℃~$temperatureMax℃"
        val healthStr = if (healthList.isEmpty()) getString(R.string.battery_unlimited)
                        else healthList.joinToString("/") { getHealthStr(it) }
        val matchTypeStr = if (matchType == 1) getString(R.string.condition_match_any) else getString(R.string.condition_match_all)
        return String.format(getString(R.string.battery_status), statusStr) + ", " +
               String.format(getString(R.string.battery_plugged), pluggedStr) + ", " +
               String.format(getString(R.string.battery_voltage), voltageStr) + ", " +
               String.format(getString(R.string.battery_temperature), temperatureStr) + ", " +
               String.format(getString(R.string.battery_health), healthStr) + ", " +
               matchTypeStr
    }

    private fun getStatusStr(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.battery_charging)
        BatteryManager.BATTERY_STATUS_DISCHARGING -> getString(R.string.battery_discharging)
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> getString(R.string.battery_not_charging)
        BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.battery_full)
        else -> getString(R.string.battery_unknown)
    }

    private fun getPluggedStr(p: Int): String = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> getString(R.string.battery_ac)
        BatteryManager.BATTERY_PLUGGED_USB -> getString(R.string.battery_usb)
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> getString(R.string.battery_wireless)
        else -> getString(R.string.battery_unlimited)
    }

    private fun getHealthStr(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> getString(R.string.battery_good)
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> getString(R.string.battery_overheat)
        BatteryManager.BATTERY_HEALTH_COLD -> getString(R.string.battery_cold)
        BatteryManager.BATTERY_HEALTH_DEAD -> getString(R.string.battery_dead)
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> getString(R.string.battery_over_voltage)
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> getString(R.string.battery_unspecified_failure)
        else -> getString(R.string.battery_unknown)
    }

    fun getStatusCheckIds(): List<Int> = getEffectiveStatusList().mapNotNull { s ->
        when (s) {
            BatteryManager.BATTERY_STATUS_CHARGING -> R.id.cb_battery_charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> R.id.cb_battery_discharging
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.id.cb_battery_not_charging
            BatteryManager.BATTERY_STATUS_FULL -> R.id.cb_battery_full
            BatteryManager.BATTERY_STATUS_UNKNOWN -> R.id.cb_battery_unknown
            else -> null
        }
    }

    fun getPluggedCheckIds(): List<Int> {
        val effective = getEffectivePluggedList()
        if (effective.isEmpty()) return listOf(R.id.cb_plugged_unlimited)
        return effective.mapNotNull { p ->
            when (p) {
                BatteryManager.BATTERY_PLUGGED_AC -> R.id.cb_plugged_ac
                BatteryManager.BATTERY_PLUGGED_USB -> R.id.cb_plugged_usb
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.id.cb_plugged_wireless
                else -> null
            }
        }
    }

    fun getHealthCheckIds(): List<Int> {
        if (healthList.isEmpty()) return listOf(R.id.cb_health_unlimited)
        return healthList.mapNotNull { h ->
            when (h) {
                BatteryManager.BATTERY_HEALTH_GOOD -> R.id.cb_health_good
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.id.cb_health_overheat
                BatteryManager.BATTERY_HEALTH_COLD -> R.id.cb_health_cold
                BatteryManager.BATTERY_HEALTH_DEAD -> R.id.cb_health_dead
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.id.cb_health_over_voltage
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.id.cb_health_unspecified_failure
                BatteryManager.BATTERY_HEALTH_UNKNOWN -> R.id.cb_health_unknown
                else -> null
            }
        }
    }

    fun getMsg(statusNew: Int, statusOld: Int, pluggedNew: Int, pluggedOld: Int, healthNew: Int, healthOld: Int, voltage: Int, temperature: Int, batteryInfo: String): String {
        if (!isMatch(statusNew, pluggedNew, voltage, healthNew, temperature)) return ""
        var msg = getString(R.string.battery_status_changed) + getStatusStr(statusOld) + "(" + getPluggedStr(pluggedOld) + ") → " + getStatusStr(statusNew) + "(" + getPluggedStr(pluggedNew) + ")"
        if (healthNew != healthOld) {
            msg += getString(R.string.battery_health_changed) + getHealthStr(healthOld) + " → " + getHealthStr(healthNew)
        }
        return msg + batteryInfo
    }
}
