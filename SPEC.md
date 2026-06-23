# 读报 · APK 技术规格文档

> 本文档是手机读报 APK 的完整开发规格，基于实测修正。下一个开发者（LLM 或人类）严格按此文档实现。

---

## 1. 概述

一个 Android APK，在手机上以干净、大号字体排版阅读四份主流官媒报纸的内容。
数据实时从各报官网获取，不做本地持久化存储（`localStorage` 缓存 30 分钟 + 内存缓存除外）。
UI 全部用 HTML+CSS+JS 实现，通过 Android WebView 加载。Android 壳代码只负责启动 WebView 和返回键处理。

---

## 2. 首批报纸（4个）

| 报纸 | 官网 | 数据获取方式 | 卡片颜色 |
|------|------|-------------|---------|
| 学习时报 | paper.studytimes.cn | HTML 解析 | `#1a5276` 藏蓝 |
| 组织人事报 | www.zuzhirenshi.com | JSON API | `#6b3a2e` 棕红 |
| 人民日报 | paper.people.com.cn | HTML 解析 | `#c0392b` 正红 |
| 南方日报 | epaper.southcn.com | HTML 解析 | `#2c7a3a` 深绿 |

---

## 3. 架构

```
APK
 └── Android 壳 (Java, ~50行)
      │  WebView 加载 assets/www/index.html
      │  返回键：可后退则后退，否则退出
      │  允许跨域请求 (AllowUniversalAccessFromFileURLs)
      │  允许混合内容、文件访问
      └── assets/www/   ← 全部 UI 和逻辑在此
           ├── index.html           页面容器 & 路由入口
           ├── css/app.css          所有样式
           ├── js/app.js            路由引擎 + 日历 + 全局设置
           ├── js/reader.js         阅读器渲染 & 正文清洗
           └── js/adapters/
               ├── base.js          共享工具 (fetch/缓存/节流)
               ├── studytimes.js    学习时报
               ├── zuzhirenshi.js   组织人事报
               ├── renminribao.js   人民日报
               └── nanfangribao.js  南方日报
```

---

## 4. UI 规格

### 4.1 总体风格

- 纯黑白配色：背景 `#fff`，正文 `#1a1a1a`，副文字 `#666`，辅助文字 `#999`
- 无圆角或极简 8px 圆角，无动画，无渐变（卡片渐变色除外）
- 字体：`"PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif`
- 最小支持 320dp 宽度，无最大限制
- 正文 `text-align: justify`
- 段首 `text-indent: 2em`（自动去除原文自带全角空格避免叠加）

### 4.2 全局主题

- 首页右上角齿轮 ⚙ 按钮，点击弹出气泡
- 支持三模式切换：☀ 白天 / ☕ 护眼 / ☾ 黑夜
- 切换后全局即时生效，所有页面响应
- 设置持久化到 `localStorage`，下次打开自动恢复
- 黑夜模式：全局背景 `#1a1a1a`，正文 `#c8c8c8`
- 护眼模式：全局背景 `#f5f0e6`，正文 `#4a3f35`
- 日历组件在黑夜模式下有报日蓝色底 `#3b6fb6`，无报日 `#555`
- 气泡打开时自动加全局点击监听，点击外部关闭

### 4.3 屏幕1：首页（报纸网格）

```
┌─────────────────────────┐
│                    ⚙    │  齿轮按钮 (右上角)
│  读报                    │  28px 700, margin-bottom 4px
│  每日官媒精选             │  14px #999, margin-bottom 28px
│                         │
│  ┌────────┐ ┌────────┐  │
│  │  学习   │ │  组织   │  │  grid 2列, gap 16px
│  │  时报   │ │  人事报  │  │  aspect-ratio 1.3
│  │        │ │        │  │  圆角 12px, 白字, 居中
│  └────────┘ └────────┘  │  卡片背景用渐变色
│  ┌────────┐ ┌────────┐  │
│  │  人民   │ │  南方   │  │  每个卡片是一个 <a>
│  │  日报   │ │  日报   │  │  点击 → 屏幕2
│  └────────┘ └────────┘  │
└─────────────────────────┘
```

