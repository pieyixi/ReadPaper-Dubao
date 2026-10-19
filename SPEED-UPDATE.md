# 2.0.3 朗读倍速

按用户要求，朗读弹层的五挡倍速改为 **0.8、1、1.1、1.2、1.3**，默认仍为 1 倍。沿用原有分段按钮样式及播放器，无新增可见中文文案。

版本为 2.0.3 / versionCode 6，包名、数据存储及 Release 签名配置保留。在线播放回归逐一点击五挡，并确认真实媒体服务收到对应速度。Android 编译和设备验证在 GitHub Actions 云端执行，本地不下载编译依赖。

云端运行 [39219829647](https://github.com/pieyixi/ReadPaper-Dubao/actions/runs/39219829647) 成功，对应提交 `9eaf837688e11e39f939f2f05b205b1dfacb2884`。

- Debug 构建、文案审计、单元测试和 lint 通过。离线报告为 27 项、0 失败、1 项联网测试按设计跳过；该联网项随后独立执行通过。lint 为 0 错误、33 条 Warning。
- API 28 模拟器实际 320dp、390dp 的原生界面回归各 4 项通过；实际新闻播放回归 1 项通过，16.817 秒，其中逐一点击并验证了五挡速度。该测试使用五来源原站正文样本及真实在线语音，不表示重新抓取五个官网或人工听感验收。
- 已取回 49 张原始截图；上一轮截图导出辅助修正也在本轮执行。证据在 `.cloud/verification-2.0.3/`，主要界面预览为 `.cloud/topbar-preview.html`。

APK 为 `dubao-2.0.3-speed-debug.apk`，11996228 字节（约 11.44 MiB）。本机核验 v1/v2 签名、包名及版本通过：

```text
Package: com.newspaper.helper
Version: 2.0.3 / 6
SHA-256: 1b0ab161d85983c22dd2198318166640ff3484938235b3c844ef41dd8c99d9ac
Debug certificate SHA-256: 49a40a4ce8edb554dc694a9f9fb4fb01e1b690b6c6e4b58b74a06868d04f8dd9
```

这是 Debug 测试包，证书与上一轮测试包不同，不能直接覆盖旧测试包。原签名及数据仍保留；需要保留原安装数据更新时，应使用原签名构建，不应卸载旧版丢弃数据。本轮未生成原签名 Release 包，未验证真机或自定义文本离线 TTS 的实际听音。
