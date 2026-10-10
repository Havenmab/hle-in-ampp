/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogPersistentAccess.kt
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
internal fun AppleInternalCatalogResolver.warmPersistentOriginalCache() {
        if (!persistentLocalizedCacheEnabled) return
        persistentOriginalCache.warmRecentAsync { count ->
            if (count != null) {
                ProviderLogger.info("Apple 原地区元数据缓存预热完成: entries=$count")
            } else {
                ProviderLogger.info("Apple 原地区元数据缓存预热延后")
            }
        }
    }


internal fun AppleInternalCatalogResolver.warmPersistentLocalizedCache(selection: Int) {
        if (!persistentLocalizedCacheEnabled) return
        if (storefrontForContentUiLanguage(selection) == null) return
        val shouldWarm = synchronized(warmedSelections) {
            if (selection in warmedSelections) false
            else synchronized(warmingSelections) { warmingSelections.add(selection) }
        }
        if (!shouldWarm) return
        val prefix = "$selection:"
        persistentLocalizedCache.warmRecentAsync(prefix) { delayedAliases ->
            if (delayedAliases != null) {
                finishPersistentCacheWarm(selection, delayedAliases)
            } else {
                synchronized(warmingSelections) { warmingSelections.remove(selection) }
                ProviderLogger.info("Apple 地区元数据缓存预热延后: selection=$selection")
            }
        }
    }


internal fun AppleInternalCatalogResolver.finishPersistentCacheWarm(selection: Int, aliases: Map<String, Alias>) {
        val artistAliases = aliases.filterKeys(::isLocalizedArtistAliasCacheKey)
        val metadataAliases = aliases.filterKeys { key ->
            !isLocalizedArtistAliasCacheKey(key)
        }
        synchronized(localizedCache) { localizedCache.putAll(metadataAliases) }
        synchronized(localizedArtistAliasCache) {
            localizedArtistAliasCache.putAll(artistAliases)
        }
        synchronized(warmedSelections) { warmedSelections.add(selection) }
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
        ProviderLogger.info(
            "Apple 地区元数据缓存预热完成: selection=$selection, " +
                "metadata=${metadataAliases.size}, artistAlias=${artistAliases.size}, " +
                "entries=${aliases.size}"
        )
    }


internal fun AppleInternalCatalogResolver.finishLocalizedCacheHit(request: LocalizedRequest, alias: Alias) {
        synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
            "Apple 地区元数据持久缓存命中: id=${request.mediaId}, " +
                "entityType=${request.entityType}, selection=${request.selection}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

