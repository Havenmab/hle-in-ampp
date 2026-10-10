/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleOriginalCacheValidation.kt
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
internal data class OriginalCacheValidation(
    val hit: AppleOriginalMetadataCache.CacheHit?,
    val rejected: List<AppleOriginalMetadataCache.CacheHit>,
)

/** Only examines the requested identity/language keys, never unrelated regional entries. */
internal fun validateOriginalCacheEntries(
    keys: Collection<String>,
    aliases: Map<String, AppleInternalCatalogResolver.Alias>,
    accept: (AppleInternalCatalogResolver.Alias) -> Boolean,
): OriginalCacheValidation {
    var hit: AppleOriginalMetadataCache.CacheHit? = null
    val rejected = mutableListOf<AppleOriginalMetadataCache.CacheHit>()
    keys.forEach { key ->
        val stored = aliases[key] ?: return@forEach
        val canonical = AppleInternalCatalogResolver.canonicalCachedOriginalAlias(stored)
            ?.takeIf(accept)
        if (canonical == null) {
            rejected += AppleOriginalMetadataCache.CacheHit(key, stored)
        } else if (hit == null) {
            hit = AppleOriginalMetadataCache.CacheHit(key, canonical)
        }
    }
    return OriginalCacheValidation(hit, rejected)
}

/** A delayed rejection must not erase a replacement published after the read. */
internal fun removeOriginalAliasIfUnchanged(
    aliases: MutableMap<String, AppleInternalCatalogResolver.Alias>,
    rejected: AppleOriginalMetadataCache.CacheHit,
): Boolean {
    if (aliases[rejected.key] != rejected.alias) return false
    aliases.remove(rejected.key)
    return true
}
