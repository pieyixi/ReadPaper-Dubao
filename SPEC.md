# 读报 · APK 技术规格文档

> 本文档是手机读报 APK 的完整开发规格。下一个开发者（LLM 或人类）严格按此文档实现。

---

## 1. 概述

一个 Android APK，在手机上以干净、大号字体排版阅读四份主流官媒报纸的内容。
数据实时从各报官网获取，不做本地持久化存储（内存缓存除外）。
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
 └── Android 壳 (Java, ~40行)
      │  WebView 加载 assets/www/index.html
      │  返回键：可后退则后退，否则退出
      │  允许网络请求、混合内容、文件访问
      └── assets/www/   ← 全部 UI 和逻辑在此
           ├── index.html           页面容器 & 路由入口
           ├── css/app.css          所有样式
           ├── js/app.js            路由引擎 + 页面渲染
           ├── js/reader.js         阅读器渲染 & 正文清洗
           └── js/adapters/
               ├── base.js          适配器基类 (接口定义)
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

### 4.2 响应式断点

```
@media (max-width: 480px) {
  .container { padding: 0 16px; }
  .reader-body { font-size: 17px; }
}
```

### 4.3 屏幕1：首页（报纸网格）

```
┌─────────────────────────┐
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

### 4.4 屏幕2：期数列表

```
┌─────────────────────────┐
│ ←  学习时报              │  返回箭头 + 报纸名
│                         │
│ 06-23  周三    35 篇  ›  │  日期 18px 600
│ 06-22  周二    18 篇  ›  │  星期 13px #999
│ 06-19  周五    35 篇  ›  │  文章数 13px #666
│ 06-17  周三    22 篇  ›  │  箭头 16px #999
│ 06-15  周一    22 篇  ›  │  每行 border-bottom 1px #eee
│ ...                     │  整行 <a> 可点击
│                         │  点击 → 屏幕3
└─────────────────────────┘
```

- 顶部有返回按钮和报纸名，返回按钮背景 `#f8f8f8`
- 列表按日期降序排列，最新在最上

### 4.5 屏幕3：文章列表（按版面分组）

```
┌─────────────────────────┐
│ ←  2026-06-23            │  返回 → 屏幕2
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
- 文章行只显示标题和字数，不显示摘要

### 4.6 屏幕4：阅读器

```
┌─────────────────────────┐
│ ←  2026-06-23            │  返回 → 屏幕3
│                         │
│ 国内大局                  │  版面标签 12px, 白字藏蓝底
│                         │
│ 积极应对人工智能对        │  文章标题 24px 700, line-height 1.4
│ 就业的冲击               │
│                         │
│ 张某某 · 2026-06-23      │  元信息 14px #666, 下边框分隔
│ ───────────────────────  │
│                         │
│ 正文段落...               │  正文 18px 400, line-height 1.85
│ 正文段落...               │  颜色 #1a1a1a
│                          │  每段 text-indent: 2em
│ [图片]                   │  margin-bottom 1.2em
│ 图：图片说明              │  图片 max-width 100%, auto height
│                          │  图片说明 13px #999, 居中
│ 正文段落...               │
│                         │
│ 底部 60px 留白            │
└─────────────────────────┘
```

- 左右 padding 18px
- 正文不包含导航、版权、广告等无关内容

---

## 5. 适配器接口

每个报纸对应一个 JS 文件，实现以下三个方法。文件放在 `js/adapters/{name}.js`。

### 5.1 接口定义

```javascript
// js/adapters/base.js — 仅作接口文档，不用实现

/**
 * 获取该报所有可读期数的列表
 * @returns {Promise<Array<{date:string, issue:string, articleCount:number}>>}
 *   date: "2026-06-23"
 *   issue: "第4274期" 或类似
 *   articleCount: 该期文章总数（可选）
 */
async function getIssues()

/**
 * 获取某期的文章列表
 * @param {string} date - "YYYY-MM-DD"
 * @returns {Promise<Array<{id:string, title:string, author:string, section:string, wordCount:number}>>}
 *   id: 该报内唯一即可，可用 URL 或 content_id
 *   section: 版面名，如"第一版·要闻"
 */
async function getArticles(date)

/**
 * 获取单篇文章完整内容
 * @param {string} id
 * @returns {Promise<{title:string, author:string, date:string, section:string, bodyHtml:string, images:Array}>}
 *   bodyHtml: 清洗后的正文HTML，只含 <p> <img> <blockquote> <strong> <em> <ul> <ol> <li>
 *   images: [{src: "绝对URL", alt: "描述"}]
 */
