/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/common/lyric/AppleOriginalMetadataPolicy.kt
 * 机械改写：包名统一到 dev.havenmab.ampp.hle.feature.region；BuildConfig.DEBUG→HleBuild.DEBUG；
 * RootConstants→HleRootConstants；AM++ 内部类型→插件 host/ 适配层。
 */
package dev.havenmab.ampp.hle.feature.region

import dev.havenmab.ampp.hle.host.AppleMusicHookPoint
import dev.havenmab.ampp.hle.host.AppleMusicRuntimeMember
import dev.havenmab.ampp.hle.host.AppleMusicHookTarget
import dev.havenmab.ampp.hle.host.HostRuntime
import dev.havenmab.ampp.hle.host.HostMethodContract
import dev.havenmab.ampp.hle.host.ProviderLogger
import dev.havenmab.ampp.hle.host.AppleReflection
import dev.havenmab.ampp.hle.host.HleBuild
import dev.havenmab.ampp.hle.host.ResolvedTarget
object AppleOriginalMetadataPolicy {
    fun shouldExposeLocalizedMetadata(
        restoreOriginalEnabled: Boolean,
        originalResolved: Boolean,
        hasOriginalMetadata: Boolean,
    ): Boolean = !restoreOriginalEnabled || hasOriginalMetadata || originalResolved

    fun shouldResolveCjkOriginalMetadata(
        mediaId: String?,
        title: String?,
        artist: String?,
        genre: String?,
    ): Boolean {
        val normalizedId = mediaId?.trim().orEmpty()
        val normalizedTitle = title?.trim().orEmpty()
        val normalizedArtist = artist?.trim().orEmpty()
        val hasCjkContext = isCjkGenre(genre) ||
            normalizedTitle.any(::isCjkCharacter) ||
            normalizedArtist.any(::isCjkCharacter)
        return normalizedId.isNotEmpty() &&
            normalizedId.all(Char::isDigit) &&
            normalizedTitle.isNotEmpty() &&
            hasCjkContext && (
                normalizedTitle.none(::isCjkCharacter) ||
                    normalizedArtist.isNotEmpty() && normalizedArtist.none(::isCjkCharacter)
            )
    }

    fun shouldProbeCjkOriginalMetadata(
        mediaId: String?,
        title: String?,
        artist: String?,
        genre: String?,
    ): Boolean {
        val normalizedId = mediaId?.trim().orEmpty()
        val normalizedTitle = title?.trim().orEmpty()
        return normalizedId.isNotEmpty() &&
            normalizedId.all(Char::isDigit) &&
            normalizedTitle.isNotEmpty()
    }

    fun isCjkGenre(genre: String?): Boolean {
        val normalized = genre.orEmpty().trim().lowercase()
        return CJK_GENRE_MARKERS.any(normalized::contains)
    }

    private fun isCjkCharacter(character: Char): Boolean = character.code in 0x3040..0x30ff ||
        character.code in 0x3400..0x4dbf ||
        character.code in 0x4e00..0x9fff ||
        character.code in 0xac00..0xd7af

    private val CJK_GENRE_MARKERS = listOf(
        "j-pop",
        "japanese",
        "k-pop",
        "korean",
        "mandopop",
        "cantopop",
        "chinese",
        "hong kong",
        "国语流行",
        "國語流行",
        "华语流行",
        "華語流行",
        "中文流行",
        "粤语流行",
        "粵語流行",
        "日本流行",
        "日语流行",
        "日語流行",
        "韩国流行",
        "韓國流行",
        "韩语流行",
        "韓語流行",
    )
}
