package com.duanxinzhuanfa.xinxi.database.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.sqlite.db.SimpleSQLiteQuery
import com.duanxinzhuanfa.xinxi.database.dao.MsgDao
import com.duanxinzhuanfa.xinxi.database.entity.MsgAndLogs
import com.duanxinzhuanfa.xinxi.database.ext.ioThread
import com.duanxinzhuanfa.xinxi.utils.Log
import com.xuexiang.xutil.data.DateUtils
import kotlinx.coroutines.flow.Flow

class MsgViewModel(private val dao: MsgDao) : ViewModel() {
    private var type: String = "sms"
    private var filter: MutableMap<String, Any> = mutableMapOf()

    fun setType(type: String): MsgViewModel {
        this.type = type
        return this
    }

    fun setFilter(filter: MutableMap<String, Any>): MsgViewModel {
        this.filter = filter
        return this
    }

    val allMsg: Flow<PagingData<MsgAndLogs>> = Pager(
        config = PagingConfig(
            pageSize = 10,
            enablePlaceholders = false,
            initialLoadSize = 10
        )
    ) {
        if (filter.isEmpty()) {
            dao.pagingSource(type)
        } else {
            val (cond, condArgs) = getOtherCondition()
            val sql = "SELECT * FROM Msg WHERE type = ? $cond ORDER BY id DESC"
            val query = SimpleSQLiteQuery(sql, arrayOf(type, *condArgs))
            dao.pagingSource(query)
        }

    }.flow.cachedIn(viewModelScope)

    fun delete(id: Long) = ioThread {
        dao.delete(id)
    }

    fun deleteAll() = ioThread {
        val sql: String
        val args: Array<Any>
        if (filter.isEmpty()) {
            sql = "DELETE FROM Msg WHERE type = ?"
            args = arrayOf(type)
        } else {
            val (cond, condArgs) = getOtherCondition()
            sql = "DELETE FROM Msg WHERE type = ? $cond"
            args = arrayOf(type, *condArgs)
        }

        Log.d("MsgViewModel", "sql: $sql")
        val query = SimpleSQLiteQuery(sql, args)
        dao.deleteAll(query)
    }

    private fun getOtherCondition(): Pair<String, Array<Any>> {
        val conditions = StringBuilder()
        val args = mutableListOf<Any>()
        filter["from"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
            conditions.append(" AND `from` LIKE ?")
            args.add("%$it%")
        }
        filter["content"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
            conditions.append(" AND content LIKE ?")
            args.add("%$it%")
        }
        filter["title"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
            conditions.append(" AND sim_info LIKE ?")
            args.add("%$it%")
        }
        filter["start_time"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
            val date = DateUtils.string2Date(it, DateUtils.yyyyMMddHHmmss.get())
            conditions.append(" AND time >= ?")
            args.add(date.time)
        }
        filter["end_time"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
            val date = DateUtils.string2Date(it, DateUtils.yyyyMMddHHmmss.get())
            conditions.append(" AND time <= ?")
            args.add(date.time)
        }
        if (filter["sim_slot"] is Int && filter["sim_slot"] != -1) {
            conditions.append(" AND sim_slot = ?")
            args.add(filter["sim_slot"] as Int)
        }
        val callTypeFilter = filter["call_type"] as? MutableList<*>
        if (!callTypeFilter.isNullOrEmpty()) {
            val placeholders = callTypeFilter.joinToString(",") { "?" }
            conditions.append(" AND call_type IN ($placeholders)")
            args.addAll(callTypeFilter.map { it.toString().toIntOrNull() ?: 0 })
        }
        val forwardStatusFilter = filter["forward_status"] as? MutableList<*>
        if (!forwardStatusFilter.isNullOrEmpty()) {
            val placeholders = forwardStatusFilter.joinToString(",") { "?" }
            conditions.append(" AND id in (SELECT DISTINCT msg_id FROM Logs WHERE type = ? and forward_status IN ($placeholders))")
            args.add(type)
            args.addAll(forwardStatusFilter.map { it.toString().toIntOrNull() ?: 0 })
        }
        return conditions.toString() to args.toTypedArray()
    }

}