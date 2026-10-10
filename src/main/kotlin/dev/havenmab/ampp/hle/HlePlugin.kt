package dev.havenmab.ampp.hle

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.amenhancer.plugin.api.AmppPlugin
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginSettingsSession
import dev.amenhancer.plugin.api.PluginUnsupportedException
import dev.havenmab.ampp.hle.feature.region.AppleContentLocalizationHooks
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver
import dev.havenmab.ampp.hle.feature.region.applyRegionConfiguration
import dev.havenmab.ampp.hle.feature.title.AppleTitleCorrectionHooks
import dev.havenmab.ampp.hle.host.AppleMusicHookProfile
import dev.havenmab.ampp.hle.host.HostProfileAssets
import dev.havenmab.ampp.hle.host.HostProfileRegistry
import dev.havenmab.ampp.hle.host.HostRuntime
import dev.havenmab.ampp.hle.host.ProviderLogger
import dev.havenmab.ampp.hle.ui.HleSettings
import dev.havenmab.ampp.hle.ui.HleSettingsScreen

/**
 * 插件入口：HLE 移植（地区/歌名/歌词）。
 *
 * 生命周期（SDK v1）：
 * - [onLoad]：在后台线程读目标表、做宿主版本门禁、登记 Hook；
 * - [onStart]/[onStop]：主线程生命周期；
 * - [createSettings]：主线程，返回普通 Android View 的设置页；
 * - 所有清理通过 `PluginContext.onClose` 登记（见 [HostRuntime.closeAllRegistrations]）。
 *
 * 本轮落地的功能（第 1 批）：
 * 1. 地区替换：content-http + amp-api + MediaApi 本地化参数 + 目录直连执行器四条缝；
 * 2. 歌曲名修正：原名解析引擎 + MediaApi/MediaSession 元数据缝落地；
 * 3. 歌词（第 3 批）只留接缝，见 `feature/lyrics/README.md`。
 */
class HlePlugin : AmppPlugin() {
    @Volatile
    private var runtime: HostRuntime? = null

    @Volatile
    private var settings: HleSettings? = null

    @Volatile
    private var catalogResolver: AppleInternalCatalogResolver? = null

    @Volatile
    private var profile: AppleMusicHookProfile? = null

    private var titleHooks: AppleTitleCorrectionHooks? = null

    override fun onLoad(context: PluginContext) {
        ProviderLogger.attach(context)

        val loaded = try {
            HostProfileAssets.load(context)
        } catch (error: Throwable) {
            throw PluginUnsupportedException(
                "插件素材 assets/${HostProfileAssets.DEFAULT_ASSET} 读取或解析失败：" +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
        }
        HostProfileRegistry.attach(loaded)
        profile = loaded

        val hostPackage = context.getHostPackageName()
        val hostVersionName = context.getHostVersionName()
        val hostVersionCode = context.getHostVersionCode()
        if (hostPackage != loaded.packageName) {
            throw PluginUnsupportedException(
                "本插件只支持 ${loaded.packageName}（目标表 ${loaded.id}），" +
                    "当前宿主包名为 $hostPackage"
            )
        }
        if (!loaded.matches(hostVersionName, hostVersionCode)) {
            throw PluginUnsupportedException(
                "当前宿主版本不受支持：$hostPackage $hostVersionName (versionCode=$hostVersionCode)。" +
                    "本插件只带 ${loaded.displayName()} 的目标表" +
                    "（来源：AM++ host-profiles 7.0.0-beta-1606）。" +
                    "请更新插件，或在 AM++ 支持该版本后重新导出目标表。"
            )
        }

        val host = HostRuntime(context, loaded)
        runtime = host
        context.onClose { host.closeAllRegistrations() }

        val loadedSettings = HleSettings(context.getDataDirectory())
        settings = loadedSettings
        ProviderLogger.diagnosticEnabled = loadedSettings.verboseDiagnostics

        val plan = loadedSettings.plan
        val resolver = AppleInternalCatalogResolver(
            context = context.getApplication(),
            classLoader = host.classLoader,
            host = host,
            mainHandler = Handler(Looper.getMainLooper()),
            cacheNamespace = plan.cacheNamespace,
        )
        catalogResolver = resolver
        resolver.applyRegionConfiguration(
            selection = plan.contentUiLanguageSelection,
            // 只有明确选了带 storefront 的地区才改写普通 Apple Music 流量；「不开启」只做原名恢复。
            regionReplacementRequested = plan.rewritesCatalogRequests,
            localizedMetadataCacheEnabled = loadedSettings.localizedMetadataCache,
        )

        // AM++ 的 HleMetadataRuntime 无条件安装四条本地化缝：即使地区为「不开启」，
        // 解析器自有的 token 请求（原名探测）也要经过它们。缝内部再按配置决定是否改写。
        val contentHooks = AppleContentLocalizationHooks(host) { resolver }
        install("MediaApi 本地化参数") { contentHooks.installMediaApiLocalization() }
        install("目录直连执行器") { contentHooks.installCatalogRequestLocalization() }
        install("content-http 本地化") { contentHooks.installContentHttpLocalization() }
        install("amp-api 网络拦截") { contentHooks.installAmpApiHttpLocalization() }

        // 第 1 批的歌曲名修正：解析引擎 + MediaApi/MediaSession 元数据缝。
        val title = AppleTitleCorrectionHooks(host, resolver, loadedSettings)
        titleHooks = title
        install("歌曲名修正元数据缝") { title.install() }

        ProviderLogger.info(
            "HLE 插件已装载: host=$hostPackage $hostVersionName($hostVersionCode), " +
                "target=${loaded.id}, region=${plan.contentUiLanguageSelection}, " +
                "rewriteCatalog=${plan.rewritesCatalogRequests}, " +
                "restoreCjkOriginal=${plan.probesOriginalMetadata}"
        )
    }

    override fun onStart() {
        ProviderLogger.info("HLE 插件已启动")
    }

    override fun onStop() {
        ProviderLogger.info("HLE 插件已停止")
    }

    override fun createSettings(context: Context): PluginSettingsSession? {
        val loadedSettings = settings ?: return null
        val hostVersion = profile?.displayName() ?: "unknown"
        val screen = HleSettingsScreen(
            context = context,
            settings = loadedSettings,
            resolver = { catalogResolver },
            hostVersionLabel = hostVersion,
            onDiagnosticsChanged = { enabled -> ProviderLogger.diagnosticEnabled = enabled },
        )
        return screen.createSession()
    }

    private fun install(label: String, block: () -> Unit) {
        runCatching { block() }
            .onFailure { ProviderLogger.error("HLE $label 安装失败", it) }
    }
}
