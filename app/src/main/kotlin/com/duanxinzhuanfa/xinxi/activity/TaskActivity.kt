package com.duanxinzhuanfa.xinxi.activity

import android.os.Bundle
import androidx.viewbinding.ViewBinding
import com.duanxinzhuanfa.xinxi.core.BaseActivity
import com.duanxinzhuanfa.xinxi.fragment.TasksFragment

class TaskActivity : BaseActivity<ViewBinding?>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPage(TasksFragment::class.java)
    }
}