package cn.ppps.forwarder.utils

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

        //状态上报防抖调度：任何设置写入后 10s 合并一次推送检查（engine 侧还有 2s 二次防抖 + 哈希比对）
        //engine 拉取应用（restoreSettings/importPreference）期间的写入会被 isPushSuppressed 过滤，防止拉取→推送回环
        private val pushHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val pushRunnable = Runnable { CloudSyncEngine.pushSoon() }

        fun schedulePush() {
            if (CloudSyncEngine.isPushSuppressed()) return
            pushHandler.removeCallbacks(pushRunnable)
            pushHandler.postDelayed(pushRunnable, 10_000)
        }

        fun init(context: Context) {
            preference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val directBootContext: Context = context.createDeviceProtectedStorageContext()
                directBootContext.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            }
        }

        //判断key是否已存储，用于区分"用户显式设置过"和"沿用默认值"
        fun contains(key: String): Boolean = preference.contains(key)

        //删除全部数据
        fun clearPreference() = preference.edit().clear().apply()

        //根据key删除存储数据
        fun clearPreference(key: String) = preference.edit().remove(key).commit()

        //导出全部数据
        fun exportPreference(): String {
            return serialize(preference.all)
        }

        //导入全部数据
        fun importPreference(data: String) {
            val map = deSerialization<Map<String, Any>>(data)
            val editor = preference.edit()
            for ((key, value) in map) {
                when (value) {
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> editor.putString(key, serialize(value))
                }
            }
            editor.apply()
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
        putPreference(name, value)
        //任何设置写入 → 防抖调度一次状态上报（10s 合并高频写，如电池/定位缓存）
        schedulePush()
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
     * 类型容错读取：云端同步/后台表单/离线导入三条写入路径都可能产生类型漂移
     * （如 Long 型键被写成 Int/String），读取端对 ClassCastException 降级兜底，避免崩溃。
     */
    private fun getPreference(name: String, default: T): T = with(preference) {
        val res: Any = when (default) {
            is Long -> try {
                getLong(name, default)
            } catch (e: ClassCastException) {
                try {
                    (getString(name, null) ?: "").toDoubleOrNull()?.toLong() ?: default
                } catch (e: Exception) {
                    default
                }
            }
            is String -> try {
                getString(name, default) ?: default
            } catch (e: ClassCastException) {
                default
            }
            is Int -> try {
                getInt(name, default)
            } catch (e: ClassCastException) {
                try {
                    (getString(name, null) ?: "").toDoubleOrNull()?.toInt() ?: default
                } catch (e: Exception) {
                    default
                }
            }
            is Boolean -> try {
                getBoolean(name, default)
            } catch (e: ClassCastException) {
                try {
                    getString(name, null) == "true"
                } catch (e: Exception) {
                    default
                }
            }
            is Float -> try {
                getFloat(name, default)
            } catch (e: ClassCastException) {
                try {
                    (getString(name, null) ?: "").toFloatOrNull() ?: default
                } catch (e: Exception) {
                    default
                }
            }
            //else -> throw IllegalArgumentException("This type can be get from Preferences")
            else -> deSerialization(getString(name, serialize(default)).toString())
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