async function getArticle(id)
```

### 5.2 实现约束

- 所有函数必须 try-catch 内部错误，失败时返回空数组或 `null`
- 不允许抛异常，不允许 reject Promise
- 网络请求间隔 ≥ 1 秒，User-Agent 设合理值
- `bodyHtml` 必须清洗干净（见第 6 节）
- `<img>` 的 `src` 如果是相对路径，转为绝对路径

---

## 6. 正文清洗规则

从各报获取的正文 HTML 包含大量杂质，`getArticle()` 返回前必须清洗：

1. **移除标签**：`<script>`、`<style>`、`<iframe>`、`<nav>`、`<header>`、`<footer>`、`<aside>`
2. **移除段落**：包含以下关键词的整段移除
   - "上一篇"、"下一篇"、"相关新闻"、"责任编辑"、"责编"、"版面编辑"
   - "版权"、"ICP"、"京ICP"、"订阅电话"、"本报邮箱"
   - "分享到"、"扫描二维码"、"点击下载"
   - "PDF"、"打印"、"字体"、"放大"、"缩小"、"默认"
3. **保留标签**：只保留 `<p>`、`<h1>`~`<h6>`、`<img>`、`<blockquote>`、`<ul>`、`<ol>`、`<li>`、`<br>`、`<strong>`、`<em>`、`<span>`（只保留文本，去掉样式）
4. **图片处理**：
   - 所有 `<img>` 必须含 `src`，否则移除
   - 相对路径转为绝对 URL（base URL 为该报官网）
   - 加上 `style="max-width:100%;height:auto;display:block;margin:16px auto"`
5. **空段落**：移除空的 `<p></p>` 和只有空白字符的段落
6. **连续换行**：压缩为单个 `\n`

---

## 7. 各报纸数据获取详情

### 7.1 组织人事报

**特征**：唯一有正经 JSON API 的报纸，适配器最简单。

**获取期数列表：**
```
POST https://www.zuzhirenshi.com/api/welcome/selectPastDianZiBao
Content-Type: application/json
Body: {"years": "2026", "months": "06"}
```
需要按年月循环请求。返回：
```json
{
  "code": 200,
  "data": [{"id": "xxx", "columnName": "第4274期", "releaseDate": "2026-06-23"}]
}
```

**获取某期文章：**
```
POST https://www.zuzhirenshi.com/api/welcome/dianZiBaoHomePage
Body: {"id": "xxx"}
```
返回 `dianzibaoShowList[]`，每个元素有 `columnName`（版面名）和 `areaCoordinateList[]`（文章，含 `newsId`、`newsName`）。

**获取文章内容：**
```
GET https://www.zuzhirenshi.com/api/welcome/selectNews?id={newsId}
```
返回 `{newName, showTitle, author, newContent (HTML), createTime}`。

### 7.2 学习时报

**获取期数列表：**
```
GET https://www.ccps.gov.cn/xxsb/index.shtml
```
从所有 `<a href>` 中匹配 `/cntheory/(\d{4}-\d{2})/(\d{2})`，合并为 `YYYY-MM-DD`，去重排序。

**获取版面文章：**
```
GET https://paper.studytimes.cn/cntheory/{YYYY-MM}/{DD}/node_{1..12}.html
```
每个版面一页。提取所有指向 `content_(\d+).html` 的链接。

**获取文章内容：**
```
GET https://paper.studytimes.cn/cntheory/{YYYY-MM}/{DD}/content_{id}.html
```
提取：
- 标题：`<h1>` 文本
- 作者：含"字数:"的 `<p>`，取前面的文本
- 正文：`<h1>` 之后所有 `<p>`，过滤导航/版权/日期行，长度 ≥ 10 字符

### 7.3 人民日报

**目标 URL 模式：**
```
版面页: http://paper.people.com.cn/rmrb/html/{YYYY-MM}/DD/nbs.D110000renmrb_01.htm
文章页: http://paper.people.com.cn/rmrb/html/{YYYY-MM}/DD/content_xxxxx.htm
```

**实现注意**：
- 先访问首页分析日期导航结构
- 此站可能动态加载，需查看 HTML 中的 JS 变量
- 文章提取逻辑同学习时报

### 7.4 南方日报

**目标 URL 模式：**
```
版面页: https://epaper.southcn.com/nfdaily/html/{YYYY-MM}/DD/node_1.htm
文章页: https://epaper.southcn.com/nfdaily/html/{YYYY-MM}/DD/content_xxxxx.htm
```

**实现注意**：
- 首页可能有 JS 跳转
- 其余同学习时报

---

## 8. JavaScript 路由规范

`app.js` 实现一个简单的 hash 路由：

```javascript
// 路由表
const routes = {
    '#/':                  renderHome,      // 首页
    '#/paper/:id':         renderIssues,    // 期数列表
    '#/paper/:id/:date':   renderArticles,  // 文章列表
    '#/reader/:id/:aid':   renderReader     // 阅读器
}

