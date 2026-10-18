/**
 * 南方日报适配器
 * URL: https://epaper.nfnews.com/nfdaily
 *
 * 文章 ID 格式: "YYYYMMDD::contentId"
 * 注意: 改版后版面页和文章页都迁移到 nfnews.com
 */
class NanFangRiBaoAdapter {
    constructor() {
        this.BASE = 'https://epaper.nfnews.com/nfdaily/html';
        this.NFNEWS = this.BASE;
        this._issuesCache = null;
    }

    _d8(daysAgo) {
        const d = new Date(); d.setDate(d.getDate() - daysAgo);
        return d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0');
    }
    _toDash(d8) { return d8.substring(0, 4) + '-' + d8.substring(4, 6) + '-' + d8.substring(6, 8); }
    _ymd(d8) { return d8.substring(0, 6) + '/' + d8.substring(6, 8); }

    async getIssues() {
        // 南方日报每天出版，直接生成最近30天日期
        const issues = [];
        for (let i = 0; i < 30; i++) {
            const d = new Date(); d.setDate(d.getDate() - i);
            issues.push({
                date: d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'),
                issue: '', articleCount: null
            });
        }
        this._issuesCache = issues;
        return issues;
    }

    async getArticles(date) {
        try {
            const d8 = date.replace(/-/g, '');
            if (d8.length !== 8) return [];

            // 获取首页版面列表
            let nodes = [];
            try {
                const idxHtml = await AdapterUtils.fetchText(`${this.BASE}/index.html`);
                const idxDoc = AdapterUtils.parseHTML(idxHtml);
                idxDoc.querySelectorAll('#list li a[href]').forEach(a => {
                    const href = a.getAttribute('href') || '';
                    const nm = href.match(/(node_\w+\.html)/i);
                    if (nm) {
                        const text = (a.textContent || '').trim();
                        const lines = text.split('\n').filter(l => l.trim());
                        nodes.push({
                            name: nm[1],
                            section: lines.length > 1 ? lines[lines.length - 1].trim() : text
                        });
                    }
                });
            } catch (e) { /* fallback below */ }

            if (nodes.length === 0) {
                for (let i = 1; i <= 8; i++) {
                    nodes.push({ name: `node_A0${i}.html`, section: `第A0${i}版` });
                }
            }

            // 只取前12个版面加速加载
            nodes = nodes.slice(0, 12);

            // 并行获取所有版面
            const ymd = this._ymd(d8);
            const tasks = nodes.map(n => ({ url: `${this.BASE}/${ymd}/${n.name}` }));
            const results = await AdapterUtils.fetchBatch(tasks, 5);
            const articles = [];

            for (let i = 0; i < results.length; i++) {
                if (results[i].status !== 'fulfilled') continue;
                const html = await results[i].value.text();
                if (html.length < 5000) continue;
                const doc = AdapterUtils.parseHTML(html);

                let secName = nodes[i].section;
                if (!secName) {
                    const secEl = doc.querySelector('.layout-list-item-text, .layout-list .layout-list-item-text');
                    if (secEl) secName = (secEl.textContent || '').trim();
                }

                doc.querySelectorAll('a.article-list-item[href]').forEach(a => {
                    const title = ((a.querySelector('p') || a).textContent || '').trim();
                    const cm = (a.getAttribute('href') || '').match(/content[_-]?(\d+)\.html/i);
                    if (cm && title.length >= 2) {
                        articles.push({
                            id: `${d8}::${cm[1]}`,
                            title, author: '', section: secName, wordCount: null
                        });
                    }
                });
            }

            return articles;
        } catch (e) { return []; }
    }

    async getArticle(id) {
        try {
            const parts = id.split('::');
            if (parts.length !== 2) return null;
            const [d8, cid] = parts;

            const url = `${this.NFNEWS}/${this._ymd(d8)}/content_${cid}.html`;
            const html = await AdapterUtils.fetchText(url, {
                headers: { 'Referer': `${this.BASE}/` }
            });
            if (html.length < 2000) return null;
            const doc = AdapterUtils.parseHTML(html);

            // 标题
            let title = '';
            const titleEl = doc.querySelector('.article-title, h1');
            if (titleEl) title = (titleEl.textContent || '').trim();
            if (!title) {
                const titleMatch = html.match(/<!--enpproperty[\s\S]*?<title>([\s\S]*?)<\/title>/);
                if (titleMatch) {
                    const titleDoc = new DOMParser().parseFromString(titleMatch[1], 'text/html');
                    title = (titleDoc.body.textContent || '').trim();
                }
            }
            if (!title) { const t = doc.querySelector('title'); if (t) title = (t.textContent || '').trim(); }

            // 作者：优先取 enpproperty 里的真实作者，meta 里的“南方日报”只是报社名
            let author = '';
            const authorMatch = html.match(/<!--enpproperty[\s\S]*?<author>([^<]+)<\/author>/);
            if (authorMatch) author = authorMatch[1].trim();
            if (!author) {
                const ma = doc.querySelector('meta[name="author"]');
                if (ma) author = (ma.getAttribute('content') || '').trim();
            }

            // 正文：优先取 enpcontent，避免把页面里的 html/body 壳和上一篇列表一起带进来
            let bodyHtml = '';
            const encMatch = html.match(/<!--enpcontent-->([\s\S]*?)<!--\/enpcontent-->/);
            if (encMatch) {
                bodyHtml = encMatch[1];
            } else {
                const contentDiv = doc.querySelector('#content');
                if (contentDiv) {
                    bodyHtml = contentDiv.innerHTML
                        .replace(/<div class="gallery"><\/div>/g, '')
                        .replace(/<!--enpproperty[\s\S]*?-->/g, '')
                        .replace(/<div class="font_change[^>]*>[\s\S]*?<\/div>/g, '');
                }
            }

            // 图片基准 URL 为文章页目录
            const baseUrl = url.substring(0, url.lastIndexOf('/') + 1);

            return {
                title, author,
                date: this._toDash(d8),
                section: '',
                bodyHtml: Reader.cleanBody(bodyHtml, baseUrl),
                images: []
            };
        } catch (e) { return null; }
    }
}
