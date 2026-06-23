/**
 * 适配器公共工具 — 无节流版（速度优先）
 */
const AdapterUtils = {
    async sleep(ms) { return new Promise(r => setTimeout(r, ms)); },

    /**
     * 无节流 fetchWithRetry — 单次请求直接发出
     */
    async fetchWithRetry(url, options = {}, retries = 2) {
        for (let i = 0; i <= retries; i++) {
            try {
                const resp = await fetch(url, options);
                if (resp.ok) return resp;
                if (i < retries) await this.sleep(500);
            } catch (e) {
                if (i < retries) await this.sleep(500);
                else throw e;
            }
        }
        throw new Error('Fetch failed: ' + url);
    },

    /**
     * 批量并行请求（并发数默认 6，批次间无延迟）
     */
    async fetchBatch(tasks, concurrency = 6) {
        const results = [];
        for (let i = 0; i < tasks.length; i += concurrency) {
            const batch = tasks.slice(i, i + concurrency);
            const batchResults = await Promise.allSettled(
                batch.map(t => this.fetchWithRetry(t.url, t.options || {}, 1))
            );
            results.push(...batchResults);
        }
        return results;
    },

    async fetchText(url, options = {}) {
        const resp = await this.fetchWithRetry(url, options);
        return resp.text();
    },

    async fetchJSON(url, options = {}) {
        const resp = await this.fetchWithRetry(url, options);
        return resp.json();
    },

    parseHTML(html) {
        const parser = new DOMParser();
        return parser.parseFromString(html, 'text/html');
    },

    getWeekday(dateStr) {
        const d = new Date(dateStr + 'T00:00:00');
        const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
        return days[d.getDay()];
    },

    /** localStorage 缓存辅助 */
    cacheGet(key) {
        try {
            const raw = localStorage.getItem('np_' + key);
            if (!raw) return null;
            const entry = JSON.parse(raw);
            if (Date.now() - entry.ts > 30 * 60 * 1000) { localStorage.removeItem('np_' + key); return null; }
            return entry.data;
        } catch (e) { return null; }
    },
    cacheSet(key, data) {
        try { localStorage.setItem('np_' + key, JSON.stringify({ ts: Date.now(), data })); } catch (e) {}
    }
};
