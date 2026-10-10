package dev.havenmab.ampp.hle.feature.region

/**
 * 插件本地常量：AM++ `com.juren233.hyperlyricsenhanced.common.RootConstants` 的值。
 *
 * 逐值移植。插件不得引用 AM++ 内部类，所以这些常量在插件里重新声明；
 * 数值必须与 HLE 的 `APPLE_MUSIC_CONTENT_UI_LANGUAGE_*` 保持一致，否则
 * 地区选择的持久化值会与 HLE 的旧配置错位。
 */
internal object HleRootConstants {
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE = 0
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN = 1
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US = 2
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK = 3
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW = 4
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR = 5
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP = 6
    const val DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE =
        APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
}

/**
 * 插件本地文案：AM++ `dev.amenhancer.module.i18n.ModuleText` 的两个格式化串。
 * AM++ 的资源字符串不能引用，这里按同样语义重写。
 */
internal object HleText {
    fun catalogLanguageDefault(): String = "跟随 Apple Music 账号"
    fun catalogLanguageName(language: String, tag: String): String = "$language（$tag）"
}
