package dev.havenmab.ampp.hle.host

/**
 * 编译期常量。AM++ 里对应 `com.juren233.hyperlyricsenhanced.BuildConfig`。
 *
 * `DEBUG` 控制 AM++ 档案里 `BuildConfig.DEBUG` 门控的日志/诊断分支。插件发布版为
 * false；诊断通道另由设置页的「详细诊断」开关（[ProviderLogger.diagnosticEnabled]）打开。
 */
internal object HleBuild {
    const val DEBUG = false
}
