package com.duanxinzhuanfa.xinxi.database.ext

import androidx.room.TypeConverter
import com.duanxinzhuanfa.xinxi.core.Core
import com.duanxinzhuanfa.xinxi.database.entity.Sender
import com.duanxinzhuanfa.xinxi.utils.Log

class ConvertersSenderList {

    @TypeConverter
    fun stringToObject(value: String): List<Sender> {
        if (value.isBlank()) return emptyList()
        val ids = try {
            value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }
        } catch (e: NumberFormatException) {
            Log.e("ConvertersSenderList", "Invalid sender id list: $value", e)
            return emptyList()
        }
        if (ids.isEmpty()) return emptyList()
        return Core.sender.getByIds(ids, value)
    }

    @TypeConverter
    fun objectToString(list: List<Sender>): String {
        return list.joinToString(",") { it.id.toString() }
    }
}