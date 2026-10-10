# HLE 移植（地区/歌名/歌词）—— 独立 AM++ 插件

把 HLE（`hyperlyricsenhanced`）在 AM++ `integ/all-features`(1.6.4) 里的
**地区替换 + 中日韩歌曲原名修正**移植为一个**独立 AM++ 插件**；歌词翻译/音译补全留作第 3 批接缝。

- 插件 id：`dev.havenmab.ampp.hle`
- 入口类：`dev.havenmab.ampp.hle.HlePlugin`
- SDK/格式：`apiVersion = 1`、`formatVersion = 1`、`minAndroidApi = 26`
- 已核验宿主：**Apple Music 7.0.0-beta / versionCode 1606**（目标表取自 AM++
  `host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1606.json`）

---

## 1. 本轮已落地（第 1 批）

### 1.1 地区替换（全保真移植）

四条缝，全部通过 SDK `context.getHooks().hook(...)` 注册：

| 缝 | 作用 | 移植自 |
|---|---|---|
| MediaApi 本地化参数 | 改写 `MediaApi` 的参数表 `l`（语言）；resolver token 请求优先 | `AppleContentLocalizationHooks.installMediaApiLocalization` |
| 目录直连执行器 | 改写 `/v1/catalog/{storefront}/` 的 storefront 参数（arg3）；读写查询表第一个 Map 参数里的 token | `installCatalogRequestLocalization` + `AppleCatalogExecutorArgs` |
| content-http | URL path storefront、`l` 查询参数、`Accept-Language` / `X-Apple-Store-Front` / `X-Apple-Request-Store-Front` 头 | `installContentHttpLocalization` |
| amp-api 网络拦截 | 覆盖不经 repository executor 的浏览/编辑页请求 | `installAmpApiHttpLocalization` |

保真要点：

- **账号 storefront 捕获与回退**：广播/电台/歌词等权益绑定路径始终回退到账号 storefront；
  `applied != current` 时不会被错误回写（`shouldCaptureAccountStorefront` /
  `shouldRestoreModuleLookupStorefront`）。
- **每请求 token 判别**：`hle_catalog_request`（token）与 `hle_catalog_module`（执行器标记）从不出网；
  五态决策 `SKIP_MARKED` / `FAIL_OPEN_UNRESOLVED_TOKEN` / `PASS` / `REWRITE_MODULE` / `REWRITE_REGION` 逐行保留。
- **storefront / language / header 策略**：`storefrontForContentUiLanguage`、
  `languageTagsForContentUiLanguage`、`localizedStorefrontHeaderValue`（`X-Apple-Store-Front`
  数字映射，如 `jp -> 143462`）、`storefrontFromContentPath`、`isAccountScopedPlaybackPath` 全部照搬。
- **诊断**：`AppleCatalogSeam`（有界 300 条）、`AppleCatalogExecutor`（有界 200 条）、
  `AppleContentHttpTiming`（`BuildConfig.DEBUG` 通道）逐行保留。

### 1.2 歌曲名修正（解析引擎保真 + MediaApi 元数据缝落地）

- **解析引擎**：`AppleInternalCatalogResolver`(1523) + `CatalogOriginalResolution`(620) +
  `NativeCatalogQueryAccess`(768) + `CatalogResponseSnapshotAccess`(337) +
  `CatalogRequestScheduling`(552) 全部移植，包括：
  - 原地区语言/ISRC/流派推断 → storefront 映射（`languageTagsForGenre` / `languageTagsForIsrc` /
    `canonicalOriginalLanguage` / `storefrontForOriginalLanguage`）；
  - 直接 ID + 兼容 ID + 等价专辑的多级别名选择（`selectExactOriginalEntityAlias` /
    `selectOriginalAlias` / `selectExactIdentityAlias` / 置信度与协作署名拒绝）；
  - 批次队列、优先级（`VISIBLE`/`ACTIVE_PAGE`/`BACKGROUND`）、in-flight 去重、
    locked-ISRC 回退、维护清空。
- **落地缝**：`feature/title/AppleTitleCorrectionHooks.kt`——asset 表 `indexed.methodContracts` 里的
  MediaApi getter 契约：`media-entity-get-title-method`、`media-entity-get-short-name-method`、
  `song-get-short-name-method`、`attributes-get-name/artist-name/album-name/short-name`、
  `media-entity-get-attributes-method`、`media-entity-to-song-converter-method`。

**明确未落地**：Listen Now / 资料库 / data-binding / 艺人 / 合集 / 队列 / 操作表 / 播放页 MediaSession /
通知等 **in-app 表面桥（约 9.4k 行）**，见下一节。