卡片颜色（渐变色）：
- 学习时报：`linear-gradient(145deg,#1a5276,#2c6a96)`
- 组织人事报：`linear-gradient(145deg,#6b3a2e,#8b5545)`
- 人民日报：`linear-gradient(145deg,#c0392b,#e0513e)`
- 南方日报：`linear-gradient(145deg,#2c7a3a,#3d9e4e)`

### 4.4 屏幕2：日历视图（替代期数列表）

```
┌─────────────────────────┐
│ ←  学习时报         ⚙   │  返回箭头 + 报纸名
│                         │
│      ‹ 2026年6月 ›      │  月份导航
│ 日 一 二 三 四 五 六    │  星期头
│  1   2  [3]  4   5   6  │  有报: 黑底白字
│  7   8   9  10  11  12  │  今日: 红底白字
│ 13  14  15  16  17  18  │  无报: 灰色不可点击
│ 19  20 [21] 22  23  24  │
│ 25  26  27  28  29  30  │  点击日期 → 屏幕3
│                         │
│  ● 有报  ○ 无报         │  图例
└─────────────────────────┘
```

- 默认显示当前月份
- 可前后翻月（跨年自动切换）
- 日期来自适配器 `getIssues()` 返回的日期集合
- 无报日期灰色不可点击

### 4.5 屏幕3：文章列表（按版面分组）

```
┌─────────────────────────┐
│ ←  2026-06-23       ⚙   │  返回 → 屏幕2
│                         │
│ 第一版·国内大局 (8篇)     │  组标题：13px 600, #666
│ 积极应对人工智能对...      │  背景 #f8f8f8, padding 8 12
│ 从五个维度学习领会...      │  文章行 15px 500
│ 加强新时代党的建设...      │  元信息 12px #999
│                         │
│ 第二版·当代世界 (6篇)     │  文章行 border-bottom 1px #f0f0f0
│ 西班牙何以不断走近中国    │  点击文章行 → 屏幕4
│ ...                     │
└─────────────────────────┘
```

- 按版面分组，每组一个有背景色的组标题
- 自动过滤广告版面（版面名含"广告""彩票""专版""公告"）
- 文章行只显示标题和字数，不显示摘要

### 4.6 屏幕4：阅读器

```
┌─────────────────────────┐
│ ← 2026-06-23        Aa  │  ←返回  Aa: 阅读器设置
│                         │
│ ┌ 字号 小 中 大 特大 ┐  │  设置面板(点击Aa展开)
│ │ 行距 紧凑标准宽松更宽│ │
│ │ 模式 ☀白天☾黑夜☕护眼│ │
│ └─────────────────────┘  │
│ 国内大局                  │  版面标签 12px, 白字藏蓝底
│                         │
│ 积极应对人工智能对        │  文章标题 24px 700, line-height 1.4
│ 就业的冲击               │
│                         │
│ 张某某 · 2026-06-23      │  元信息 14px #666, 下边框分隔
│ ───────────────────────  │
│                         │
│ 正文段落...               │  字号行距跟随设置, text-align: justify
│ 正文段落...               │  段首 text-indent: 2em
│                          │  段首全角空格已自动清除
│ [图片]                   │  图片 max-width:100%, 居中, 懒加载
│ 图：图片说明              │
│                          │  图片说明 13px #999, 居中
│ 正文段落...               │
│                         │
│ 底部 60px 留白            │
└─────────────────────────┘
```

- 设置面板：字号(小/中/大/特大)、行距(紧凑/标准/宽松/更宽)、模式(白天/黑夜/护眼)
- 正文不包含导航、版权、广告、彩票等无关内容

---

## 5. 适配器接口

每个报纸对应一个 JS 文件，实现以下三个方法。文件放在 `js/adapters/{name}.js`。

### 5.1 接口定义

```javascript
/**
 * 获取该报所有可读期数列表
 * @returns {Promise<Array<{date:string, issue:string, articleCount:number}>>}
 */
async function getIssues()

/**
 * 获取某期的文章列表
 * @param {string} date - "YYYY-MM-DD"
 * @returns {Promise<Array<{id:string, title:string, author:string, section:string, wordCount:number}>>}
 *   id: 自包含 URL 构造信息的唯一 ID（见 5.3）
 */
async function getArticles(date)

/**
 * 获取单篇文章完整内容（可通过 ID 独立调用，不依赖 getArticles 先执行）
 * @param {string} id
 * @returns {Promise<{title:string, author:string, date:string, section:string, bodyHtml:string}>}
 */
async function getArticle(id)
```

