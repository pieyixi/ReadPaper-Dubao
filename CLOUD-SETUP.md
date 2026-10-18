# Codex Cloud 设置

当前开发分支：`codex/native-reader-tts`。

本地最新五来源版本已保存于提交 `9daff92`。此分支从远程 main 创建，保留了本地全部 13 个变动文件。不要重新从旧版四报覆盖当前源码。

## 用户只需要完成的操作

1. 登录 Codex 网页，打开“环境 / Environments”，创建环境。
2. 仓库选择 `pieyixi/ReadPaper-Dubao`；若有分支选项，选择 `codex/native-reader-tts`。
3. 如果看到“安装脚本 / Setup script / Install script”，填入：

   ```bash
   bash scripts/cloud-setup.sh
   ```

4. 网络允许下载依赖。默认包管理器策略之外，根据实际失败域名放行 Google Android/Maven、Gradle 分发、Maven Central 和 JDK 包源。
5. 保存/发布环境。把环境名称告诉当前本地对话；后续任务与检查由 Codex 继续。

若界面先进入环境设置对话，可直接发送：

> 使用 pieyixi/ReadPaper-Dubao 的 codex/native-reader-tts 分支。读取 CLOUD-SETUP.md 和 CLOUD-TASK.md。只在云端安装 JDK 17、Android SDK 与 Gradle；先运行 bash scripts/cloud-setup.sh，再验证 bash scripts/cloud-build.sh debug。准备好并发布环境，供后续原生读报和语音功能开发。不要在用户电脑下载编译环境。

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

- GitHub 最新本地成果已同步到开发分支。
- 云端安装/构建入口与完整开发任务已准备。
- 当前账号通过 Codex CLI 查询到的云环境列表为空，需先在产品界面创建并发布环境。
- 本地未执行 SDK 安装、Gradle 构建或模拟器运行。
- 原生迁移、TTS 与手机验收尚未完成，不能将准备工作标为产品完成。
