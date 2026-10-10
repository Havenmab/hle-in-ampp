/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogOriginalResolution.kt
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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.havenmab.ampp.hle.feature.region.HleRootConstants
import dev.havenmab.ampp.hle.feature.region.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogAccess
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogResponseSnapshot
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogEntitySnapshot
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogArtistSnapshot
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogIdentity
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogSong
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.LocalizedRequest
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.LockedIsrcFallbackTask
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.OriginalEntityRequest
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.CatalogRequestLocalization
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Alias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.OriginalResolution
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.LocalizedLookup
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.RequestPriority
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.LocalizedEntityType
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.CURRENT_LANGUAGE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.CACHE_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.LOCALIZED_CACHE_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.REQUEST_PRIORITY_CACHE_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.ORIGINAL_METADATA_CACHE_SCHEMA
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.MAX_LOCALIZED_BATCHES_RUNNING
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.MAX_LOCKED_ISRC_FALLBACK_RUNNING
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_DELAY_MS
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_SIZE
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.MAX_ORIGINAL_ENTITY_BATCHES_RUNNING
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_DELAY_MS
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.QUERY_SLOW_RESPONSE_MS
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.QUERY_TIMEOUT_MS
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.ARTIST_ALIAS_CACHE_SCHEMA
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.CATALOG_REQUEST_TOKEN_PARAM
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.COLLABORATION_ARTIST_PATTERNS
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.collaborationArtistCache
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isCoroutineSuspended
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.originalSongCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.originalDirectEntityCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.legacyAmbiguousSongCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.originalEntityCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.originalEntityCacheLookupKeys
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.localizedMetadataCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isLocalizedArtistAliasCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.originalLanguageCacheKeyVariants
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.selectNextRequestIndex
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.canStartRequest
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.higherPriority
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.priorityForRequestScope
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.normalizeRequestScopeIds
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.languageTagsForGenre
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.knownLanguageTagsForGenre
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.languageTagsForIsrc
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.languageTagsForOriginalMetadata
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.canonicalOriginalLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.supportedOriginalLanguageOrNull
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.storefrontForOriginalLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isLegacyTraditionalChineseLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.canonicalCachedOriginalAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.regionalOriginalAliases
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isAcceptableOriginalAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.selectExactOriginalEntityAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.selectExactIdentityAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.containsHanCharacters
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.storefrontForContentUiLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.languageTagsForContentUiLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.languageTagForContentUiLanguage
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.localizedStorefrontHeaderValue
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.normalizedArtistNameKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.artistCacheKey
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.storefrontFromContentPath
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isAccountScopedPlaybackPath
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.selectLocalizedArtistName
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.selectOriginalAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isConfidentOriginalSongAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isReusableOriginalSongAlias
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isCollaborationArtistName
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.isOriginalTitle
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.shouldResolve
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.nonLatinLetterCount
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.normalize
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.shouldCacheCatalogIdentity
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver.Companion.shouldRetryEmptyCatalogIdentity
internal fun AppleInternalCatalogResolver.resolveOriginalMetadataFromCatalog(
        metadata: MediaMetadataCache.Metadata,
        allowEmptyIdentityRetry: Boolean = true,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    ) {
        val fallbackLanguages = if (AppleOriginalMetadataPolicy.isCjkGenre(metadata.genre)) {
            languageTagsForGenre(metadata.genre)
        } else {
            emptyList()
        }
        resolveCatalogIdentity(metadata.id, fallbackLanguages) { identity ->
            identity.fallbackAliases.firstOrNull()?.let { alias ->
                publishOriginalCandidate(metadata.id, alias)
            }
            if (allowEmptyIdentityRetry && shouldRetryEmptyCatalogIdentity(
                    mediaId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artist,
                    genre = metadata.genre,
                    isrc = identity.isrc,
                    catalogGenres = identity.genres,
                )
            ) {
                ProviderLogger.info(
                    "Apple 内部歌曲空身份重试: id=${metadata.id}, " +
                        "title=${metadata.title}, artist=${metadata.artist}"
                )
                mainHandler.post {
                    resolveOriginalMetadataFromCatalog(
                        metadata = metadata,
                        allowEmptyIdentityRetry = false,
                        priority = currentRequestPriority(metadata.id, priority),
                    )
                }
                return@resolveCatalogIdentity
            }
            val results = identity.fallbackAliases.toMutableList()
            val isrc = identity.isrc
            val languages = languageTagsForOriginalMetadata(
                genre = metadata.genre,
                catalogGenres = identity.genres,
                isrc = isrc,
            )
            if (languages.isEmpty()) {
                finishResolve(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = isrc != null,
                    artistIds = identity.artistIds,
                )
                return@resolveCatalogIdentity
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishResolve(
                        metadata = metadata,
                        languages = languages,
                        results = results,
                        originKnown = isrc != null,
                        artistIds = identity.artistIds,
                    )
                    return
                }
                val language = languages[index]
                selectExactIdentityAlias(identity.fallbackAliases, language)?.let { exactAlias ->
                    finishResolve(
                        metadata = metadata,
                        languages = listOf(language),
                        results = listOf(exactAlias),
                        originKnown = true,
                        artistIds = identity.artistIds,
                    )
                    return
                }
                resolveOriginalEntityForLanguage(
                    mediaId = metadata.id,
                    lookupIds = listOf(metadata.id),
                    entityType = LocalizedEntityType.SONG,
                    language = language,
                    priority = currentRequestPriority(metadata.id, priority),
                ) { resolvedAlias ->
                    val exactAlias = resolvedAlias?.takeIf { alias ->
                        (alias.title.isNotBlank() || alias.artist.isNotBlank()) &&
                            isConfidentOriginalSongAlias(
                                alias = alias,
                                localizedTitle = metadata.title.orEmpty(),
                                localizedArtist = metadata.artist.orEmpty(),
                            )
                    }
                    if (exactAlias != null) {
                        val regionalArtistIds =
                            catalogIdentityCache[metadata.id]?.artistIds.orEmpty()
                        finishResolve(
                            metadata = metadata,
                            languages = listOf(language),
                            results = listOf(exactAlias),
                            originKnown = true,
                            artistIds = (
                                identity.artistIds + regionalArtistIds
                                ).distinct(),
                        )
                    } else {
                        if (resolvedAlias != null) {
                            invalidateOriginalEntity(metadata.id, LocalizedEntityType.SONG)
                        }
                        if (isrc == null) {
                            queryNext(index + 1)
                        } else {
                            queryByIsrc(isrc, language) { song ->
                                song?.alias?.let(results::add)
                                queryNext(index + 1)
                            }
                        }
                    }
                }
            }
            queryNext(0)
        }
    }


