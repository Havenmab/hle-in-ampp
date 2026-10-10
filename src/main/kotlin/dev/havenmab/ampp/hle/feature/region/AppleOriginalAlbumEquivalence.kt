/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleOriginalAlbumEquivalence.kt
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
/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */


/**
 * Apple defines filter[equivalents] as a storefront identity mapping, not a title search:
 * https://developer.apple.com/documentation/applemusicapi/get-equivalent-ids-for-the-albums-8aky3
 * Request one source ID at a time: Android's MediaApiResponse.Meta does not retain filters.
 * A unique response can then be attributed to that source without guessing batch order.
 */
internal fun AppleInternalCatalogResolver.queryEquivalentOriginalAlbum(
    request: AppleInternalCatalogResolver.OriginalEntityRequest,
    lookupId: String,
    onResolved: (AppleInternalCatalogResolver.Alias?) -> Unit,
) {
    queryResponse(
        storefront = request.storefront,
        language = request.language,
        description = "original-album-equivalent-id=$lookupId",
        path = AppleInternalCatalogResolver.LocalizedEntityType.ALBUM.path,
        queryParams = equivalentAlbumQueryParams(lookupId, request.language),
        snapshotEntityType = AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
        transformOffMain = { snapshot ->
            runCatching {
                snapshot
                    ?.let {
                        parseCatalogEntities(
                            it,
                            request.language,
                            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
                        )
                    }
                    .orEmpty()
            }.onFailure { ProviderLogger.error("Apple 等价专辑响应解析失败", it) }
                .getOrDefault(emptyList())
        },
    ) { albums ->
        val equivalent = uniqueOriginalAlbumEquivalent(albums.orEmpty(), request.language)
        if (HleBuild.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple 原地区等价专辑: id=${request.mediaId}, lookupId=$lookupId, " +
                    "storefront=${request.storefront}, candidates=${albums?.size ?: 0}, " +
                    "equivalentId=${equivalent?.id}, hit=${equivalent != null}",
            )
        }
        onResolved(equivalent?.alias)
    }
}

internal fun equivalentAlbumQueryParams(lookupId: String, language: String): Map<String, String> {
    require(lookupId.isNotEmpty() && lookupId.all(Char::isDigit))
    return linkedMapOf(
        "filter[equivalents]" to lookupId,
        "l" to language,
        "platform" to "android",
        "include[albums]" to "artists",
    )
}

internal fun uniqueOriginalAlbumEquivalent(
    albums: List<AppleInternalCatalogResolver.CatalogSong>,
    language: String,
): AppleInternalCatalogResolver.CatalogSong? = albums.singleOrNull()?.takeIf { album ->
    val id = album.id
    !id.isNullOrBlank() && id.all(Char::isDigit) && album.alias.title.isNotBlank() &&
        AppleInternalCatalogResolver.isAcceptableOriginalAlias(album.alias, language)
}

/** Keep exact results first and hold the existing batch slot until serial fallbacks finish. */
internal fun resolveOriginalEntityBatchResults(
    requests: List<AppleInternalCatalogResolver.OriginalEntityRequest>,
    exact: Map<String, AppleInternalCatalogResolver.Alias>,
    queryEquivalentAlbum: (
        AppleInternalCatalogResolver.OriginalEntityRequest,
        String,
        (AppleInternalCatalogResolver.Alias?) -> Unit,
    ) -> Unit,
    onResolved: (AppleInternalCatalogResolver.OriginalEntityRequest, AppleInternalCatalogResolver.Alias?) -> Unit,
    onComplete: () -> Unit,
) {
    val missingAlbums = requests.filter { request ->
        val alias = AppleInternalCatalogResolver.selectExactOriginalEntityAlias(
            request.mediaId, request.lookupIds, exact, request.language,
        )
        if (alias != null || request.entityType != AppleInternalCatalogResolver.LocalizedEntityType.ALBUM) {
            onResolved(request, alias)
            false
        } else {
            true
        }
    }

    fun resolveNext(index: Int) {
        val request = missingAlbums.getOrNull(index) ?: return onComplete()
        val ids = (listOf(request.mediaId) + request.lookupIds)
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
        fun queryNext(idIndex: Int) {
            val id = ids.getOrNull(idIndex)
            if (id == null) {
                onResolved(request, null)
                resolveNext(index + 1)
                return
            }
            queryEquivalentAlbum(request, id) { candidate ->
                val alias = candidate?.takeIf {
                    AppleInternalCatalogResolver.isAcceptableOriginalAlias(it, request.language)
                }
                if (alias == null) {
                    queryNext(idIndex + 1)
                } else {
                    // Cache/bind under request.mediaId; the equivalent ID is not a playback ID.
                    onResolved(request, alias)
                    resolveNext(index + 1)
                }
            }
        }
        queryNext(0)
    }
    resolveNext(0)
}
