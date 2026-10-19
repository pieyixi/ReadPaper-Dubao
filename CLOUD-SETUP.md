# Codex Cloud 设置

当前界面改进分支：`codex/topbar-controls`；2.0.2 顶栏与朗读弹层详见 `TOPBAR-UPDATE.md`。免费在线语音基线分支为 `codex/free-online-speech`。

本分支基于 2.0.0 原生版本 `96f9f45`，接入免费云扬在线语音并保留粘贴文本的默认离线朗读。原生迁移历史分支为 `codex/native-reader-tts`。本轮实现与实际验证状态见 `SPEECH-INTEGRATION.md`，不要从旧版四报或 WebView 实现覆盖当前源码。

## 用户只需要完成的操作

1. 登录 Codex 网页，打开“环境 / Environments”，创建环境。
2. 仓库选择 `pieyixi/ReadPaper-Dubao`；若有分支选项，选择 `codex/topbar-controls`。
3. 如果看到“安装脚本 / Setup script / Install script”，填入：

   ```bash
   bash scripts/cloud-setup.sh
   ```

4. 网络允许下载依赖。默认包管理器策略之外，根据实际失败域名放行 Google Android/Maven、Gradle 分发、Maven Central 和 JDK 包源。
5. 保存/发布环境。把环境名称告诉当前本地对话；后续任务与检查由 Codex 继续。

若界面先进入环境设置对话，可直接发送：

> 使用 pieyixi/ReadPaper-Dubao 的 codex/topbar-controls 分支。读取 CLOUD-SETUP.md、CLOUD-TASK.md、TOPBAR-UPDATE.md 和 SPEECH-INTEGRATION.md。只在云端安装 JDK 17、Android SDK 与 Gradle；先运行 bash scripts/cloud-setup.sh，再验证 bash scripts/cloud-build.sh debug。准备好并发布环境，供后续原生读报和语音功能开发。不要在用户电脑下载编译环境。

不要把 `release.jks` 上传到公开代码仓库。环境准备和 Debug 检查不依赖 Release 私钥。

## 云端命令

```bash
bash scripts/cloud-setup.sh
bash scripts/cloud-build.sh debug
bash scripts/cloud-build.sh checks
```

`cloud-setup.sh` 仅支持 Linux。在没有 SDK 时，它将所需 SDK 放在云环境的用户目录，并保存可复用的环境配置；不会创建模拟器或下载系统镜像。默认编译平台为当前工程的 API 34，可用 `ANDROID_API_LEVEL` 与 `ANDROID_BUILD_TOOLS` 覆盖。迁移时若升级工具链，需要同步调整脚本与文档并在云端复测。

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## Release 签名

Release 构建从环境变量读取 `DUBAO_KEYSTORE_FILE`、`DUBAO_STORE_PASSWORD`、`DUBAO_KEY_ALIAS` 和 `DUBAO_KEY_PASSWORD`。若不设置文件路径，也可由 `DUBAO_KEYSTORE_BASE64` 在临时目录还原。使用当前已有密钥，不生成替代密钥。

构建命令：

```bash
bash scripts/cloud-build.sh release
```

本地旧密钥仍保留。私钥尚未迁入云端时，Release 构建应明确失败，不能悄悄使用 Debug 签名。

## 当前真实状态

- 当前界面代码同步到 `codex/topbar-controls`；本轮不合并默认分支。在线语音基线保留在 `codex/free-online-speech`。
- 除 Codex Cloud 安装/构建入口外，仓库已提供 `Android build and speech checks` GitHub Actions，可在标准 Ubuntu 云端运行器构建和检查。使用 Actions 不依赖本地 CLI 发现 Codex Cloud 环境。
- 本地未执行 SDK 安装、Gradle 构建或模拟器运行；只取回 APK 和检查证据。
- 原生迁移历史证据见 `NATIVE-ACCEPTANCE.md`，本轮在线语音证据见 `SPEECH-INTEGRATION.md`。构建、JVM、模拟器、真机和听感分别记录。
