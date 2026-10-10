# 第 2 批接缝清单：in-app 表面桥（歌曲名修正的完整覆盖）

本文件是给下一个代理的**搬迁清单**：不需要重新调研，照着列出的文件、目标点来源与依赖搬即可。

## 1. 本轮（第 1 批）已经落地的部分

已移植并随插件发布的文件（`feature/region/`，单一包）：

| 文件 | 来源（AM++ `integ/all-features`） | 行数 |
|---|---|---|
| `AppleInternalCatalogResolver.kt` | `host-applemusic/.../xposed/AppleInternalCatalogResolver.kt` | 1523 |
| `CatalogOriginalResolution.kt` | `.../xposed/CatalogOriginalResolution.kt` | 620 |
| `NativeCatalogQueryAccess.kt` | `.../xposed/NativeCatalogQueryAccess.kt` | 768 |
| `CatalogResponseSnapshotAccess.kt` | `.../xposed/CatalogResponseSnapshotAccess.kt` | 337 |
| `CatalogRequestScheduling.kt` | `.../xposed/CatalogRequestScheduling.kt` | 552 |
| `CatalogPersistentAccess.kt` | `.../xposed/CatalogPersistentAccess.kt` | 169 |
| `CatalogRegionAccess.kt` | `.../xposed/CatalogRegionAccess.kt` | 181 |
| `AppleCatalogResponseWorkDispatcher.kt` | `.../xposed/AppleCatalogResponseWorkDispatcher.kt` | 153 |
| `AppleContentHttpTimingTracker.kt` | `.../xposed/AppleContentHttpTimingTracker.kt` | 174 |
| `AppleCollaborationArtistCache.kt` | `.../xposed/AppleCollaborationArtistCache.kt` | 27 |
| `AppleOriginalAlbumEquivalence.kt` | `.../xposed/AppleOriginalAlbumEquivalence.kt` | 127 |
| `AppleOriginalCacheValidation.kt` | `.../xposed/AppleOriginalCacheValidation.kt` | 37 |
| `AppleMetadataOverrideStore.kt` | `.../xposed/AppleMetadataOverrideStore.kt` | 358 |
| `AppleMediaApiAttributeSnapshots.kt` | `.../xposed/AppleMediaApiAttributeSnapshots.kt` | 34 |
| `AppleOriginalMetadataPolicy.kt` | `.../common/lyric/AppleOriginalMetadataPolicy.kt` | 76 |
| `MediaMetadataCache.kt` | `.../xposed/MediaMetadataCache.kt` | 174 |
| `AppleContentLocalizationHooks.kt` | `.../xposed/hooks/AppleContentLocalizationHooks.kt` | 1086 |

替换掉的两处 AM++ 依赖：

- 持久缓存：AM++ 的 `AppleOriginalMetadataCache.kt`(530) / `AppleLocalizedMetadataCache.kt`(272) 直接把
  SQLite 写进宿主 `Context` 的 `databases/`，并用宿主 `SharedPreferences` 存歌手地区偏好；插件不允许写宿主
  命名空间，已换成 `AppleMetadataCaches.kt`（插件 `getDataDirectory()` 下的 JSON 文件，API 与语义一致）。
  **第 2 批若要恢复 SQLite，只需替换这两个类，不要改调用方。**
- `AppleMetadataResolutionEngine.kt`(283)：本轮只取 `normalizedAssociatedArtistIds`
  （见 `MetadataResolutionSupport.kt`）；第 2 批搬全时替换该文件即可。

**已落地的应用面**：`feature/title/AppleTitleCorrectionHooks.kt`——MediaApi 元数据 getter 契约
（实体/属性的名称 getter、以及 `media-entity-to-song-converter-method`）。

## 2. 还没落地的部分（第 2 批，唯一剩余的大块）

规模：约 **9.4k 行**（不含 `TargetSymbols.kt` 与 `metadata/AppleMetadataPolicies.kt`）。

### 2.1 桥与适配（必须先搬）

