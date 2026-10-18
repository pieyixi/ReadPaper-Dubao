# 读报

Kotlin 原生 Android 读报器，保留学习时报、组织人事报、人民日报、南方日报、求是五个来源，支持文章与自定义粘贴文本的本机朗读。包名仍为 `com.newspaper.helper`，版本为 `2.0.0`（versionCode 3）。

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
- Media3 媒体服务管理通知、后台播放、媒体按键和音频焦点。系统 TextToSpeech 分段生成本机 WAV，仅使用已安装的离线音色，首段可用即可播放，最多提前准备后续两段。当前只保证块/片段高亮，不承诺逐字高亮。
- 自定义文本不经过新闻清洗，不上传服务器；只在本机保存文本摘要对应的朗读位置，正文不写入文章库。临时文本通过私有缓存文件传递，避免大文本跨 Binder；音频缓存设有 32 MiB/24 小时清理策略，停止时清理。
- 缺少中文语音时，播放图标进入系统语音安装/设置；安装或选择引擎后可重试。语音质量和可用音色取决于设备引擎。

## 签名与开发代理

Release 必须使用原始密钥，通过安全环境配置提供 `DUBAO_KEYSTORE_FILE`（或 `DUBAO_KEYSTORE_BASE64`）、`DUBAO_STORE_PASSWORD`、`DUBAO_KEY_ALIAS`、`DUBAO_KEY_PASSWORD` 后执行 `bash scripts/cloud-build.sh release`。缺少原签名时明确失败，不生成替代 Release 密钥。

Release 只信任系统 CA。Debug 额外信任开发设备安装的用户 CA，供云端代理验证 HTTPS 使用；这不会关闭证书校验，也不在源码中放置环境证书或凭证。
