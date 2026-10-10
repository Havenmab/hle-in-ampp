package dev.havenmab.ampp.hle.host

import dev.amenhancer.plugin.api.PluginContext

/**
 * 插件自己的宿主目标描述（形如参考插件 ampp-media-integrations 的宿主目标条目）。
 *
 * 语义约束沿用 AM++ 档案：`resolutionPolicy`（如 `exact-required`）、
 * `allowFirstMatch`、`includeSynthetic`、完整参数类型与返回类型都保留，
 * [HostTargetResolver] 必须按这些约束解析，不得退化成“同名方法取第一个”。
 */
internal data class AppleMusicHookTarget(
    val className: String,
    val methodName: String? = null,
    val parameterCount: Int? = null,
    val parameterTypeNames: List<String?>? = null,
    val returnTypeName: String? = null,
    val isStatic: Boolean? = null,
    val includeSynthetic: Boolean = false,
    val allowFirstMatch: Boolean = false,
    val resolutionPolicy: String? = null,
    val contractId: String? = null,
    val runtimeMemberNames: Map<AppleMusicRuntimeMember, String> = emptyMap(),
    val requiredInvokedMethodDescriptors: List<String> = emptyList(),
    val requiredInvokedMethodNames: List<String> = emptyList(),
    val requiredCallerMethodNames: List<String> = emptyList(),
) {
    /** True when this target describes a class rather than one of its methods. */
    val isClassTarget: Boolean get() = methodName == null

    fun runtimeMemberName(member: AppleMusicRuntimeMember): String =
        checkNotNull(runtimeMemberNames[member]) {
            "Missing runtime member $member for $className#${methodName ?: "<class>"}"
        }

    fun runtimeMemberNameOrNull(member: AppleMusicRuntimeMember): String? =
        runtimeMemberNames[member]

    /** 档案里该目标声明的 RuntimeMember 名，回退到目标点内另一个同族目标（HTTP 两条缝共用）。 */
    fun runtimeMemberName(member: AppleMusicRuntimeMember, fallback: AppleMusicHookTarget?): String =
        runtimeMemberNames[member]
            ?: fallback?.runtimeMemberNames?.get(member)
            ?: error("Missing runtime member $member for $className#${methodName ?: "<class>"}")
}

/** 目标表 `indexed.methodContracts` 里的一条宿主方法契约。 */
internal data class HostMethodContract(
    val id: String,
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returns: String?,
    val isStatic: Boolean,
)

