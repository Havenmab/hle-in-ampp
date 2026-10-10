package dev.havenmab.ampp.hle.feature.region

import android.os.Handler
import dev.havenmab.ampp.hle.host.ProviderLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 持久化别名存储：插件本地实现，替代 AM++ 的 SQLite 缓存
 * （`AppleOriginalMetadataCache` / `AppleLocalizedMetadataCache`）。
 *
 * 这是本插件**明确声明的简化**：AM++ 把缓存写进宿主 Context 的
 * `databases/` 与 `SharedPreferences`；插件不允许写宿主命名空间，所以换成
 * 插件 `getDataDirectory()` 下的 JSON 文件。对外 API 与语义
 * （内存 LRU + 持久层、`getFirst` 的兼容键回退、拒绝项清理、维护清空、
 * 歌手地区偏好）与移植前保持一致，只是存储引擎不同。
 */
private class AliasFileStore(private val file: File) {
    private val lock = Any()
    private var loaded = false
    private val entries = LinkedHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val artistRegions = LinkedHashMap<String, String>()
    private val pendingWrites = AtomicInteger(0)

    private fun ensureLoaded() = synchronized(lock) {
        if (loaded) return@synchronized
        loaded = true
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val aliasArray = root.optJSONArray("aliases") ?: JSONArray()
            for (index in 0 until aliasArray.length()) {
                val entry = aliasArray.optJSONObject(index) ?: continue
                val key = entry.optString("key").takeIf { it.isNotEmpty() } ?: continue
                entries[key] = AppleInternalCatalogResolver.Alias(
                    title = entry.optString("title"),
                    artist = entry.optString("artist"),
                    language = entry.optString("language"),
                    album = entry.optString("album"),
                )
            }
            root.optJSONObject("artistRegions")?.let { regions ->
                regions.keys().forEach { key ->
                    artistRegions[key] = regions.optString(key)
                }
            }
        }.onFailure { ProviderLogger.error("HLE 元数据缓存读取失败: ${file.name}", it) }
    }

    fun snapshot(): Map<String, AppleInternalCatalogResolver.Alias> = synchronized(lock) {
        ensureLoaded()
        LinkedHashMap(entries)
    }

    fun get(key: String): AppleInternalCatalogResolver.Alias? = synchronized(lock) {
        ensureLoaded()
        entries[key]
    }

    fun put(key: String, alias: AppleInternalCatalogResolver.Alias) = synchronized(lock) {
        ensureLoaded()
        entries[key] = alias
    }

    fun putAll(aliases: Map<String, AppleInternalCatalogResolver.Alias>) = synchronized(lock) {
        ensureLoaded()
        entries.putAll(aliases)
    }

    fun remove(key: String) = synchronized(lock) {
        ensureLoaded()
        entries.remove(key)
    }

    fun clear(): Int = synchronized(lock) {
        ensureLoaded()
        val size = entries.size
        entries.clear()
        artistRegions.clear()
        size
    }

    fun artistRegion(keys: Collection<String>): String? = synchronized(lock) {
        ensureLoaded()
        keys.firstNotNullOfOrNull { key -> artistRegions[key.trim()] }
    }

    fun rememberArtistRegion(keys: Collection<String>, language: String) = synchronized(lock) {
        ensureLoaded()
        keys.forEach { key -> artistRegions[key.trim()] = language }
    }

    /**
     * 原子落盘（临时文件 + rename）；文件 I/O 失败只记日志，不影响内存结果，
     * 与 AM++ 的 SQLite 写入失败处理一致。
     */
    fun flush() {
        val aliases: List<AppleInternalCatalogResolver.Alias>
        val keys: List<String>
        val regions: Map<String, String>
        synchronized(lock) {
            ensureLoaded()
            keys = entries.keys.toList()
            aliases = keys.map { entries.getValue(it) }
            regions = LinkedHashMap(artistRegions)
        }
        val root = JSONObject()
        val aliasArray = JSONArray()
        keys.forEachIndexed { index, key ->
            val alias = aliases[index]
            aliasArray.put(
                JSONObject()
                    .put("key", key)
                    .put("title", alias.title)
                    .put("artist", alias.artist)
                    .put("language", alias.language)
                    .put("album", alias.album),
            )
        }
        root.put("aliases", aliasArray)
        val regionsJson = JSONObject()
        regions.forEach { (key, value) -> regionsJson.put(key, value) }
        root.put("artistRegions", regionsJson)
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { ProviderLogger.error("HLE 元数据缓存写入失败: ${file.name}", it) }
    }
}