### 1.3 第 2 批 / 第 3 批接缝

| 接缝 | 文件 |
|---|---|
| in-app 表面桥（第 2 批，歌名修正的完整覆盖） | `src/main/kotlin/dev/havenmab/ampp/hle/feature/title/README.md` |
| 歌词翻译/音译补全（第 3 批） | `src/main/kotlin/dev/havenmab/ampp/hle/feature/lyrics/README.md` |

两份都写明了：文件清单 + 行数、插件要读的目标点 id、依赖、诊断词表、以及各自需要自己解决的问题。

---

## 2. 明确未落地（诚实清单）

### 2.1 第 2 批：in-app 表面桥（约 **9.4k 行**，唯一剩余的大块）

AM++ 的原名修正最终要写进 Apple Music 的各个界面。插件本轮只做到 **MediaApi 元数据 getter**
这一层，以下表面**没有**接入：

| 表面 | AM++ 文件 | 行数 |
|---|---|---|
| Listen Now | `metadata/AppleListenNowHooks.kt` | 1549 |
| 资料库 / Compose（含 album 页） | `metadata/AppleLibrarySurfaceHooks.kt` | 1263 |
| data-binding 文本 | `metadata/AppleDataBindingMetadataHooks.kt` | 1113 |
| 艺人页 | `metadata/AppleArtistSurfaceHooks.kt` | 1040 |
| 合集页 | `metadata/AppleCollectionSurfaceHooks.kt` | 1003 |
| 队列 / 历史 | `metadata/AppleQueueMetadataHooks.kt` | 659 |
| 操作表 | `metadata/AppleActionSheetMetadataHooks.kt` | 320 |
| 播放页 / MediaSession / 通知 | `ApplePlaybackMetadataCoordinator/Hooks` + `hooks/AppleFrameworkMetadataHooks.kt` | 475+116+431 |
| 内容条目 getter | `metadata/AppleContentItemMetadataHooks.kt` | 222 |
| 专辑 Compose 页 | `metadata/AppleAlbumComposeMetadataHooks.kt` + `AppleAlbumComposePageRegistry.kt` | 247+99 |
| 封面延续 / media3 / 播放项转换 | `AppleInAppArtworkContinuityHooks` / `AppleMedia3MetadataCoordinator` / `ApplePlaybackItemConversionHooks` | 263+156+145 |
| 解析/应用/注册协调器 | `AppleInAppMetadataResolutionCoordinator`(1279) `AppleInAppMetadataApplier`(637) `AppleMediaApiMetadataCoordinator`(587) `AppleInAppMetadataRefreshQueue`(441) `AppleInAppMetadataRegistrationCoordinator`(326) `AppleMetadataOverrideApplicationCoordinator`(259) `AppleInAppMetadataRegistry`(238) `AppleMetadataSurfaceRuntime`(197) `AppleMetadataSurfaceCoordinator`(182) `AppleVisibleMetadataDiagnostics`(177) `AppleMetadataResolutionEngine`(283) `metadata/AppleMetadataPolicies.kt`(750) `metadata/model/*`(502) | ≈ 5.8k |
| 桥与适配 | `HleMetadataSurfaceBridge`(571) `HleMetadataSurfaceHookInstaller`(99) `MetadataHostAdapters`(1117) `HleMetadataCacheIndex`(198) | ≈ 2.0k |

未搬的原因：这是一整层宿主 UI 适配，且 AM++ 用 `TargetSymbols.kt`(2536) 与 DexKit 定位；
插件已用目标表（96 个 `hookTargets` + 41 个方法契约）取代符号层，但把 5.8k 行的协调器全部重接到
目标表需要单独一轮。**目标表已经包含这些表面需要的全部目标点**，下一轮不需要重新做宿主调研。

### 2.2 第 3 批：歌词翻译 / 音译补全（约 **15k 行**）

完全未实现，只留接缝与诊断词表。详见 `feature/lyrics/README.md`。

### 2.3 本轮明确**不适用的** AM++ 组件（不是遗漏，是插件边界）

- `TargetSymbols.kt`(2536)、`AppleMusicDexKitResolver.kt`(960)、`AppleMusicHookProfiles.kt`(997)、
  `AppleMusicHookContract.kt`(325)、`AppleMusicDexKitWatchdog.kt`(132)：AM++ 的宿主符号解析层 →
  插件用 `assets/applemusic-1606.json` + `host/HostTargetResolver` 取代。
