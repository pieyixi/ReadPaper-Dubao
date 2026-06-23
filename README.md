<h1 align="center">读报</h1>
<p align="center"><strong>每日官媒精选 · 纯净阅读</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%205.0%2B-green" alt="Android">
  <img src="https://img.shields.io/badge/minSdk-21-blue" alt="API 21+">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" alt="License">
</p>

---

## 简介

一款简洁的 Android 新闻阅读器，实时从四份主流官媒官网获取内容，以纯净排版呈现。无广告、无推送、无持久化存储。

## 支持报纸

|   | 报纸 | 来源 | 获取方式 |
|---|------|------|---------|
| 🔵 | **学习时报** | paper.studytimes.cn | HTML 解析 |
| 🟤 | **组织人事报** | www.zuzhirenshi.com | JSON API |
| 🔴 | **人民日报** | paper.people.com.cn | HTML 解析 |
| 🟢 | **南方日报** | epaper.southcn.com | HTML 解析 |

## 功能

- 📅 精美日历视图，有报高亮、无报灰显
- 📰 按期数 → 版面 → 文章 逐层浏览
- ✂ 正文智能清洗，去除广告 / 彩票 / 版权等杂质
- 🖼 文章图片自动加载（跨域相对路径转绝对 URL）
- 🌙 白天 · 黑夜 · 护眼 三种全局主题
- 🔤 字号 4 档、行距 4 档可调
- ⚡ localStorage 缓存，二次打开秒开
- 🗓 并行请求 + 日期生成，加载速度极快
- 📱 320dp ~ 宽屏全响应式适配

## 架构

```
APK
 └── Android 壳 (MainActivity, ~50行)
      │  WebView 加载 assets/www/index.html
      │  返回键处理 | 跨域设置
      └── assets/www/
           ├── index.html         路由入口
           ├── css/app.css        全部样式
           ├── js/app.js          路由引擎 + 日历 + 设置
           ├── js/reader.js       正文清洗 + 阅读器渲染
           └── js/adapters/
               ├── base.js        共享工具 (fetch/缓存)
               ├── studytimes.js  学习时报
               ├── zuzhirenshi.js 组织人事报
               ├── renminribao.js 人民日报
               └── nanfangribao.js 南方日报
```

## 快速开始

### 环境要求
- **JDK 17** — [下载](https://adoptium.net/temurin/releases/?version=17)
- **Android SDK** — 脚本自动下载，或手动设 `ANDROID_HOME`
- **Gradle Wrapper** — 已打包在 `gradle/wrapper/`

### 一键打包

```bash
# Windows
双击 制作APK.bat

# 或手动
echo sdk.dir=你的SDK路径 > local.properties
gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

## 项目结构

```
android/
├── SPEC.md                       # 完整技术规格
├── 制作APK.bat                    # 一键打包脚本
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat         # Gradle Wrapper
├── release.jks                   # 签名密钥 (需自行生成)
├── res/dubao.png                 # 图标源文件
└── app/
    ├── build.gradle.kts          # 应用构建 + 签名配置
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                  # 资源 (图标/主题/字符串)
        ├── java/.../MainActivity.java
        └── assets/www/           # 全部 UI 和逻辑
```

## 适配器接口

每个报纸实现三个方法，文件在 `js/adapters/{name}.js`：

```javascript
getIssues()           // → [{date, issue, articleCount}, ...]
getArticles(date)     // → [{id, title, author, section, wordCount}, ...]
getArticle(id)        // → {title, author, date, section, bodyHtml}
```

采用 **self-contained ID** 设计：文章 ID 自包含 URL 构造信息，`getArticle` 无需依赖 `getArticles` 先调用。

## 正文清洗规则

- 移除 `<script>` `<style>` `<nav>` 等非内容标签
- 过滤"上一篇""版权""ICP备""广告""彩票""遗失声明"等关键词
- 图片相对路径自动转绝对 URL
- 白名单保留 `<p>` `<h1>~<h6>` `<img>` `<blockquote>` `<strong>` `<em>`
- 空段落 / 纯广告段落移除
- 段首全角空格去除，避免 CSS text-indent 叠加

## License

MIT
