package dev.havenmab.ampp.hle.feature.region

/**
 * Region replacement selection for Apple Music's online content.
 *
 * 逐行移植 `core/src/main/kotlin/dev/amenhancer/module/config/RegionSelection.kt`：
 * [displayName] 是 HLE 的选项文案，[contentUiLanguageSelection] 是 HLE 的
 * `APPLE_MUSIC_CONTENT_UI_LANGUAGE_*` 值，两处必须一致。
 * [NONE] 保持账号自身的地区与语言；其余值改写目录请求的 storefront/language
 * 以及 content-HTTP 缝。
 *
 * 它与元数据开关（`overrideAccountLanguage` / `restoreCjkOriginalMetadata`）
 * 相互独立：三种控件可任意组合，任何一个投影都不读另一个。
 *
 * 每个 storefront 的 `X-Apple-Store-Front` 数值映射放在解析器
 * （[AppleInternalCatalogResolver.localizedStorefrontHeaderValue]）里，
 * 保证 HTTP 改写只有一个事实来源。
 */
internal enum class RegionSelection(
    val storageValue: String,
    val displayName: String,
    val contentUiLanguageSelection: Int,
    val catalogStorefront: String?,
    val catalogLanguage: String?,
    val cacheNamespace: String,
) {
    NONE(
        storageValue = "none",
        displayName = "不开启",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
        catalogStorefront = null,
        catalogLanguage = null,
        // 沿用已退役的「无地区」档案的命名空间，迁移过来的安装仍能读到自己已有的
        // 持久化原地区元数据缓存库。
        cacheNamespace = "original_hyper_v1",
    ),
    MAINLAND_CHINA(
        storageValue = "mainland_china",
        displayName = "简体中文（中国）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN,
        catalogStorefront = "cn",
        catalogLanguage = "zh-CN",
        cacheNamespace = "cn_v1",
    ),
    ZH_HANS_US(
        storageValue = "zh_hans_us",
        displayName = "简体中文（美国）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US,
        catalogStorefront = "us",
        catalogLanguage = "zh-Hans",
        cacheNamespace = "us_v1",
    ),
    HONG_KONG(
        storageValue = "hong_kong",
        displayName = "繁体中文（香港）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK,
        catalogStorefront = "hk",
        catalogLanguage = "zh-HK",
        cacheNamespace = "hk_v1",
    ),
    TAIWAN(
        storageValue = "taiwan",
        displayName = "繁体中文（台湾）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW,
        catalogStorefront = "tw",
        catalogLanguage = "zh-TW",
        cacheNamespace = "tw_v1",
    ),
    KOREA(
        storageValue = "korea",
        displayName = "韩语（韩国）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR,
        catalogStorefront = "kr",
        catalogLanguage = "ko-KR",
        cacheNamespace = "kr_v1",
    ),
    JAPAN(
        storageValue = "japan",
        displayName = "日语（日本）",
        contentUiLanguageSelection = HleRootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
        catalogStorefront = "jp",
        catalogLanguage = "ja-JP",
        cacheNamespace = "jp_v1",
    ),
    ;

    /** Whether this selection rewrites ordinary Apple Music traffic to [catalogStorefront]. */
    val replacesRegion: Boolean get() = catalogStorefront != null

    companion object {
        /** The retired single picker's storage value that also meant "no region". */
        const val LEGACY_NO_REGION_STORAGE_VALUE = "original_hyper"

        fun decode(raw: String?): RegionSelection = entries.firstOrNull {
            it.storageValue.equals(raw?.trim(), ignoreCase = true)
        } ?: NONE

        /**
         * Maps the retired `title_correction_mode` picker value onto the region
         * control.  It carried both concepts, so only the region half is read here;
         * the title-correction half migrates from `restore_cjk_original_metadata`.
         */
        fun fromLegacyTitleCorrectionMode(raw: String?): RegionSelection {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty() ||
                normalized.equals(LEGACY_NO_REGION_STORAGE_VALUE, ignoreCase = true)
            ) {
                return NONE
            }
            return decode(normalized)
        }

        /** Maps the v11 target-language setting into the region model. */
        fun fromLegacyTargetLanguage(raw: String?): RegionSelection = when (
            CatalogLanguagePolicy.normalize(raw)
        ) {
            "zh-CN" -> MAINLAND_CHINA
            "ja-JP" -> JAPAN
            else -> NONE
        }
    }
}
