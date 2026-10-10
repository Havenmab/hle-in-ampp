# 第 3 批接缝清单：歌词翻译 / 音译补全

本文件是给下一个代理的**搬迁清单**：文件、依赖、诊断词表、已知问题，照着搬即可，不需要重新调研。
本轮（第 1 批）**只留缝，不实现**。

## 0. 已知问题（本轮不修，必须如实保留）

**Apple 官方音译在首次播放时不上屏**：同一首歌第二次进入播放页（或把 App 切到后台再切回来）才显示。
根因方向与需要保留的证据：

- 原生歌词模型写入（`native-write`）发生在首播之前/之后；
- 歌词展示层的刷新（`presentation-refresh`）没有跟随这次写入；
- 音译词表读取（`pronunciation-words`）在首播时可能拿到空表；
- 渲染探针（`render-probe`）用于确认文本最终是否进入 RecyclerView/原生渲染路径。

下一批要做的就是在 `feature/lyrics/` 里落地这四个诊断词表并复现/修复，**不要**在本轮试图修复。

诊断词表（AM++ 里对应 `ProviderLogger.diagnostic` 的 `event=...` 前缀，插件里用
`dev.havenmab.ampp.hle.host.ProviderLogger.diagnostic`）：

| 词汇 | 含义 | AM++ 来源 |
|---|---|---|
| `native-write` | 写入原生歌词模型（line/word/translation/pronunciation） | `AppleNativeLyricModelHooks.kt` |
| `presentation-refresh` | 写入后触发展示层刷新（RecyclerView notify / view model 重载） | `AppleLyricsPresentationRebind.kt`、`LyricsItemUpdateCoordinator.kt` |
| `pronunciation-words` | 读取/展开原生音译词表与语言列表 | `ApplePronunciationPolicy.kt`、`ApplePronunciationVisibilityPolicy.kt` |
| `render-probe` | 渲染探针：确认文本是否真的进入渲染路径 | `LyricsRenderProbe.kt` |

## 1. core 侧（纯逻辑，可直接搬；来源 `core/src/main/kotlin/dev/amenhancer/module/...`）

### 1.1 在线翻译/音译主链

| 文件 | 行数 | 说明 |
|---|---|---|
| `lyrics/online/OnlineTranslationEnrichment.kt` | 276 | 翻译/音译富化入口 |
| `lyrics/online/OnlineTranslationMatcher.kt` | 1122 | 在线行匹配 |
| `lyrics/online/LyricMatchPolicy.kt` | 427 | 匹配策略 |
| `lyrics/online/OnlineLyricSourcePolicy.kt` | 74 | 源优先级（插件必须自己协调：SDK 没有统一歌词源接口） |
| `lyrics/online/OnlineTranslationSelector.kt` | 68 | 语言选择 |
| `lyrics/online/OnlineEnrichmentPolicy.kt` | 55 | 是否需要富化 |
| `lyrics/online/ChineseLyricsPolicy.kt` | 117 | 「不显示国语歌拼音」策略 |
| `lyrics/online/RomanizationPolicy.kt` | 90 | 罗马化语言展开 |
| `lyrics/online/OnlineTranslationMatchStats.kt` / `OnlineTranslationExtraction.kt` / `OnlineTranslationDiagnostics.kt` / `OnlineMatchDiagnostics.kt` | 54/47/100/65 | 统计与诊断 |
| `lyrics/online/TranslationModels.kt` / `LyricsModels.kt` / `LyricMetadataSearchOrder.kt` | 104/58/45 | 模型 |

### 1.2 TTML 读写与注入

| 文件 | 行数 | 说明 |
|---|---|---|
| `lyrics/online/AppleLyricTtmlWriter.kt` | 264 | 写 TTML（翻译/音译 lane） |
| `lyrics/online/AppleLyricTtmlReader.kt` | 152 | 读 TTML |
| `lyrics/online/AppleLyricTtmlLaneInjector.kt` | 91 | lane 注入 |
| `lyrics/AmllTtmlFormatConverter.kt` | 655 | AMLL → TTML |
| `lyrics/TtmlInputPolicy.kt` | 43 | 输入判定 |
| `lyrics/online/QrcParser.kt` / `LrcParser.kt` | 460/109 | 在线源格式解析 |

### 1.3 音译

