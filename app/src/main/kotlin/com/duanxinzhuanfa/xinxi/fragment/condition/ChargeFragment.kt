package com.duanxinzhuanfa.xinxi.fragment.condition

import android.annotation.SuppressLint
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.core.BaseFragment
import com.duanxinzhuanfa.xinxi.databinding.FragmentTasksConditionChargeBinding
import com.duanxinzhuanfa.xinxi.entity.condition.ChargeSetting
import com.duanxinzhuanfa.xinxi.utils.KEY_BACK_DATA_CONDITION
import com.duanxinzhuanfa.xinxi.utils.KEY_BACK_DESCRIPTION_CONDITION
import com.duanxinzhuanfa.xinxi.utils.KEY_EVENT_DATA_CONDITION
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.TASK_CONDITION_CHARGE
import com.duanxinzhuanfa.xinxi.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.button.SmoothCheckBox
import com.xuexiang.xui.widget.picker.XRangeSlider

@Page(name = "Charge")
@Suppress("PrivatePropertyName", "SameParameterValue")
class ChargeFragment : BaseFragment<FragmentTasksConditionChargeBinding?>(), View.OnClickListener {

    private val TAG: String = ChargeFragment::class.java.simpleName
    private var titleBar: TitleBar? = null

    @JvmField
    @AutoWired(name = KEY_EVENT_DATA_CONDITION)
    var eventData: String? = null

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentTasksConditionChargeBinding {
        return FragmentTasksConditionChargeBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        titleBar = super.initTitle()!!.setImmersive(false).setTitle(R.string.task_charge)
        return titleBar
    }

