# 读报

[下载最新正式 Release](https://github.com/pieyixi/ReadPaper-Dubao/releases/latest)。本次修复版本 **2.0.2**（versionCode 10），云端完整检查通过后自动发布，实际结果以 Release 附带的验证报告为准；本次导航、播放入口、字号和求是清洗修复见 [RELEASE-2.0.2.md](RELEASE-2.0.2.md)，此前音频缓存改进见 [SPEECH-CACHE-UPDATE.md](SPEECH-CACHE-UPDATE.md)，开发与发布统一在 **main**：包含此前调试阶段验证的便签历史、标题/日期/字数、长按多选、置顶/删除、固定悬浮新建按钮、原文自然段循环与五挡倍速。`mock.html` 是界面预览基准，用户确认后同步 Kotlin 原生 View。报纸及自定义便签均优先使用免费云扬在线合成，朗读内容会发送至在线服务；便签保存在本机，私人音频独立缓存。调试阶段的验证记录见 [NOTES-UPDATE.md](NOTES-UPDATE.md)，原生基线正式发布记录见 [RELEASE-2.0.0.md](RELEASE-2.0.0.md)。


Kotlin 原生 Android 读报器，保留学习时报、组织人事报、人民日报、南方日报、求是五个来源。报纸和自定义便签默认使用免费的云扬在线自然语音。包名仍为 `com.newspaper.helper`。朗读倍速为 0.8、1、1.1、1.2、1.3 五挡，更新记录见 [SPEED-UPDATE.md](SPEED-UPDATE.md)。

首页仅保留粘贴与设置，正文/粘贴页保留耳机朗读与设置；日期、年份、期号、目录不显示右侧功能按钮。底部播放条已移除，控制放入顶栏悬浮弹层。变更及验证记录见 [TOPBAR-UPDATE.md](TOPBAR-UPDATE.md)。

业务页面使用 View/XML、ViewBinding 和 RecyclerView/TextView。`app/src/main/assets/www/` 完整保留为迁移前的文案与视觉参照，不再作为业务入口；`test.html` 仍只是旧调试页面。自动化结果与尚未验证的项目见 [NATIVE-ACCEPTANCE.md](NATIVE-ACCEPTANCE.md)，实现约定见 [SPEC.md](SPEC.md)。

## 构建与检查（Codex Cloud）

使用 JDK 17、Android SDK/API 34、Build Tools 34.0.0 和 Gradle Wrapper 8.4。依赖版本固定在 Gradle 文件中；无需用户的 Windows 电脑下载开发环境。

```bash
cd /workspace/ReadPaper-Dubao
# 当前云环境每个新 shell 都需激活；没有该文件时先按 CLOUD-SETUP.md 准备工具。
source /workspace/.dubao-tools/activate.sh
python3 scripts/audit-ui.py
bash scripts/cloud-build.sh debug
bash scripts/cloud-build.sh checks
```

在同一个 checkout 中串行运行 Gradle，避免两个构建同时改写产物。Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`；lint 与 JVM 报告在 `app/build/reports/`。源码仓库不包含 APK、SDK、缓存或签名文件。

也可使用仓库的 `Android build and speech checks` GitHub Actions：推送 main 后，标准 Ubuntu 云端运行器负责单元测试、Debug/Release lint、文案审计、原密钥签名正式包，以及 API 28 模拟器的 320/390dp 原生界面检查；独立在线语音抽查成功后再运行真实播放检查。Debug 仅用于自动化设备测试，交付 APK 为校验过原始证书、2.0.2/versionCode 10、非 debuggable 的 Release，另实测正式包冷启动。APK 与报告作为短期附件保存，正式发行附件存放在 GitHub Release；无需在本机下载构建依赖。构建成功、外部接口可用、模拟器通过和人耳听音是不同结论。

真实网站联网检查单独执行，避免离线测试依赖官网可用性：

```bash
DUBAO_LIVE=1 bash ./gradlew --no-daemon --max-workers=3 testDebugUnitTest --tests '*LiveSourcesTest'
```

设备测试位于 `app/src/androidTest/`，分别检查原生界面、真实 TTS/ExoPlayer，以及设备端五来源 HTTPS。测试需要可用 Android 设备；云扬真实语音测试需要联网；仅系统后备语音检查需要已安装支持中文的系统 TTS 引擎。不能把没有运行的设备测试计为通过。

## 阅读与朗读

- 日报：首页 → 月历 → 按版面分组的文章 → 正文；求是：首页 → 年份 → 期号 → 文章 → 正文。
- 三主题、四档字号/行距沿用正式网页基线。新增操作使用图标；粘贴编辑区首次打开为空，使用系统编辑/粘贴菜单。
- 获取、正文解析、数据库、长文本分段和音频文件处理都在后台执行。网络请求可取消，最多四个并发，设有超时与有限重试。
- Room 保存文章正文块和阅读/朗读位置；DataStore 保存设置及页面。旧网页资产和旧 WebView 数据不删除；新原生设置独立保存，不解析旧 WebView 内部数据库。
- Media3 媒体服务管理通知、后台播放、媒体按键和音频焦点。报纸及粘贴文本由 Kotlin/OkHttp 接入 Edge 云扬，分段生成 MP3；首段在线不可用时才尝试系统 TTS 后备。首个完整短句优先准备，后续按句群合成，目标预取约 45 秒音频、最多八段，并按倍速换算。当前只保证块/片段高亮，不承诺逐字高亮。
- 自定义文本不经过新闻清洗；点击朗读会向在线合成服务发送所选文本。标题和正文在本机独立便签表保存，正文不写入新闻文章缓存，朗读位置按文本摘要保存。临时文本通过私有缓存文件传递，避免大文本跨 Binder；已合成音频按文章/便签、原文和音色参数保存于本机私有且不参与系统云备份的目录；停止或重启后仍复用。无按天过期，容量 256 MiB，超限只回收闲置文章，当前整篇受保护；删除便签同步删除其音频。
- 在线首段失败时尝试系统离线后备，已经播放后不在中途自动换声；失败可用原有播放图标重试。离线模式缺少中文语音时进入系统语音安装/设置。离线质量取决于设备引擎。

在线声音不需要用户账户、计费密钥或自建服务器，但 Edge 第三方接入没有官方稳定性及长期免费承诺，不支持任意新闻风格 SSML。选型依据与试听工具见 [SPEECH-RESEARCH.md](SPEECH-RESEARCH.md)；本轮接入验证见 [SPEECH-INTEGRATION.md](SPEECH-INTEGRATION.md)。

## 签名与开发代理

Release 必须使用原始密钥，通过安全环境配置提供 `DUBAO_KEYSTORE_FILE`（或 `DUBAO_KEYSTORE_BASE64`）、`DUBAO_STORE_PASSWORD`、`DUBAO_KEY_ALIAS`、`DUBAO_KEY_PASSWORD` 后执行 `bash scripts/cloud-build.sh release`。缺少原签名时明确失败，不生成替代 Release 密钥。

Release 只信任系统 CA。Debug 额外信任开发设备安装的用户 CA，供云端代理验证 HTTPS 使用；这不会关闭证书校验，也不在源码中放置环境证书或凭证。