| 文件 | 行数 | 作用 | 目标点来源 |
|---|---|---|---|
| `dev/amenhancer/module/hook/HleMetadataRuntime.kt` | 582 | AM++ 的总装线：创建 resolver / store / 各 hooks 并互相接线。**插件的 `HlePlugin.onLoad` 已按它接线**，第 2 批把新增 hooks 接到同一个方法里 | 无（装配代码） |
| `HleMetadataSurfaceBridge.kt` | 571 | 各表面 hooks ↔ store/coordinator 的桥 | 无 |
| `HleMetadataSurfaceHookInstaller.kt` | 99 | 用 `AppleMusicHookProfiles` 解析并安装表面目标 | 见下 |
| `MetadataHostAdapters.kt` | 1117 | 把 AM++ 的 `TargetSymbols`/DexKit 结果适配成表面 hooks 需要的 host 接口 | 需改成插件 `host/HostTargetResolver` |
| `HleMetadataCacheIndex.kt` | 198 | 「清空检索库」的组合入口（resolver + store + MediaMetadataCache） | 无 |
| `TargetSymbols.kt` | 2536 | **不要整体搬**：它是 AM++ 的宿主符号表，插件已用 `assets/applemusic-1606.json` 取代 | — |
| `AppleMusicHostFactory.kt` / `ApkTargetClassSource.kt` / `TargetBuildLookup.kt` | 162/41/18 | 目标类来源工厂 | 同样由目标表取代 |

**插件替换方案**：`MetadataHostAdapters` 里凡是从 `AppleMusicHookProfiles` / `AppleMusicHookResolver`
取类与成员的地方，一律改成：

```kotlin
runtime.targets.resolveMethods(AppleMusicHookPoint.<点>)      // 方法目标（exact-required）
runtime.targets.resolveClasses(AppleMusicHookPoint.<点>)      // 类目标
target.runtimeMemberNames[AppleMusicRuntimeMember.<成员>]      // 成员名
```

### 2.2 各 in-app 表面（按宿主表面分组）

每个表面在插件里的目标点 id 都已经在 `assets/applemusic-1606.json` 的 `hookTargets` 里
（96 项，全部保留 AM++ 的 `resolutionPolicy=exact-required` / `allowFirstMatch` 语义）。

| 宿主表面 | AM++ 文件 | 行数 | 插件要读的 `hookTargets` id |
|---|---|---|---|
| Listen Now（新发现） | `metadata/AppleListenNowHooks.kt` | 1549 | `LISTEN_NOW_MODEL_BUILDER` `LISTEN_NOW_BOUND_LISTENER` `LISTEN_NOW_MODEL` `LISTEN_NOW_ARTWORK_RESOLVER` `LISTEN_NOW_DELEGATING_ITEM` `LISTEN_NOW_CUSTOM_IMAGE_VIEW` `LISTEN_NOW_MEDIA_ENTITY` `LISTEN_NOW_COLLECTION_ITEM_VIEW` |
| 资料库 / Compose（含 album 页） | `metadata/AppleLibrarySurfaceHooks.kt` | 1263 | `LIBRARY_COMPOSE_VIEW_MODEL_GETTER` `LIBRARY_EPOXY_BUILD` `LIBRARY_COMPOSE_CONTENT` `COMPOSE_OBSERVE_AS_STATE` `COMPOSE_NEVER_EQUAL_POLICY` `LIBRARY_ENTITY_CLASSES` `ALBUM_COMPOSE_RESUME` `ALBUM_COMPOSE_CONTENT` `ALBUM_COMPOSE_VIEW_MODEL_GETTER` `ALBUM_COMPOSE_PAGE_ID` `ALBUM_COMPOSE_CURRENT_DATA` `ALBUM_COMPOSE_TRACK_MAPPER` `ALBUM_COMPOSE_TRACK_COMPARATOR` `ALBUM_COMPOSE_REFRESH` `ALBUM_COMPOSE_HEADER_REFRESH` `ALBUM_COMPOSE_ROW` `ALBUM_COMPOSE_ENTITY_ID` |
| data-binding 文本 | `metadata/AppleDataBindingMetadataHooks.kt` | 1113 | `DATA_BINDING_RUNTIME_CLASSES` |
| 艺人页 | `metadata/AppleArtistSurfaceHooks.kt` | 1040 | `ARTIST_SURFACE_CLASSES` `ARTIST_COMPOSE_ENTITY_TYPE` `ARTIST_COMPOSE_ENTITY_RELATIONSHIPS` `ARTIST_COMPOSE_RELATIONSHIP_ENTITIES` `ARTIST_COMPOSE_FRAGMENT_RESUMED` |
| 合集页（Epoxy） | `metadata/AppleCollectionSurfaceHooks.kt` | 1003 | `COLLECTION_SURFACE_CLASSES` |
| 播放队列 / 历史 | `metadata/AppleQueueMetadataHooks.kt` | 659 | `IN_APP_QUEUE_UPDATE` `IN_APP_HISTORY_UPDATE` `IN_APP_QUEUE_ADAPTER_SUBMIT` `IN_APP_QUEUE_ADAPTER_BIND` |
| 操作表（分享/更多） | `metadata/AppleActionSheetMetadataHooks.kt` | 320 | `IN_APP_ACTION_SHEET_BINDING` |
| 播放页 / MediaSession / 通知 | `metadata/ApplePlaybackMetadataCoordinator.kt` + `ApplePlaybackMetadataHooks.kt` + `hooks/AppleFrameworkMetadataHooks.kt` | 475/116/431 | `IN_APP_GLOBAL_METADATA_DISPATCHER` `IN_APP_NOW_PLAYING_METADATA_LISTENER` `LOCAL_MEDIA_PLAYER_METADATA_UPDATED` `EPOXY_FINAL_BIND` `APPLE_SONG_MODEL_CLASS` `APPLE_PLAYER_UTIL_CLASS` |
| 内容条目 getter | `metadata/AppleContentItemMetadataHooks.kt` | 222 | `CONTENT_ITEM_METADATA_CLASSES` |
| 专辑 Compose 页 | `metadata/AppleAlbumComposeMetadataHooks.kt` + `AppleAlbumComposePageRegistry.kt` | 247/99 | 见「资料库 / Compose」行 |
| 专辑封面延续 | `metadata/AppleInAppArtworkContinuityHooks.kt` | 263 | `APPLE_SONG_MODEL_CLASS` 等 |
| media3 元数据 | `metadata/AppleMedia3MetadataCoordinator.kt` | 156 | `IN_APP_GLOBAL_METADATA_DISPATCHER` |
| 播放项转换 | `metadata/ApplePlaybackItemConversionHooks.kt` | 145 | `metadata-to-playback-item-method`（已在目标表的 `indexed.methodContracts`） |

