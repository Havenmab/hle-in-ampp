/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogRequestScheduling.kt
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
internal fun AppleInternalCatalogResolver.enqueueOriginalEntityRequest(request: OriginalEntityRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(originalEntityPending) {
            val existing = originalEntityPending[prioritized.requestKey]
            originalEntityPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                    callbacks = existing.callbacks + prioritized.callbacks,
                )
            }
            if (
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processOriginalEntityBatch, ORIGINAL_ENTITY_BATCH_DELAY_MS)
        }
    }


internal fun AppleInternalCatalogResolver.processOriginalEntityBatch() {
        val batch = synchronized(originalEntityPending) {
            originalEntityBatchScheduled = false
            if (
                originalEntityPending.isEmpty() ||
                !canStartOriginalEntityBatchLocked()
            ) return
            val pendingValues = originalEntityPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(OriginalEntityRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<OriginalEntityRequest>()
            val selectedIds = linkedSetOf<String>()
            originalEntityPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (
                        selected.isNotEmpty() &&
                        selectedIds.size + newIds.size > ORIGINAL_ENTITY_BATCH_SIZE
                    ) return@forEach
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { originalEntityPending.remove(it.requestKey) }
            originalEntityBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                originalEntityBackgroundBatchesRunning += 1
            }
            selected
        }

        val first = batch.first()
        queryByConfiguredRegion(
            mediaIds = batch.flatMap(OriginalEntityRequest::lookupIds).distinct(),
            entityType = first.entityType,
            storefront = first.storefront,
            language = first.language,
        ) { resolved ->
            resolveOriginalEntityBatchResults(
                requests = batch,
                exact = resolved.mapValues { it.value.alias },
                queryEquivalentAlbum = ::queryEquivalentOriginalAlbum,
                onResolved = { request, alias ->
                    if (alias != null) {
                        persistentOriginalCache.put(request.directCacheKey, alias)
                    }
                    ProviderLogger.info(
                        "Apple 原地区实体查询完成: id=${request.mediaId}, " +
                            "entityType=${request.entityType}, language=${request.language}, " +
                            "storefront=${request.storefront}, " +
                            "batch=${batch.size}, priority=${request.priority}, hit=${alias != null}, " +
                            "value=${alias?.title}/${alias?.artist}/${alias?.album}"
                    )
                    request.callbacks.forEach { callback -> callback(alias) }
                },
                onComplete = {
                    synchronized(originalEntityPending) {
                        originalEntityBatchesRunning -= 1
                        if (first.priority == RequestPriority.BACKGROUND) {
                            originalEntityBackgroundBatchesRunning -= 1
                        }
                    }
                    scheduleOriginalEntityBatchIfCapacity()
                },
            )
        }
        scheduleOriginalEntityBatchIfCapacity()
    }


internal fun AppleInternalCatalogResolver.scheduleOriginalEntityBatchIfCapacity() {
        val shouldSchedule = synchronized(originalEntityPending) {
            if (
                originalEntityPending.isEmpty() ||
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processOriginalEntityBatch)
    }


internal fun AppleInternalCatalogResolver.canStartOriginalEntityBatchLocked(): Boolean {
        val nextPriority = originalEntityPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = originalEntityBatchesRunning,
            backgroundRunning = originalEntityBackgroundBatchesRunning,
            maxRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING,
        )
    }


internal fun AppleInternalCatalogResolver.enqueueLocalizedRequest(request: LocalizedRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(localizedPending) {
            val existing = localizedPending[prioritized.requestKey]
            localizedPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                )
            }
            if (
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processLocalizedBatch, LOCALIZED_BATCH_DELAY_MS)
        }
    }


