package dev.havenmab.ampp.hle.host

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 一个已按目标表约束验证过的宿主方法。 */
internal class ResolvedTarget(
    val target: AppleMusicHookTarget,
    val clazz: Class<*>,
    val method: Method,
) {
    val displayName: String get() = "${target.className}#${target.methodName}"
}

/**
 * 用插件自带的目标表在宿主 ClassLoader 上解析类与方法。
 *
 * 约束语义与 AM++ 一致：
 * - `resolutionPolicy = exact-required`（本表所有方法目标都是）→ 参数个数、完整参数类型、
 *   返回类型、static 修饰符全部必须核对；缺一项都不算命中；
 * - 多个候选且 `allowFirstMatch = false` → 判为歧义并拒绝（AM++ 的 `ambiguityPolicy =
 *   reject-ambiguous`），绝不“取第一个同名方法”；
 * - `includeSynthetic = false` → 跳过 synthetic/bridge 方法。
 *
 * 解析在 onLoad 阶段完成并缓存；运行时回调里不再扫描 DEX。
 */
internal class HostTargetResolver(
    private val classLoader: ClassLoader,
    private val profile: AppleMusicHookProfile,
) {
    private val classCache = HashMap<String, Class<*>>()
    private val methodCache = HashMap<String, Method>()

    fun targets(point: AppleMusicHookPoint): List<AppleMusicHookTarget> = profile.targets(point)

    fun hasTargets(point: AppleMusicHookPoint): Boolean = targets(point).isNotEmpty()

    /** 解析目标点里所有“方法型”目标（跳过纯类目标）。 */
    fun resolveMethods(point: AppleMusicHookPoint): List<ResolvedTarget> =
        targets(point).filterNot { it.isClassTarget }.map { target ->
            ResolvedTarget(target, loadClass(target.className), resolveMethod(target))
        }

    /** 解析目标点里所有“类型”目标。 */
    fun resolveClasses(point: AppleMusicHookPoint): List<ResolvedTarget> =
        targets(point).filter { it.isClassTarget }.map { target ->
            ResolvedTarget(target, loadClass(target.className), classOnlyMarker(target))
        }

    /** 目标点必须唯一解析到一个方法（installMediaApiLocalization 等单目标缝）。 */
    fun resolveMethod(point: AppleMusicHookPoint): ResolvedTarget {
        val methods = resolveMethods(point)
        check(methods.isNotEmpty()) { "No method target declared for $point" }
        check(methods.size == 1) {
            "Expected exactly one verified target for $point, found ${methods.size}"
        }
        return methods.single()
    }

    /** 目标点必须唯一解析到一个类（MEDIA_API_REPOSITORY_HOLDER_CLASS 等）。 */
    fun resolveClass(point: AppleMusicHookPoint): Class<*> {
        val classes = resolveClasses(point)
        check(classes.isNotEmpty()) { "No class target declared for $point" }
        return classes.first().clazz
    }

    fun loadClass(className: String): Class<*> = classCache.getOrPut(className) {
        Class.forName(className, false, classLoader)
    }

    private fun resolveMethod(target: AppleMusicHookTarget): Method {
        val methodName = requireNotNull(target.methodName) { "Not a method target: ${target.className}" }
        val key = "${target.className}#$methodName"
        methodCache[key]?.let { return it }
        val clazz = loadClass(target.className)
        val candidates = declaredMethodsInHierarchy(clazz)
            .filter { method -> method.name == methodName }
            .filter { method -> target.includeSynthetic || !method.isSynthetic && !method.isBridge }
            .filter { method -> matchesParameterShape(method, target) }
            .filter { method -> matchesReturnType(method, target) }
            .filter { method -> target.isStatic == null || Modifier.isStatic(method.modifiers) == target.isStatic }
            .toList()
        if (candidates.isEmpty()) {
            throw NoSuchMethodException(
                "Target table entry not found: ${target.className}#$methodName" +
                    parameterShapeDescription(target)
            )
        }
        val selected = if (candidates.size == 1) {
            candidates.single()
        } else {
            // Ambiguity is a hard failure unless the profile explicitly allows first-match.
            check(target.allowFirstMatch) {
                "Target table entry is ambiguous (${candidates.size} candidates): " +
                    "${target.className}#$methodName"
            }
            candidates.first()
        }
        selected.isAccessible = true
        methodCache[key] = selected
        return selected
    }

    private fun matchesParameterShape(method: Method, target: AppleMusicHookTarget): Boolean {
        val expectedCount = target.parameterCount
        if (expectedCount != null && method.parameterCount != expectedCount) return false
        val expectedTypes = target.parameterTypeNames ?: return true
        if (expectedTypes.size != method.parameterCount) return false
        return expectedTypes.indices.all { index ->
            val expected = expectedTypes[index] ?: return@all true
            method.parameterTypes[index].name == expected
        }
    }

    private fun matchesReturnType(method: Method, target: AppleMusicHookTarget): Boolean {
        val expected = target.returnTypeName ?: return true
        return method.returnType.name == expected
    }

    private fun declaredMethodsInHierarchy(clazz: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = clazz
        while (current != null) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun parameterShapeDescription(target: AppleMusicHookTarget): String =
        buildString {
            append(" (")
            append(target.parameterTypeNames?.joinToString(",") { it ?: "*" }
                ?: target.parameterCount?.toString() ?: "*")
            append(") -> ")
            append(target.returnTypeName ?: "*")
            if (target.resolutionPolicy != null) append(", policy=${target.resolutionPolicy}")
        }

    private fun classOnlyMarker(target: AppleMusicHookTarget): Method =
        AppleReflection.findMethodOrNull(Any::class.java, "toString")
            ?: error("unreachable")

    companion object {
        /** 从 Bundle 风格的对象里读字符串字段（宿主数据绑定常用）。 */
        fun stringValue(value: Any?): String? = value?.toString()?.takeIf { it.isNotEmpty() }
    }
}
