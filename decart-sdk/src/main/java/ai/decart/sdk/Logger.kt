package ai.decart.sdk

import android.util.Log

enum class LogLevel(val priority: Int) {
    DEBUG(0), INFO(1), WARN(2), ERROR(3)
}

interface Logger {
    fun debug(message: String, data: Map<String, Any?>? = null)
    fun info(message: String, data: Map<String, Any?>? = null)
    fun warn(message: String, data: Map<String, Any?>? = null)
    fun error(message: String, data: Map<String, Any?>? = null)
}

object NoopLogger : Logger {
    override fun debug(message: String, data: Map<String, Any?>?) {}
    override fun info(message: String, data: Map<String, Any?>?) {}
    override fun warn(message: String, data: Map<String, Any?>?) {}
    override fun error(message: String, data: Map<String, Any?>?) {}
}

class AndroidLogger(private val minLevel: LogLevel = LogLevel.WARN) : Logger {
    private val tag = "DecartSDK"

    override fun debug(message: String, data: Map<String, Any?>?) {
        if (minLevel.priority <= LogLevel.DEBUG.priority) Log.d(tag, format(message, data))
    }
    override fun info(message: String, data: Map<String, Any?>?) {
        if (minLevel.priority <= LogLevel.INFO.priority) Log.i(tag, format(message, data))
    }
    override fun warn(message: String, data: Map<String, Any?>?) {
        if (minLevel.priority <= LogLevel.WARN.priority) Log.w(tag, format(message, data))
    }
    override fun error(message: String, data: Map<String, Any?>?) {
        if (minLevel.priority <= LogLevel.ERROR.priority) Log.e(tag, format(message, data))
    }

    private fun format(message: String, data: Map<String, Any?>?): String {
        return if (data != null) "$message $data" else message
    }
}
