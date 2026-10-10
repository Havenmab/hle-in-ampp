package dev.havenmab.ampp.hle.host

import dev.amenhancer.plugin.api.PluginContext

/**
 * 诊断/日志出口。
 *
 * AM++ 的 `ProviderLogger` 走 `ModernXposedRuntime.log`；插件里只允许走
 * `PluginContext.log`，所以这里保留同名 API，换成 SDK 出口。
 * `diagnostic` 对应 AM++ 里 `[debug]` 过滤通道：默认关闭，由设置页的
 * 「详细诊断」开关打开（日志会带插件 ID 前缀，由宿主追加）。
 */
internal object ProviderLogger {
    const val TAG = "[HLE]"

    @Volatile
    private var sink: ((String, Throwable?) -> Unit)? = null

    @Volatile
    var diagnosticEnabled: Boolean = false

    fun attach(context: PluginContext) {
        sink = { message, error -> context.log("$TAG $message", error) }
    }

    fun debug(message: String) = emit(message, null)

    fun info(message: String) = emit(message, null)

    fun error(message: String, throwable: Throwable? = null) = emit(message, throwable)

    /** `[debug]` 过滤通道：只有打开详细诊断时才写出，避免刷日志。 */
    fun diagnostic(message: String) {
        if (!diagnosticEnabled) return
        emit("$DEBUG_PREFIX$message", null)
    }

    private fun emit(message: String, throwable: Throwable?) {
        val current = sink
        if (current != null) {
            runCatching { current(message, throwable) }
        }
    }

    private const val DEBUG_PREFIX = "[debug] "
}