internal class AppleMusicHookProfile(
    val id: String,
    val packageName: String,
    val versionName: String,
    val versionCodes: Set<Long>,
    private val hookTargets: Map<AppleMusicHookPoint, List<AppleMusicHookTarget>>,
    val catalogQueryRenames: Map<String, Map<String, String>> = emptyMap(),
    val methodContracts: Map<String, HostMethodContract> = emptyMap(),
    val strict: Boolean = false,
    val source: String = "",
) {
    fun targets(hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        hookTargets[hookPoint].orEmpty()

    /** AM++ `catalogQueryRenames`：按 owner 类名 + 首选方法名查已核验的重命名。 */
    fun catalogQueryRename(ownerClassName: String, preferredName: String): String? =
        catalogQueryRenames[ownerClassName]?.get(preferredName)

    fun methodContract(id: String): HostMethodContract? = methodContracts[id]

    /**
     * AM++ 的版本匹配语义：strict 时要求 versionName 与 versionCode 同时命中；
     * 否则 versionCode 命中或 versionName 相等即可。
     */
    fun matches(hostVersionName: String?, hostVersionCode: Long): Boolean =
        if (strict) {
            hostVersionName == versionName && hostVersionCode in versionCodes
        } else {
            hostVersionCode in versionCodes || hostVersionName == versionName
        }

    fun displayName(): String = "$versionName(${versionCodes.joinToString("/")})"
}

/**
 * 读取随插件发布的宿主目标表（`assets/applemusic-1606.json`）。
 *
 * 目标表由 AM++ 的 `host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1606.json`
 * 转换而来：保留 `hookTargets`（96 项，含 exact-required 语义）、`indexed`
 * （23 个类、41 个方法契约、字段与字段契约）、`verification` 与
 * `capabilities`；插件自己追加 `region` 段，声明本轮读取的目标点与契约 id。
 */
internal object HostProfileAssets {
    const val DEFAULT_ASSET = "applemusic-1606.json"

    fun load(context: PluginContext): AppleMusicHookProfile {
        val text = context.openAsset(DEFAULT_ASSET).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        return parse(text)
    }

    fun parse(text: String): AppleMusicHookProfile {
        val root = org.json.JSONObject(text)
        val versionName = root.optString("versionName")
        val versionCode = root.optLong("versionCode", 0L)
        val strict = root.optBoolean("strict", false)
        val hookTargetsJson = root.optJSONObject("hookTargets")
            ?: org.json.JSONObject()
        val targets = LinkedHashMap<AppleMusicHookPoint, MutableList<AppleMusicHookTarget>>()
        hookTargetsJson.keys().forEach { key ->
            val point = runCatching { AppleMusicHookPoint.valueOf(key) }.getOrNull()
                ?: return@forEach
            val array = hookTargetsJson.optJSONArray(key) ?: return@forEach
            val list = targets.getOrPut(point) { mutableListOf() }
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                list += parseTarget(entry)
            }
        }
        return AppleMusicHookProfile(
            id = root.optString("id", DEFAULT_ASSET),
            packageName = root.optString("packageName"),
            versionName = versionName,
            versionCodes = setOf(versionCode),
            hookTargets = targets,
            catalogQueryRenames = parseCatalogQueryRenames(root.optJSONObject("catalogQueryRenames")),
            methodContracts = parseMethodContracts(root.optJSONObject("indexed")),
            strict = strict,
            source = root.optString("sourceProfile", ""),
        )
    }

    private fun parseMethodContracts(
        indexed: org.json.JSONObject?,
    ): Map<String, HostMethodContract> {
        val contracts = indexed?.optJSONObject("methodContracts") ?: return emptyMap()
        val result = LinkedHashMap<String, HostMethodContract>()
        contracts.keys().forEach { id ->
            val entry = contracts.optJSONObject(id) ?: return@forEach
            val owner = entry.optString("owner").takeIf { it.isNotEmpty() } ?: return@forEach
            val name = entry.optString("name").takeIf { it.isNotEmpty() } ?: return@forEach
            val parameters = entry.optJSONArray("parameters")?.let { array ->
                (0 until array.length()).map { index -> array.optString(index) }
            }.orEmpty()
            result[id] = HostMethodContract(
                id = id,
                owner = owner,
                name = name,
                parameters = parameters,
                returns = entry.optString("returns").takeIf { it.isNotEmpty() },
                isStatic = entry.optBoolean("static", false),
            )
        }
        return result
    }

    private fun parseCatalogQueryRenames(
        json: org.json.JSONObject?,
    ): Map<String, Map<String, String>> {
        json ?: return emptyMap()
        val result = LinkedHashMap<String, Map<String, String>>()
        json.keys().forEach { owner ->
            val renames = json.optJSONObject(owner) ?: return@forEach
            val entry = LinkedHashMap<String, String>()
            renames.keys().forEach { name -> entry[name] = renames.optString(name) }
            if (entry.isNotEmpty()) result[owner] = entry
        }
        return result
    }

    private fun parseTarget(json: org.json.JSONObject): AppleMusicHookTarget {
        val parameterTypes = json.optJSONArray("parameterTypeNames")?.let { array ->
            (0 until array.length()).map { index ->
                if (array.isNull(index)) null else array.optString(index)
            }
        }
        val members = LinkedHashMap<AppleMusicRuntimeMember, String>()
        json.optJSONObject("runtimeMemberNames")?.let { map ->
            map.keys().forEach { key ->
                val member = runCatching { AppleMusicRuntimeMember.valueOf(key) }.getOrNull()
                    ?: return@forEach
                members[member] = map.optString(key)
            }
        }
        return AppleMusicHookTarget(
            className = json.optString("className"),
            methodName = json.optString("methodName").takeIf { it.isNotEmpty() },
            parameterCount = if (json.isNull("parameterCount")) null else json.optInt("parameterCount"),
            parameterTypeNames = parameterTypes,
            returnTypeName = json.optString("returnTypeName").takeIf { it.isNotEmpty() },
            isStatic = if (json.isNull("isStatic")) null else json.optBoolean("isStatic"),
            includeSynthetic = json.optBoolean("includeSynthetic", false),
            allowFirstMatch = json.optBoolean("allowFirstMatch", false),
            resolutionPolicy = json.optString("resolutionPolicy").takeIf { it.isNotEmpty() },
            contractId = json.optString("contractId").takeIf { it.isNotEmpty() },
            runtimeMemberNames = members,
            requiredInvokedMethodDescriptors =
                json.optJSONArray("requiredInvokedMethodDescriptors").stringList(),
            requiredInvokedMethodNames =
                json.optJSONArray("requiredInvokedMethodNames").stringList(),
            requiredCallerMethodNames =
                json.optJSONArray("requiredCallerMethodNames").stringList(),
        )
    }

    private fun org.json.JSONArray?.stringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { index -> optString(index) }
    }
}