- `ModernXposedRuntime`、AM++ 模块 schema/设置、`:host-applemusic` 类型、AM++ 资源字符串：
  **一律没有引用**，等价物在 `host/`（Hook 注册、反射、日志）与 `ui/`（View 设置页）里重新实现。

---

## 3. 简化（与 AM++ 不一致的地方，逐条说明）

1. **持久缓存引擎**：AM++ 的 `AppleOriginalMetadataCache`(530) / `AppleLocalizedMetadataCache`(272)
   把 SQLite 写进宿主 `Context` 的 `databases/`，歌手地区偏好写宿主 `SharedPreferences`。
   插件**不允许**写宿主命名空间，改为 `AppleMetadataCaches.kt`：插件 `getDataDirectory()` 下的
   JSON 文件（`apple_original_metadata_<namespace>.json` /
   `apple_original_artist_regions_<namespace>.json` / `apple_metadata_<namespace>.json`）。
   API 与语义保持一致（内存 LRU、`getFirst` 兼容键回退、拒绝项清理、维护清空、预热、epoch 防回灌）。
   *影响*：跨进程/跨版本的缓存读写在文件粒度上（原子 rename），性能特征与 SQLite 不同，行为等价。
2. **设置键**：AM++ 用模块 schema（`region_selection`、`override_account_language`、
   `restore_cjk_original_metadata`、`localized_metadata_cache` 等）与宿主偏好。插件改用自己的
   `getDataDirectory()/hle-settings.properties`，键名沿用 HLE 语义命名（见 `ui/HleSettings.kt`）。
   **不会**读写 AM++ 的配置，因此不会与 AM++ 的 HLE 设置互相同步。
3. **诊断开关**：AM++ 的 `BuildConfig.DEBUG` 门控诊断分支。插件里 `HleBuild.DEBUG = false`
   （发布版），`[debug]` 通道另由设置页「详细诊断」开关控制（`ProviderLogger.diagnosticEnabled`）。
   *影响*：`content-http` 计时、`AppleContentHttpHeaders` 等只走 `diagnostic` 通道的日志默认不输出；
   可见通道的 `AppleCatalogSeam` / `AppleCatalogExecutor` 决策行照常输出（与 AM++ 一致）。
4. **目标解析层**：AM++ 的精确目标来自 `AppleMusicHookProfiles` + DexKit 结构回退。插件**只有**
   目标表里的精确目标；目标缺失时该缝不安装并记一条 `info`，不做全 DEX 扫描（这是刻意行为，
   避免冷启动扫描，也与 AM++ 对 6.5.1/6.5.2 的处理一致）。
5. **冲突语义**：插件所有 Hook 以 `exclusive = false` 注册。若 AM++ 内置的 HLE 元数据功能同时开启，
   两者会同时生效（宿主会给潜在冲突提示）。建议只开一边。
6. **`AppleMetadataResolutionEngine` / `ConfiguredMetadataRetryPolicy` / `AccountMetadata`**：
   本轮只取 `AppleMetadataOverrideStore` 实际使用的成员（见 `MetadataResolutionSupport.kt`），
   其余属于第 2 批。

---

## 4. 已知问题

### 4.1 Apple 官方音译首播不上屏（本轮不修复）

**现象**：Apple 官方的发音/音译歌词在**第一次**播放这首歌时不显示；把 App 切到后台再切回来
（或第二次进入播放页）才出现。

**与本轮的关系**：这是歌词模型写入与展示刷新之间的时序问题，属于第 3 批歌词范围。插件**保留**
了下一批复现/修复所需的全部诊断词汇：

| 词汇 | 含义 |
|---|---|
| `native-write` | 写入原生歌词模型（行/词/翻译/音译） |
| `presentation-refresh` | 写入后触发展示层刷新 |
| `pronunciation-words` | 读取/展开原生音译词表与语言列表 |
| `render-probe` | 渲染探针：文本是否真的进入渲染路径 |

词汇表与接缝的对应关系写在 `feature/lyrics/README.md` 第 0 节。**本轮不修复，如实标注。**

### 4.2 其他

- 歌曲名修正目前只覆盖 MediaApi 元数据读取；在 Listen Now / 资料库 / 队列等 in-app 表面**看不到**
  原名修正（第 2 批）。
- 地区替换依赖宿主的 `MediaApi` 字段与三条 HTTP 缝；如果未来 Apple Music 改了这些目标，
  需要重新导出目标表（插件会抛 `PluginUnsupportedException` 并给出清晰文案）。

---

## 5. 构建

前置：JDK 17、Android SDK（platform 37 / build-tools 37.0.0）、Gradle Wrapper（随工程）。

