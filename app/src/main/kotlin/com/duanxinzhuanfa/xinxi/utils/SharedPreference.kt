package com.duanxinzhuanfa.xinxi.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@Suppress("unused", "UNCHECKED_CAST")
class SharedPreference<T>(private val name: String, private val default: T) : ReadWriteProperty<Any?, T> {

    companion object {
        lateinit var preference: SharedPreferences

        fun init(context: Context) {
            preference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val directBootContext: Context = context.createDeviceProtectedStorageContext()
                directBootContext.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            }
        }

        //删除全部数据
        fun clearPreference() = preference.edit().clear().apply()

        //根据key删除存储数据
        fun clearPreference(key: String) = preference.edit().remove(key).commit()

        //导出全部数据
        fun exportPreference(): String {
            return serialize(preference.all)
        }

        // 旧 fork 源残留 key 前缀，导入时自动跳过以保持 SP 清洁
        private val OLD_FORK_KEY_PREFIXES = listOf(
            "com.idormy.sms.forwarder",
            "cn.ppps.forwarder.widget"
        )

        /**
         * 清理旧 fork 源的残留 SharedPreferences key。
         * 在 App 初始化后调用，一次性清除无用的旧数据。
         */
        fun cleanOldForkKeys() {
            val editor = preference.edit()
            var cleaned = 0
            for (key in preference.all.keys) {
                if (OLD_FORK_KEY_PREFIXES.any { key.startsWith(it) }) {
                    editor.remove(key)
                    cleaned++
                }
            }
            if (cleaned > 0) {
                editor.apply()
                Log.d("SharedPreference", "Cleaned $cleaned old fork source keys")
            }
        }

        //导入全部数据
        fun importPreference(data: String) {
            try {
                val map = deSerialization<Map<String, Any>>(data)
                val editor = preference.edit()
                var skipped = 0
                for ((key, value) in map) {
                    // 跳过旧 fork 源的 key
                    if (OLD_FORK_KEY_PREFIXES.any { key.startsWith(it) }) {
                        skipped++
                        continue
                    }
                    try {
                        when (value) {
                            is Long -> editor.putLong(key, value)
                            is Int -> editor.putInt(key, value)
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            else -> editor.putString(key, serialize(value))
                        }
                    } catch (e: Exception) {
                        // 单个 key 反序列化失败不阻塞其他 key 的导入
                        Log.w("SharedPreference", "importPreference: skip key '$key', ${e.message}")
                        skipped++
                    }
                }
                editor.apply()
                if (skipped > 0) {
                    Log.d("SharedPreference", "importPreference: skipped $skipped incompatible keys")
                }
            } catch (e: Exception) {
                // 整体导入失败（如整个文件格式损坏）不崩溃
                Log.e("SharedPreference", "importPreference failed: ${e.message}")
            }
        }

        /**
         * 序列化对象
         * @throws IOException
         */
        @Throws(IOException::class)
        private fun <T> serialize(obj: T): String {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(
                byteArrayOutputStream
            )
            objectOutputStream.writeObject(obj)
            var serStr = byteArrayOutputStream.toString("ISO-8859-1")
            serStr = java.net.URLEncoder.encode(serStr, "UTF-8")
            objectOutputStream.close()
            byteArrayOutputStream.close()
            return serStr
        }

        /**
         * 反序列化对象
         * @param str
         * @throws IOException
         * @throws ClassNotFoundException
         */
        @Throws(IOException::class, ClassNotFoundException::class)
        private fun <T> deSerialization(str: String): T {
            val redStr = java.net.URLDecoder.decode(str, "UTF-8")
            val byteArrayInputStream = ByteArrayInputStream(
                redStr.toByteArray(charset("ISO-8859-1"))
            )
            val objectInputStream = ObjectInputStream(
                byteArrayInputStream
            )
            val obj = objectInputStream.readObject() as T
            objectInputStream.close()
            byteArrayInputStream.close()
            return obj
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        return putPreference(name, value)
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return getPreference(name, default)
    }

    /**
     * 查找数据 返回给调用方法一个具体的对象
     * 如果查找不到类型就采用反序列化方法来返回类型
     * default是默认对象 以防止会返回空对象的异常
     * 即如果name没有查找到value 就返回默认的序列化对象，然后经过反序列化返回
     *
     * 安全策略：旧版本数据（如 cn.ppps.forwarder.entity.LocationInfo）在包名变更后
     * 反序列化会抛出 ClassNotFoundException，此处捕获并返回默认值，同时清理旧数据。
     */
    private fun getPreference(name: String, default: T): T = with(preference) {
        val res: Any = when (default) {
            is Long -> getLong(name, default)
            is String -> this.getString(name, default)!!
            is Int -> getInt(name, default)
            is Boolean -> getBoolean(name, default)
            is Float -> getFloat(name, default)
            else -> {
                try {
                    val stored = getString(name, null) ?: return@with default
                    deSerialization<T>(stored) ?: default
                } catch (e: Exception) {
                    // 包名变更后旧序列化数据无法反序列化（ClassNotFoundException），
                    // 返回默认值并清除旧数据，避免反复报错
                    Log.w("SharedPreference", "Failed to deserialize key '$name', clearing old data: ${e.message}")
                    preference.edit().remove(name).apply()
                    default
                }
            }
        }
        return res as T
    }

    private fun putPreference(name: String, value: T) = with(preference.edit()) {
        when (value) {
            is Long -> putLong(name, value)
            is Int -> putInt(name, value)
            is String -> putString(name, value)
            is Boolean -> putBoolean(name, value)
            is Float -> putFloat(name, value)
            //else -> throw IllegalArgumentException("This type can be saved into Preferences")
            else -> putString(name, serialize(value))
        }.apply()
    }
}