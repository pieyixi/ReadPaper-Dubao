# 原生读报与语音验收记录

日期：2026-10-18。开发分支：`codex/native-reader-tts`；包名 `com.newspaper.helper`，2.0.0 / versionCode 3。所有安装、构建与测试在 Codex Cloud 完成，未使用用户 Windows 编译环境。

## 已实现

- Kotlin + 原生 View/XML/ViewBinding + Material + RecyclerView/TextView；移除 Java WebView 业务入口，保留完整网页资产作对照。
- 五来源日历/年份/期号/版面/正文；目标日期全目录、求是新旧 URL 和冷启动、学习时报官方月历、图片独占段落与短句、表格、图注、列表和引用。
- 后台 OkHttp/Jsoup、取消/超时/有限重试/四并发；完整、空、失败、部分失败分开处理，失败不冒充完整缓存。修正旧版按关键词删除合法新闻的问题。
- 共享正文块、Room 文章与独立阅读/朗读位置、DataStore 三主题和四档字号/行距、页面与滚动恢复；黑夜日历蓝底白字。
- 独立 Media3 媒体服务与系统 TTS；有限预取、首段可播、前后段、暂停续播/停止/倍速/队列、切文取消、后台、通知和块高亮。自定义编辑文本起始为空，不上传，不经新闻过滤；大文本不放进 Binder。仅选用已安装的离线音色，避免引擎把自定义文本送往网络。
- 文案白名单 `ui-copy-baseline.json` 与 `scripts/audit-ui.py`；23 条可见资源来自正式旧页面，来源名称、副标题和四个基线文件散列均检查。

## 构建与自动化

- `assembleDebug`、`assembleDebugAndroidTest`：通过。
- `testDebugUnitTest`：12 个真实执行，0 失败，0 跳过。覆盖五来源真实 HTML/JSON、2019/2022 求是旧链接、图片/短句/表格/合法关键词、作者元数据、协议限制、部分失败、404 与403 区分、取消、冷进入期号、长文本及代理对分段。
- `lintDebug`：通过，0 错误、30 警告。主要是固定依赖可升级、保留图标形状、绘制分配与编辑控件标签提示；未通过关闭检查消除错误。
- `python3 scripts/audit-ui.py`：通过。`git diff --check`：通过。
- `DUBAO_LIVE=1 ... --tests '*LiveSourcesTest'`：6 项联网检查通过。下面是实际内容记录；数量是当次官网结果，不是硬编码验收条件。

## 五来源联网证据