| 文件 | 行数 |
|---|---|
| `lyrics/online/ApplePronunciationPolicy.kt` | 145 |
| `lyrics/online/ApplePronunciationVisibilityPolicy.kt` | 92 |
| 依赖：`AppleMetadataPolicies.expandAppleLyricsPronunciationLanguages` / `expandAppleLyricsTranslationLanguages` / `selectAppleLyricsTranslationLanguage`（在 AM++ 的 `metadata/AppleMetadataPolicies.kt`，第 665-745 行附近；第 2 批搬该文件时一并带来） | — |

### 1.4 原生 overlay 与自定义歌词

| 文件 | 行数 | 说明 |
|---|---|---|
| `lyrics/online/NativeLyricOverlayStore.kt` | 483 | 原生歌词 overlay 存储 |
| `lyrics/CustomLyricsUpdateCoordinator.kt` | 485 | 自定义歌词更新协调 |
| `lyrics/CustomLyricsUpdateTransaction.kt` | 302 | 事务 |
| `lyrics/CustomLyricsBackupCodec.kt` | 245 | 备份编解码（插件要自己放 `getDataDirectory()`，不要进 AM++ 的歌词备份） |
| `lyrics/CustomLyricsImportTransaction.kt` / `CustomLyricsRestoreTransaction.kt` / `CustomLyricsOnlineImporter.kt` / `CustomLyricsFilePolicy.kt` / `CustomLyricsFileReader.kt` | 178/142/58/55/16 | 导入/恢复/文件 |
| `lyrics/CustomLyricsClearTransaction.kt` | 98 | 「清空自定义歌词」事务 |
| `hook/CustomLyricsCompletionFeed.kt` | 195 | 补全数据源 |
| `hook/AutoLyricsReplacementSession.kt` / `AutoLyricsRefreshSession.kt` / `AutoLyricsTimingPolicy.kt` | 551/65/44 | 自动替换/刷新会话 |
| `hook/CustomLyricsReplacementSession.kt` | 433 | 自定义替换会话 |

### 1.5 在线源客户端（网络）

| 文件 | 行数 | 源 |
|---|---|---|
| `lyrics/online/NeSource.kt` | 492 | 网易云 |
| `lyrics/online/KugouSource.kt` | 401 | 酷狗 |
| `lyrics/online/KuwoSource.kt` | 318 | 酷我 |
| `lyrics/online/QmSource.kt` | 177 | QQ 音乐 |
| `lyrics/source/LunabeatClient.kt` | 296 | lunabeat |
| `lyrics/source/OnlineLyricClients.kt` | 229 | 客户端集合 |
| `lyrics/source/OnlineLyricHttpClient.kt` | 173 | HTTP 层 |
| `lyrics/online/SearchLyricsSource.kt` | 23 | 搜索源接口 |
| `hook/CompositeOnlineSearchAutoLyricsSource.kt`（host 侧） | 949 | 组合搜索源 |

**需要**：网络权限由宿主提供（插件进程内运行），但**线程/连接生命周期要自己管**，并登记
`context.onClose` 关闭（见 SDK 指南 §7.4）。快速切歌要取消过期请求。

### 1.6 渲染探针

| 文件 | 行数 |
|---|---|
| `lyrics/online/LyricsRenderProbe.kt` | 120 |

## 2. host 侧（需要在插件里重新适配到目标表）

