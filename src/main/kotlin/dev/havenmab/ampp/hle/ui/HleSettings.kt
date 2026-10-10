package dev.havenmab.ampp.hle.ui

import dev.havenmab.ampp.hle.feature.region.RegionSelection
import dev.havenmab.ampp.hle.feature.region.RegionTitleRequestPlan
import dev.havenmab.ampp.hle.feature.region.RegionTitleRequestPolicy
import java.io.File
import java.util.Properties

/**
 * 插件设置。全部存于 `PluginContext.getDataDirectory()` 下的一个 properties 文件，
 * 不使用宿主 SharedPreferences（AM++ 用模块 schema + 宿主偏好；插件不允许）。
 *
 * 键名沿用 HLE 的语义命名，便于对照迁移：
 * - `apple_music_content_region`     ← HLE 的地区选择（注册表值见 [RegionSelection]）
 * - `override_account_language`      ← HLE 的歌曲信息替换至设定地区语言
 * - `restore_cjk_original_metadata`  ← HLE 的替换中日韩歌曲信息为原地区原名
 * - `localized_metadata_cache`       ← HLE 的创建检索库以提升替换体验
 * - `verbose_diagnostics`            ← 本轮新增：打开 `[debug]` 诊断通道
 */
internal class HleSettings(private val dataDirectory: File) {
    private val file = File(dataDirectory, FILE_NAME)
    private val values = Properties()

    init {
        runCatching {
            if (file.isFile) file.inputStream().use(values::load)
        }
    }

    var regionSelection: RegionSelection
        get() = RegionSelection.decode(values.getProperty(KEY_REGION))
        set(value) {
            values.setProperty(KEY_REGION, value.storageValue)
        }

    var overrideAccountLanguage: Boolean
        get() = boolean(KEY_OVERRIDE_ACCOUNT_LANGUAGE, false)
        set(value) {
            values.setProperty(KEY_OVERRIDE_ACCOUNT_LANGUAGE, value.toString())
        }

    var restoreCjkOriginalMetadata: Boolean
        get() = boolean(KEY_RESTORE_CJK_ORIGINAL_METADATA, false)
        set(value) {
            values.setProperty(KEY_RESTORE_CJK_ORIGINAL_METADATA, value.toString())
        }

    var localizedMetadataCache: Boolean
        get() = boolean(KEY_LOCALIZED_METADATA_CACHE, true)
        set(value) {
            values.setProperty(KEY_LOCALIZED_METADATA_CACHE, value.toString())
        }

    var verboseDiagnostics: Boolean
        get() = boolean(KEY_VERBOSE_DIAGNOSTICS, false)
        set(value) {
            values.setProperty(KEY_VERBOSE_DIAGNOSTICS, value.toString())
        }

    val plan: RegionTitleRequestPlan
        get() = RegionTitleRequestPolicy.plan(
            region = regionSelection,
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
        )

    @Synchronized
    fun save() {
        runCatching {
            dataDirectory.mkdirs()
            val temp = File(dataDirectory, "$FILE_NAME.tmp")
            temp.outputStream().use { values.store(it, "HLE plugin settings") }
            if (!temp.renameTo(file)) {
                file.outputStream().use { values.store(it, "HLE plugin settings") }
                temp.delete()
            }
        }
    }

    private fun boolean(key: String, fallback: Boolean): Boolean =
        values.getProperty(key)?.trim()?.let { raw ->
            when (raw.lowercase()) {
                "true" -> true
                "false" -> false
                else -> fallback
            }
        } ?: fallback

    companion object {
        const val FILE_NAME = "hle-settings.properties"

        private const val KEY_REGION = "apple_music_content_region"
        private const val KEY_OVERRIDE_ACCOUNT_LANGUAGE = "override_account_language"
        private const val KEY_RESTORE_CJK_ORIGINAL_METADATA = "restore_cjk_original_metadata"
        private const val KEY_LOCALIZED_METADATA_CACHE = "localized_metadata_cache"
        private const val KEY_VERBOSE_DIAGNOSTICS = "verbose_diagnostics"
    }
}