internal fun AppleInternalCatalogResolver.resolveManyForContentUiLanguageSingleLanguage(
        lookups: Collection<LocalizedLookup>,
        selection: Int,
        priority: RequestPriority,
        storefront: String,
        language: String,
        onResolved: (mediaId: String, alias: Alias?) -> Unit,
        onComplete: () -> Unit = {},
    ) {
        val requests = lookups.asSequence()
            .mapNotNull { lookup ->
                val mediaId = lookup.mediaId.trim()
                if (mediaId.isEmpty() || !mediaId.all(Char::isDigit)) return@mapNotNull null
                val normalizedLookupIds = sequenceOf(mediaId)
                    .plus(lookup.lookupIds.asSequence())
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                    .distinct()
                    .take(LOCALIZED_BATCH_SIZE)
                    .toList()
                val cacheKey = localizedMetadataCacheKey(
                    selection,
                    lookup.entityType,
                    mediaId,
                    language,
                )
                rememberRequestPriority(mediaId, priority)
                LocalizedRequest(
                    cacheKey = cacheKey,
                    requestKey = "$cacheKey:${normalizedLookupIds.joinToString(",")}".trim(),
                    mediaId = mediaId,
                    lookupIds = normalizedLookupIds,
                    entityType = lookup.entityType,
                    selection = selection,
                    storefront = storefront,
                    language = language,
                    priority = currentRequestPriority(mediaId, priority),
                )
            }
            .distinctBy(LocalizedRequest::requestKey)
            .toList()
        if (requests.isEmpty()) {
            onComplete()
            return
        }

        val requestCompletionCount = AtomicLong(requests.size.toLong())
        val complete: (LocalizedRequest, Alias?) -> Unit = { request, alias ->
            onResolved(request.mediaId, alias)
            if (requestCompletionCount.decrementAndGet() == 0L) onComplete()
        }
        val uncached = mutableListOf<LocalizedRequest>()
        requests.forEach { request ->
            val cached = synchronized(localizedCache) { localizedCache[request.cacheKey] }
            if (cached != null) {
                complete(request, cached)
                return@forEach
            }
            val ownsRequest = synchronized(localizedInFlight) {
                val callbacks = localizedInFlight[request.requestKey]
                if (callbacks != null) {
                    callbacks += { alias -> complete(request, alias) }
                    promotePendingRequests(listOf(request.mediaId), request.priority)
                    false
                } else {
                    localizedInFlight[request.requestKey] =
                        mutableListOf({ alias -> complete(request, alias) })
                    true
                }
            }
            if (ownsRequest) uncached += request
        }
        if (uncached.isEmpty()) return
        persistentLocalizedCache.getMany(uncached.map(LocalizedRequest::cacheKey)) { cached ->
            uncached.forEach { request ->
                val alias = cached[request.cacheKey]
                if (alias != null) finishLocalizedCacheHit(request, alias)
                else enqueueLocalizedRequest(request)
            }
        }
    }


