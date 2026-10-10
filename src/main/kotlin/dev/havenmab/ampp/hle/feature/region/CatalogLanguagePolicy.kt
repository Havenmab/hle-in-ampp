package dev.havenmab.ampp.hle.feature.region

import java.util.Locale

/**
 * Validation and formatting for the optional language used by Apple's
 * ordinary Catalog requests.
 *
 * 移植自 `core/src/main/kotlin/dev/amenhancer/module/config/CatalogLanguagePolicy.kt`。
 * 唯一改动：`displayName` 不再引用 AM++ 的 `ModuleText` 资源串，改用插件本地文案
 * （[HleText]）；其余逻辑逐行保留。
 *
 * HLE 自己的原地区元数据请求不使用这个值：它们带请求 token，并按探测到的源语言本地化。
 */
internal object CatalogLanguagePolicy {
    /** An empty value deliberately means "leave Apple Music's request language alone". */
    const val DISABLED_TARGET_LANGUAGE = ""

    fun normalize(raw: String?): String {
        val candidate = raw.orEmpty().trim().replace('_', '-')
        if (candidate.isEmpty()) return DISABLED_TARGET_LANGUAGE
        val tag = buildLocale(candidate)?.toLanguageTag().orEmpty()
        return tag.takeUnless {
            it.isBlank() || it.equals("und", ignoreCase = true)
        }.orEmpty()
    }

    fun isConfigured(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun isValid(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun displayName(tag: String?, displayLocale: Locale = Locale.getDefault()): String {
        val normalized = normalize(tag)
        if (normalized.isEmpty()) return HleText.catalogLanguageDefault()
        val locale = Locale.forLanguageTag(normalized)
        val language = locale.getDisplayLanguage(displayLocale).ifBlank { normalized }
        return HleText.catalogLanguageName(language, normalized)
    }

    private fun buildLocale(tag: String): Locale? =
        runCatching { Locale.Builder().setLanguageTag(tag).build() }
            .getOrNull()
            ?: runCatching { Locale.forLanguageTag(tag) }.getOrNull()
}
