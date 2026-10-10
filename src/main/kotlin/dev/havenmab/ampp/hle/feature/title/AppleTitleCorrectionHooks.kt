/*
 * 第 1 批「歌曲名修正」的落地缝（MediaApi 元数据 getter 契约）。
 *
 * 与 AM++ 的关系：AM++ 的原名修正由 `AppleContentItemMetadataHooks` +
 * `AppleInAppMetadataResolutionCoordinator` + `HleMetadataSurfaceBridge` 等把别名写进
 * Listen Now / 资料库 / data-binding / 艺人 / 合集 / 队列 / 操作表等全部 in-app 表面。
 * 本轮**只落地 MediaApi 元数据缝**（asset 表 `indexed.methodContracts` 里的
 * `media-entity-*` / `attributes-*` / `song-get-short-name-method` 等 getter 契约），
 * 解析引擎（[AppleInternalCatalogResolver] + [CatalogOriginalResolution] +
 * [NativeCatalogQueryAccess]）与 AM++ 一致。剩余 in-app 表面列入第 2 批，
 * 接缝清单见 `feature/title/README.md`。
 */

package dev.havenmab.ampp.hle.feature.title

import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver
import dev.havenmab.ampp.hle.feature.region.AppleMetadataOverrideStore
import dev.havenmab.ampp.hle.feature.region.AppleOriginalMetadataPolicy
import dev.havenmab.ampp.hle.feature.region.MediaMetadataCache
import dev.havenmab.ampp.hle.host.AppleMusicHookPoint
import dev.havenmab.ampp.hle.host.AppleMusicRuntimeMember
import dev.havenmab.ampp.hle.host.AppleReflection
import dev.havenmab.ampp.hle.host.HookCall
import dev.havenmab.ampp.hle.host.HostRuntime
import dev.havenmab.ampp.hle.host.ProviderLogger
import dev.havenmab.ampp.hle.host.ResolvedTarget
import dev.havenmab.ampp.hle.ui.HleSettings
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 把解析出的原名/地区语言别名应用到 Apple Music 的 MediaApi 元数据读取缝。
 *
 * 数据流：
 * 1. 宿主读 `MediaEntity#getTitle` / `Attributes#getName` 等（`assets` 目标表里的 getter 契约）；
 * 2. 本缝从 receiver 上取 catalog id 与本地化标题/歌手/流派（`CATALOG_*` 成员名来自
 *    `MEDIA_API_REPOSITORY_HOLDER_CLASS` 目标），填充 [MediaMetadataCache] 并触发解析；
 * 3. 解析完成后 [AppleMetadataOverrideStore] 记下别名；
 * 4. 后续读取同名 getter 时返回原名/地区语言名。
 *
 * 两个开关与 AM++ 一致：
 * - `restore_cjk_original_metadata` → [AppleInternalCatalogResolver.resolveOriginalMetadata]（原地区原名）；
 * - 地区 + `override_account_language` → [AppleInternalCatalogResolver.resolveForContentUiLanguage]（设定地区语言）。
 * 原名优先于地区语言，与 AM++ 的 `effectiveAlias` 顺序相同。
 */
