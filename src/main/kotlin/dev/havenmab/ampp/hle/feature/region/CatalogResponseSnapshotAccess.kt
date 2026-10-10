/*
 * 移植自 AM++ integ/all-features: host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogResponseSnapshotAccess.kt
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
internal fun AppleInternalCatalogResolver.snapshotCatalogResponse(
        response: Any,
        entityType: LocalizedEntityType,
    ): CatalogResponseSnapshot {
        val data = AppleReflection.call(
            response,
            catalogMember(AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD),
        )
        return CatalogResponseSnapshot(
            entities = collectionValues(data).mapNotNull { entity ->
                snapshotCatalogEntity(entity, entityType)
            }.toList(),
        )
    }


internal fun AppleInternalCatalogResolver.snapshotCatalogEntity(
        entity: Any,
        entityType: LocalizedEntityType,
    ): CatalogEntitySnapshot? {
        val id = (AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
        ) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val attributes = AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
        ) ?: return null
        val rawAttributes = AppleMediaApiAttributeSnapshots.get(attributes)
        val title = if (rawAttributes != null) {
            rawAttributes.name?.trim().orEmpty()
        } else {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }
        val attributeArtist = if (rawAttributes != null) {
            rawAttributes.artistName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val albumName = if (entityType == LocalizedEntityType.ARTIST) {
            ""
        } else if (rawAttributes != null) {
            rawAttributes.albumName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val relationshipArtists = if (entityType == LocalizedEntityType.ARTIST) {
            emptyList()
        } else runCatching {
            @Suppress("UNCHECKED_CAST")
            val relationships = AppleReflection.call(
                entity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD),
            ) as? Map<String, Any?>
            val artistRelationship = relationships?.get("artists")
                ?: relationships?.get("artist")
            val artistEntities = collectionValues(
                artistRelationship?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(
                            AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD,
                        ),
                    ) ?: AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD),
                    )
                },
            )
            artistEntities.mapNotNull { artistEntity ->
                val artistAttributes = AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
                )
                val rawArtistAttributes = artistAttributes?.let(
                    AppleMediaApiAttributeSnapshots::get,
                )
                val artistName = if (rawArtistAttributes != null) {
                    rawArtistAttributes.name
                } else artistAttributes?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
                    ) as? String
                }
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val artistId = (AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                if (artistName == null && artistId == null) null
                else CatalogArtistSnapshot(id = artistId, name = artistName)
            }
        }.getOrDefault(emptyList())
        val isrc = if (entityType == LocalizedEntityType.SONG) {
            runCatching {
                (AppleReflection.call(
                    attributes,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }.getOrNull()
        } else {
            null
        }
        val genres = if (entityType != LocalizedEntityType.ARTIST) {
            runCatching {
                collectionValues(
                    AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD),
                    ),
                ).mapNotNull { value ->
                    value.toString().trim().takeIf(String::isNotEmpty)
                }
            }.getOrDefault(emptyList()).ifEmpty {
                runCatching {
                    (AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD),
                    ) as? String)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::listOf)
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        val artistPresent = when (entityType) {
            LocalizedEntityType.ARTIST -> title.isNotEmpty()
            else -> attributeArtist.isNotEmpty() || relationshipArtists.isNotEmpty()
        }
        if (title.isEmpty() && !artistPresent && isrc == null) return null
        return CatalogEntitySnapshot(
            id = id,
            title = title,
            attributeArtist = attributeArtist,
            albumName = albumName,
            isrc = isrc,
            genres = genres.toList(),
            relationshipArtists = relationshipArtists.toList(),
        )
    }


internal fun AppleInternalCatalogResolver.parseCatalogSong(response: CatalogResponseSnapshot, language: String): CatalogSong? =
        parseCatalogSongs(response, language).firstOrNull()


internal fun AppleInternalCatalogResolver.parseCatalogSongs(
        response: CatalogResponseSnapshot,
        language: String,
    ): List<CatalogSong> = parseCatalogEntities(response, language, LocalizedEntityType.SONG)


internal fun AppleInternalCatalogResolver.parseCatalogEntities(
        response: CatalogResponseSnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): List<CatalogSong> = response.entities.mapNotNull { entity ->
        parseCatalogEntity(entity, language, entityType)
    }

    /** Pure conversion from the immutable host snapshot; safe to run off the main thread. */

internal fun AppleInternalCatalogResolver.parseCatalogEntity(
        entity: CatalogEntitySnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): CatalogSong? {
        val album = when (entityType) {
            LocalizedEntityType.SONG -> entity.albumName
            LocalizedEntityType.ALBUM -> entity.title
            LocalizedEntityType.ARTIST -> ""
        }
        val relationshipArtists = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::name)
        val relationshipArtistIds = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::id)
            .distinct()
        val artist = when (entityType) {
            LocalizedEntityType.ARTIST -> entity.title
            else -> selectLocalizedArtistName(
                attributeArtist = entity.attributeArtist,
                relationshipArtists = relationshipArtists,
                language = language,
            )
        }
        if (relationshipArtists.isNotEmpty() && artist != entity.attributeArtist) {
            ProviderLogger.info(
                "Apple 歌手关系名称已优先: attributes=${entity.attributeArtist}, relationship=$artist",
            )
        }
        val isrc = entity.isrc.takeIf { entityType == LocalizedEntityType.SONG }
        if (entity.title.isEmpty() && artist.isEmpty() && isrc == null) return null
        return CatalogSong(
            id = entity.id,
            alias = Alias(entity.title, artist, language, album),
            isrc = isrc,
            genres = if (entityType == LocalizedEntityType.ARTIST) emptyList() else entity.genres,
            artistIds = if (entityType == LocalizedEntityType.ARTIST) {
                emptyList()
            } else {
                relationshipArtistIds
            },
        )
    }


internal fun AppleInternalCatalogResolver.collectionValues(value: Any?): List<Any> = when (value) {
        is Array<*> -> value.filterNotNull()
        is Iterable<*> -> value.filterNotNull()
        is Map<*, *> -> value.values.filterNotNull()
        else -> emptyList()
    }

