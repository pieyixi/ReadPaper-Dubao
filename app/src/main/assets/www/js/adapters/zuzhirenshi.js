/**
 * 组织人事报适配器
 * URL: https://www.zuzhirenshi.com
 * 数据方式: JSON API
 *
 * 文章 ID: newsId (UUID)，可直接用于 getArticle
 */
class ZuZhiRenShiAdapter {
    constructor() {
        this.API = 'https://www.zuzhirenshi.com/api/welcome';
        this._issuesCache = null;
    }

    async getIssues() {
        try {
            const now = new Date();
            const Y = now.getFullYear();
            const M = now.getMonth() + 1;

            // 并行查询所有需要的月份
            const months = [];
            for (let m = 1; m <= M; m++) months.push({ y: Y, m: String(m).padStart(2, '0') });
            months.push({ y: Y - 1, m: '12' });
            months.push({ y: Y - 1, m: '11' });

            const tasks = months.map(({ y, m }) => ({
                url: this.API + '/selectPastDianZiBao',
                options: {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ years: String(y), months: m })
                }
            }));

            const results = await AdapterUtils.fetchBatch(tasks, 3);
            const allIssues = [];

            for (const r of results) {
                if (r.status !== 'fulfilled') continue;
                try {
                    const data = await r.value.json();
                    if (data && data.code === 200 && Array.isArray(data.data)) {
                        data.data.forEach(item => {
                            if (item.releaseDate) {
                                allIssues.push({
                                    date: item.releaseDate.trim(),
                                    issue: item.columnName || '',
                                    articleCount: null,
                                    _id: item.id
                                });
                            }
                        });
                    }
                } catch (e) { /* skip */ }
            }

            allIssues.sort((a, b) => b.date.localeCompare(a.date));
            this._issuesCache = allIssues;
            return allIssues;
        } catch (e) { return []; }
    }

    async getArticles(date) {
        try {
            if (!this._issuesCache) await this.getIssues();
            const issue = (this._issuesCache || []).find(i => i.date === date);
            if (!issue || !issue._id) return [];

            const data = await AdapterUtils.fetchJSON(this.API + '/dianZiBaoHomePage', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: issue._id })
            });

            if (!data || data.code !== 200) return [];
            const showList = data.data?.dianzibaoShowList || data.dianzibaoShowList || [];
            const articles = [];

            showList.forEach(section => {
                const secName = section.columnName || '未分类';
                (section.areaCoordinateList || []).forEach(item => {
                    if (item.newsId && item.newsName) {
                        articles.push({
                            id: item.newsId,  // UUID，getArticle 直接用
                            title: item.newsName,
                            author: '',
                            section: secName,
                            wordCount: null
                        });
                    }
                });
            });

            return articles;
        } catch (e) { return []; }
    }

    async getArticle(id) {
        try {
            const url = this.API + '/selectNews?id=' + encodeURIComponent(id);
            const resp = await AdapterUtils.fetchJSON(url);
            if (!resp || resp.code !== 200) return null;

            // API 返回 {code:200, data:{showTitle, newContent, author, ...}}
            const d = resp.data || resp;
            const title = d.showTitle || d.newName || '';
            const author = d.author || '';
            const date = (d.createTime || '').split(' ')[0];
            const section = d.columnName || '';
            const rawHtml = d.newContent || d.content || '';

            return {
                title, author, date, section,
                bodyHtml: Reader.cleanBody(rawHtml, 'https://www.zuzhirenshi.com/'),
                images: []
            };
        } catch (e) { return null; }
    }
}