| 文件 | 行数 | 插件要读的目标点 id（已在 `assets/applemusic-1606.json`） |
|---|---|---|
| `hook/AppleNativeLyricModelHooks.kt` | 3062 | `LYRICS_NATIVE_PRESENTATION` `LYRICS_VIEW_MODEL_LOAD` `LYRICS_VIEW_MODEL_BUILD` `LYRICS_RESULT_PRESENTATION` `LYRICS_RECYCLER_ADAPTER` `LYRICS_WORD_RENDER_ADAPTER` `LYRICS_TTML_PARSER` `LYRICS_PREFERRED_LANGUAGES_REQUEST` `LYRICS_OFFICIAL_PRONUNCIATION_MATCH` `LYRICS_AVAILABILITY_HAS_LYRICS` `LYRICS_AVAILABILITY_TIME_SYNCED` `LYRICS_GRADIENT_MASK_UPDATE` `LYRICS_WORD_VECTOR_CLASS` + `indexed.classes` 的 `SONG_INFO_PTR` `SONG_INFO_NATIVE` `LYRICS_LINE_VECTOR` `TTML_PARSER_NATIVE` `LYRICS_EVENT_PROCESSOR` `LYRICS_HIGHLIGHT_CALLBACK_OWNER` `LYRICS_CURRENT_ITEM_FIELD` `CJK_KARAOKE_ANIMATION_OWNER` |
| `hook/LyricsItemUpdateCoordinator.kt` | 241 | `lyrics-item-update-method`（契约） |
| `hook/LyricsTypefaceSession.kt` + `LyricsTypefaceLoading.kt` + `AppleMusicLyricsTypefaceTarget.kt` | 442/82/79 | `LYRICS_UI_ON_CREATE_VIEW` `LYRICS_UI_ON_RESUME` `LYRICS_UI_ON_DESTROY_VIEW` |
| `hook/AppleLyricsPresentationRebind.kt` | 157 | `LYRICS_RESULT_PRESENTATION` |
| `hook/AppleLyricsPreferenceReader.kt` + `SharedPreferencesNeSessionStore.kt` | 124/92 | `APPLE_SHARED_PREFERENCES_CLASS`（注意：这是宿主偏好；插件只读，不写） |
| `hook/AppleMusicNativeLyricsTarget.kt` | 89 | `LYRICS_SOURCE_MENU_CLICK_LISTENER`（歌词源菜单，注意 `AppleMusicHookContracts.LYRICS_SOURCE_MENU_CLICK_LISTENER` 的契约语义） |
| `hook/TtmlNativeParser.kt` / `OpaqueTtmlParser.kt` / `NativeTtmlParseOrigin.kt` | 123/19/32 | `LYRICS_TTML_PARSER` |
| `hook/AppleMusicCustomLyricsTarget.kt` | 721 | `PLAYER_SONG_BINDING_EXECUTE` `PLAYER_LYRICS_AVAILABILITY_CALCULATOR` |
| `hook/AppleMusicBidirectionalLyricBlurTarget.kt` + `OpenSourceLyricBlurPort.kt` + `LyricBlurRenderer.kt` | 437/405/139 | `LYRICS_UI_*`（可选，属于视觉增强） |
| `hook/AppleMusicCjkKaraokeAnimationTarget.kt` | 600 | `CJK_KARAOKE_ANIMATION_OWNER` `CJK_UNICODE_BLOCK_HELPER_OWNER` |
| `hook/LyricHighlightProbe.kt` + `LyricHighlightSession.kt` + `LyricWordHighlightState.kt` | 333/87/89 | `LYRICS_HIGHLIGHT_CALLBACK_OWNER` |
| `hook/LyricCreditsRowResourceHook.kt` + `CreditsRowIdentity.kt` + `InstrumentalRowIdentity.kt` | 12/28/16 | `LYRICS_RESULT_PRESENTATION` |
| `hook/CurrentItemIdentitySeam.kt` | 230 | `LYRICS_CURRENT_ITEM_FIELD` `CURRENT_ITEM_DURATION_OWNER` |
| `hook/LyricsTypeface*` / `TabletLyricTypography.kt` | — | 可选（平板排版，属插件 A 范畴） |

`LYRICS_*` 目标点在目标表里共 20+ 项（另有 `indexed.methodContracts` 里 20 项播放页/歌词契约、
`indexed.classes` 里 16 个歌词相关类），**不需要新增宿主调研**。

## 3. 设置页要加的控制项（第 3 批）

计划里列出的：歌词翻译开关、歌词发音（音译）开关、Apple-only 音译说明、不显示国语歌拼音、
清空自定义歌词。当前 `ui/HleSettingsScreen.kt` 只放了「歌词（第 3 批预留）」的说明文字；
加控制项时按现有 `Switch`/`Button` 模式追加，并走 `HleSettings` 的 properties 文件。

## 4. SDK 边界（第 3 批必须自己解决的点）

1. **没有统一歌词源接口**：源优先级（网易云/QQ/酷我/酷狗/lunabeat）必须由插件自己协调
   （`OnlineLyricSourcePolicy` + `ClaimResource` 声明可减少与其他插件的静默冲突）。
2. **没有 XML 资源**：歌词菜单/对话框只能改宿主对象或自建 View。
3. **不能注册插件 Activity/Service**：自定义歌词的导入/导出要复用宿主选择器或用 View 内嵌。
4. **异步结果归属**：快速切歌时，在线结果必须属于当前歌曲（AM++ 用
   `AppleVisibleMetadataResolutionLeases` 的思路）；否则会串歌。
5. **诊断**：`native-write` / `presentation-refresh` / `pronunciation-words` / `render-probe`
   四个词表要保持，已知问题（首播不上屏）的复现/修复都依赖它们。
