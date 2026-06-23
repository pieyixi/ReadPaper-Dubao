/**
 * 人民日报适配器
 * URL: http://paper.people.com.cn/rmrb/pc/
 *
 * 文章 ID 格式: "YYYYMMDD::contentId"
 */
class RenMinRiBaoAdapter {
    constructor() {
        this.LAYOUT = 'http://paper.people.com.cn/rmrb/pc/layout';
        this.CONTENT = 'http://paper.people.com.cn/rmrb/pc/content';
        this._issuesCache = null;
        this.MAX_PAGES = 12;  // 前12版足够覆盖要闻/评论/理论/经济/政治
    }

    _d8(daysAgo) {
        const d = new Date(); d.setDate(d.getDate() - daysAgo);
        return d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0');
    }
    _toDash(d8) { return d8.substring(0, 4) + '-' + d8.substring(4, 6) + '-' + d8.substring(6, 8); }
    // URL 中日期格式是 YYYYMM/DD（不是 YYYYMMDD！）
    _ymd(d8) { return d8.substring(0, 6) + '/' + d8.substring(6, 8); }

    async getIssues() {
        // 人民日报每天出版，直接生成最近30天日期（零网络请求）
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
            const ymd = this._ymd(d8);

            // 并行探测所有版面
            const tasks = [];
            for (let n = 1; n <= this.MAX_PAGES; n++) {
                const ns = String(n).padStart(2, '0');
                tasks.push({ url: `${this.LAYOUT}/${ymd}/node_${ns}.html` });
            }

            const results = await AdapterUtils.fetchBatch(tasks, 5);
            const articles = [];

            for (let i = 0; i < results.length; i++) {
                if (results[i].status !== 'fulfilled') continue;
                const html = await results[i].value.text();
                if (html.length < 5000) continue;

                const doc = AdapterUtils.parseHTML(html);
                const nodeNum = i + 1;
                const ns = String(nodeNum).padStart(2, '0');

                // 版面名
                let secName = '';
                doc.querySelectorAll('.swiper-slide a').forEach(a => {
                    if ((a.getAttribute('href') || '').includes(`node_${ns}`))
                        secName = (a.textContent || '').trim();
                });
                if (!secName) {
                    const ban = doc.querySelector('.ban');
                    if (ban) secName = (ban.textContent || '').trim();
                }
                if (!secName) secName = '第' + ns + '版';

                // 文章
                doc.querySelectorAll('.news-list a[href]').forEach(a => {
                    const title = (a.textContent || '').trim();
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
            const ymd = this._ymd(d8);

            const url = `${this.CONTENT}/${ymd}/content_${cid}.html`;
            const html = await AdapterUtils.fetchText(url);
            if (html.length < 2000) return null;
            const doc = AdapterUtils.parseHTML(html);

            // 标题
            let title = '';
            const mainH1 = doc.querySelector('.article h1, #ozoom h1');
            if (mainH1) title = (mainH1.textContent || '').trim();
            if (!title) {
                const h1 = doc.querySelector('h1');
                if (h1) title = (h1.textContent || '').trim();
            }
            if (!title) { const t = doc.querySelector('title'); if (t) title = (t.textContent || '').trim(); }

            // 作者：.sec 或 enpproperty 注释
            let author = '';
            const sec = doc.querySelector('.sec');
            if (sec) author = (sec.textContent || '').replace(/《人民日报》.*?版\)?/g, '').trim();
            if (!author) {
                const m = html.match(/<author>([^<]+)<\/author>/);
                if (m) author = m[1].trim();
            }

            // 正文：取 #ozoom 全部内容（可能含 enpcontent 之外的图片）
            let bodyHtml = '';
            const oz = doc.querySelector('#ozoom');
            if (oz) {
                bodyHtml = oz.innerHTML.replace(/<!--enpproperty[\s\S]*?-->/g, '');
            } else {
                const encMatch = html.match(/<!--enpcontent-->([\s\S]*?)<!--\/enpcontent-->/);
                if (encMatch) bodyHtml = encMatch[1];
            }

            // 图片路径以文章页 URL 为基准
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