// 参数格式
// id: "studytimes"|"zuzhirenshi"|"renminribao"|"nanfangribao"
// date: "2026-06-23"
// aid: 文章 id
```

路由变化时触发渲染，渲染函数负责：
1. 清空 `#app` 容器
2. 生成对应的 HTML
3. 绑定事件（通过事件委托，不直接用 onclick）

---

## 9. Android 壳代码

### MainActivity.java

```java
package com.newspaper.helper;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import androidx.appcompat.app.AppCompatActivity;

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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ...");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://") || url.startsWith("http")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }
}
```

### AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="读报"
        android:usesCleartextTraffic="true"
        android:theme="@style/AppTheme">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 10. 项目文件结构

```
android/
├── build.gradle.kts                   根构建文件
├── settings.gradle.kts                项目设置
├── gradle.properties                  Gradle 属性
├── gradlew                            Gradle wrapper (linux/mac)
├── gradlew.bat                        Gradle wrapper (windows)
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── 制作APK.bat                         一键打包脚本
│
└── app/
    ├── build.gradle.kts               App 构建文件
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/strings.xml
        │   ├── values/themes.xml
        │   └── drawable/ic_launcher.xml
        ├── java/com/newspaper/helper/
        │   └── MainActivity.java
        └── assets/www/
            ├── index.html             路由入口
            ├── css/app.css            全部样式
            ├── js/app.js              路由+渲染引擎
            ├── js/reader.js           阅读器渲染
            └── js/adapters/
                ├── base.js            接口声明
                ├── studytimes.js
                ├── zuzhirenshi.js
                ├── renminribao.js
                └── nanfangribao.js
```

---

## 11. build.gradle.kts 关键配置

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
}
android {
    namespace = "com.newspaper.helper"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.newspaper.helper"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
}
```

---

## 12. 打包脚本 (制作APK.bat)

```
1. 检测 JDK 17：java -version 中必须包含 "17.0"
2. 如果 android/sdk/ 不存在：
   a. 下载 Android command line tools (Linux/Mac/Windows)
   b. 解压到 android/sdk/
   c. yes | sdkmanager "platforms;android-34"
3. 如果 gradlew 不存在：
   a. 下载 Gradle 8.4 并生成 wrapper
4. 创建 local.properties: sdk.dir=当前目录/sdk
5. gradlew assembleDebug
6. 输出: android/app/build/outputs/apk/debug/app-debug.apk

如果 JDK 17 不存在，报错并提示下载地址：
https://adoptium.net/temurin/releases/?version=17
```

---

## 13. 开发实现顺序

| 步 | 内容 | 可测试 |
|----|------|--------|
| 1 | Android 壳（MainActivity + build config） | 空页面 APK |
| 2 | index.html + 路由框架 + 首页网格 | 首页能看到 4 卡片 |
| 3 | 期数列表页 renderIssues() | 点卡片看到期数 |
| 4 | 文章列表页 renderArticles() | 点期数看到文章 |
| 5 | 阅读器页 renderReader() | 点文章看到正文 |
| 6 | 全 CSS 调优（纯黑+白底） | 视觉定稿 |
| 7 | 组织人事报适配器（有 API 最简单） | 第一个可读报纸 |
| 8 | 学习时报适配器 | 第二个 |
| 9 | 人民日报适配器 | 第三个 |
| 10 | 南方日报适配器 | 第四个 |
| 11 | 打包脚本 + 完整测试 | 最终 APK |

---

## 14. 边界与注意事项

1. **URL 有效性**：人民日报和南方日报的 URL 模式基于经验推断，实现时需用近期日期实地测试验证
2. **反爬处理**：所有请求设 `User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ...`，请求间隔 ≥ 1 秒，失败重试 2 次
3. **图片加载**：图片从原站加载，可能慢或失败，设 `loading="lazy"`，失败时显示占位
4. **无数据状态**：无数据时显示"暂无内容"友好提示，不白屏
5. **错误状态**：网络错误时显示"加载失败，点击重试"按钮
6. **离线**：不要求离线阅读，无网络时显示"请检查网络连接"
7. **内存缓存**：已读文章内容可缓存在 JS 对象中，页面刷新即失效，不作持久化
8. **屏幕适配**：最小 320dp，content 区左右 padding 随屏幕宽度调整
9. **Android 版本**：最低 API 21（Android 5.0）
10. **无广告**：App 内不出任何形式的广告和付费内容