### 5.2 实现约束

- 所有函数必须 try-catch，失败返回空数组或 `null`
- 不允许抛异常
- `bodyHtml` 必须经 `Reader.cleanBody()` 清洗
- `<img>` 的 `src` 相对路径转为绝对 URL（baseUrl 为文章页目录，非域名根）
- 适配器实例由 `app.js` 单例缓存，保证内部 `_articleMeta` 等不丢失
- 文章 ID 自包含 URL 信息，`getArticle` 可直接构造 URL 无需查找缓存

### 5.3 文章 ID 设计（Self-Contained ID）

`getArticle(id)` 必须能不依赖 `getArticles` 先调用而独立工作。ID 格式：

| 报纸 | ID 格式 | 示例 |
|------|---------|------|
| 学习时报 | `YYYY-MM/DD::contentId` | `2026-06/22::9975428` |
| 人民日报 | `YYYYMMDD::contentId` | `20260623::30164452` |
| 南方日报 | `YYYYMMDD::contentId` | `20260623::10173718` |
| 组织人事报 | `newsId` (UUID) | `6da43c45-...` |

`getArticle` 解析 ID 中的日期 + contentId 直接构造文章页 URL，无需缓存。

---

## 6. 正文清洗规则

通过 `Reader.cleanBody(html, baseUrl)` 实现，使用 DOMParser 解析。

### 6.1 清洗步骤

1. **移除整标签**：`<script>` `<style>` `<iframe>` `<nav>` `<header>` `<footer>` `<aside>`
2. **关键词过滤**：包含以下关键词且文字 < 80 字符的段落整段移除：
   - 导航类：`上一篇` `下一篇` `相关新闻` `返回目录` `PDF下载`
   - 工具类：`放大` `缩小` `字体` `打印本页` `全文复制` `关闭窗口`
   - 版权类：`版权` `ICP备` `京ICP` `订阅电话` `本报邮箱`
   - 广告类：`广告` `彩票` `福彩` `体彩` `开奖` `投注`
   - 公告类：`遗失声明` `注销公告` `清算公告` `减资公告` `债权申报` `环评公示`
3. **标签白名单**：只保留 `<p>` `<h1>~<h6>` `<img>` `<blockquote>` `<ul>` `<ol>` `<li>` `<br>` `<hr>` `<strong>` `<em>` `<b>` `<i>` `<u>` `<span>` `<a>` `<sub>` `<sup>` `<pre>` `<code>`；非白名单标签 unwrap（保留子内容）
4. **属性清洗**：`<span>` 保留 `style`（排版字体），`<a>` 保留 `href`，其他标签去除全部属性
5. **图片处理**：
   - 无 `src` 则移除
   - 相对路径转绝对 URL（`baseUrl` 为文章页目录）
   - 添加 `loading="lazy"` + `max-width:100%;height:auto;display:block;margin:16px auto`
6. **段首空白清除**：去除 `<p>` 第一文本节点开头的半角空格和全角空格 `\u3000`，防止与 CSS `text-indent:2em` 叠加
7. **空段落移除**：无文本内容且无 `<img>` `<br>` 的 `<p>` 移除
8. **纯广告段落移除**：段落文字仅由 `广告` `专题` `专版` `公告` `声明` 组成则移除

### 6.2 人民日报/南方日报特化处理

两报的文章页使用 `enpcontent` 标记区分正文和元数据：

```html
<!--enpcontent--><p>正文段落</p>...<!--/enpcontent-->
<!--enpproperty <author>XXX</author><date>...</date>/enpproperty-->
```

- **人民日报**：优先提取 `#ozoom` 全部内容（`enpcontent` 前后的图片都能保留），过滤 `enpproperty` 注释
- **南方日报**：优先提取 `#content` 全部内容（含 `enpcontent` 前后的图片表格），过滤 `enpproperty` 注释
- **作者提取**：来自 `.sec` 或 `<meta name="author">` 或 `enpproperty` 中的 `<author>` 标签