internal fun AppleInternalCatalogResolver.resolveLocalizedRequestByLockedIsrc(
        request: LocalizedRequest,
        onResolved: (resolvedLookupId: String?, alias: Alias?) -> Unit,
    ) {
        val candidateIds = (listOf(request.mediaId) + request.lookupIds)
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
        if (candidateIds.isEmpty()) {
            onResolved(null, null)
            return
        }

        val attemptedIsrcs = mutableSetOf<String>()
        fun queryNext(index: Int) {
            if (index >= candidateIds.size) {
                onResolved(null, null)
                return
            }
            val identityId = candidateIds[index]
            fun queryIdentity(identity: CatalogIdentity?) {
                val isrc = identity?.isrc?.trim()?.takeIf(String::isNotEmpty)
                if (isrc == null || !attemptedIsrcs.add(isrc)) {
                    queryNext(index + 1)
                    return
                }
                ProviderLogger.info(
                    "Apple 固定地区歌曲启用 ISRC 备用查询: id=${request.mediaId}, " +
                        "identityId=$identityId, isrc=$isrc, storefront=${request.storefront}, " +
                        "language=${request.language}",
                )
                queryByIsrc(
                    isrc = isrc,
                    language = request.language,
                    storefrontOverride = request.storefront,
                ) { song ->
                    val alias = song?.alias?.takeIf {
                        it.title.isNotBlank() || it.artist.isNotBlank()
                    }
                    if (alias != null) {
                        onResolved(song?.id, alias)
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            catalogIdentityCache[identityId]?.let { cached ->
                queryIdentity(cached)
                return
            }
            // Keep the identity probe region-neutral.  It obtains only ISRC/relationship facts;
            // no alias from this account-region response is ever published for fixed mode.
            resolveCatalogIdentity(identityId, emptyList()) { identity ->
                queryIdentity(identity)
            }
        }
        queryNext(0)
    }


internal fun AppleInternalCatalogResolver.finishResolve(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        originKnown: Boolean,
        artistIds: List<String>,
    ) {
        val canonicalLanguages = languages.map(::canonicalOriginalLanguage).distinct()
        val sourceLanguage = canonicalLanguages.singleOrNull()
        val acceptableResults = regionalOriginalAliases(results, canonicalLanguages)
        val selected = selectOriginalAlias(
            variants = acceptableResults,
            localizedTitle = metadata.title.orEmpty(),
            localizedArtist = metadata.artist.orEmpty()
        )
        val confirmedRegionalAlias = if (originKnown) {
            acceptableResults.lastOrNull { alias ->
                canonicalOriginalLanguage(alias.language) in canonicalLanguages &&
                    isConfidentOriginalSongAlias(
                        alias = alias,
                        localizedTitle = metadata.title.orEmpty(),
                        localizedArtist = metadata.artist.orEmpty(),
                    )
            }
        } else {
            null
        }
        val originalAlias = selected ?: confirmedRegionalAlias
        if (originalAlias != null) {
            synchronized(cache) { cache[metadata.id] = originalAlias }
            persistentOriginalCache.put(originalSongCacheKey(metadata.id), originalAlias)
        }
        val resolvedAlbum = originalAlbumFromResolution(
            alias = originalAlias,
            acceptableResults = acceptableResults,
        )
        discardOriginalCandidates(metadata.id)
        val callbacks = synchronized(inFlight) { inFlight.remove(metadata.id).orEmpty() }
        ProviderLogger.info(
            "Apple 内部原名查询完成: id=${metadata.id}, genre=${metadata.genre}, " +
                "languages=$canonicalLanguages, selected=${originalAlias?.title}/${originalAlias?.artist}"
        )
        val resolution = OriginalResolution(
            alias = originalAlias,
            language = originalAlias?.language?.takeIf(String::isNotBlank)
                ?: sourceLanguage,
            originKnown = originKnown,
            artistIds = artistIds,
            album = resolvedAlbum,
        )
        callbacks.forEach { callback -> callback(resolution) }
    }


internal fun AppleInternalCatalogResolver.finishCachedOriginalResolve(mediaId: String, alias: Alias) {
        discardOriginalCandidates(mediaId)
        val callbacks = synchronized(inFlight) { inFlight.remove(mediaId).orEmpty() }
        ProviderLogger.info(
            "Apple 原地区元数据缓存命中: id=$mediaId, language=${alias.language}"
        )
        val resolution = OriginalResolution(
            alias = alias,
            language = alias.language.takeIf(String::isNotBlank),
            originKnown = true,
            artistIds = emptyList(),
            album = alias.album,
        )
        callbacks.forEach { callback -> callback(resolution) }
    }


internal fun AppleInternalCatalogResolver.registerOriginalCandidateCallback(
        mediaId: String,
        callback: (Alias) -> Unit,
    ) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.getOrPut(mediaId) { mutableListOf() }.add(callback)
        }
        catalogIdentityCache[mediaId]
            ?.fallbackAliases
            ?.firstOrNull()
            ?.let { alias -> publishOriginalCandidate(mediaId, alias) }
    }


internal fun AppleInternalCatalogResolver.publishOriginalCandidate(mediaId: String, alias: Alias) {
        if (alias.title.isBlank() && alias.artist.isBlank()) return
        val callbacks = synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId).orEmpty()
        }
        callbacks.forEach { callback -> callback(alias) }
    }


