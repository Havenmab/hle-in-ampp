/*
 * 第 2 批（in-app 表面桥）会用到的支持类型，本轮只取 [AppleMetadataOverrideStore] 实际引用的成员。
 *
 * 来源：
 *  - `metadata/model/AppleMetadataModels.kt` 的 `AccountMetadata`
 *  - `ConfiguredMetadataRetryPolicy.kt`（24 行，整份）
 *  - `metadata/AppleMetadataResolutionEngine.kt` 的 `normalizedAssociatedArtistIds`
 *
 * 其余 in-app 解析/应用逻辑（解析引擎的 280 行、表面桥的数千行）按批次留在第 2 批，
 * 见 `feature/title/README.md`。
 */
package dev.havenmab.ampp.hle.feature.region

/** 账号侧（未替换）的歌曲元数据快照；用于判断别名是否真的带来了变化。 */
internal data class AccountMetadata(
    val title: String?,
    val artist: String?,
)

/** Small, platform-free policy for retrying a transient empty fixed-region lookup. */
internal object ConfiguredMetadataRetryPolicy {
    const val RETRY_AFTER_MILLIS = 10_000L

    fun shouldSkip(
        lastMissUptimeMillis: Long,
        nowUptimeMillis: Long,
        retryAfterMillis: Long = RETRY_AFTER_MILLIS,
    ): Boolean {
        if (retryAfterMillis <= 0L) return false
        val elapsed = nowUptimeMillis - lastMissUptimeMillis
        // A monotonic clock should not move backwards; if a caller supplies a regressed value,
        // treat the old miss as expired rather than blocking the request indefinitely.
        return elapsed in 0 until retryAfterMillis
    }
}

internal object AppleMetadataResolutionEngine {
    fun normalizedAssociatedArtistIds(artistIds: Collection<String>): Set<String> =
        artistIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
            .toCollection(linkedSetOf())
}