internal fun AppleInternalCatalogResolver.processLocalizedBatch() {
        val batch = synchronized(localizedPending) {
            localizedBatchScheduled = false
            if (
                localizedPending.isEmpty() ||
                !canStartLocalizedBatchLocked()
            ) return
            val pendingValues = localizedPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(LocalizedRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<LocalizedRequest>()
            val selectedIds = linkedSetOf<String>()
            localizedPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (selected.isNotEmpty() && selectedIds.size + newIds.size > LOCALIZED_BATCH_SIZE) {
                        return@forEach
                    }
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { localizedPending.remove(it.requestKey) }
            localizedBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                localizedBackgroundBatchesRunning += 1
            }
            selected
        }

        queryByConfiguredRegion(
            mediaIds = batch.flatMap(LocalizedRequest::lookupIds).distinct(),
            entityType = batch.first().entityType,
            storefront = batch.first().storefront,
            language = batch.first().language,
        ) { songs ->
            // Candidate ID matching and empty-alias filtering do not touch host objects.  Keep
            // them off the main thread, then publish the bounded cache/callback mutations there.
            catalogBackgroundExecutor.execute {
                val matches = runCatching {
                    batch.map { request ->
                        val resolvedEntry = request.lookupIds.firstNotNullOfOrNull { lookupId ->
                            songs?.get(lookupId)?.let { lookupId to it }
                        }
                        val alias = resolvedEntry?.second?.alias?.takeIf {
                            it.title.isNotBlank() || it.artist.isNotBlank()
                        }
                        Triple(request, resolvedEntry?.first, alias)
                    }
                }.onFailure { error ->
                    ProviderLogger.error("Apple 地区批量元数据后台候选匹配失败", error)
                }.getOrElse {
                    batch.map { request -> Triple(request, null, null) }
                }
                mainHandler.post {
                    var remaining = matches.size
                    fun finishBatch() {
                        remaining -= 1
                        if (remaining > 0) return
                        synchronized(localizedPending) {
                            localizedBatchesRunning -= 1
                            if (batch.first().priority == RequestPriority.BACKGROUND) {
                                localizedBackgroundBatchesRunning -= 1
                            }
                        }
                        scheduleLocalizedBatchIfCapacity()
                    }
                    matches.forEach { (request, resolvedLookupId, alias) ->
                        if (alias != null) {
                            finishLocalizedRequest(request, resolvedLookupId, alias)
                            finishBatch()
                        } else if (request.entityType == LocalizedEntityType.SONG) {
                            // A storefront can assign a different catalog ID to the same song.
                            // Reuse the original HLE identity/ISRC lookup as a fallback, but keep
                            // this second query on the fixed profile's storefront and language.
                            enqueueLockedIsrcFallback(request) { fallbackLookupId, fallbackAlias ->
                                finishLocalizedRequest(request, fallbackLookupId, fallbackAlias)
                                finishBatch()
                            }
                        } else {
                            finishLocalizedRequest(request, null, null)
                            finishBatch()
                        }
                    }
                }
            }
        }
        scheduleLocalizedBatchIfCapacity()
    }

    /** Enqueues one fixed-region miss for the bounded identity/ISRC fallback scheduler. */

internal fun AppleInternalCatalogResolver.enqueueLockedIsrcFallback(
        request: LocalizedRequest,
        onResolved: (resolvedLookupId: String?, alias: Alias?) -> Unit,
    ) {
        lockedIsrcFallbackPending += LockedIsrcFallbackTask(
            request = request,
            onResolved = onResolved,
        )
        scheduleLockedIsrcFallbacks()
    }


internal fun AppleInternalCatalogResolver.scheduleLockedIsrcFallbacks() {
        while (
            lockedIsrcFallbackRunning < MAX_LOCKED_ISRC_FALLBACK_RUNNING &&
                lockedIsrcFallbackPending.isNotEmpty()
        ) {
            val task = nextLockedIsrcFallbackTask() ?: break
            lockedIsrcFallbackRunning += 1
            resolveLocalizedRequestByLockedIsrc(task.request) { resolvedLookupId, alias ->
                lockedIsrcFallbackRunning = (lockedIsrcFallbackRunning - 1).coerceAtLeast(0)
                task.onResolved(resolvedLookupId, alias)
                scheduleLockedIsrcFallbacks()
            }
        }
    }

    /**
     * Selects the highest effective priority at dispatch time.  The effective value is read from
     * [requestPriorityByMediaId], so a visible-page promotion or a request-scope update also
     * reorders tasks that are already waiting in this fallback queue.  Equal priorities retain
     * insertion order to avoid unnecessary churn.
     */

