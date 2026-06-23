/**
 * 读报 App - 路由 & 渲染引擎
 */
const APP = {
    newspapers: [
        { id: 'studytimes',   name: '学习时报',   subtitle: '中共中央党校主办',    color: 'st' },
        { id: 'zuzhirenshi',  name: '组织人事报',  subtitle: '中国人事报刊社',      color: 'zz' },
        { id: 'renminribao',  name: '人民日报',    subtitle: '中共中央机关报',      color: 'rmrb' },
        { id: 'nanfangribao', name: '南方日报',    subtitle: '中共广东省委机关报',   color: 'nfrb' },
    ],

    cache: {},
    _adapters: {},
    _navToken: 0,  // 导航令牌——防止旧页面覆盖新页面

    init() {
        // 恢复全局主题设置
        try {
            const saved = JSON.parse(localStorage.getItem('np_theme') || '{}');
            if (saved.dark !== undefined) Reader._settings.dark = saved.dark;
            if (saved.eye !== undefined) Reader._settings.eye = saved.eye;
            if (saved.fontSize !== undefined) Reader._settings.fontSize = saved.fontSize;
            if (saved.lineHeight !== undefined) Reader._settings.lineHeight = saved.lineHeight;
        } catch(e) {}
        Reader.applyTheme();

        const app = document.getElementById('app');
        app.addEventListener('click', (e) => {
            const action = e.target.closest('[data-action]');
            if (action) {
                const act = action.dataset.action;
                if (act === 'back') history.back();
                else if (act === 'retry') this.route();
                else if (act === 'cal-prev') this._calNavigate(-1);
                else if (act === 'cal-next') this._calNavigate(1);
                else if (act === 'toggle-gear') {
                    const popup = document.querySelector('.gear-popup');
                    if (!popup) return;
                    if (popup.style.display !== 'none') {
                        popup.style.display = 'none';
                    } else {
                        popup.style.display = 'block';
                        // 延迟添加全局点击关闭（避免立即触发）
                        setTimeout(() => {
                            const close = function(e) {
                                if (!e.target.closest('.gear-popup') && !e.target.closest('.gear-btn')) {
                                    popup.style.display = 'none';
                                    document.removeEventListener('click', close);
                                }
                            };
                            document.addEventListener('click', close);
                        }, 0);
                    }
                }
                else if (act === 'gear-mode') {
                    const val = action.dataset.val;
                    if (val === 'light') { Reader._settings.dark = false; Reader._settings.eye = false; }
                    else if (val === 'dark') { Reader._settings.dark = true; Reader._settings.eye = false; }
                    else if (val === 'eye') { Reader._settings.dark = false; Reader._settings.eye = true; }
                    Reader.applyTheme();
                    this._saveTheme();
                    // 关闭气泡
                    const popup = document.querySelector('.gear-popup');
                    if (popup) popup.style.display = 'none';
                    // 重绘首页
                    document.getElementById('app').innerHTML = '';
                    this.renderHome();
                }
                else if (act === 'toggle-settings') {
                    const panel = document.querySelector('.reader-settings-panel, .global-settings-panel');
                    if (panel) panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
                }
                else if (act === 'rs-font') {
                    Reader._settings.fontSize = parseInt(action.dataset.val);
                    const body = document.querySelector('.reader-body');
                    if (body) body.style.fontSize = Reader._fontSizes[Reader._settings.fontSize];
                    this._updateSettingsUI();
                    this._saveTheme();
                }
                else if (act === 'rs-line') {
                    Reader._settings.lineHeight = parseInt(action.dataset.val);
                    const body = document.querySelector('.reader-body');
                    if (body) body.style.lineHeight = Reader._lineHeights[Reader._settings.lineHeight];
                    this._updateSettingsUI();
                    this._saveTheme();
                }
                else if (act === 'rs-mode') {
                    const val = action.dataset.val;
                    Reader._settings.dark = (val === 'dark');
                    Reader._settings.eye = (val === 'eye');
                    Reader.applyTheme();
                    this._updateSettingsUI();
                    this._saveTheme();
                }
                return;
            }
        });
        window.addEventListener('hashchange', () => this.route());
        this.route();
    },

    route() {
        const hash = location.hash.slice(1) || '/';
        const app = document.getElementById('app');
        app.innerHTML = '';

        if (hash === '/') return this.renderHome();

        const im = hash.match(/^\/paper\/(\w+)$/);
        if (im) return this.renderIssues(im[1]);

        const am = hash.match(/^\/paper\/(\w+)\/([\d-]+)$/);
        if (am) return this.renderArticles(am[1], am[2]);

        const rm = hash.match(/^\/reader\/(\w+)\/(.+)$/);
        if (rm) return this.renderReader(rm[1], rm[2]);

        location.hash = '#/';
    },

    // ===== 首页 =====
    renderHome() {
        const s = Reader._settings;
        const div = document.createElement('div');
        div.className = 'home-page';
        const modeLabel = s.dark ? '黑夜' : (s.eye ? '护眼' : '白天');
        div.innerHTML = `
            <div class="home-top">
                <span class="gear-btn" data-action="toggle-gear">⚙</span>
                <div class="gear-popup" style="display:none">
                    <div class="gear-row gear-modes">
                        <span class="${!s.dark&&!s.eye?'gear-on':''}" data-action="gear-mode" data-val="light">☀ 白天</span>
                        <span class="${s.eye?'gear-on':''}" data-action="gear-mode" data-val="eye">☕ 护眼</span>
                        <span class="${s.dark?'gear-on':''}" data-action="gear-mode" data-val="dark">☾ 黑夜</span>
                    </div>
                    <div class="gear-row" style="font-size:12px;color:var(--text3);padding-top:6px;border-top:1px solid var(--border);margin-top:4px">
                        字号行距等更多设置<br>在文章页面右上角 Aa
                    </div>
                </div>
            </div>
            <h1>读报</h1>
            <p class="subtitle">每日官媒精选</p>
            <div class="newspaper-grid">
                ${this.newspapers.map(n => `
                    <a class="np-card np-${n.color}" href="#/paper/${n.id}">
                        <span class="name">${n.name}<small>${n.subtitle}</small></span>
                    </a>
                `).join('')}
            </div>`;
        document.getElementById('app').appendChild(div);
    },

    // ===== 期数列表 =====
    async renderIssues(id) {
        const paper = this.newspapers.find(n => n.id === id);
        if (!paper) { location.hash = '#/'; return; }

        const token = ++this._navToken;
        this._showLoading('加载期数...');

        // 优先读缓存
        let issues = AdapterUtils.cacheGet('issues_' + id);
        if (!issues) {
            const adapter = this.getAdapter(id);
            if (!adapter) { this._showError('暂不支持该报纸'); return; }
            try {
                issues = await adapter.getIssues();
                if (token !== this._navToken) return;  // 用户已离开
                if (issues && issues.length > 0) AdapterUtils.cacheSet('issues_' + id, issues);
            } catch (e) {
                if (token !== this._navToken) return;
                this._showError('加载失败', null);
                return;
            }
        }

        if (token !== this._navToken) return;

        if (!issues || issues.length === 0) {
            this._showEmpty(paper.name, '暂无期数数据', '#/');
            return;
        }

        issues.sort((a, b) => b.date.localeCompare(a.date));
        // 改为日历视图
        this.renderCalendar(id, paper.name, issues);
    },

    // ===== 日历视图 =====
    _calYear: new Date().getFullYear(),
    _calMonth: new Date().getMonth() + 1,
    _calPaperId: null,
    _calIssues: null,

    _calNavigate(dir) {
        if (dir < 0) {
            if (this._calMonth === 1) { this._calYear--; this._calMonth = 12; }
            else this._calMonth--;
        } else {
            if (this._calMonth === 12) { this._calYear++; this._calMonth = 1; }
            else this._calMonth++;
        }
        const paper = this.newspapers.find(n => n.id === this._calPaperId);
        this.renderCalendar(this._calPaperId, paper ? paper.name : '', this._calIssues);
    },

    renderCalendar(id, paperName, issues) {
        this._calPaperId = id;
        this._calIssues = issues;
        const validDates = new Set(issues.map(i => i.date));
        const today = new Date().toISOString().slice(0, 10);
        const y = this._calYear, m = this._calMonth;

        const firstDay = new Date(y, m - 1, 1).getDay();
        const daysInMonth = new Date(y, m, 0).getDate();

        const cells = [];
        for (let i = 0; i < firstDay; i++) cells.push('<div class="cal-cell cal-empty"></div>');
        for (let d = 1; d <= daysInMonth; d++) {
            const ds = `${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
            const has = validDates.has(ds);
            const isToday = ds === today;
            if (has) {
                cells.push(`<a class="cal-cell cal-has${isToday?' cal-today':''}" href="#/paper/${id}/${ds}">${d}</a>`);
            } else {
                cells.push(`<div class="cal-cell cal-none${isToday?' cal-today':''}">${d}</div>`);
            }
        }
        while (cells.length < 42) cells.push('<div class="cal-cell cal-empty"></div>');

        const rows = [];
        for (let i = 0; i < 6; i++) {
            rows.push(`<div class="cal-row">${cells.slice(i*7, (i+1)*7).join('')}</div>`);
        }

        document.getElementById('app').innerHTML = `
            <div class="page"><div class="page-header">
                <a class="back" href="#/">←</a><h2>${paperName}</h2>
            </div>
            <div class="calendar">
                <div class="cal-nav">
                    <span class="cal-arrow" data-action="cal-prev">‹</span>
                    <span class="cal-title">${y}年${m}月</span>
                    <span class="cal-arrow" data-action="cal-next">›</span>
                </div>
                <div class="cal-weekdays">
                    <span>日</span><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span>
                </div>
                ${rows.join('')}
                <div class="cal-legend">
                    <span class="cal-dot-active"></span> 有报
                    <span class="cal-dot-none"></span> 无报
                </div>
            </div></div>`;
    },

    // ===== 文章列表 =====
    async renderArticles(id, date) {
        const paper = this.newspapers.find(n => n.id === id);
        if (!paper) { location.hash = '#/'; return; }

        const token = ++this._navToken;
        this._showLoading('加载文章列表...');

        const ck = `${id}:${date}`;
        let articles = this.cache[ck];

        if (!articles) {
            // 读缓存
            articles = AdapterUtils.cacheGet('arts_' + ck);
        }

        if (!articles) {
            const adapter = this.getAdapter(id);
            if (!adapter) { this._showError('暂不支持'); return; }
            try {
                articles = await adapter.getArticles(date);
                if (token !== this._navToken) return;
                if (articles && articles.length > 0) {
                    this.cache[ck] = articles;
                    AdapterUtils.cacheSet('arts_' + ck, articles);
                }
            } catch (e) {
                if (token !== this._navToken) return;
                this._showError('加载失败', null);
                return;
            }
        }

        if (token !== this._navToken) return;

        if (!articles || articles.length === 0) {
            this._showEmpty(date, '暂无文章数据', `#/paper/${id}`);
            return;
        }

        // 过滤广告/彩票/专版内容
        const AD_KEYWORDS = ['广告', '彩票', '福彩', '体彩', '专版', '公告', '遗失', '清算', '减资', '债权'];
        articles = articles.filter(a => {
            const sec = (a.section || '').toLowerCase();
            const title = (a.title || '').toLowerCase();
            for (const kw of AD_KEYWORDS) {
                if (sec.includes(kw) || title.includes(kw)) return false;
            }
            return true;
        });

        // 按版面分组
        const sections = {};
        articles.forEach(a => {
            const sec = a.section || '未分类';
            if (!sections[sec]) sections[sec] = [];
            sections[sec].push(a);
        });

        const groupsHTML = Object.entries(sections).map(([sec, arts]) => `
            <div class="section-group"><div class="section-header">${this._esc(sec)} (${arts.length}篇)</div>
            ${arts.map(a => `
                <a class="art-item" href="#/reader/${id}/${encodeURIComponent(a.id)}">
                    <div class="art-title">${this._esc(a.title)}</div>
                    <div class="art-meta">${a.author ? this._esc(a.author) + ' · ' : ''}${a.wordCount ? a.wordCount + '字' : ''}</div>
                </a>`).join('')}
            </div>`).join('');

        document.getElementById('app').innerHTML = `
            <div class="page"><div class="page-header">
                <a class="back" href="#/paper/${id}">←</a><h2>${date}</h2>
            </div><div class="article-list">${groupsHTML}</div></div>`;
    },

    // ===== 阅读器 =====
    async renderReader(id, aid) {
        const token = ++this._navToken;
        this._showLoading('加载文章...');

        let articleId;
        try { articleId = decodeURIComponent(aid); } catch (e) { articleId = aid; }

        const ck = `${id}:${articleId}`;
        let article = this.cache[ck];

        if (!article) {
            const adapter = this.getAdapter(id);
            if (!adapter) { this._showError('暂不支持'); return; }
            try {
                article = await adapter.getArticle(articleId);
                if (token !== this._navToken) return;
                if (article) this.cache[ck] = article;
            } catch (e) {
                if (token !== this._navToken) return;
                this._showError('加载失败', null);
                return;
            }
        }

        if (token !== this._navToken) return;

        if (!article) {
            document.getElementById('app').innerHTML = Reader.render({
                title: '文章加载失败', author: '', date: '', section: '',
                bodyHtml: '<p style="text-indent:0;color:var(--text2)">该文章暂时无法加载</p>'
            });
            return;
        }

        document.getElementById('app').innerHTML = Reader.render(article);
    },

    // ===== 辅助 =====
    getAdapter(id) {
        if (this._adapters[id]) return this._adapters[id];
        const map = {
            studytimes: StudyTimesAdapter, zuzhirenshi: ZuZhiRenShiAdapter,
            renminribao: RenMinRiBaoAdapter, nanfangribao: NanFangRiBaoAdapter,
        };
        const Cls = map[id];
        if (!Cls) return null;
        this._adapters[id] = new Cls();
        return this._adapters[id];
    },

    _showLoading(msg) {
        document.getElementById('app').innerHTML = `<div class="loading">${msg || '加载中...'}</div>`;
    },
    _showError(msg, backUrl) {
        const btn = backUrl ? `<a class="retry-btn" href="${backUrl}">返回</a>`
                           : `<span class="retry-btn" data-action="retry">点击重试</span>`;
        document.getElementById('app').innerHTML = `<div class="error">${msg}<br>${btn}</div>`;
    },
    _showEmpty(title, msg, backUrl) {
        document.getElementById('app').innerHTML = `
            <div class="page"><div class="page-header">
                <a class="back" href="${backUrl || '#/'}">←</a><h2>${title}</h2>
            </div><div class="empty">${msg}<br><small>请检查网络连接后重试</small></div>
            <div style="text-align:center;margin-top:16px"><span class="retry-btn" data-action="retry">点击重试</span></div></div>`;
    },

    _esc(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    },

    _updateSettingsUI() {
        const s = Reader._settings;
        document.querySelectorAll('.rs-row').forEach(row => {
            row.querySelectorAll('.rs-btn').forEach(b => {
                const act = b.dataset.action;
                const val = b.dataset.val;
                if (act === 'rs-font') b.classList.toggle('rs-on', parseInt(val) === s.fontSize);
                else if (act === 'rs-line') b.classList.toggle('rs-on', parseInt(val) === s.lineHeight);
                else if (act === 'rs-mode') b.classList.toggle('rs-on',
                    (val === 'dark' && s.dark) || (val === 'eye' && s.eye) || (val === 'light' && !s.dark && !s.eye));
            });
        });
        // 更新阅读器 body 字号行距
        const body = document.querySelector('.reader-body');
        if (body) {
            body.style.fontSize = Reader._fontSizes[s.fontSize];
            body.style.lineHeight = Reader._lineHeights[s.lineHeight];
        }
    },

    _saveTheme() {
        const s = Reader._settings;
        try { localStorage.setItem('np_theme', JSON.stringify({dark:s.dark, eye:s.eye, fontSize:s.fontSize, lineHeight:s.lineHeight})); } catch(e) {}
    },

};

document.addEventListener('DOMContentLoaded', () => APP.init());
