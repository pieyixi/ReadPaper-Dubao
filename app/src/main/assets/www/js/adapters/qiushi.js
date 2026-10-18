/**
 * 求是杂志适配器
 * URL: https://www.qstheory.cn
 *
 * 结构: 目录页(年份列表) → 年份页(期数列表) → 期数页(文章列表) → 文章
 * 文章 ID 格式: "YYYYMMDD::uuid" (自包含 URL 信息)
 *
 * 与日报的区别:
 * - 求是是半月刊，按期数组织，不是按日期
 * - getYears() 返回年份列表
 * - getIssues(year) 返回该年期数列表
 * - 没有版面概念，文章直接平铺
 */
class QiushiAdapter {
    constructor() {
        this.BASE = 'https://www.qstheory.cn';
        this.DIR_URL = 'https://www.qstheory.cn/qs/mulu.htm';
        this._yearIndex = null;
        this._issuesCache = {};
    }

    /**
     * 获取所有年份列表（第一级）
     * @returns {Promise<Array<{year:string, url:string}>>}
     */
    async getYears() {
        try {
            const cached = AdapterUtils.cacheGet('qs_years_v2');
            if (cached) { this._yearIndex = cached; return cached; }

            const html = await AdapterUtils.fetchText(this.DIR_URL);
            const doc = AdapterUtils.parseHTML(html);
            const years = [];
            const seen = new Set();

            // 新版目录页结构: <div class="book"><a href="...">2026年</a></div>
            // 兼容旧版 .booktitle，并回退到所有含“YYYY年”文字的年链接
            doc.querySelectorAll('.book a, .booktitle a, a[href]').forEach(a => {
                const text = (a.textContent || '').trim();
                const m = text.match(/(\d{4})年/);
                if (!m) return;
                let href = a.getAttribute('href') || '';
                if (href.startsWith('//')) href = 'https:' + href;
                if (!/^https?:\/\//.test(href)) {
                    const base = this.DIR_URL.substring(0, this.DIR_URL.lastIndexOf('/') + 1);
                    try { href = new URL(href, base).href; } catch (e) { return; }
                }
                if (seen.has(href)) return;
                seen.add(href);
                years.push({ year: m[1], url: href });
            });

            years.sort((a, b) => parseInt(b.year) - parseInt(a.year));
            this._yearIndex = years;
            AdapterUtils.cacheSet('qs_years_v2', years);
            return years;
        } catch (e) {
            return [];
        }
    }

    /**
     * 获取某年的期数列表（第二级）
     * 同时提取每期文章，缓存供 getArticles 零请求使用
     * @param {string} year - "2026"
     * @returns {Promise<Array<{date:string, issue:string, articleCount:number}>>}
     */
    async getIssues(year) {
        try {
            // 先确保年份索引存在
            if (!this._yearIndex) await this.getYears();
            const yearEntry = (this._yearIndex || []).find(y => y.year === String(year));
            if (!yearEntry || !yearEntry.url) return [];

            // 读缓存
            const cacheKey = 'qs_issues_' + year;
            const cached = AdapterUtils.cacheGet(cacheKey);
            if (cached) { this._issuesCache[year] = cached; return cached; }

            const html = await AdapterUtils.fetchText(yearEntry.url);
            const doc = AdapterUtils.parseHTML(html);
            const issues = [];

            // 年份页结构:
            // <p><a href="..."><strong>《求是》2026年第1期</strong></a></p>
            // <p><a href="article1">标题1</a> /作者</p>
            // ...
            const paragraphs = doc.querySelectorAll('#detailContent p');
            let currentIssue = null;

            paragraphs.forEach(p => {
                const links = p.querySelectorAll('a[href]');
                if (!links.length) return;

                const firstLink = links[0];
                const title = (firstLink.textContent || '').trim();
                let href = firstLink.getAttribute('href') || '';
                if (href.includes('ebook.qstheory.cn') || href.includes('mulu.htm')) return;

                // 相对路径转绝对
                if (!href.startsWith('http')) {
                    if (href.startsWith('../')) href = href.substring(3);
                    if (!href.startsWith('/')) href = '/' + href;
                    href = this.BASE + href;
                }

                const urlMatch = href.match(/qstheory\.cn\/(\d{8})\/([a-f0-9]+)\/c\.html/);
                if (!urlMatch) return;

                // 判断是期数标题还是文章
                const issueMatch = title.match(/《求是》\s*(\d{4}年第\d+期)/);
                if (issueMatch) {
                    // 新的期数
                    currentIssue = {
                        date: issueMatch[1],
                        issue: issueMatch[1],
                        articleCount: null,
                        _issueUrl: href,
                        _publishDate: urlMatch[1],
                        _articles: []
                    };
                    issues.push(currentIssue);
                } else if (currentIssue && title.length >= 2) {
                    // 当前期的一篇文章
                    let author = '';
                    const pText = p.textContent || '';
                    const am = pText.match(/\/\s*([^\n<]+)/);
                    if (am) author = am[1].trim().replace(/\s+/g, '');

                    currentIssue._articles.push({
                        id: `${urlMatch[1]}::${urlMatch[2]}`,
                        title: title,
                        author: author,
                        section: '',
                        wordCount: null
                    });
                }
            });

            // 按期号倒序（从新到旧）
            issues.sort((a, b) => {
                const nA = parseInt(a.issue.match(/第(\d+)期/)?.[1] || '0');
                const nB = parseInt(b.issue.match(/第(\d+)期/)?.[1] || '0');
                return nB - nA;
            });

            // 缓存每期文章列表
            issues.forEach(iss => {
                if (iss._articles && iss._articles.length > 0) {
                    iss.articleCount = iss._articles.length;
                    AdapterUtils.cacheSet('arts_qiushi:' + iss.date, iss._articles);
                }
            });

            this._issuesCache[year] = issues;
            AdapterUtils.cacheSet(cacheKey, issues);
            return issues;
        } catch (e) {
            return [];
        }
    }