### 2.3 解析/应用/注册 协调器（表面 hooks 的公共依赖）

| 文件 | 行数 |
|---|---|
| `metadata/AppleInAppMetadataResolutionCoordinator.kt` | 1279 |
| `metadata/AppleInAppMetadataApplier.kt` | 637 |
| `metadata/AppleMediaApiMetadataCoordinator.kt` | 587 |
| `metadata/AppleInAppMetadataRefreshQueue.kt` | 441 |
| `metadata/AppleInAppMetadataRegistrationCoordinator.kt` | 326 |
| `metadata/AppleMetadataOverrideApplicationCoordinator.kt` | 259 |
| `metadata/AppleInAppMetadataRegistry.kt` | 238 |
| `metadata/AppleMetadataSurfaceRuntime.kt` | 197 |
| `metadata/AppleMetadataSurfaceCoordinator.kt` | 182 |
| `metadata/AppleVisibleMetadataDiagnostics.kt` | 177 |
| `metadata/AppleMetadataResolutionEngine.kt` | 283（替换 `MetadataResolutionSupport.kt`） |
| `metadata/AppleMetadataConfigurationDispatcher.kt` | 20 |
| `metadata/NativeMetadataLifecycleContract.kt` | 42 |
| `AppleVisibleMetadataResolutionLeases.kt` | 38 |
| `AppleMetadataCompatibilityModels.kt` | 11 |
| `metadata/AppleMetadataPolicies.kt` | 750（纯策略；含 `visibleTextFieldForContentItemGetter`、`contentItemMetadataOverride`、`localizedVisibleText`、`selectIndependentArtistAlias` 等） |
| `metadata/model/*.kt` | 502（`AppleCollectionSurfaceModels` 211 / `AppleListenNowModels` 88 / `AppleMetadataModels` 80 / `AppleQueueMetadataModels` 78 / `AppleDataBindingModels` 45） |
| `internal/WeakIdentityMap.kt` | 65 |
| `internal/ThreadLocalState.kt` | 51 |

### 2.4 第 2 批的验收建议

1. 目标表已经包含全部目标点，**不需要新增宿主调研**；缺目标就跳过并 `ProviderLogger.info`，与 AM++ 的逐点 fail-open 一致。
2. 每搬一个表面，先在真机上验证该页面的标题/歌手/专辑显示原名；
3. 冲突：AM++ 内置的同名表面 Hook 与本插件会重叠。非独占注册只提示不阻止；如果同开，建议在 AM++ 里关掉 HLE 元数据功能，或让本插件用 `claimResource("shared:in-app-metadata-surface", true)` 与用户沟通。

## 3. 第 2 批明确不做的事

- 不搬 `TargetSymbols.kt` / `AppleMusicDexKitResolver.kt` / `AppleMusicHookProfiles.kt` / `AppleMusicHookContract.kt`：
  这四份（共约 4800 行）是 AM++ 的宿主符号解析与契约校验层，插件已用目标表 + `HostTargetResolver` 取代。
- 不搬 `AppleMusicCustomLyricsTarget.kt`、`AppleMusicDualPaneTarget.kt`、`AppleNativeLyricModelHooks.kt` 等：
  属于第 3 批（歌词）或插件 A（平板顶栏）。
