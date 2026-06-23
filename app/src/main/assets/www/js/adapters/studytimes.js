/**
 * 学习时报适配器
 * URL: https://paper.studytimes.cn
 *
 * 文章 ID 格式: "YYYY-MM/DD::content_NNNNNNN" (自包含 URL 信息)
 */
class StudyTimesAdapter {
    constructor() {
        this.BASE = 'https://paper.studytimes.cn';
        this.INDEX_URL = 'https://www.ccps.gov.cn/xxsb/index.shtml';
        this._issuesCache = null;
    }

    async getIssues() {
        try {
            const html = await AdapterUtils.fetchText(this.INDEX_URL);
            const doc = AdapterUtils.parseHTML(html);
            const dateSet = new Set();
            doc.querySelectorAll('a[href]').forEach(a => {
                const m = (a.getAttribute('href') || '').match(/\/cntheory\/(\d{4}-\d{2})\/(\d{2})/);
                if (m) dateSet.add(m[1] + '-' + m[2]);
            });
            const issues = Array.from(dateSet)
                .map(d => ({ date: d, issue: '', articleCount: null }))
                .sort((a, b) => b.date.localeCompare(a.date));
            this._issuesCache = issues;
            return issues;
        } catch (e) { return []; }
    }

    async getArticles(date) {
        try {
            const [Y, M, D] = date.split('-');
            if (!Y) return [];
            const dp = `${Y}-${M}/${D}`;

            // 第一步：获取 node_1 读取版面目录（找出所有版面名称和节点号）
            const node1Url = `${this.BASE}/cntheory/${dp}/node_1.html`;
            let html;
            try { html = await AdapterUtils.fetchText(node1Url); } catch (e) { return []; }
            if (html.length < 3000) return [];

            const doc = AdapterUtils.parseHTML(html);

            // 提取版面列表
            const sections = [];
            doc.querySelectorAll('.layout-catalogue-list .layout-catalogue-item a[href*="node_"]').forEach(a => {
                const nm = (a.getAttribute('href') || '').match(/node_(\d+)\.html/);
                if (nm) sections.push({ num: parseInt(nm[1]), name: (a.textContent || '').trim() });
            });
            if (sections.length === 0) {
                for (let i = 1; i <= 12; i++) sections.push({ num: i, name: '第' + i + '版' });
            }

            // 第二步：并行获取所有版面的文章列表
            const tasks = sections.map(sec => ({
                url: sec.num === 1 ? node1Url : `${this.BASE}/cntheory/${dp}/node_${sec.num}.html`
            }));

            const results = await AdapterUtils.fetchBatch(tasks, 4);
            const articles = [];

            for (let i = 0; i < results.length; i++) {
                const r = results[i];
                if (r.status !== 'fulfilled') continue;
                const secHtml = await r.value.text();
                if (secHtml.length < 3000) continue;

                const secDoc = i === 0 ? doc : AdapterUtils.parseHTML(secHtml);
                const secName = sections[i].name;

                secDoc.querySelectorAll('.news-list a[href*="content_"]').forEach(a => {
                    const title = (a.textContent || '').trim();
                    const cm = (a.getAttribute('href') || '').match(/content_(\d+)\.html/);
                    if (cm && title.length >= 2) {
                        articles.push({
                            id: `${dp}::${cm[1]}`,
                            title: title,
                            author: '',
                            section: secName,
                            wordCount: null
                        });
                    }
                });
            }

            return articles;
        } catch (e) { return []; }
    }

    async getArticle(id) {
        try {
            // 解析自包含 ID: "YYYY-MM/DD::contentId"
            const parts = id.split('::');
            if (parts.length !== 2) return null;
            const [dp, contentId] = parts;

            const url = `${this.BASE}/cntheory/${dp}/content_${contentId}.html`;
            const html = await AdapterUtils.fetchText(url);
            if (html.length < 2000) return null;
            const doc = AdapterUtils.parseHTML(html);

            // 标题：优先取 #news_content 内的 h1
            let title = '';
            const mainH1 = doc.querySelector('#news_content h1');
            if (mainH1) title = (mainH1.textContent || '').trim();
            if (!title) {
                const h1 = doc.querySelector('h1');
                if (h1) title = (h1.textContent || '').trim();
            }

            // 作者
            let author = '';
            const ds = doc.querySelector('.datesource');
            if (ds) {
                const t = (ds.textContent || '').trim();
                const ap = t.split(/字数|字\s*[:：]/)[0].trim();
                if (ap && ap.length < 50) author = ap;
            }

            // 正文
            let bodyHtml = '';
            const content = doc.querySelector('cms-content#content, #content, #news_content');
            if (content) {
                const parts = [];
                content.querySelectorAll('p').forEach(p => {
                    if ((p.textContent || '').trim().length >= 5) parts.push(p.outerHTML);
                });
                bodyHtml = parts.join('\n');
            }

            const date = dp.replace('/', '-').split('/')[0];
            const baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
            return {
                title, author,
                date: date,
                section: '',
                bodyHtml: Reader.cleanBody(bodyHtml, baseUrl),
                images: []
            };
        } catch (e) { return null; }
    }
}