internal fun AppleInternalCatalogResolver.discardOriginalCandidates(mediaId: String) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId)
        }
    }


internal fun AppleInternalCatalogResolver.resolveCatalogIdentity(
        mediaId: String,
        languages: List<String>,
        onResult: (CatalogIdentity) -> Unit
    ) {
        catalogIdentityCache[mediaId]?.takeIf(::isUsefulCatalogIdentity)?.let {
            onResult(it)
            return
        }
        catalogIdentityCache.remove(mediaId)
        val ownsRequest = synchronized(catalogIdentityInFlight) {
            val callbacks = catalogIdentityInFlight[mediaId]
            if (callbacks != null) {
                callbacks += onResult
                false
            } else {
                catalogIdentityInFlight[mediaId] = mutableListOf(onResult)
                true
            }
        }
        if (!ownsRequest) return

        queryById(mediaId, null) { currentSong ->
            currentSong?.isrc?.let { isrc ->
                ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
                finishCatalogIdentity(
                    mediaId,
                    CatalogIdentity(
                        isrc = isrc,
                        fallbackAliases = listOfNotNull(currentSong.alias),
                        genres = currentSong.genres,
                        artistIds = currentSong.artistIds,
                    ),
                )
                return@queryById
            }

            val fallbackAliases = mutableListOf<Alias>().apply {
                currentSong?.alias?.let(::add)
            }
            val fallbackGenres = mutableListOf<String>().apply {
                currentSong?.genres?.let(::addAll)
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishCatalogIdentity(
                        mediaId,
                        CatalogIdentity(
                            isrc = null,
                            fallbackAliases = fallbackAliases,
                            genres = fallbackGenres,
                            artistIds = currentSong?.artistIds.orEmpty(),
                        ),
                    )
                    return
                }
                val language = languages[index]
                queryById(mediaId, language) { song ->
                    song?.alias?.let(fallbackAliases::add)
                    song?.genres?.let(fallbackGenres::addAll)
                    val isrc = song?.isrc
                    if (isrc != null) {
                        ProviderLogger.info(
                            "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                        )
                        finishCatalogIdentity(
                            mediaId,
                            CatalogIdentity(
                                isrc = isrc,
                                fallbackAliases = fallbackAliases,
                                genres = fallbackGenres.distinct(),
                                artistIds = (
                                    currentSong?.artistIds.orEmpty() + song.artistIds
                                ).distinct(),
                            ),
                        )
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            queryNext(0)
        }
    }


internal fun AppleInternalCatalogResolver.rememberCatalogIdentity(mediaId: String, song: CatalogSong) {
        val isrc = song.isrc ?: return
        val identity = CatalogIdentity(
            isrc = isrc,
            fallbackAliases = listOfNotNull(song.alias),
            genres = song.genres,
            artistIds = song.artistIds,
        )
        finishCatalogIdentity(mediaId, identity)
    }


internal fun AppleInternalCatalogResolver.finishCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
        val merged = synchronized(catalogIdentityCache) {
            val previous = catalogIdentityCache[mediaId]
            val next = if (previous == null) identity else {
                CatalogIdentity(
                    isrc = previous.isrc ?: identity.isrc,
                    fallbackAliases = (previous.fallbackAliases + identity.fallbackAliases).distinct(),
                    genres = (previous.genres + identity.genres).distinct(),
                    artistIds = (previous.artistIds + identity.artistIds).distinct(),
                )
            }
            if (isUsefulCatalogIdentity(next)) {
                catalogIdentityCache[mediaId] = next
            } else {
                catalogIdentityCache.remove(mediaId)
            }
            next
        }
        val cacheable = isUsefulCatalogIdentity(merged)
        // The resolver may finish after a different metadata profile has become active. Keep
        // catalog identity facts in this resolver's namespace instead of letting a late callback
        // mutate whichever profile happens to be globally selected at that moment.
        MediaMetadataCache.updateCatalogGenres(
            mediaId = mediaId,
            genres = merged.genres,
            profile = cacheNamespace,
        )
        // Remove and drain the in-flight callbacks on main together with their publication. This
        // prevents a new request from observing a removed key while the background preprocessing
        // result is still waiting in the main queue.
        fun publish() {
            val callbacks = synchronized(catalogIdentityInFlight) {
                catalogIdentityInFlight.remove(mediaId).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                ProviderLogger.info(
                    "Apple 内部歌曲身份已就绪: id=$mediaId, isrc=${merged.isrc}, " +
                        "genres=${merged.genres}, " +
                        "artistIds=${merged.artistIds}, candidates=${merged.fallbackAliases.size}, " +
                        "cached=$cacheable"
                )
                callbacks.forEach { callback -> callback(merged) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish() else mainHandler.post(::publish)
    }


internal fun AppleInternalCatalogResolver.isUsefulCatalogIdentity(identity: CatalogIdentity): Boolean =
        shouldCacheCatalogIdentity(identity.isrc, identity.genres)