---

## 7. 各报纸数据获取详情（实测修正版）

### 7.1 组织人事报

**特征**：JSON API，最稳定。

**getIssues：**
```
POST https://www.zuzhirenshi.com/api/welcome/selectPastDianZiBao
Body: {"years":"2026","months":"06"}
```
并行请求今年 1~当前月 + 去年 11~12 月。返回：
```json
{"code":200, "data":[{"id":"uuid", "columnName":"第2875期", "releaseDate":"2026-06-23"}]}
```

**getArticles：**
```
POST /api/welcome/dianZiBaoHomePage  Body: {"id":"uuid"}
```
返回 `data.dianzibaoShowList[]`，含 `columnName` + `areaCoordinateList[]`（`newsId`, `newsName`）。

**getArticle：**
```
GET /api/welcome/selectNews?id={newsId}
```
返回 `data.showTitle` / `data.newContent` / `data.author` / `data.createTime`。

### 7.2 学习时报

**getIssues：**
```
GET https://www.ccps.gov.cn/xxsb/index.shtml
```
提取 `href` 中 `/cntheory/(YYYY-MM)/(DD)` 的日期。

**getArticles：**
```
GET https://paper.studytimes.cn/cntheory/{YYYY-MM}/{DD}/node_1.html
```
从 `node_1` 读取版面目录（`.layout-catalogue-list a`），然后并行获取各版面的 `.news-list a[href*="content_"]`。

**getArticle：**
```
GET .../cntheory/{YYYY-MM}/{DD}/content_{id}.html
```
- 标题：`#news_content h1`（优先，页面有两个 h1，第一个为空）
- 作者：`p.datesource`（取"字数"前面的文本）
- 正文：`cms-content#content` 内的 `<p>` 标签

### 7.3 人民日报（URL 实测修正）

**⚠ 注意：spec 原始 URL 模式已过时。实际为 PC layout 路径。**

**getIssues：**
人民日报每日出版，直接生成最近 30 天 `YYYY-MM-DD` 列表（零网络请求）。

**getArticles：**
```
http://paper.people.com.cn/rmrb/pc/layout/{YYYYMM}/{DD}/node_{NN}.html
```
日期格式为 `YYYYMM/DD`（如 `202606/23`），不是 `YYYYMMDD`。并行获取前 12 版面。版面名来自 `.swiper-slide a` 或 `.ban`。文章链接为 `content_(\d+).html`。

**getArticle：**
```
http://paper.people.com.cn/rmrb/pc/content/{YYYYMM}/{DD}/content_{id}.html
```
- 标题：`.article h1, #ozoom h1`（优先）→ `h1` → `<title>`
- 作者：`.sec` 或 `enpproperty` 注释中的 `<author>`
- 正文：`#ozoom` 全量（过滤 `enpproperty`）

### 7.4 南方日报（URL 实测修正）

**⚠ 注意：spec 原始 URL 模式已过时。实际有字母前缀版面 + 双域名。**

**getIssues：**
南方日报每日出版，直接生成最近 30 天 `YYYY-MM-DD` 列表。

**getArticles：**
```
https://epaper.southcn.com/nfdaily/html/{YYYYMM}/{DD}/node_{XX}.html
```
日期格式 `YYYYMM/DD`。版面名有字母前缀：`node_A01.html` `node_DC01.html` 等。版面列表从首页 `#list li a` 提取。前 12 个版面并行获取。文章链接从 `[id^="artPList"] a` 提取。

**getArticle：**
```
https://epaper.nfnews.com/nfdaily/html/{YYYYMM}/{DD}/content_{id}.html
```
注意：文章内容在 **nfnews.com** 域名，版面页在 southcn.com。需带 Referer。
- 标题：`h1` → `<title>`
- 作者：`<meta name="author">` 或 `enpproperty` 注释
- 正文：`#content` 全量（含 enpcontent 前后的图片/表格），过滤 `enpproperty`

---

## 8. JavaScript 路由规范

