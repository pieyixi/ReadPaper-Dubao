/**
 * 读报 App - 阅读器渲染 & 正文清洗
 */
const Reader = {
    REMOVE_TAGS: new Set(['script', 'style', 'iframe', 'nav', 'header', 'footer', 'aside']),
    KEEP_TAGS: new Set([
        'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
        'img', 'blockquote', 'ul', 'ol', 'li', 'br', 'hr',
        'strong', 'em', 'b', 'i', 'u', 'span', 'a',
        'sub', 'sup', 'pre', 'code'
    ]),
    SKIP_TAGS: new Set(['html', 'head', 'body', '#document']),
    FILTER_KEYWORDS: [
        '上一篇', '下一篇', '相关新闻', '相关阅读', '推荐阅读',
        '责任编辑', '责编', '版面编辑', '本版编辑',
        '版权', 'ICP备', '京ICP', '订阅电话', '本报邮箱',
        '分享到', '扫描二维码', '点击下载', '返回目录',
        'PDF下载', '打印本页', '关闭窗口', '字体：',
        '放大', '缩小', '默认', '全文复制',
        '广告', '彩票', '福彩', '体彩', '开奖', '投注',
        '遗失声明', '注销公告', '清算公告', '减资公告',
        '债权申报', '环境影响评价', '环评公示',
    ],
    BLOCK_KEYWORDS: ['广告', '专题', '专版', '公告', '声明'],

    _settings: { fontSize: 2, lineHeight: 2, dark: false, eye: false },
    _fontSizes: ['15px','17px','19px','21px'],
    _lineHeights: ['1.65','1.85','2.05','2.25'],

    cleanBody(html, baseUrl) {
        if (!html || typeof html !== 'string') return '';
        try {
            const doc = new DOMParser().parseFromString(html, 'text/html');
            this.REMOVE_TAGS.forEach(tag => { doc.querySelectorAll(tag).forEach(el => el.remove()); });
            this._removeFilteredElements(doc.body);
            this._cleanNode(doc.body);
            this._processImages(doc, baseUrl);
            doc.querySelectorAll('p').forEach(p => {
                if (!(p.textContent||'').trim() && !p.querySelector('img') && !p.querySelector('br')) p.remove();
            });
            doc.querySelectorAll('p').forEach(p => {
                for (const c of p.childNodes) {
                    if (c.nodeType === 3) { c.textContent = c.textContent.replace(/^[\s\u3000]+/, ''); break; }
                    if (c.nodeType === 1 && (c.textContent||'').trim()) break;
                }
            });
            doc.querySelectorAll('p').forEach(p => {
                const t = (p.textContent||'').trim();
                for (const kw of this.BLOCK_KEYWORDS) { if (t === kw || t === '第'+kw) { p.remove(); break; } }
            });
            let result = doc.body.innerHTML || '';
            return result.replace(/\n{3,}/g, '\n\n').trim();
        } catch (e) { return this._fallbackClean(html); }
    },

    _removeFilteredElements(root) {
        root.querySelectorAll('p,div,li,td,span').forEach(el => {
            if (el.tagName === 'DIV' && el.querySelectorAll('p,img,h1,h2,h3').length) return;
            if (el.tagName === 'SPAN' && el.querySelectorAll('*').length) return;
            const t = (el.textContent||'').trim();
            if (t.length < 80) { for (const kw of this.FILTER_KEYWORDS) { if (t.includes(kw)) { el.remove(); return; } } }
        });
        root.querySelectorAll('a').forEach(a => {
            const t = (a.textContent||'').trim();
            if (t.length < 30) {
                for (const kw of this.FILTER_KEYWORDS) {
                    if (t.includes(kw)) {
                        const p = a.closest('p,div,li');
                        if (p && (p.textContent||'').trim().length < 100) p.remove(); else a.remove();
                        return;
                    }
                }
            }
        });
    },

    _cleanNode(node) {
        if (!node || this.SKIP_TAGS.has(node.nodeName?.toLowerCase())) {
            Array.from(node.childNodes||[]).forEach(c => this._cleanNode(c)); return;
        }
        if (node.nodeType === 3) return;
        const tag = node.nodeName?.toLowerCase();
        if (this.KEEP_TAGS.has(tag)) {
            if (tag === 'img') return;
            if (tag === 'a') { const h = node.getAttribute('href'); while (node.attributes.length) node.removeAttribute(node.attributes[0].name); if (h) node.setAttribute('href', h); }
            else if (tag === 'span') { const s = node.getAttribute('style'); while (node.attributes.length) node.removeAttribute(node.attributes[0].name); if (s) node.setAttribute('style', s); }
            else { while (node.attributes.length) node.removeAttribute(node.attributes[0].name); }
            Array.from(node.childNodes).forEach(c => this._cleanNode(c));
        } else {
            const p = node.parentNode;
            const children = Array.from(node.childNodes);
            if (p) { children.forEach(c => { p.insertBefore(c, node); this._cleanNode(c); }); p.removeChild(node); }
        }
    },

    _processImages(doc, baseUrl) {
        doc.querySelectorAll('img').forEach(img => {
            let src = img.getAttribute('src') || img.getAttribute('data-src') || '';
            if (!src) { img.remove(); return; }
            if (!/^https?:\/\//.test(src) && !src.startsWith('data:')) {
                try { src = new URL(src, baseUrl).href; } catch (e) { img.remove(); return; }
            }
            while (img.attributes.length) img.removeAttribute(img.attributes[0].name);
            img.setAttribute('src', src);
            img.setAttribute('loading', 'lazy');
            img.setAttribute('style', 'max-width:100%;height:auto;display:block;margin:16px auto');
        });
    },

    _fallbackClean(html) {
        let text = html.replace(/<script[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<style[^>]*>[\s\S]*?<\/style>/gi,'')
            .replace(/<br\s*\/?>/gi,'\n').replace(/<[^>]+>/g,'\n')
            .replace(/&nbsp;/g,' ').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
        const lines = text.split('\n').map(l => l.trim()).filter(l => {
            if (!l || l.length < 5) return false;
            for (const kw of this.FILTER_KEYWORDS) { if (l.includes(kw) && l.length < 80) return false; }
            return true;
        });
        return lines.map(l => `<p>${l}</p>`).join('\n');
    },

    applyTheme() {
        document.body.classList.toggle('dark', this._settings.dark);
        document.body.classList.toggle('eye', this._settings.eye);
    },

    render(article) {
        const s = this._settings;
        const badge = article.section ? `<div class="reader-section-badge">${this._esc(article.section)}</div>` : '';
        return `
            <div class="reader-page">
                <div class="reader-nav">
                    <span class="back" data-action="back">←</span>
                    <span class="back-label">${this._esc(article.date||'')}</span>
                    <span class="reader-settings-btn" data-action="toggle-settings">Aa</span>
                </div>
                <div class="reader-settings-panel" style="display:none">
                    <div class="rs-row"><span class="rs-label">字号</span>
                        ${['小','中','大','特大'].map((l,i) => `<span class="rs-btn${s.fontSize===i?' rs-on':''}" data-action="rs-font" data-val="${i}">${l}</span>`).join('')}
                    </div>
                    <div class="rs-row"><span class="rs-label">行距</span>
                        ${['紧凑','标准','宽松','更宽'].map((l,i) => `<span class="rs-btn${s.lineHeight===i?' rs-on':''}" data-action="rs-line" data-val="${i}">${l}</span>`).join('')}
                    </div>
                    <div class="rs-row"><span class="rs-label">模式</span>
                        <span class="rs-btn${!s.dark&&!s.eye?' rs-on':''}" data-action="rs-mode" data-val="light">白天</span>
                        <span class="rs-btn${s.dark?' rs-on':''}" data-action="rs-mode" data-val="dark">黑夜</span>
                        <span class="rs-btn${s.eye?' rs-on':''}" data-action="rs-mode" data-val="eye">护眼</span>
                    </div>
                </div>
                ${badge}
                <h1 class="reader-title">${this._esc(article.title||'无标题')}</h1>
                <div class="reader-meta">${article.author?this._esc(article.author)+' · ':''}${this._esc(article.date||'')}</div>
                <div class="reader-body" style="font-size:${this._fontSizes[s.fontSize]};line-height:${this._lineHeights[s.lineHeight]}">
                    ${article.bodyHtml||'<p style="text-indent:0;color:var(--text3)">暂无内容</p>'}
                </div>
            </div>`;
    },

    _esc(s) {
        if (!s) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }
};
