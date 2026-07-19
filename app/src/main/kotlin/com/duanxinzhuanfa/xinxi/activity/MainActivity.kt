package com.duanxinzhuanfa.xinxi.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayout
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.duanxinzhuanfa.xinxi.App
import com.duanxinzhuanfa.xinxi.R
import com.duanxinzhuanfa.xinxi.adapter.menu.DrawerAdapter
import com.duanxinzhuanfa.xinxi.adapter.menu.DrawerItem
import com.duanxinzhuanfa.xinxi.adapter.menu.SimpleItem
import com.duanxinzhuanfa.xinxi.adapter.menu.SpaceItem
import com.duanxinzhuanfa.xinxi.core.BaseActivity
import com.duanxinzhuanfa.xinxi.core.webview.AgentWebActivity
import com.duanxinzhuanfa.xinxi.databinding.ActivityMainBinding
import com.duanxinzhuanfa.xinxi.fragment.AboutFragment
import com.duanxinzhuanfa.xinxi.fragment.AppListFragment
import com.duanxinzhuanfa.xinxi.fragment.ClientFragment
import com.duanxinzhuanfa.xinxi.fragment.FrpcFragment
import com.duanxinzhuanfa.xinxi.fragment.LogsFragment
import com.duanxinzhuanfa.xinxi.fragment.RulesFragment
import com.duanxinzhuanfa.xinxi.fragment.SendersFragment
import com.duanxinzhuanfa.xinxi.fragment.ServerFragment
import com.duanxinzhuanfa.xinxi.fragment.SettingsFragment
import com.duanxinzhuanfa.xinxi.fragment.TasksFragment
import com.duanxinzhuanfa.xinxi.service.ForegroundService
import com.duanxinzhuanfa.xinxi.utils.ACTION_START
import com.duanxinzhuanfa.xinxi.utils.CommonUtils.Companion.restartApplication
import com.duanxinzhuanfa.xinxi.utils.EVENT_LOAD_APP_LIST
import com.duanxinzhuanfa.xinxi.utils.FRPC_LIB_VERSION
import com.duanxinzhuanfa.xinxi.utils.KeepAliveUtils
import com.duanxinzhuanfa.xinxi.utils.Log
import com.duanxinzhuanfa.xinxi.utils.SettingUtils
import com.duanxinzhuanfa.xinxi.utils.XToastUtils
import com.duanxinzhuanfa.xinxi.utils.sdkinit.XUpdateInit
import com.duanxinzhuanfa.xinxi.widget.GuideTipsDialog.Companion.showTips
import com.duanxinzhuanfa.xinxi.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xui.XUI.getContext
import com.xuexiang.xui.utils.ResUtils
import com.xuexiang.xui.utils.ThemeUtils
import com.xuexiang.xui.utils.ViewUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.net.NetworkUtils
import com.yarolegovich.slidingrootnav.SlideGravity
import com.yarolegovich.slidingrootnav.SlidingRootNav
import com.yarolegovich.slidingrootnav.SlidingRootNavBuilder
import com.yarolegovich.slidingrootnav.callback.DragStateListener
import java.io.File

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>(), DrawerAdapter.OnItemSelectedListener {

    private val TAG: String = MainActivity::class.java.simpleName
    private val POS_LOG = 0
    private val POS_RULE = 1
    private val POS_SENDER = 2
    private val POS_SETTING = 3
    private val POS_TASK = 5 //4为空行
    private val POS_SERVER = 6
    private val POS_CLIENT = 7
    private val POS_FRPC = 8
    private val POS_APPS = 9
    private val POS_HELP = 11 //10为空行
    private val POS_ABOUT = 12
    private var needToAppListFragment = false

    private lateinit var mTabLayout: TabLayout
    private lateinit var mSlidingRootNav: SlidingRootNav
    private lateinit var mLLMenu: LinearLayout
    private lateinit var mMenuTitles: Array<String>
    private lateinit var mMenuIcons: Array<Drawable>
    private lateinit var mAdapter: DrawerAdapter

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initData()
        initViews()
        initSlidingMenu(savedInstanceState)

        //不在最近任务列表中显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SettingUtils.enableExcludeFromRecents) {
            val am = App.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.let {
                val tasks = it.appTasks
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].setExcludeFromRecents(true)
                }
            }
        }

        //检查通知权限是否获取
        XXPermissions.with(this)
            .permission(PermissionLists.getNotificationServicePermission())
            .permission(PermissionLists.getPostNotificationsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        XToastUtils.error(R.string.tips_notification)
                        return
                    }
                    //启动前台服务
                    if (!ForegroundService.isRunning) {
                        val serviceIntent = Intent(getTopActivity(), ForegroundService::class.java)
                        serviceIntent.action = ACTION_START
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                }
            })

        //监听已安装App信息列表加载完成事件
        LiveEventBus.get(EVENT_LOAD_APP_LIST, String::class.java).observe(this) {
            if (needToAppListFragment) {
                openNewPage(AppListFragment::class.java)
            }
        }

        //首次启动引导：主动请求短信/电话/通话记录/联系人等核心权限（Android 不允许自动授予，必须弹窗）
        requestCorePermissions()

        //首次启动引导：提示将应用加入电池优化白名单，保证后台持续保活且更省电
        promptBatteryOptimization()
    }

    /**
     * 首次启动主动请求核心危险权限，避免装完手机后功能因权限未授予而静默失效。
     * 仅引导一次，已授予/已拒绝的不会再弹窗。
     */
    private fun requestCorePermissions() {
        if (SettingUtils.requestedCorePermissions) return
        SettingUtils.requestedCorePermissions = true

        XXPermissions.with(this)
            // 核心功能权限
            .permission(PermissionLists.getReceiveSmsPermission())
            .permission(PermissionLists.getReadSmsPermission())
            .permission(PermissionLists.getSendSmsPermission())
            .permission(PermissionLists.getReadPhoneStatePermission())
            .permission(PermissionLists.getReadPhoneNumbersPermission())
            .permission(PermissionLists.getReadCallLogPermission())
            .permission(PermissionLists.getReadContactsPermission())
            // Android 13+ 通知权限（否则弹窗、保活都会受限）
            .permission(PermissionLists.getPostNotificationsPermission())
            // 定位权限（首次一并请求，避免后面开功能再弹）
            .permission(PermissionLists.getAccessCoarseLocationPermission())
            .permission(PermissionLists.getAccessFineLocationPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    if (deniedList.isEmpty()) return
                    // 若用户勾选了"不再询问"，引导去系统设置手动开启
                    if (XXPermissions.isDoNotAskAgainPermissions(getTopActivity(), deniedList)) {
                        XXPermissions.startPermissionActivity(getTopActivity(), deniedList)
                    } else {
                        XToastUtils.error(R.string.tips_core_permissions)
                    }
                }
            })
    }

    /**
     * 首次启动直接将应用跳转到电池优化白名单设置页。
     * 注意：Android 不允许应用自动授予电池优化豁免，必须由用户在系统设置中手动开启，
     * 因此这里直接打开系统设置页（无自定义中间弹窗），由用户一键确认即可。
     */
    private fun promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (SettingUtils.promptedBatteryOptimization) return
        if (KeepAliveUtils.isIgnoreBatteryOptimization(this)) return
        SettingUtils.promptedBatteryOptimization = true
        KeepAliveUtils.ignoreBatteryOptimization(this)
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_logs), R.drawable.selector_icon_tabbar_logs)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_rules), R.drawable.selector_icon_tabbar_rules)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_senders), R.drawable.selector_icon_tabbar_senders)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_settings), R.drawable.selector_icon_tabbar_settings)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        switchPage(LogsFragment::class.java)
        mTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                needToAppListFragment = false
                mAdapter.setSelected(tab.position)
                when (tab.position) {
                    POS_LOG -> switchPage(LogsFragment::class.java)
                    POS_RULE -> switchPage(RulesFragment::class.java)
                    POS_SENDER -> switchPage(SendersFragment::class.java)
                    POS_SETTING -> switchPage(SettingsFragment::class.java)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initData() {
        mMenuTitles = ResUtils.getStringArray(this, R.array.menu_titles)
        mMenuIcons = ResUtils.getDrawableArray(this, R.array.menu_icons)

        //仅当开启自动检查且有网络时自动检查更新/获取提示
        if (SettingUtils.autoCheckUpdate && NetworkUtils.isHaveInternet()) {
            showTips(this)
            XUpdateInit.checkUpdate(this, false, SettingUtils.joinPreviewProgram)
        }
    }

    //按返回键不退出回到桌面
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    fun openMenu() {
        mSlidingRootNav.openMenu()
    }

    fun closeMenu() {
        mSlidingRootNav.closeMenu()
    }

    fun isMenuOpen(): Boolean {
        return mSlidingRootNav.isMenuOpened
    }

    private fun initSlidingMenu(savedInstanceState: Bundle?) {
        mSlidingRootNav = SlidingRootNavBuilder(this).withGravity(if (ResUtils.isRtl(this)) SlideGravity.RIGHT else SlideGravity.LEFT).withMenuOpened(false).withContentClickableWhenMenuOpened(false).withSavedState(savedInstanceState).withMenuLayout(R.layout.menu_left_drawer).inject()
        mLLMenu = mSlidingRootNav.layout.findViewById(R.id.ll_menu)
        ViewUtils.setVisibility(mLLMenu, false)
        mAdapter = DrawerAdapter(
            mutableListOf(
                createItemFor(POS_LOG).setChecked(true),
                createItemFor(POS_RULE),
                createItemFor(POS_SENDER),
                createItemFor(POS_SETTING),
                SpaceItem(15),
                createItemFor(POS_TASK),
                createItemFor(POS_SERVER),
                createItemFor(POS_CLIENT),
                createItemFor(POS_FRPC),
                createItemFor(POS_APPS),
                SpaceItem(15),
                createItemFor(POS_HELP),
                createItemFor(POS_ABOUT),
            )
        )
        mAdapter.setListener(this)
        val list: RecyclerView = findViewById(R.id.list)
        list.isNestedScrollingEnabled = false
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = mAdapter
        mAdapter.setSelected(POS_LOG)
        mSlidingRootNav.isMenuLocked = false
        mSlidingRootNav.layout.addDragStateListener(object : DragStateListener {
            override fun onDragStart() {
                ViewUtils.setVisibility(mLLMenu, true)
            }

            override fun onDragEnd(isMenuOpened: Boolean) {
                ViewUtils.setVisibility(mLLMenu, isMenuOpened)
            }
        })
    }

    override fun onItemSelected(position: Int) {
        needToAppListFragment = false
        when (position) {
            POS_LOG, POS_RULE, POS_SENDER, POS_SETTING -> {
                val tab = mTabLayout.getTabAt(position)
                tab?.select()
                mSlidingRootNav.closeMenu()
            }

            POS_TASK -> openNewPage(TasksFragment::class.java)
            POS_SERVER -> openNewPage(ServerFragment::class.java)
            POS_CLIENT -> openNewPage(ClientFragment::class.java)
            POS_FRPC -> {
                if (App.FrpclibInited) {
                    openNewPage(FrpcFragment::class.java)
                    return
                }

                MaterialDialog.Builder(this)
                    .iconRes(R.drawable.ic_menu_frpc)
                    .title(R.string.menu_frpc)
                    .content(R.string.frpclib_load_failed)
                    .positiveText(R.string.confirm)
                    .show()
            }

            POS_APPS -> {
                //检查读取应用列表权限是否获取
                XXPermissions.with(this)
                    .permission(PermissionLists.getGetInstalledAppsPermission())
                    .request(object : OnPermissionCallback {
                        override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                            val allGranted = deniedList.isEmpty()
                            if (!allGranted) {
                                // 判断请求失败的权限是否被用户勾选了不再询问的选项
                                val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(getTopActivity(), deniedList)
                                if (doNotAskAgain) {
                                    XXPermissions.startPermissionActivity(getContext(), deniedList)
                                }
                                // 处理权限请求失败的逻辑
                                XToastUtils.error(R.string.tips_get_installed_apps)
                                return
                            }
                            // 处理权限请求成功的逻辑
                            if (App.UserAppList.isEmpty() && App.SystemAppList.isEmpty()) {
                                XToastUtils.info(getString(R.string.loading_app_list))
                                val request = OneTimeWorkRequestBuilder<LoadAppListWorker>().build()
                                WorkManager.getInstance(getContext()).enqueue(request)
                                needToAppListFragment = true
                                return
                            }
                            openNewPage(AppListFragment::class.java)
                        }
                    })
            }

            POS_HELP -> AgentWebActivity.goWeb(this, getString(R.string.url_help))
            POS_ABOUT -> openNewPage(AboutFragment::class.java)
        }
    }

    private fun createItemFor(position: Int): DrawerItem<*> {
        return SimpleItem(mMenuIcons[position], mMenuTitles[position])
            .withIconTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withTextTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withSelectedIconTint(ThemeUtils.getMainThemeColor(this))
            .withSelectedTextTint(ThemeUtils.getMainThemeColor(this))
    }

    //查看FrpcLib版本（已内置在APK中）
    private fun downloadFrpcLib() {
        val version = try {
            frpclib.Frpclib.getVersion()
        } catch (e: Throwable) {
            "unknown"
        }
        MaterialDialog.Builder(this)
            .iconRes(R.drawable.ic_menu_frpc)
            .title(R.string.menu_frpc)
            .content(getString(R.string.frpclib_builtin_tips, FRPC_LIB_VERSION, version))
            .positiveText(R.string.confirm)
            .show()
    }

}
