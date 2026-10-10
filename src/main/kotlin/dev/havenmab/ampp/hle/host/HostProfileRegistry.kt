package dev.havenmab.ampp.hle.host

/**
 * 已加载宿主目标表的进程级引用。
 *
 * 对应 AM++ 的 `dev.amenhancer.host.applemusic.AppleMusicHostProfiles`（它读
 * `:host-applemusic` 的资源）；插件里数据来自随包发布的 `assets/applemusic-1606.json`，
 * 由 [HostProfileAssets] 解析后在 onLoad 阶段挂上。
 */
internal object HostProfileRegistry {
    @Volatile
    private var profile: AppleMusicHookProfile? = null

    fun attach(loaded: AppleMusicHookProfile) {
        profile = loaded
    }

    fun current(): AppleMusicHookProfile =
        checkNotNull(profile) { "Host profile not attached yet" }

    fun catalogQueryRename(ownerClassName: String, preferredName: String): String? =
        profile?.catalogQueryRename(ownerClassName, preferredName)
}
