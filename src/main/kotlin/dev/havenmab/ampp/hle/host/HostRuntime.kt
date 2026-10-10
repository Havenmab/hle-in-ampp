package dev.havenmab.ampp.hle.host

import android.app.Application
import dev.amenhancer.plugin.api.AmppPlugin
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook
import dev.amenhancer.plugin.api.PluginObservation
import dev.amenhancer.plugin.api.PluginObserver
import dev.amenhancer.plugin.api.PluginRegistration
import java.io.File
import java.io.InputStream
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * SDK 回调里可读写的调用快照。
 *
 * AM++ 用的是 libxposed 的 `XposedInterface.Chain`（`chain.args` / `chain.getArg(i)`）；
 * 插件只能拿到 SDK 的 [PluginCall]，所以这里做一层同形适配，让移植过来的业务代码
 * 仍然按 `args` / `arg(i)` / `setArg(i, v)` 书写。
 * `getArguments()` 返回的是运行时持有的数组本身，写回即生效（SDK 文档约定）。
 */
internal class HookCall(private val call: PluginCall) {
    val receiver: Any? get() = call.receiver
    val args: List<Any?> get() = call.arguments.toList()

    /** SDK 持有的可变参数数组本身（`getArguments()` 不是副本）。 */
    val rawArguments: Array<Any?> get() = call.arguments
    val result: Any? get() = call.result
    val throwable: Throwable? get() = call.throwable
    val outcomeChanged: Boolean get() = call.isOutcomeChanged

    fun arg(index: Int): Any? = call.arguments.getOrNull(index)

    fun setArg(index: Int, value: Any?) {
        val arguments = call.arguments
        if (index in arguments.indices) arguments[index] = value
    }

    /** 提交返回值（AM++ 里 `chain.returnResult` 的等价物）。 */
    fun returnResult(value: Any?) = call.returnResult(value)

    fun throwException(error: Throwable) = call.throwException(error)
}

/**
 * 插件与宿主/SDK 之间的唯一适配层。
 *
 * 职责：
 * 1. 持有宿主 ClassLoader 与已解析的目标（[HostTargetResolver]）；
 * 2. 把 AM++ 的 Hook 形态（before/after + 参数改写）映射到 SDK 的
 *    `PluginHooks.hook/observe`，并统一登记，便于 `onClose` 清理；
 * 3. 提供插件 data/cache 目录与素材读取。
 *
 * 业务代码（feature/）只通过这里接触宿主。
 */
internal class HostRuntime(
    private val context: PluginContext,
    val profile: AppleMusicHookProfile,
) {
    val classLoader: ClassLoader = context.getHostClassLoader()
    val application: Application = context.getApplication()
    val targets = HostTargetResolver(classLoader, profile)

    private val registrations = mutableListOf<PluginRegistration>()
    private val lock = Any()

    val dataDirectory: File get() = context.getDataDirectory()
    val cacheDirectory: File get() = context.getCacheDirectory()

    val hostPackageName: String get() = context.getHostPackageName()
    val hostVersionName: String? get() = context.getHostVersionName()
    val hostVersionCode: Long get() = context.getHostVersionCode()

    fun openAsset(path: String): InputStream = context.openAsset(path)

    /** 目标表 `indexed.methodContracts` 里的宿主方法契约（插件自己的 getter 契约来源）。 */
    fun methodContract(id: String): HostMethodContract? = profile.methodContract(id)

    fun onClose(cleanup: Runnable) = context.onClose(cleanup)

    fun claimResource(key: String, exclusive: Boolean): PluginRegistration =
        context.claimResource(key, exclusive)

    fun log(message: String, error: Throwable? = null) {
        if (error == null) context.log(message, null) else context.log(message, error)
    }

    /**
     * 只读观察：AM++ 里 `installHook(..., after = { _, result -> ... })` 的观察形态。
     * 回调异常由 SDK 统一处理，不会中断宿主。
     */
    fun observe(
        executable: Executable,
        before: ((PluginObservation) -> Unit)? = null,
        after: ((PluginObservation) -> Unit)? = null,
    ): PluginRegistration {
        val registration = context.getHooks().observe(
            executable,
            object : PluginObserver {
                override fun before(call: PluginObservation) {
                    before?.invoke(call)
                }

                override fun after(call: PluginObservation) {
                    after?.invoke(call)
                }
            },
        )
        track(registration)
        return registration
    }

    fun observe(method: Method, after: (PluginObservation) -> Unit): PluginRegistration =
        observe(method, null, after)

    /**
     * 可修改注册：`before` 可以改参数或跳过原方法，`after` 可以读/改返回值。
     * 默认非独占：本插件是 AM++ 内置同名功能的移植，重叠时靠宿主冲突提示，不做独占抢占。
     */
    fun hook(
        executable: Executable,
        exclusive: Boolean = false,
        before: ((HookCall) -> Unit)? = null,
        after: ((HookCall) -> Unit)? = null,
    ): PluginRegistration {
        val registration = context.getHooks().hook(
            executable,
            exclusive,
            object : PluginHook {
                override fun before(call: PluginCall) {
                    before?.invoke(HookCall(call))
                }

                override fun after(call: PluginCall) {
                    after?.invoke(HookCall(call))
                }
            },
        )
        track(registration)
        return registration
    }

    /**
     * 参数改写：等价于 AM++ 的 `installArgumentRewriteHook(method) { chain -> newArgs }`。
     * 返回 null 表示不改写。
     */
    fun hookArgumentRewrite(
        method: Method,
        exclusive: Boolean = false,
        rewrite: (HookCall) -> List<Any?>?,
    ): PluginRegistration = hook(
        method,
        exclusive,
        before = { call ->
            val rewritten = rewrite(call) ?: return@hook
            val live = call.rawArguments
            if (live.size != rewritten.size) {
                ProviderLogger.error(
                    "Argument rewrite changed arity for ${method.declaringClass.name}#${method.name}: " +
                        "expected ${live.size}, got ${rewritten.size}; rewrite dropped",
                )
                return@hook
            }
            for (index in live.indices) {
                live[index] = rewritten[index]
            }
        },
    )

    /** 结果替换：AM++ 里 `after = { _, result -> newResult }` 的等价物。 */
    fun hookResultRewrite(
        method: Method,
        exclusive: Boolean = false,
        rewrite: (HookCall) -> Any?,
    ): PluginRegistration = hook(
        method,
        exclusive,
        after = { call ->
            if (call.throwable != null) return@hook
            call.returnResult(rewrite(call))
        },
    )

    /** 关闭所有本插件登记的 Hook（逻辑解绑；物理框架注册由宿主管理）。 */
    fun closeAllRegistrations() {
        val snapshot = synchronized(lock) {
            val copy = registrations.toList()
            registrations.clear()
            copy
        }
        snapshot.asReversed().forEach { registration ->
            runCatching { registration.close() }
                .onFailure { ProviderLogger.error("Hook 解绑失败", it) }
        }
    }

    private fun track(registration: PluginRegistration) {
        synchronized(lock) { registrations += registration }
    }
}
