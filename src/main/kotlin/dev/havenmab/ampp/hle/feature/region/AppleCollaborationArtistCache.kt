/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleCollaborationArtistCache.kt
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
/** Bounded memoization of a pure text policy; never stores media IDs, aliases or host objects. */
internal class AppleCollaborationArtistCache(
    private val capacity: Int = 256,
    private val classify: (String) -> Boolean,
) {
    private val results = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    fun isCollaboration(credit: String): Boolean {
        val normalized = credit.trim()
        if (normalized.isEmpty()) return false
        // Avoid retaining unusually large server-provided strings.
        if (normalized.length > 512) return classify(normalized)
        synchronized(results) { results[normalized]?.let { return it } }
        val result = classify(normalized)
        synchronized(results) {
            results[normalized] = result
            if (results.size > capacity) {
                val oldest = results.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
        return result
    }
}