Hash 路由 + 导航令牌防竞态：

```javascript
// 路由表
'#/'                  → renderHome()       首页
'#/paper/:id'         → renderIssues()     日历视图
'#/paper/:id/:date'   → renderArticles()   文章列表
'#/reader/:id/:aid'   → renderReader()     阅读器
```

**导航令牌机制**：每次路由 `++this._navToken`，异步回调中检查 `token !== this._navToken`，防止用户快速返回时旧请求覆盖当前页面。

**事件委托**：所有交互通过 `#app` 上的全局 `click` 事件委托处理，按 `data-action` 分发。

---

## 9. Android 壳代码

### MainActivity.java

```java
public class MainActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);       // 允许 file:// 间访问
        s.setAllowUniversalAccessFromFileURLs(true);  // 允许跨域 fetch
        s.setMixedContentMode(MIXED_CONTENT_ALWAYS_ALLOW);
        // ...

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else finish();
    }
}
```

关键是 `setAllowUniversalAccessFromFileURLs(true)` 让 `file://` 页面可以跨域 `fetch` 各报网站。

---

## 10. 性能优化

| 策略 | 说明 |
|------|------|
| **并行请求** | `AdapterUtils.fetchBatch()` 每批 6 并发，批次间无延迟 |
| **零请求 getIssues** | 人民日报/南方日报每日出版，直接生成日期列表 |
| **localStorage 缓存** | 期数列表和文章列表缓存 30 分钟，二次打开秒开 |
| **版面限制** | 人民日报 12 版，南方日报 12 版（非必要不抓全部）|
| **导航令牌** | 防止竞态覆盖，用户快速操作不卡顿 |

---

## 11. build.gradle.kts 关键配置

```kotlin
android {
    namespace = "com.newspaper.helper"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.newspaper.helper"
        minSdk = 21; targetSdk = 34
        versionCode = 1; versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = "sg3cxhmjyj"
            keyAlias = "newspaper"
            keyPassword = "sg3cxhmjyj"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

---

## 12. 项目文件结构

```
android/
├── .gitignore
├── SPEC.md                        本文档
├── README.md
├── build.gradle.kts               根构建
├── settings.gradle.kts
├── gradle.properties              (含 android.overridePathCheck=true)
├── gradlew / gradlew.bat          Wrapper
├── gradle/wrapper/
├── 制作APK.bat                     一键打包脚本
├── release.jks                    签名密钥 (不提交git)
├── res/dubao.png                  图标源文件
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── mipmap-*/ic_launcher.png   (5个密度)
        │   ├── drawable/ic_launcher.xml
        │   └── values/
        ├── java/.../MainActivity.java
        └── assets/www/
            ├── index.html
            ├── css/app.css
            ├── js/app.js
            ├── js/reader.js
            └── js/adapters/
                ├── base.js
                ├── studytimes.js
                ├── zuzhirenshi.js
                ├── renminribao.js
                └── nanfangribao.js
```

---

## 13. 边界与注意事项

1. **URL 有效性**：人民日报和南方日报的 URL 为实测验证后的正确模式，如网站改版需重新测试
2. **请求策略**：不设全局节流（速度优先），User-Agent 设手机浏览器值
3. **图片加载**：图片从原站加载，相对路径转绝对 URL（baseUrl 为文章页目录），`loading="lazy"`
4. **无数据状态**：显示"暂无内容"+ 重试按钮
5. **错误状态**：显示"加载失败"+ 重试按钮
6. **缓存**：内存缓存 + localStorage 缓存（30 分钟），页面刷新保留
7. **屏幕适配**：最小 320dp，content 区左右 padding 随宽度调整
8. **Android 版本**：最低 API 21（Android 5.0）
9. **无广告**：App 内不出任何形式的广告和付费内容
10. **中文路径**：项目路径含中文时，`gradle.properties` 需 `android.overridePathCheck=true`，SDK 路径通过 `subst` 映射虚拟驱动器
11. **签名**：Release 用 `release.jks`，密码独立管理，不上传 git
12. **学习时报 h1**：页面有两个 `<h1>`，第一个为空（`.fl`），需优先取 `#news_content h1`