/** 原地区（歌曲/专辑/歌手原名）持久缓存。API 与 AM++ 的 SQLite 版一致。 */
internal class AppleOriginalMetadataCache(
    directory: File,
    private val mainHandler: Handler,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
    private val artistRegionPreferencesName: String = DEFAULT_ARTIST_REGION_PREFERENCES_NAME,
) {
    private val store = AliasFileStore(File(directory, databaseName))
    private val artistStore = AliasFileStore(File(directory, artistRegionPreferencesName))
    private val memoryCache = object : LinkedHashMap<String, AppleInternalCatalogResolver.Alias>(
        256,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AppleInternalCatalogResolver.Alias>?,
        ): Boolean = size > MAX_ENTRIES
    }
    private val interactiveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE metadata cache").apply { isDaemon = true }
    }
    private val warmExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE metadata warm").apply { isDaemon = true }
    }
    private val warmLock = Any()
    private var warmed = false
    private var warming = false
    private val warmCallbacks = mutableListOf<(Int?) -> Unit>()
    private val maintenanceEpoch = AtomicLong(0L)
    private val artistRegionLock = Any()

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * 「清空检索库」维护缝：丢弃内存行、清空歌手地区偏好与持久文件。
     * 原实现还会关闭 SQLite 句柄；文件版直接清空并立刻落盘。
     */
    fun resetForMaintenance(): Int {
        val dropped = synchronized(memoryCache) {
            val size = memoryCache.size
            memoryCache.clear()
            size
        }
        maintenanceEpoch.incrementAndGet()
        synchronized(warmLock) {
            warmed = false
            warming = false
        }
        synchronized(artistRegionLock) {
            runCatching { artistStore.clear() }
                .onFailure { ProviderLogger.error("Apple 原地区歌手地区偏好清理失败", it) }
        }
        runCatching { store.clear() }
            .onFailure { ProviderLogger.error("Apple 原地区元数据缓存清理失败", it) }
        runCatching {
            store.flush()
            artistStore.flush()
        }
        return dropped
    }

    fun get(key: String, onResult: (AppleInternalCatalogResolver.Alias?) -> Unit) {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) {
            onResult(null)
            return
        }
        synchronized(memoryCache) { memoryCache[normalizedKey] }?.let { cached ->
            onResult(cached)
            return
        }
        interactiveExecutor.execute {
            val alias = if (enabled) {
                synchronized(memoryCache) { memoryCache[normalizedKey] }
                    ?: runCatching { store.get(normalizedKey) }
                        .onFailure { ProviderLogger.error("Apple 原地区元数据缓存读取失败", it) }
                        .getOrNull()
                        ?.also { remember(normalizedKey, it) }
            } else {
                null
            }
            mainHandler.post { onResult(alias.takeIf { enabled }) }
        }
    }

    /**
     * 在一批等价缓存键里读取第一个可接受的别名。调用方用它覆盖直接 ID 与兼容键，
     * 因此一次落空不会退化成每个旧键一次 I/O。
     */
    fun getFirst(
        keys: Collection<String>,
        accept: (AppleInternalCatalogResolver.Alias) -> Boolean = { true },
        onResult: (CacheHit?) -> Unit,
    ) {
        val normalizedKeys = keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (!enabled || normalizedKeys.isEmpty()) {
            onResult(null)
            return
        }
        synchronized(memoryCache) {
            validateOriginalCacheEntries(normalizedKeys, memoryCache, accept)
        }.let { cached ->
            cached.rejected.forEach(::discardRejected)
            cached.hit?.let { hit ->
                onResult(hit)
                return
            }
        }
        interactiveExecutor.execute {
            val hit = if (enabled) {
                runCatching { readFirst(normalizedKeys, accept) }
                    .onFailure { ProviderLogger.error("Apple 原地区元数据兼容缓存读取失败", it) }
                    .getOrNull()
            } else {
                null
            }
            hit?.let { remember(it.key, it.alias) }
            mainHandler.post { onResult(hit.takeIf { enabled }) }
        }
    }

    /** 只读内存中已预热/已访问的项，不在当前线程触发磁盘 I/O。 */
    fun cached(key: String): AppleInternalCatalogResolver.Alias? {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) return null
        val result = synchronized(memoryCache) {
            validateOriginalCacheEntries(listOf(normalizedKey), memoryCache) { true }
        }
        result.rejected.forEach(::discardRejected)
        return result.hit?.alias
    }

    fun warmRecentAsync(onResult: (Int?) -> Unit) {
        if (!enabled) {
            onResult(null)
            return
        }
        val startWarm = synchronized(warmLock) {
            when {
                warmed -> {
                    mainHandler.post {
                        onResult(synchronized(memoryCache) { memoryCache.size }.takeIf { enabled })
                    }
                    false
                }
                warming -> {
                    warmCallbacks += onResult
                    false
                }
                else -> {
                    warming = true
                    warmCallbacks += onResult
                    true
                }
            }
        }
        if (!startWarm) return
        val epoch = maintenanceEpoch.get()
        warmExecutor.execute {
            val aliases = if (enabled) {
                runCatching { store.snapshot() }
                    .onFailure { ProviderLogger.error("Apple 原地区元数据缓存预热失败", it) }
                    .getOrNull()
            } else {
                null
            }
            if (aliases != null && maintenanceEpoch.get() == epoch) {
                synchronized(memoryCache) { memoryCache.putAll(aliases) }
            }
            val callbacks = synchronized(warmLock) {
                warmed = aliases != null
                warming = false
                warmCallbacks.toList().also { warmCallbacks.clear() }
            }
            mainHandler.post {
                val count = aliases?.size?.takeIf { enabled }
                callbacks.forEach { callback -> callback(count) }
            }
        }
    }

    fun put(key: String, alias: AppleInternalCatalogResolver.Alias) {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) return
        val canonicalAlias = AppleInternalCatalogResolver.canonicalCachedOriginalAlias(alias)
            ?: return
        remember(normalizedKey, canonicalAlias)
        interactiveExecutor.execute {
            if (!enabled) return@execute
            runCatching {
                store.put(normalizedKey, canonicalAlias)
                store.flush()
            }.onFailure { ProviderLogger.error("Apple 原地区元数据缓存写入失败", it) }
        }
    }

    fun remove(key: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return
        synchronized(memoryCache) { memoryCache.remove(normalizedKey) }
        interactiveExecutor.execute {
            runCatching {
                store.remove(normalizedKey)
                store.flush()
            }.onFailure { ProviderLogger.error("Apple 原地区元数据旧缓存清理失败", it) }
        }
    }

    fun cachedArtistRegion(keys: Collection<String>): String? {
        val normalized = keys.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return null
        return runCatching { artistStore.artistRegion(normalized) }
            .onFailure { ProviderLogger.error("Apple 原地区歌手地区偏好读取失败", it) }
            .getOrNull()
    }

    fun rememberArtistRegion(keys: Collection<String>, language: String) {
        val normalized = keys.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return
        synchronized(artistRegionLock) {
            runCatching {
                artistStore.rememberArtistRegion(normalized, language)
                artistStore.flush()
            }.onFailure { ProviderLogger.error("Apple 原地区歌手地区偏好写入失败", it) }
        }
    }

    private fun remember(key: String, alias: AppleInternalCatalogResolver.Alias) {
        synchronized(memoryCache) { memoryCache[key] = alias }
    }

    private fun readFirst(
        keys: List<String>,
        accept: (AppleInternalCatalogResolver.Alias) -> Boolean,
    ): CacheHit? {
        val stored = HashMap<String, AppleInternalCatalogResolver.Alias>()
        keys.forEach { key -> store.get(key)?.let { stored[key] = it } }
        return validateOriginalCacheEntries(keys, stored, accept).hit
    }

    private fun discardRejected(rejected: CacheHit) {
        // 只删仍然是那个被拒值的项；期间若已被替换，保留新值（与 AM++ 的
        // removeOriginalAliasIfUnchanged 语义一致）。
        val current = synchronized(memoryCache) { memoryCache[rejected.key] }
        if (current != null && current != rejected.alias) return
        synchronized(memoryCache) { memoryCache.remove(rejected.key) }
        store.remove(rejected.key)
    }

    data class CacheHit(
        val key: String,
        val alias: AppleInternalCatalogResolver.Alias,
    )

    internal companion object {
        const val DEFAULT_DATABASE_NAME = "apple_original_metadata.json"
        const val DEFAULT_ARTIST_REGION_PREFERENCES_NAME = "apple_original_artist_regions.json"
        const val MAX_ENTRIES = 4_096

        internal fun currentDatabaseNameForTest(): String = DEFAULT_DATABASE_NAME

        internal fun currentArtistRegionPreferencesNameForTest(): String =
            DEFAULT_ARTIST_REGION_PREFERENCES_NAME
    }
}