    /**
     * 获取某期的文章列表（第三级）
     * @param {string} date - 期号如 "2026年第1期"
     * @returns {Promise<Array<{id:string, title:string, author:string, section:string, wordCount:number}>>}
     */
    async getArticles(date) {
        try {
            // 优先读缓存（getIssues 已经把文章一起缓存了）
            const ck = 'arts_qiushi:' + date;
            const cached = AdapterUtils.cacheGet(ck);
            if (cached) return cached;

            // 缓存没有 → 找到期数页 URL 再抓
            let issueUrl = null;

            // 从各年份缓存中找
            for (const y in this._issuesCache) {
                const found = this._issuesCache[y].find(i => i.date === date);
                if (found) { issueUrl = found._issueUrl; break; }
            }

            // 还没找到 → 从年份索引找
            if (!issueUrl && this._yearIndex) {
                const yearMatch = date.match(/(\d{4})年/);
                if (yearMatch) {
                    const ye = this._yearIndex.find(y => y.year === yearMatch[1]);
                    if (ye) {
                        const issues = await this.getIssues(ye.year);
                        const found = issues.find(i => i.date === date);
                        if (found) issueUrl = found._issueUrl;
                    }
                }
            }

            if (!issueUrl) return [];

            // 抓期数页
            const html = await AdapterUtils.fetchText(issueUrl);
            const doc = AdapterUtils.parseHTML(html);
            const articles = [];
            const dc = doc.querySelector('#detailContent');
            if (!dc) return [];

            dc.querySelectorAll('a[href]').forEach(a => {
                const title = (a.textContent || '').trim();
                let href = a.getAttribute('href') || '';
                if (href.includes('ebook.qstheory.cn') || href.includes('mulu.htm')) return;
                if (title.includes('《求是》') && /年\s*$/.test(title)) return;
                if (title.length < 2) return;

                if (!href.startsWith('http')) {
                    if (href.startsWith('../')) href = href.substring(3);
                    if (!href.startsWith('/')) href = '/' + href;
                    href = this.BASE + href;
                }

                const um = href.match(/qstheory\.cn\/(\d{8})\/([a-f0-9]+)\/c\.html/);
                if (!um) return;

                let author = '';
                const pp = a.closest('p');
                if (pp) {
                    const am = (pp.textContent || '').match(/\/\s*([^\n<]+)/);
                    if (am) author = am[1].trim().replace(/\s+/g, '');
                }

                articles.push({
                    id: `${um[1]}::${um[2]}`,
                    title: title,
                    author: author,
                    section: '',
                    wordCount: null
                });
            });

            AdapterUtils.cacheSet(ck, articles);
            return articles;
        } catch (e) {
            return [];
        }
    }

    /**
     * 获取单篇文章完整内容（第四级）
     * @param {string} id - "YYYYMMDD::uuid"
     * @returns {Promise<{title:string, author:string, date:string, section:string, bodyHtml:string}>}
     */
    async getArticle(id) {
        try {
            const parts = id.split('::');
            if (parts.length !== 2) return null;
            const [publishDate, uuid] = parts;

            const url = `${this.BASE}/${publishDate}/${uuid}/c.html`;
            const html = await AdapterUtils.fetchText(url);
            if (html.length < 1000) return null;
            const doc = AdapterUtils.parseHTML(html);

            // 标题
            let title = '';
            const h1 = doc.querySelector('.inner h1, h1');
            if (h1) title = (h1.textContent || '').trim();

            // 作者
            let author = '';
            doc.querySelectorAll('.appellation').forEach(el => {
                const t = (el.textContent || '').trim();
                if (/^作者[：:]/.test(t)) author = t.replace(/^作者[：:]/, '').trim();
            });

            // 来源
            let source = '';
            doc.querySelectorAll('.appellation').forEach(el => {
                const t = (el.textContent || '').trim();
                if (/^来源[：:]/.test(t)) source = t.replace(/^来源[：:]/, '').trim();
            });

            // 日期
            let date = '';
            const pubtime = doc.querySelector('.pubtime');
            if (pubtime) {
                const dm = (pubtime.textContent || '').match(/(\d{4}-\d{2}-\d{2})/);
                if (dm) date = dm[1];
            }

            // 正文
            let bodyHtml = '';
            const dc = doc.querySelector('#detailContent');
            if (dc) bodyHtml = dc.innerHTML;

            const baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
            return {
                title: title,
                author: author,
                date: date,
                section: source || '求是',
                bodyHtml: Reader.cleanBody(bodyHtml, baseUrl),
                images: []
            };
        } catch (e) {
            return null;
        }
    }
}
