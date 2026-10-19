# 读报

Kotlin 原生 Android 读报器，保留学习时报、组织人事报、人民日报、南方日报、求是五个来源。新闻默认使用免费的云扬在线自然语音，自定义粘贴文本默认本机朗读。包名仍为 `com.newspaper.helper`，版本为 `2.0.3`（versionCode 6）。朗读倍速为 0.8、1、1.1、1.2、1.3 五挡，更新记录见 [SPEED-UPDATE.md](SPEED-UPDATE.md)。

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

也可使用仓库的 `Android build and speech checks` GitHub Actions：标准 Ubuntu 云端运行器负责 Debug 构建、单元测试、lint、文案审计，以及 API 28 模拟器的 320/390dp 原生界面检查；独立在线语音抽查成功后再运行真实播放检查。APK 与报告作为短期附件保存，不需要在用户电脑下载构建依赖。构建成功、外部接口可用、模拟器通过和人耳听音是不同结论。

真实网站联网检查单独执行，避免离线测试依赖官网可用性：

```bash
DUBAO_LIVE=1 bash ./gradlew --no-daemon --max-workers=3 testDebugUnitTest --tests '*LiveSourcesTest'
```

设备测试位于 `app/src/androidTest/`，分别检查原生界面、真实 TTS/ExoPlayer，以及设备端五来源 HTTPS。测试需要可用 Android 设备；真实语音测试另需已安装支持中文的系统 TTS 引擎。不能把没有运行的设备测试计为通过。

## 阅读与朗读

- 日报：首页 → 月历 → 按版面分组的文章 → 正文；求是：首页 → 年份 → 期号 → 文章 → 正文。
- 三主题、四档字号/行距沿用正式网页基线。新增操作使用图标；粘贴编辑区首次打开为空，使用系统编辑/粘贴菜单。
- 获取、正文解析、数据库、长文本分段和音频文件处理都在后台执行。网络请求可取消，最多四个并发，设有超时与有限重试。
- Room 保存文章正文块和阅读/朗读位置；DataStore 保存设置及页面。旧网页资产和旧 WebView 数据不删除；新原生设置独立保存，不解析旧 WebView 内部数据库。
- Media3 媒体服务管理通知、后台播放、媒体按键和音频焦点。新闻由 Kotlin/OkHttp 直接接入 Edge 云扬，分段生成 MP3；粘贴文本用系统离线 TTS 生成 WAV。首个完整短句优先准备，后续按句群合成，目标预取约 45 秒音频、最多八段，并按倍速换算。当前只保证块/片段高亮，不承诺逐字高亮。
- 自定义文本不经过新闻清洗，不上传服务器；只在本机保存文本摘要对应的朗读位置，正文不写入文章库。临时文本通过私有缓存文件传递，避免大文本跨 Binder；音频缓存设有 32 MiB/24 小时清理策略，停止时删除当前窗口的私有文本音频。
- 在线首段失败时尝试系统离线后备，已经播放后不在中途自动换声；失败可用原有播放图标重试。离线模式缺少中文语音时进入系统语音安装/设置。离线质量取决于设备引擎。

在线声音不需要用户账户、计费密钥或自建服务器，但 Edge 第三方接入没有官方稳定性及长期免费承诺，不支持任意新闻风格 SSML。选型依据与试听工具见 [SPEECH-RESEARCH.md](SPEECH-RESEARCH.md)；本轮接入验证见 [SPEECH-INTEGRATION.md](SPEECH-INTEGRATION.md)。

## 签名与开发代理

Release 必须使用原始密钥，通过安全环境配置提供 `DUBAO_KEYSTORE_FILE`（或 `DUBAO_KEYSTORE_BASE64`）、`DUBAO_STORE_PASSWORD`、`DUBAO_KEY_ALIAS`、`DUBAO_KEY_PASSWORD` 后执行 `bash scripts/cloud-build.sh release`。缺少原签名时明确失败，不生成替代 Release 密钥。

Release 只信任系统 CA。Debug 额外信任开发设备安装的用户 CA，供云端代理验证 HTTPS 使用；这不会关闭证书校验，也不在源码中放置环境证书或凭证。
