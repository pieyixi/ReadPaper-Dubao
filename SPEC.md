# 原生读报实现规格

## 工具链与边界

Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.4 / JDK 17；minSdk 21、compile/targetSdk 34，Java API 通过 desugar_jdk_libs 2.0.4 兼容。UI 使用 Material 1.11.0、RecyclerView 1.3.2、ViewBinding、Lifecycle 2.7.0、Coroutines 1.8.1。数据层使用 OkHttp 4.12.0、Jsoup 1.17.2、Room 2.6.1、DataStore 1.1.1；播放为 Media3 1.3.1 与系统 TextToSpeech。图片由 Coil 2.6.0 异步加载并按屏幕宽度限制解码尺寸。

包名、应用名称、图标保留；versionCode 3、versionName 2.0.0。Release 使用原签名，Debug 独立可构建。业务代码没有 WebView、JavaScript 桥接或 HTML 页面渲染器。

## 正文与数据边界

`Models.kt` 的 `Article` 包含来源元数据与顺序排列的 `BodyBlock`；块有稳定 SHA-256 ID，类型包括标题、段落、图片、图注、列表项、引用与表格。figure/list/quote 通过 parent ID 保留分组；表格保留行与列，用原生横向滚动文字展示。图片只保留 HTTP/HTTPS URL；脚本、表单、iframe、导航与明确工具栏按结构剔除。正文内的“广告、公告、债权、彩票”等合法新闻内容不会按词删除。

`ContentParser` 在图片与块级边界刷新内联文本，保留图片独占段落、短句、相邻图注及原始顺序。显示与朗读使用同一份块；图注默认朗读，图片本身不生成语音。自定义文本不经过 HTML/新闻过滤，空行不生成音频。

`ReadingPosition` 分开更新阅读块索引/像素偏移与朗读片段索引/毫秒位置，避免两个异步写入互相覆盖。Room 存文章与播放队列；DataStore 存主题、字号、行距、页面路由。路由保留来源、期号 URL、日期与文章完整 URL，冷启动不依赖适配器内存缓存。未完成版面列表不作为完整结果缓存。

## 五来源

| 来源 | 目录 | 正文 |
|---|---|---|
| 学习时报 | 官方 `cntheory/YYYY-MM/nav.json` 月历 → 目标日 `node_1.html` 的完整版面目录 | `cms-content#content` / `#content` |
| 组织人事报 | 月份 POST `selectPastDianZiBao` → 期号 UUID POST `dianZiBaoHomePage`；读取 `data.dianzibaoShowList[].areaCoordinateList[]` | GET `selectNews?id=UUID` 的 `data.newContent` |
| 人民日报 | HTTPS `rmrb/pc/layout/YYYYMM/DD/node_01.html` 的实际版面链接，不固定前十二版 | `rmrb/pc/content/.../content_ID.html` 的 `#ozoom` |
| 南方日报 | `nfdaily/html/YYYYMM/DD/node_A01.html` 的目标日真实目录，遍历全部实际版面 | `#content` 全量，保留 enpcontent 标记之外的正文图片 |
| 求是 | 年份页 → 期号完整 URL → 文章完整 URL | 新 `#detailContent`，旧 `.clipboard_text` / `.inner .text` |

求是兼容 `YYYYMMDD/ID/c.html`、`dukan/qs/YYYY-MM/DD/c_ID.htm` 及旧站额外 `/2014/` 路径，正确解析 `//` 和相对链接。学习时报使用原网站日历接口，避免依赖当前返回 WAF 403 的 ccps 索引。人民日报、南方日报的月历日期是候选出版日，进入时仍验证目标日目录；404 表示该期无数据，403/超时等不会伪装为空。

`SourceResult` 显式区分完整与部分结果。部分失败在列表顶部复用“加载失败”“点击重试”显示，同时保留成功版面；全部失败显示错误页。没有新增可见说明文案。HTTP 最多四并发，12 秒连接/25 秒读取/40 秒调用超时，最多重试两次；400–499（429 除外）不重复请求。离开页面取消协程和底层 Call，旧结果不覆盖新路由。响应限制 12 MiB。

## 原生界面与文案

正式参考仍是 `assets/www/index.html`、`css/app.css`、`js/app.js` 与 `js/reader.js`；其散列固定在 `ui-copy-baseline.json`。`scripts/audit-ui.py` 检查基线完整性、可见字符串白名单与网页渲染禁用。

首页保留两列五来源渐变卡片、名称、副标题和标题层级；日报月历、按版面分组列表、求是年份/期号层级保持原流程。日历黑夜有报日使用蓝底白字，修复原 CSS 后续规则覆盖字色的问题。正文使用 RecyclerView 按块复用，设置改变立即重绑，阅读位置在暂停与滚动停止时保存。字号 15/17/19/21sp，行距 1.65/1.85/2.05/2.25，与正式 reader.js 一致。

所有新增播放、暂停、停止、前后段、队列、粘贴使用绘制图标；速度使用无文字刻度的原生 Slider。`a11y_` 资源只用于控件语义。粘贴编辑器初始为空，旋转时由 ViewModel 保留内容，不把大文本放入 SavedState/Binder。

## 语音与媒体服务

`SpeechEngine` 是可替换接口；`AndroidSpeechEngine` 包装系统 TTS 初始化、语言检查、实际文件生成、错误与取消。中文选择已安装的离线简体中文语音，非中文使用离线系统语言音色；不选需网络或尚未安装的音色，缺失引擎/语音进入系统安装或设置。

`Segmenter` 在后台按正文块、句子边界和引擎输入长度拆分，不切开 UTF-16 代理对。`ReadAloudService` 持有 ExoPlayer/MediaSession，使用 speech 音频属性、音频焦点、唤醒锁和耳机拔出处理；Media3 管理媒体通知与锁屏/媒体按键。首段合成完即 prepare/play，只预取当前位置之后两段；合成超时 60 秒。列表滑动不会触发整篇音频生成。

世代令牌、协程取消与 engine.stop 隔离切文/切文本后的旧回调。播放器保留当前、前一片段和有限后续媒体项；跳到窗口外按新位置重建。暂停保留毫秒位置，后台每两秒保存进度。队列按文章顺序播放，保存来源列表与当前索引；冷启动可从保存的队列继续。自定义文本仅保留在本机私有临时文件与内存，不写入持久文章库，不上传；仅按文本 SHA-256 标识保存朗读位置，重新粘贴相同文本时可恢复。播放完成后再次播放从头开始。

音频缓存按 32 MiB 和 24 小时清理，正在使用的有限窗口不被删除；停止时清理音频。块高亮和合理跟随用于文章与自定义文本；不承诺引擎无关的逐字高亮。

## 验证边界

JVM 回归、真实网络、Android 原生界面、实际语音合成/解码和人耳听音是不同证据。软件模拟器结果不能代替真机与原签名 Release 性能测量。每次交付应以 `NATIVE-ACCEPTANCE.md` 的实际记录为准，不以框架或编译成功推断流畅性与音质。