internal class AppleTitleCorrectionHooks(
    private val runtime: HostRuntime,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val settings: HleSettings,
) {
    private val store = AppleMetadataOverrideStore()
    private val attributesOwner = Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val configuredRequested = ConcurrentHashMap.newKeySet<String>()
    private val repositoryMembers: Map<AppleMusicRuntimeMember, String> by lazy {
        runtime.targets.resolveClasses(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
            .firstOrNull()
            ?.target
            ?.runtimeMemberNames
            .orEmpty()
    }

    fun install() {
        val plan = settings.plan
        if (!plan.probesOriginalMetadata && !plan.overrideAccountLanguage) {
            ProviderLogger.info(
                "歌曲名修正未安装: 原名恢复与地区语言替换都未开启（地区=${settings.regionSelection.storageValue}）"
            )
            return
        }
        // 先装实体的 attributes 绑定缝，再装名称 getter 缝。
        hookPoint(
            "media-entity-get-attributes-method",
            after = { call ->
                val receiver = call.receiver
                val attributes = call.result
                if (receiver != null && attributes != null) {
                    val mediaId = mediaIdOf(receiver)
                    if (mediaId != null) {
                        attributesOwner[attributes] = mediaId
                        ensureResolution(receiver, mediaId)
                    }
                }
            },
        )
        hookPoint("media-entity-get-title-method", after = { it.applyAlias("title") })
        hookPoint("media-entity-get-short-name-method", after = { it.applyAlias("title") })
        hookPoint("song-get-short-name-method", after = { it.applyAlias("title") })
        hookPoint("attributes-get-name-method", after = { it.applyAlias("title") })
        hookPoint("attributes-get-short-name-method", after = { it.applyAlias("title") })
        hookPoint("attributes-get-artist-name-method", after = { it.applyAlias("artist") })
        hookPoint("attributes-get-album-name-method", after = { it.applyAlias("album") })
        // MediaApi → 宿主 model.Song 的转换缝：转换时就绑定并应用，减少一次读取。
        hookPoint(
            "media-entity-to-song-converter-method",
            after = { call ->
                val input = call.arg(0)
                if (input != null) {
                    val mediaId = mediaIdOf(input)
                    if (mediaId != null) {
                        AppleReflection.fieldOrNull(
                            input,
                            name(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
                        )?.let { attributes -> attributesOwner[attributes] = mediaId }
                        ensureResolution(input, mediaId)
                        store.originalMetadata(mediaId)?.let { alias ->
                            applyTo(call.result, alias.title, alias.artist, alias.album)
                        }
                    }
                }
            },
        )
        ProviderLogger.info(
            "歌曲名修正 MediaApi 元数据缝已安装: 原名=${plan.probesOriginalMetadata}, " +
                "地区语言=${plan.overrideAccountLanguage}, " +
                "members=${repositoryMembers.size}"
        )
    }

    /**
     * 从契约 id 解析目标；契约缺失就跳过该缝（不是致命错误：不同宿主实体类型可能没有
     * short-name getter 之类），与 AM++ 逐个 `runCatching` 的行为一致。
     */
    private fun hookPoint(contractId: String, after: (HookCall) -> Unit) {
        val target = contractTarget(contractId) ?: run {
            ProviderLogger.info("歌曲名修正跳过契约 $contractId: 目标表中不存在")
            return
        }
        runCatching {
            runtime.hook(target, before = null, after = after)
        }.onFailure {
            ProviderLogger.error("歌曲名修正契约 $contractId 安装失败", it)
        }
    }

    private fun contractTarget(contractId: String): java.lang.reflect.Method? {
        val contract = runtime.methodContract(contractId) ?: return null
        val clazz = runCatching { runtime.targets.loadClass(contract.owner) }.getOrNull() ?: return null
        return AppleReflection.findMethodOrNull(
            clazz = clazz,
            name = contract.name,
            parameterCount = contract.parameters.size,
        )
    }

    /** 读取 receiver 的 catalog id（数字）；读不到就返回 null。 */
    private fun mediaIdOf(receiver: Any): String? {
        val raw = AppleReflection.callIfPresent(
            receiver,
            name(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
        )?.toString()?.trim()
        return raw?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /**
     * 触发一次解析（原名 + 地区语言），并把结果写进 [AppleMetadataOverrideStore]。
     * `requested` 避免同一首歌反复入队；解析器自身还有 in-flight 去重。
     */
    private fun ensureResolution(source: Any, mediaId: String) {
        val title = readAttribute(source, AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD)
        val artist = readAttribute(source, AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD)
        val genre = readAttribute(source, AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD)
        if (title.isNullOrEmpty()) return
        MediaMetadataCache.put(
            MediaMetadataCache.Metadata(
                id = mediaId,
                title = title,
                artist = artist,
                genre = genre,
                duration = 0L,
                queueId = 0L,
            )
        )
        val plan = settings.plan
        if (plan.probesOriginalMetadata) {
            if (AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                    mediaId = mediaId,
                    title = title,
                    artist = artist,
                    genre = genre,
                )
            ) {
                if (requested.add(mediaId)) {
                    catalogResolver.resolveOriginalMetadata(
                        metadata = MediaMetadataCache.Metadata(
                            id = mediaId,
                            title = title,
                            artist = artist,
                            genre = genre,
                            duration = 0L,
                            queueId = 0L,
                        ),
                    ) { resolution ->
                        resolution.alias?.let { alias ->
                            store.rememberOriginalMetadata(
                                mediaId = mediaId,
                                alias = alias,
                                confirmed = resolution.originKnown,
                            )
                            ProviderLogger.info(
                                "Apple 原名已应用(MediaApi): id=$mediaId, " +
                                    "title=${alias.title}, artist=${alias.artist}, " +
                                    "language=${alias.language}, originKnown=${resolution.originKnown}"
                            )
                        }
                    }
                }
            }
        }
        if (plan.overrideAccountLanguage && configuredRequested.add(mediaId)) {
            catalogResolver.resolveForContentUiLanguage(
                mediaId = mediaId,
                selection = plan.contentUiLanguageSelection,
            ) { alias ->
                alias?.let { resolved ->
                    store.rememberConfiguredMetadata(mediaId, resolved)
                    ProviderLogger.info(
                        "Apple 地区语言元数据已应用(MediaApi): id=$mediaId, " +
                            "title=${resolved.title}, language=${resolved.language}"
                    )
                }
            }
        }
    }

    private fun readAttribute(source: Any, member: AppleMusicRuntimeMember): String? {
        val direct = AppleReflection.callIfPresent(source, name(member)) as? String
        if (!direct.isNullOrBlank()) return direct.trim()
        val attributes = AppleReflection.callIfPresent(
            source,
            name(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
        ) ?: return null
        return (AppleReflection.callIfPresent(attributes, name(member)) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    /** 应用别名：原名优先，其次是地区语言覆盖。 */
    private fun HookCall.applyAlias(field: String) {
        val receiver = receiver ?: return
        val mediaId = mediaIdOf(receiver) ?: attributesOwner[receiver] ?: return
        val alias = store.originalMetadata(mediaId) ?: store.configuredMetadata(mediaId) ?: return
        val replacement = when (field) {
            "title" -> alias.title
            "artist" -> alias.artist
            "album" -> alias.album
            else -> ""
        }
        if (replacement.isBlank()) return
        if (replacement == result?.toString()) return
        returnResult(replacement)
    }

    private fun applyTo(target: Any?, title: String, artist: String, album: String) {
        target ?: return
        if (title.isNotBlank()) {
            AppleReflection.callIfPresent(
                target,
                name(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_NAME_METHOD),
                title,
            )
        }
        if (artist.isNotBlank()) {
            AppleReflection.callIfPresent(
                target,
                name(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD),
                artist,
            )
        }
        if (album.isNotBlank()) {
            AppleReflection.callIfPresent(
                target,
                name(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD),
                album,
            )
        }
    }

    private fun name(member: AppleMusicRuntimeMember): String =
        repositoryMembers[member] ?: member.name
}