/** 地区本地化缓存（固定地区批量查询结果）。 */
internal class AppleLocalizedMetadataCache(
    directory: File,
    private val mainHandler: Handler,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
) {
    private val store = AliasFileStore(File(directory, databaseName))
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE localized cache").apply { isDaemon = true }
    }

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 维护缝：文件版直接清空并落盘（原实现关闭 SQLite 句柄）。 */
    fun closeForMaintenance(): Int {
        val dropped = runCatching { store.clear() }
            .onFailure { ProviderLogger.error("Apple 地区元数据缓存清理失败", it) }
            .getOrDefault(0)
        runCatching { store.flush() }
        return dropped
    }

    fun warmRecentBlocking(prefix: String): Map<String, AppleInternalCatalogResolver.Alias>? {
        if (!enabled) return null
        return runCatching {
            store.snapshot().filterKeys { key -> key.startsWith(prefix) }
        }.onFailure { ProviderLogger.error("Apple 地区元数据缓存预热失败", it) }
            .getOrNull()
    }

    fun warmRecentAsync(
        prefix: String,
        onResult: (Map<String, AppleInternalCatalogResolver.Alias>?) -> Unit,
    ) {
        if (!enabled) {
            onResult(null)
            return
        }
        executor.execute {
            val aliases = warmRecentBlocking(prefix)
            mainHandler.post { onResult(aliases.takeIf { enabled }) }
        }
    }

    fun getMany(
        keys: Collection<String>,
        onResult: (Map<String, AppleInternalCatalogResolver.Alias>) -> Unit,
    ) {
        val normalizedKeys = keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (!enabled || normalizedKeys.isEmpty()) {
            onResult(emptyMap())
            return
        }
        executor.execute {
            val aliases = if (enabled) {
                runCatching {
                    normalizedKeys.mapNotNull { key -> store.get(key)?.let { key to it } }.toMap()
                }.onFailure { ProviderLogger.error("Apple 地区元数据缓存读取失败", it) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            mainHandler.post { onResult(aliases.takeIf { enabled }.orEmpty()) }
        }
    }

    fun put(key: String, alias: AppleInternalCatalogResolver.Alias) {
        putMany(mapOf(key to alias))
    }

    fun putMany(aliases: Map<String, AppleInternalCatalogResolver.Alias>) {
        if (!enabled || aliases.isEmpty()) return
        val normalized = aliases.filterKeys(String::isNotBlank)
        executor.execute {
            if (!enabled) return@execute
            runCatching {
                store.putAll(normalized)
                store.flush()
            }.onFailure { ProviderLogger.error("Apple 地区元数据缓存写入失败", it) }
        }
    }

    internal companion object {
        const val DEFAULT_DATABASE_NAME = "apple_metadata.json"
    }
}