| 来源 | 日期 | 目录文章数 | 实际正文标题与链接 | 正文块数 |
|---|---|---:|---|---:|
| 学习时报 | 2026-10-16 | 39 | [把好事实事办到困难群众心坎上](https://paper.studytimes.cn/cntheory/2026-10/16/content_9987903.html) | 26 |
| 组织人事报 | 2026-10-16 | 43 | [《求是》杂志发表习近平总书记重要文章 以更高标准、更实举措推进全面从严治党](https://www.zuzhirenshi.com/api/welcome/selectNews?id=c24b0a49-b7e6-45b1-908d-044697cbde4c) | 5 |
| 人民日报 | 2026-10-18 | 38 | [“有高远目标，更有耐心有定力”](https://paper.people.com.cn/rmrb/pc/content/202610/18/content_30186269.html) | 40 |
| 南方日报 | 2026-10-18 | 24 | [“有高远目标，更有耐心有定力”](https://epaper.nfnews.com/nfdaily/html/202610/18/content_10183807.html?origin=4) | 43 |
| 南方日报 | 2026-10-01 | 49 | [习近平同文莱苏丹哈桑纳尔互致贺电](https://epaper.nfnews.com/nfdaily/html/202610/01/content_10182771.html?origin=4) | 4 |

| 求是年份 | 官网期数 | 抽查期号 | 文章数 | 实际正文 | 块数 |
|---|---:|---|---:|---|---:|
| 2019 | 24 | 2019年第24期 | 18 | [本期导读](https://www.qstheory.cn/dukan/qs/2019-12/16/c_1125346113.htm) | 9 |
| 2022 | 24 | 2022年第24期 | 19 | [本期导读](https://www.qstheory.cn/dukan/qs/2022-12/16/c_1129211583.htm) | 9 |
| 2026 | 19 | 2026年第19期 | 24 | [本期导读](https://www.qstheory.cn/20260930/5c9d186e89d34b61a2395e276051382a/c.html) | 10 |

原页/JSON 精简回归样本位于 `app/src/test/resources/sources/`，地址记录在 `provenance.json`。联网完整响应与统计位于云端 `/workspace/native-evidence/network/`。学习时报 ccps 索引实测 WAF 403，已使用该报原站日历自身的 `cntheory/YYYY-MM/nav.json`，并实际验证目标日 12 个版面。求是旧正文 `.clipboard_text` 和带额外 `/2014/` 的期号链接均由真实页面发现并纳入测试。

## Android 运行与语音证据

平台为 Android 9/API 28 x86_64 软件模拟器，无 KVM；不能代替真机或 Android 14 前台服务策略验证。

- 320dp：`NativeUiTest` 的首页五卡片/三主题、月历、无业务 WebView、阅读/字号行距/滚动恢复、长文本编辑及旋转恢复三项通过；固定页面原始截图已保存。
- Android 端 `DeviceNetworkTest`：五来源实际 HTTPS、完整目录、正文及人民日报真实图片解码通过。Debug 使用开发模拟器安装的用户 CA，证书验证保留；Release 只信任系统 CA。
- 官方 eSpeak 1.52.0（`com.reecedunn.espeak`）已在云端模拟器安装，签名校验通过。来源：`https://github.com/espeak-ng/espeak-ng/releases/download/1.52.0/espeak-1.52.0-signed.apk`；SHA-256 为 `741b46abf84d843f9b0cce80136f60ffee9b9fe591601b9701aa2a9ff99c9ddf`。该引擎仅用于验证，不打入应用，不要求用户使用该引擎。
- 真实系统 TTS 生成中文 WAV：单声道 22050 Hz，约 5.82 秒，PCM 非零，RMS 约 3890。文件位于交付证据 `real-chinese.wav`，不是模拟的合成完成回调。
- `SpeechPlaybackTest` 最终独立执行 2 项通过（134.674 秒）：实际 eSpeak WAV 与 ExoPlayer 解码、报纸/自定义文本、暂停位置冻结与继续、前后段、倍速、切换取消、后台、瞬时音频焦点丢失及恢复、屏幕关闭后的实际播放进度、媒体按键、两篇文章自动连续播放。日志 `reports/android-media.log`。
- `ColdMediaStartTest` 独立执行 1 项通过（26.051 秒）：没有活动播放器时，真正以 `startForegroundService` + 媒体播放按键启动服务，从 Room 队列恢复并实际解码，确认服务处于前台；日志 `reports/android-cold-media.log`。
- 390dp：`NativeUiTest` 独立执行 3 项通过（69.461 秒），包括三主题首页/月历/正文、四档字号行距、滚动与旋转恢复、空编辑器系统剪贴板粘贴及约 3 万字文本。固定截图位于证据附件 `screenshots/native-390/`。启动软件模拟器时遇到 System UI 自身 ANR，关闭该系统对话框后重跑本组全部通过；没有将系统 ANR 算作应用通过证据。
- 320dp 的三项 UI 通过来自早期混合测试中的独立测试状态；该次混合测试包含当时尚未修复的其他失败，原始日志完整保留为 `reports/android-320-mixed.log`，不宣称该混合套件整体通过。Android 五来源联网通过也来自单独类的实际成功状态，保留完整混合日志与 JSON，相关语音失败已由上述最终独立测试复验。
- 迁移前首页截图来自原资产的 Chromium 实际渲染，320/390dp 各三主题；迁移后截图来自实际运行的原生模拟器。截图证明布局与文案对照，不表示跨渲染器逐像素一致。

## 已修复的验证发现

- 旧求是年份/正文选择器和学习时报 ccps 索引访问问题。
- 从 `meta author` 错把“第001版”当作者，及学习时报署名遗漏。
- Activity 重建时 ViewModel 中阅读位置仍为初始值，已更新内存位置并在 onPause 保存，设备复测通过。
- 合成/切段窗口收到标准暂停后，后续音频可能自动播放：媒体会话通过 ForwardingPlayer 记录意图，最终真实媒体测试复验通过。
- 冷启动媒体按键等待 TTS 才进入前台，曾触发系统五秒期限；现先显示仅含既有名称/真实标题的前台通知，冷启动独立测试通过。
- 测试媒体按键的 UiAutomation shell 句柄过早关闭，导致只收到取消的抬键；改为读完输出，使用真实按键完成复验。
- 初轮测试入口返回非 Unit、旧测试 APK 未包含新增类，均修正，按实际测试执行数核验。

## 性能、签名及未验证项

- 原 keystore 和 `DUBAO_*` 签名变量未配置；`bash scripts/cloud-build.sh release` 按设计明确失败，没有生成替代 Release 密钥。
- 原签名 Release 冷启动、长文滚动、首次出声和边听边操作的性能测量未执行。软件模拟器 Debug 的结果不替代这些证据，不宣称流畅性已达到真机验收。
- 模拟器以 `-no-audio` 启动，已经能验证真实 WAV 与 ExoPlayer 解码进度，物理听音、音质、真实蓝牙/耳机拔出、厂商后台限制及真机锁屏仍未验证。
- API 21 与 Android 14 真机运行尚未验证。使用的技术与编译成功不是这些环境的通过证据。
- Debug APK 保持原包名，但其 Debug 证书不是原 Release 证书，不能当作原版的签名兼容升级包；不建议为安装而删除原用户数据。

## GitHub 与交付

代码发布到 `codex/native-reader-tts`，GitHub 预发布标签为 `v2.0.0-native-preview.1`：

https://github.com/pieyixi/ReadPaper-Dubao/releases/tag/v2.0.0-native-preview.1

云端交付文件位于 `/workspace/shared/`：`dubao-2.0.0-native-debug.apk`、`native-evidence.zip`（构建/测试/lint 报告、联网响应与统计、截图、真实中文 WAV、证书信息）、`native-reader-tts.patch` 和 `SHA256SUMS`。APK 为 Debug 预发布，不更新旧版稳定 Release。源码仓库只提交实现、回归样本、文档，不提交二进制附件。

GitHub 代码推送和 Release API 写入成功，但 `uploads.github.com` 的附件上传返回 `401 Bad credentials`，使用当前云环境凭据无法上传。因此 GitHub 预发布页暂时只有说明，没有 APK 附件；上述完整文件保留在云端交付目录。附件上传未完成，不标记为已发布二进制包。

已从 GitHub 下载原 v1.0.0 APK 并验证其证书 SHA-256：`0c22a51a5eda729e3e45e742e7cbd326484120b4fc924c0c6e9ddd847b4a9669`；当前 Debug 证书为 `fa1c721a72c5a7d378f1edff5a6c3770391fe6c9b049a78ff133d23b640410c1`。正式签名包仍受原签名缺失限制，不能把 Debug 包当作原版的签名兼容升级包。