    override fun initViews() {
        Log.d(TAG, "initViews eventData:$eventData")
        if (eventData != null) {
            val settingVo = Gson().fromJson(eventData, ChargeSetting::class.java)
            Log.d(TAG, "initViews settingVo:$settingVo")
            binding!!.tvDescription.text = settingVo.description
            val statusIds = settingVo.getStatusCheckIds()
            binding!!.cbBatteryCharging.isChecked = R.id.cb_battery_charging in statusIds
            binding!!.cbBatteryDischarging.isChecked = R.id.cb_battery_discharging in statusIds
            binding!!.cbBatteryNotCharging.isChecked = R.id.cb_battery_not_charging in statusIds
            binding!!.cbBatteryFull.isChecked = R.id.cb_battery_full in statusIds
            binding!!.cbBatteryUnknown.isChecked = R.id.cb_battery_unknown in statusIds
            val pluggedIds = settingVo.getPluggedCheckIds()
            binding!!.cbPluggedAc.isChecked = R.id.cb_plugged_ac in pluggedIds
            binding!!.cbPluggedUsb.isChecked = R.id.cb_plugged_usb in pluggedIds
            binding!!.cbPluggedWireless.isChecked = R.id.cb_plugged_wireless in pluggedIds
            binding!!.cbPluggedUnlimited.isChecked = R.id.cb_plugged_unlimited in pluggedIds
            val healthIds = settingVo.getHealthCheckIds()
            binding!!.cbHealthUnlimited.isChecked = R.id.cb_health_unlimited in healthIds
            binding!!.cbHealthGood.isChecked = R.id.cb_health_good in healthIds
            binding!!.cbHealthOverheat.isChecked = R.id.cb_health_overheat in healthIds
            binding!!.cbHealthCold.isChecked = R.id.cb_health_cold in healthIds
            binding!!.cbHealthDead.isChecked = R.id.cb_health_dead in healthIds
            binding!!.cbHealthOverVoltage.isChecked = R.id.cb_health_over_voltage in healthIds
            binding!!.cbHealthUnspecifiedFailure.isChecked = R.id.cb_health_unspecified_failure in healthIds
            binding!!.cbHealthUnknown.isChecked = R.id.cb_health_unknown in healthIds
            val voltageLimited = settingVo.voltageMax > 0
            binding!!.cbVoltageUnlimited.isChecked = !voltageLimited
            binding!!.layoutVoltage.visibility = if (voltageLimited) View.VISIBLE else View.GONE
            if (voltageLimited) {
                binding!!.xrsVoltage.setStartingMinMax(settingVo.voltageMin, settingVo.voltageMax)
            } else {
                binding!!.xrsVoltage.setStartingMinMax(3000, 4500)
            }
            val temperatureLimited = settingVo.temperatureLimited
            binding!!.cbTemperatureUnlimited.isChecked = !temperatureLimited
            binding!!.layoutTemperature.visibility = if (temperatureLimited) View.VISIBLE else View.GONE
            if (temperatureLimited) {
                binding!!.xrsTemperature.setStartingMinMax(settingVo.temperatureMin, settingVo.temperatureMax)
            } else {
                binding!!.xrsTemperature.setStartingMinMax(0, 45)
            }
            if (settingVo.matchType == 1) {
                binding!!.rbMatchAny.isChecked = true
            } else {
                binding!!.rbMatchAll.isChecked = true
            }
        } else {
            // 新建任务的默认值（XML 的 android:checked 对 SmoothCheckBox 不一定生效）
            binding!!.cbBatteryCharging.isChecked = true
            binding!!.cbPluggedUnlimited.isChecked = true
            binding!!.cbVoltageUnlimited.isChecked = true
            binding!!.cbTemperatureUnlimited.isChecked = true
            binding!!.cbHealthUnlimited.isChecked = true
            binding!!.rbMatchAll.isChecked = true
            binding!!.xrsVoltage.setStartingMinMax(3000, 4500)
            binding!!.xrsTemperature.setStartingMinMax(0, 45)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun initListeners() {
        binding!!.btnDel.setOnClickListener(this)
        binding!!.btnSave.setOnClickListener(this)
        val listener = SmoothCheckBox.OnCheckedChangeListener { _, _ -> checkSetting(true) }
        listOf(
            binding!!.cbBatteryCharging, binding!!.cbBatteryDischarging,
            binding!!.cbBatteryNotCharging, binding!!.cbBatteryFull, binding!!.cbBatteryUnknown,
            binding!!.cbPluggedAc, binding!!.cbPluggedUsb,
            binding!!.cbPluggedWireless, binding!!.cbPluggedUnlimited,
            binding!!.cbHealthUnlimited, binding!!.cbHealthGood, binding!!.cbHealthOverheat,
            binding!!.cbHealthCold, binding!!.cbHealthDead, binding!!.cbHealthOverVoltage,
            binding!!.cbHealthUnspecifiedFailure, binding!!.cbHealthUnknown
        ).forEach { cb -> cb.setOnCheckedChangeListener(listener) }
        binding!!.cbVoltageUnlimited.setOnCheckedChangeListener { _, isChecked ->
            binding!!.layoutVoltage.visibility = if (isChecked) View.GONE else View.VISIBLE
            checkSetting(true)
        }
        binding!!.cbTemperatureUnlimited.setOnCheckedChangeListener { _, isChecked ->
            binding!!.layoutTemperature.visibility = if (isChecked) View.GONE else View.VISIBLE
            checkSetting(true)
        }
        val rangeSliderListener = object : XRangeSlider.OnRangeSliderListener {
            override fun onMinChanged(slider: XRangeSlider, min: Int) {
                checkSetting(true)
            }

            override fun onMaxChanged(slider: XRangeSlider, max: Int) {
                checkSetting(true)
            }
        }
        binding!!.xrsVoltage.setOnRangeSliderListener(rangeSliderListener)
        binding!!.xrsTemperature.setOnRangeSliderListener(rangeSliderListener)
        binding!!.rgMatchType.setOnCheckedChangeListener { _, _ -> checkSetting(true) }
    }

    @SingleClick
    override fun onClick(v: View) {
        try {
            when (v.id) {
                R.id.btn_del -> {
                    popToBack()
                    return
                }
                R.id.btn_save -> {
                    val settingVo = checkSetting()
                    val intent = Intent()
                    intent.putExtra(KEY_BACK_DESCRIPTION_CONDITION, settingVo.description)
                    intent.putExtra(KEY_BACK_DATA_CONDITION, Gson().toJson(settingVo))
                    setFragmentResult(TASK_CONDITION_CHARGE, intent)
                    popToBack()
                    return
                }
            }
        } catch (e: Exception) {
            XToastUtils.error(e.message.toString(), 30000)
            e.printStackTrace()
            Log.e(TAG, "onClick error:$e")
        }
    }

    private fun checkSetting(updateView: Boolean = false): ChargeSetting {
        val statusCheckIds = mutableListOf<Int>()
        if (binding!!.cbBatteryCharging.isChecked) statusCheckIds.add(R.id.cb_battery_charging)
        if (binding!!.cbBatteryDischarging.isChecked) statusCheckIds.add(R.id.cb_battery_discharging)
        if (binding!!.cbBatteryNotCharging.isChecked) statusCheckIds.add(R.id.cb_battery_not_charging)
        if (binding!!.cbBatteryFull.isChecked) statusCheckIds.add(R.id.cb_battery_full)
        if (binding!!.cbBatteryUnknown.isChecked) statusCheckIds.add(R.id.cb_battery_unknown)

        if (!updateView && statusCheckIds.isEmpty()) {
            throw Exception(getString(R.string.battery_status_required))
        }

        val pluggedCheckIds = mutableListOf<Int>()
        if (binding!!.cbPluggedAc.isChecked) pluggedCheckIds.add(R.id.cb_plugged_ac)
        if (binding!!.cbPluggedUsb.isChecked) pluggedCheckIds.add(R.id.cb_plugged_usb)
        if (binding!!.cbPluggedWireless.isChecked) pluggedCheckIds.add(R.id.cb_plugged_wireless)
        if (binding!!.cbPluggedUnlimited.isChecked) pluggedCheckIds.add(R.id.cb_plugged_unlimited)

        val healthCheckIds = mutableListOf<Int>()
        if (binding!!.cbHealthUnlimited.isChecked) healthCheckIds.add(R.id.cb_health_unlimited)
        if (binding!!.cbHealthGood.isChecked) healthCheckIds.add(R.id.cb_health_good)
        if (binding!!.cbHealthOverheat.isChecked) healthCheckIds.add(R.id.cb_health_overheat)
        if (binding!!.cbHealthCold.isChecked) healthCheckIds.add(R.id.cb_health_cold)
        if (binding!!.cbHealthDead.isChecked) healthCheckIds.add(R.id.cb_health_dead)
        if (binding!!.cbHealthOverVoltage.isChecked) healthCheckIds.add(R.id.cb_health_over_voltage)
        if (binding!!.cbHealthUnspecifiedFailure.isChecked) healthCheckIds.add(R.id.cb_health_unspecified_failure)
        if (binding!!.cbHealthUnknown.isChecked) healthCheckIds.add(R.id.cb_health_unknown)

        var voltageMin = 0
        var voltageMax = 0
        if (!binding!!.cbVoltageUnlimited.isChecked) {
            voltageMin = binding!!.xrsVoltage.selectedMin
            voltageMax = binding!!.xrsVoltage.selectedMax
        }

        val temperatureLimited = !binding!!.cbTemperatureUnlimited.isChecked
        var temperatureMin = 0
        var temperatureMax = 0
        if (temperatureLimited) {
            temperatureMin = binding!!.xrsTemperature.selectedMin
            temperatureMax = binding!!.xrsTemperature.selectedMax
        }

        val matchType = if (binding!!.rbMatchAny.isChecked) 1 else 0

        val settingVo = ChargeSetting(
            statusCheckIds = statusCheckIds,
            pluggedCheckIds = pluggedCheckIds,
            healthCheckIds = healthCheckIds,
            voltageMin = voltageMin,
            voltageMax = voltageMax,
            temperatureLimited = temperatureLimited,
            temperatureMin = temperatureMin,
            temperatureMax = temperatureMax,
            matchType = matchType,
        )
        if (updateView) binding!!.tvDescription.text = settingVo.description
        return settingVo
    }
}