internal fun AppleInternalCatalogResolver.nextLockedIsrcFallbackTask(): LockedIsrcFallbackTask? {
        val priorities = lockedIsrcFallbackPending.map { task ->
            currentRequestPriority(task.request.mediaId, task.request.priority)
        }
        val index = selectNextRequestIndex(priorities) ?: return null
        return lockedIsrcFallbackPending.removeAt(index)
    }

    /**
     * Resolves a fixed-region song missed by the ID batch through the same identity/ISRC fallback
     * used by the original-region HLE path.  The identity probe is only a module-owned metadata
     * lookup; the follow-up song query still uses [request.language]'s locked storefront.
     */

internal fun AppleInternalCatalogResolver.scheduleLocalizedBatchIfCapacity() {
        val shouldSchedule = synchronized(localizedPending) {
            if (
                localizedPending.isEmpty() ||
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processLocalizedBatch)
    }


internal fun AppleInternalCatalogResolver.canStartLocalizedBatchLocked(): Boolean {
        val nextPriority = localizedPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = localizedBatchesRunning,
            backgroundRunning = localizedBackgroundBatchesRunning,
            maxRunning = MAX_LOCALIZED_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING,
        )
    }


internal fun AppleInternalCatalogResolver.updatePendingRequestPriorities(
        scopedPriorities: Map<String, RequestPriority>,
        onlyMediaIds: Set<String>? = null,
    ): Int {
        var changed = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        // Fallback tasks read their effective priority when a slot is selected.  Rescheduling
        // here also wakes the queue when a completed task left capacity available during a scope
        // update or explicit promotion.
        scheduleLockedIsrcFallbacks()
        return changed
    }


internal fun AppleInternalCatalogResolver.currentScopedPriority(mediaId: String): RequestPriority =
        synchronized(requestPriorityByMediaId) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        }


internal fun AppleInternalCatalogResolver.rememberRequestPriority(mediaId: String, priority: RequestPriority) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty()) return
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive) {
                requestPriorityByMediaId.putIfAbsent(
                    normalizedId,
                    RequestPriority.BACKGROUND,
                )
                return
            }
            requestPriorityByMediaId[normalizedId] = higherPriority(
                requestPriorityByMediaId[normalizedId] ?: RequestPriority.BACKGROUND,
                priority,
            )
        }
    }


internal fun AppleInternalCatalogResolver.currentRequestPriority(
        mediaId: String,
        fallback: RequestPriority,
    ): RequestPriority = synchronized(requestPriorityByMediaId) {
        if (requestScopeActive) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        } else {
            higherPriority(requestPriorityByMediaId[mediaId.trim()] ?: fallback, fallback)
        }
    }


internal fun AppleInternalCatalogResolver.finishLocalizedRequest(
        request: LocalizedRequest,
        resolvedLookupId: String?,
        alias: Alias?,
    ) {
        if (alias != null) {
            synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
            persistentLocalizedCache.put(request.cacheKey, alias)
        }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
                "Apple 播放元数据地区查询完成: id=${request.mediaId}, " +
                "lookupIds=${request.lookupIds}, resolvedBy=$resolvedLookupId, " +
                "entityType=${request.entityType}, " +
                "selection=${request.selection}, storefront=${request.storefront}, " +
                "language=${request.language}, priority=${request.priority}, " +
                "value=${alias?.title}/${alias?.artist}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

