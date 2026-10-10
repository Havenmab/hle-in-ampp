package dev.havenmab.ampp.hle.feature.region

/**
 * 歌词请求路径判定。
 *
 * 本轮只移植地区缝需要的那一个纯函数（`isAppleLyricsRequestPath`）；它所属的
 * AM++ `metadata/AppleMetadataPolicies.kt`（750 行，含大量 in-app 表面策略与歌词
 * 语言展开）按批次划分：in-app 表面策略 → 第 2 批；歌词语言展开/选择 → 第 3 批。
 * 见 `feature/title/README.md` 与 `feature/lyrics/README.md`。
 */
internal fun isAppleLyricsRequestPath(pathSegments: List<String>): Boolean =
    pathSegments.getOrNull(3) == "songs" &&
        pathSegments.lastOrNull()?.contains("lyrics") == true
