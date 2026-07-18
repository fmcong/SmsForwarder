package cn.ppps.forwarder.database.ext

import androidx.room.TypeConverter
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Sender
import cn.ppps.forwarder.utils.Log

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