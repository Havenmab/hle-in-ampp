/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMediaApiAttributeSnapshots.kt
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
import java.util.IdentityHashMap
internal object AppleMediaApiAttributeSnapshots {
    private const val MAX_SNAPSHOTS = 8_192
    private val snapshots = IdentityHashMap<Any, Snapshot>()

    fun remember(
        attributes: Any,
        name: String?,
        artistName: String?,
        albumName: String?,
    ): Snapshot = synchronized(snapshots) {
        snapshots[attributes] ?: Snapshot(
            name = name,
            artistName = artistName,
            albumName = albumName,
        ).also { snapshot ->
            if (snapshots.size >= MAX_SNAPSHOTS) snapshots.clear()
            snapshots[attributes] = snapshot
        }
    }

    fun get(attributes: Any): Snapshot? = synchronized(snapshots) {
        snapshots[attributes]
    }

    data class Snapshot(
        val name: String?,
        val artistName: String?,
        val albumName: String?,
    )
}