```bash
# 1) 从 AM++ 源码导出 SDK（编译期依赖，不打进插件）
source /home/havenmab/DSH-Workspace/applemusic/.toolchain/env.sh
cd /home/havenmab/DSH-Workspace/applemusic/forkmain
bash gradlew :plugin-api:exportSdk --no-daemon
# 产出 plugin-api/build/sdk/ampp-plugin-api-v1.jar

# 2) 放进插件工程（本仓库不提交 lib/*.jar）
cp plugin-api/build/sdk/ampp-plugin-api-v1.jar \
   /home/havenmab/DSH-Workspace/applemusic/plugin-hle/lib/

# 3) 构建插件 ZIP
cd /home/havenmab/DSH-Workspace/applemusic/plugin-hle
bash gradlew clean pluginZip --no-daemon
# 产出 build/dist/hle-in-ampp.zip
```

可选参数：`-PandroidSdk=/abs/path/to/android-sdk`、`-PamppSdk=/abs/path/to/ampp-plugin-api-v1.jar`。

打包链：Kotlin 编译 → JAR → **D8**（`--min-api 26`，`--lib android.jar`，SDK 为 `--classpath`）
→ `code.jar`（含 `classes.dex`）→ ZIP（`plugin.json` + `code.jar` + `assets/`）。

**依赖说明**：Kotlin 工程默认带 `kotlin-stdlib`（`implementation`），构建时把它作为 **D8 的程序输入**
一起打进 `code.jar`（宿主不保证提供 Kotlin 运行时）。SDK 与 `android.jar` 仅 `compileOnly`，
绝不进入 `code.jar`。没有其他第三方依赖。

## 6. 安装

1. 打开 Apple Music → 设置 → AM++ 模块设置 → 插件 → 导入 ZIP；
2. 确认名称为「HLE 移植（地区/歌名/歌词）」、作者 Havenmab；
3. 新导入的插件默认关闭，打开「下次启动启用」；
4. **完全停止并重开 Apple Music**（Hook 在 `onLoad` 阶段登记）；
5. 在插件设置里选择地区/开关，再次重启 Apple Music 生效。

日志：`adb logcat -s AppleMusicEnhancer`，插件日志带 `[HLE]` 前缀。

## 7. 支持范围与版本门禁

- 目标表只含 **7.0.0-beta / versionCode 1606** 一份；
- `onLoad` 里比对宿主包名、`versionName`、`versionCode`；
- 不匹配时抛 `PluginUnsupportedException`，文案指明当前版本、目标表版本、以及「请更新插件或重新导出目标表」。

目标表转换语义（`host/HostTargets.kt` + `assets/applemusic-1606.json`）：

- 保留 AM++ 档案的**约束语义**：`resolutionPolicy = exact-required`、`allowFirstMatch`、
  `includeSynthetic`、完整参数类型/返回类型/static；
- **拒绝歧义**：`allowFirstMatch = false` 且多候选 → 硬失败（不取第一个同名方法），
  与 AM++ 的 `ambiguityPolicy = reject-ambiguous` 一致；
- 从 `host-applemusic/.../7.0.0-beta-1606.json`(3562 行 / 127 KB) 转换而来：保留
  `hookTargets`(96) / `indexed.classes`(23) / `indexed.methodContracts`(41) / `indexed.fields` /
  `indexed.fieldContracts` / `verification` / `capabilities` / `catalogQueryRenames`；
- 插件追加 `region` 段，声明本轮读取的目标点与契约 id。

## 8. 目录结构

```
plugin-hle/
├── settings.gradle.kts / build.gradle.kts     Kotlin JVM + D8 dex + pluginZip
├── plugin.json                                SDK/格式 v1，entryClass=dev.havenmab.ampp.hle.HlePlugin
├── lib/ampp-plugin-api-v1.jar                 （不入库，构建前导出）
├── assets/applemusic-1606.json                宿主目标表（转换自 AM++ 1606 档案，122 KB）
├── README.md
└── src/main/kotlin/dev/havenmab/ampp/hle/
    ├── HlePlugin.kt                           入口（AmppPlugin）
    ├── host/                                  宿主适配层：目标表加载/解析、目标解析、Hook 桥、反射、日志
    ├── feature/region/                        地区替换 + 原名解析引擎（第 1 批）
    ├── feature/title/                         歌名修正落地缝 + 第 2 批接缝清单(README)
    ├── feature/lyrics/                        第 3 批接缝清单(README)
    └── ui/                                    HleSettings + HleSettingsScreen（普通 View 设置页）
```
