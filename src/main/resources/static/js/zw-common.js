/* ============================================================
   知微 · 前端公共库
   Api / Auth / 主题 / SSE 解析 / Markdown / 时间线渲染
   ============================================================ */
(function () {
  'use strict';

  const TOKEN_KEY = 'zw-token';
  const USER_KEY = 'zw-user';
  const THEME_KEY = 'zw-theme';

  // ---------------- Auth ----------------
  const Auth = {
    getToken() { return localStorage.getItem(TOKEN_KEY) || ''; },
    setToken(t) { localStorage.setItem(TOKEN_KEY, t || ''); },
    getUser() {
      try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; }
    },
    setUser(u) { localStorage.setItem(USER_KEY, JSON.stringify(u || null)); },
    clear() { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY); },
    isAdmin() {
      const u = this.getUser();
      return !!u && (u.role === 'ADMIN' || u.role === 'SUPER_ADMIN');
    },
    isSuperAdmin() { const u = this.getUser(); return !!u && u.role === 'SUPER_ADMIN'; },
    /** 未登录直接跳登录页 */
    require() {
      if (!this.getToken()) { location.replace('login.html'); return false; }
      return true;
    },
    logout() { this.clear(); location.replace('login.html'); }
  };

  // ---------------- 请求 ----------------
  async function request(method, url, body, opts) {
    opts = opts || {};
    const headers = Object.assign({ 'Accept': 'application/json' }, opts.headers || {});
    const token = Auth.getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;

    let payload;
    if (body instanceof FormData) {
      payload = body;
    } else if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }

    const resp = await fetch(url, { method, headers, body: payload });
    if (resp.status === 401) {
      // 登录/注册接口的 401 是"账号密码错"，要原样报错，不能清空并重载页面
      // （否则用户看到的就是"一直停在登录页、没有任何提示"）
      var isAuthCall = url.indexOf('/api/auth/') === 0;
      if (!isAuthCall) {
        Auth.clear();
        location.replace('login.html');
        throw new Error('登录已过期，请重新登录');
      }
      throw new Error((json && json.msg) || '用户名或密码错误');
    }
    let json = null;
    try { json = await resp.json(); } catch (e) { /* 可能无 body */ }
    if (!resp.ok) {
      throw new Error((json && json.msg) || ('HTTP ' + resp.status));
    }
    if (json && typeof json.code === 'number' && json.code !== 0) {
      throw new Error(json.msg || '请求失败');
    }
    return json ? json.data : null;
  }

  const Api = {
    get: (url, opts) => request('GET', url, undefined, opts),
    post: (url, body, opts) => request('POST', url, body, opts),
    put: (url, body, opts) => request('PUT', url, body, opts),
    del: (url, opts) => request('DELETE', url, undefined, opts),
    upload: (url, formData) => request('POST', url, formData)
  };

  // ---------------- 工具 ----------------
  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function formatTime(value) {
    if (!value) return '';
    const d = new Date(value);
    if (isNaN(d.getTime())) return String(value);
    const pad = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
      + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function relativeTime(value) {
    if (!value) return '';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '';
    const diff = Date.now() - d.getTime();
    if (diff < 60e3) return '刚刚';
    if (diff < 3600e3) return Math.floor(diff / 60e3) + ' 分钟前';
    if (diff < 86400e3) return Math.floor(diff / 3600e3) + ' 小时前';
    if (diff < 7 * 86400e3) return Math.floor(diff / 86400e3) + ' 天前';
    return formatTime(value).slice(0, 10);
  }

  /** 轻量 Markdown：优先用 CDN 的 marked + DOMPurify，缺失时降级为纯文本转义 */
  function renderMarkdown(md) {
    let text = md == null ? '' : String(md);
    // MinerU 图片资产：file:/// 或相对 assets/ 路径改写到 /api/assets/（必须在 sanitize 前做，
    // 否则 DOMPurify 会把 file: 协议的 src 整个剥掉）
    // MinerU 图片资产：解析器写回的是相对路径（images/xxx.jpg 或 assets/.../xxx.jpg），
    // 统一改写成 /api/assets/{文件名}（后端会按文件名反查真实目录）。
    // 必须在 DOMPurify 之前处理。
    text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, function (m, alt, src) {
      if (src.indexOf('/api/assets/') === 0 || /^(https?:)?\/\//i.test(src)) {
        return m;
      }
      var name = src.split('/').pop();
      if (!name || name.indexOf('.') < 0) {
        return m;
      }
      return '![' + alt + '](/api/assets/' + name + ')';
    });
    if (window.marked && window.DOMPurify) {
      try { return DOMPurify.sanitize(marked.parse(text)); } catch (e) { /* 降级 */ }
    }
    return escapeHtml(text).replace(/\n/g, '<br>');
  }

  function toast(msg, type) {
    let el = document.getElementById('zw-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'zw-toast';
      document.body.appendChild(el);
    }
    el.className = 'zw-toast' + (type ? ' ' + type : '');
    el.textContent = msg;
    // 触发重排，保证连续 toast 也有入场动画
    void el.offsetWidth;
    el.classList.add('show');
    clearTimeout(el._timer);
    el._timer = setTimeout(function () { el.classList.remove('show'); }, 2600);
  }

  // ---------------- 主题 ----------------
  const Theme = {
    apply(t) {
      document.documentElement.setAttribute('data-theme', t === 'dark' ? 'dark' : 'light');
      localStorage.setItem(THEME_KEY, t);
    },
    init() {
      this.apply(localStorage.getItem(THEME_KEY) || 'light');
    },
    toggle() {
      const cur = document.documentElement.getAttribute('data-theme');
      this.apply(cur === 'dark' ? 'light' : 'dark');
    }
  };
  Theme.init();

  // ---------------- SSE ----------------
  /**
   * 手写 SSE 解析：逐行读 id:/data:。
   * 不用 EventSource —— 它不支持 POST，也带不了 Authorization 头。
   *
   * @returns {{promise: Promise<void>, abort: function(boolean)}}
   */
  function openStream(url, body, handlers) {
    handlers = handlers || {};
    const controller = new AbortController();
    let lastSeq = 0;

    const promise = (async () => {
      const headers = {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache'
      };
      const token = Auth.getToken();
      if (token) headers['Authorization'] = 'Bearer ' + token;

      let resp;
      try {
        resp = await fetch(url, {
          method: 'POST', headers, body: JSON.stringify(body || {}), signal: controller.signal
        });
      } catch (e) {
        if (handlers.onError) handlers.onError(e, lastSeq);
        return;
      }
      if (!resp.ok) {
        let msg = 'HTTP ' + resp.status;
        try { const d = await resp.json(); msg = d.msg || msg; } catch (e) { /* ignore */ }
        if (handlers.onError) handlers.onError(new Error(msg), lastSeq);
        return;
      }

      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      let pendingId = '';

      const handleLine = (rawLine) => {
        const line = rawLine.trim();
        if (!line) return;
        if (line.startsWith('id:')) { pendingId = line.slice(3).trim(); return; }
        if (!line.startsWith('data:')) return;
        const jsonStr = line.slice(5).trim();
        if (!jsonStr) return;
        let data;
        try { data = JSON.parse(jsonStr); } catch (e) { pendingId = ''; return; }
        const seq = pendingId ? Number(pendingId) : 0;
        if (seq && seq <= lastSeq) { pendingId = ''; return; }   // 补发重叠，丢掉
        if (seq) lastSeq = seq;
        pendingId = '';
        if (handlers.onEvent) handlers.onEvent(data, seq);
      };

      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf('\n')) !== -1) {
            handleLine(buffer.slice(0, idx));
            buffer = buffer.slice(idx + 1);
          }
        }
        if (buffer.trim()) handleLine(buffer);
        if (handlers.onDone) handlers.onDone(lastSeq);
      } catch (e) {
        if (e && e.name === 'AbortError') { if (handlers.onDone) handlers.onDone(lastSeq); return; }
        if (handlers.onError) handlers.onError(e, lastSeq);
      }
    })();

    return { promise, abort: (silent) => controller.abort(silent) };
  }

  // ---------------- 时间线渲染 ----------------
  /**
   * 把 SSE 事件流渲染成时间线（ChatGPT/Gemini 那种"灰色可折叠行"）。
   * 事件合并规则：Thinking / Text 按序累加；ToolStart 建条目、ToolEnd 按 toolCallId 回填；
   * TodoWrite 的调用不渲染成工具条目；Citations 走来源卡片。
   */
  const Timeline = (function () {
    const STEP_META = {
      Thinking: { icon: 'think', label: '已思考' },
      // running 是 ToolStart 到 ToolEnd 之间显示的文案 —— 不能一调用就报「已...」
      Skill: { icon: 'skill', label: '已加载技能', running: '加载技能中' },
      kbSearch: { icon: 'kb', label: '已检索知识库', running: '检索中' },
      webSearch: { icon: 'web', label: '已联网搜索', running: '搜索中' },
      fileParse: { icon: 'file', label: '已解析文件', running: '解析中' }
    };

    function iconSvg(kind) {
      if (kind === 'think') {
        return '<path d="M12 2a7 7 0 0 0-4 12.7V17a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2v-2.3A7 7 0 0 0 12 2z"/><path d="M10 21h4"/>';
      }
      if (kind === 'file') {
        return '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>'
          + '<path d="M14 2v6h6"/><path d="M8 13h8M8 17h5"/>';
      }
      if (kind === 'web') {
        // 地球：ChatGPT 联网图标的同款样式
        return '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/>'
          + '<path d="M12 3c2.5 2.5 3.8 5.6 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.6-3.8-9S9.5 5.5 12 3z"/>';
      }
      if (kind === 'skill') {
        return '<path d="M13 2 4.09 13.14A1 1 0 0 0 4.88 14.8H11l-1 7.2 8.91-11.14A1 1 0 0 0 18.12 9.2H12z"/>';
      }
      return '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>';
    }

    function fmtArgs(raw) {
      if (!raw) return '';
      if (typeof raw === 'string') {
        try { return JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { return raw; }
      }
      return JSON.stringify(raw, null, 2);
    }

    function create(container) {
      let items = [];

      // 引用角标点击 → 跳到对应来源卡片（事件委托，重渲染也不会失效）
      container.addEventListener('click', function (e) {
        const cite = e.target.closest ? e.target.closest('.cite') : null;
        if (!cite) return;
        const target = document.getElementById('zw-src-' + cite.textContent.trim());
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });

      // 命中片段：展开 / 收起（折叠成一个长方体，点开内部滚动）
      container.addEventListener('click', function (e) {
        const btn = e.target.closest ? e.target.closest('.hits-more') : null;
        if (!btn) return;
        const box = btn.closest('.hits-box');
        if (!box) return;
        const open = box.classList.toggle('open');
        btn.textContent = open ? '收起' : ('展开全部 ' + (btn.dataset.n || '') + ' 段');
      });

      function reset() {
        items = [];
        container.innerHTML = '';
      }

      function rebuild() {
        container.innerHTML = items.map(html).join('');
        bindToggle();
      }

      /**
       * 渲染：条目数量/类型都没变时走原位更新，不重建 DOM。
       * 原因：每次事件都 innerHTML 全量重建会把 .ic-think 上的呼吸动画一遍遍重启，
       * 实际看起来是图标在抖/不动 —— 呼吸效果必须让同一个节点一直活着。
       */
      function render() {
        const nodes = container.children;
        const n = nodes.length;
        if (n > items.length) { rebuild(); return; }
        // 前缀对不上（条目被替换/删过）就只能重建
        for (let i = 0; i < n; i++) {
          if (nodes[i].getAttribute('data-kind') !== items[i].kind) { rebuild(); return; }
        }
        // 只是新增条目：先更新已有节点（比如刚被封口的思考行要改成「已思考」），再追加新节点
        if (n < items.length) {
          for (let i = 0; i < n; i++) patch(nodes[i], items[i]);
          const box = document.createElement('div');
          box.innerHTML = items.slice(n).map(html).join('');
          while (box.firstChild) container.appendChild(box.firstChild);
          bindToggle();
          return;
        }
        for (let i = 0; i < items.length; i++) patch(nodes[i], items[i]);
      }

      /** 只改内容不动骨架 */
      function patch(el, item) {
        if (item.kind === 'thinking') {
          const label = el.querySelector('.step-label');
          if (label) {
            const t = item.streaming ? '思考中' : '已思考';
            if (label.textContent !== t) label.textContent = t;
          }
          el.classList.toggle('streaming', !!item.streaming);
          el.classList.toggle('open', !!(item.open || item.streaming));
          const next = item.content ? '<div class="kv">' + renderMarkdown(item.content) + '</div>' : '';
          let bd = el.querySelector('.step-bd');
          if (bd) {
            if (bd.innerHTML !== next) bd.innerHTML = next;
          } else if (next) {
            bd = document.createElement('div');
            bd.className = 'step-bd';
            bd.innerHTML = next;
            el.appendChild(bd);
          }
          return;
        }
        if (item.kind === 'text') {
          const next = decorateCitations(renderMarkdown(item.content));
          if (el.innerHTML !== next) el.innerHTML = next;
          return;
        }
        if (item.kind === 'tool') {
          // 原位改文案：ToolEnd 只需把「搜索中」换成「已联网搜索」，不重建节点
          const label = el.querySelector('.step-label');
          if (label) {
            const h = toolLabel(item);
            if (label.innerHTML !== h) label.innerHTML = h;
          }
          el.classList.toggle('running', item.status === 'running');
          const body = toolBody(item);
          const bd = el.querySelector('.step-bd');
          if (bd) { if (bd.innerHTML !== body) bd.innerHTML = body; }
          return;
        }
        // 来源 / 错误：节点不多，整条换掉最省事
        const tmp = document.createElement('div');
        tmp.innerHTML = html(item);
        const fresh = tmp.firstElementChild;
        if (fresh) el.replaceWith(fresh);
      }

      function bindToggle() {
        container.querySelectorAll('.step-hd[data-toggle]').forEach(function (hd) {
          hd.onclick = function () {
            const step = hd.closest('.step');
            if (step) step.classList.toggle('open');
          };
        });
      }

      function html(item) {
        if (item.kind === 'thinking') {
          const body = item.content
            ? '<div class="kv">' + renderMarkdown(item.content) + '</div>'
            : '';
          return stepHtml('think', item.streaming ? '思考中' : '已思考', body, {
            cls: (item.open || item.streaming) ? 'open' : '',
            stream: !!item.streaming,
            kind: 'thinking'
          });
        }
        if (item.kind === 'text') {
          return '<div class="ai-body" data-kind="text" style="margin:6px 0 0">' + decorateCitations(renderMarkdown(item.content))
            + '</div>';
        }
        if (item.kind === 'tool') {
          const meta = STEP_META[item.name] || null;
          return stepHtml(meta ? meta.icon : 'kb', toolLabel(item), toolBody(item), {
            kind: 'tool',
            cls: item.status === 'running' ? 'running' : ''
          });
        }
        if (item.kind === 'todo') {
          return '<div class="step" data-kind="todo"><div class="step-bd" style="display:block">'
            + item.items.map(t => '<div>' + escapeHtml(t) + '</div>').join('') + '</div></div>';
        }
        if (item.kind === 'sources') {
          // 只保留正文里真正引用过的 [n]：kbSearch 检索过但模型没用的片段不算来源
          const used = new Set();
          items.forEach(function (it) {
            if (it.kind === 'text' && it.content) {
              const m = String(it.content).match(/\[(\d{1,2})\]/g) || [];
              m.forEach(function (x) { used.add(parseInt(x.replace(/[^0-9]/g, ''), 10)); });
            }
          });
          const keep = (item.citations || []).filter(function (c) { return used.has(Number(c.no)); });
          return sourcesHtml(keep);
        }
        if (item.kind === 'error') {
          return '<div class="step" data-kind="error"><div class="step-bd" style="display:block;border-left-color:var(--danger)">'
            + '<strong style="color:var(--danger)">出错了</strong><br>' + escapeHtml(item.message || '') + '</div></div>';
        }
        if (item.kind === 'stopped') {
          // 用户主动停止是正常操作，不是错误 —— 灰色小标签，别用红色「出错了」
          return '<div class="stopped" data-kind="stopped">'
            + '<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2.5"/></svg>'
            + '<span>' + escapeHtml(item.message || '已停止生成') + '</span></div>';
        }
        return '';
      }

      function toolDisplayName(item) {
        // 技能加载：入参里就是技能名；检索：显示检索语句
        try {
          const args = typeof item.args === 'string' ? JSON.parse(item.args) : item.args;
          if (!args) return '';
          return args.command || args.query || '';
        } catch (e) { return ''; }
      }

      /** 工具行标题：执行中显示「搜索中 / 检索中」，ToolEnd 之后才变「已联网搜索」 */
      function toolLabel(item) {
        const meta = STEP_META[item.name] || null;
        const running = item.status === 'running';
        const label = meta
          ? (running && meta.running ? meta.running : meta.label)
          : (running ? '执行中' : '已调用工具');
        const detailName = meta && item.name !== 'Thinking' ? toolDisplayName(item) : item.name;
        return label + (detailName ? ' <strong>' + escapeHtml(detailName) + '</strong>' : '');
      }

      function toolBody(item) {
        let body = '';
        if (item.args) {
          body += '<div class="kv"><div class="k">工具入参</div><pre>' + escapeHtml(fmtArgs(item.args)) + '</pre></div>';
        }
        if (item.hitsHtml) {
          body += '<div class="kv"><div class="k">命中片段</div>' + item.hitsHtml + '</div>';
        } else if (item.result) {
          body += '<div class="kv"><div class="k">执行结果</div><pre>' + escapeHtml(item.result.slice(0, 2000)) + '</pre></div>';
        }
        return body || '<div class="kv" style="color:var(--text-3)">执行中…</div>';
      }

      /**
       * 过程行骨架。headline 用 span 包一层，流式时只改它的文字，
       * 不动 .ic 节点 —— 呼吸动画才不会被重建打断。
       */
      function stepHtml(iconKind, headline, body, opts) {
        opts = opts || {};
        return '<div class="step' + (opts.cls ? ' ' + opts.cls : '') + (opts.stream ? ' streaming' : '')
          + '" data-kind="' + (opts.kind || iconKind) + '">'
          + '<div class="step-hd" data-toggle="1">'
          + '<span class="ic ic-' + iconKind + '">'
          + '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
          + 'stroke-linecap="round" stroke-linejoin="round">' + iconSvg(iconKind) + '</svg></span>'
          + '<span class="step-label">' + headline + '</span>'
          + '<svg class="chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
          + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>'
          + '</div>'
          + (body ? '<div class="step-bd">' + body + '</div>' : '')
          + '</div>';
      }

      function sourcesHtml(citations) {
        if (!citations || !citations.length) return '';
        const cards = citations.map(function (c, i) {
          const cls = 's' + ((i % 4) + 1);
          const raw = c.text || '';
          const hasImg = raw.indexOf('![') >= 0;
          // 含图片的片段用 Markdown 渲染（这样 ![](…) 能真的显示成图），
          // 纯文字的仍走转义，避免把正文里的符号当 HTML 执行
          const body = hasImg ? renderMarkdown(raw) : escapeHtml(raw);
          return '<div class="src ' + cls + '" id="zw-src-' + c.no + '">'
            + '<div class="src-t"><span class="n">' + c.no + '</span>' + escapeHtml(c.fileName) + '</div>'
            + (c.headingPath ? '<div class="src-f">' + escapeHtml(c.headingPath) + '</div>' : '')
            + '<div class="src-x' + (hasImg ? ' has-img' : '') + '">' + body + '</div></div>';
        }).join('');
        return '<div class="srcs" data-kind="sources"><div class="srcs-hd">'
          + '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" '
          + 'stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>'
          + '<path d="M14 2v6h6"/></svg>来源</div><div class="src-grid">' + cards + '</div></div>';
      }

      /** 收尾用：没有思考内容就不该留一行空的「已思考」 */
      function dropEmptyThinking() {
        const last = items[items.length - 1];
        if (last && last.kind === 'thinking' && !last.content) items.pop();
      }

      /**
       * 一段思考结束了：后面出现工具调用 / 正文 / 新一段思考时，
       * 旧的那段必须从「思考中」定格成「已思考」，否则一轮里会并排好几个「思考中」。
       */
      function sealThinking() {
        items.forEach(function (i) {
          if (i.kind === 'thinking' && i.streaming) i.streaming = false;
        });
      }

      /** 发出请求后立刻占位「思考中」，不用等第一个 Thinking 事件（否则前面几秒是空白） */
      function startThinking() {
        const last = items[items.length - 1];
        if (last && last.kind === 'thinking') return;
        items.push({ kind: 'thinking', content: '', streaming: true });
        render();
        const turn = container.closest ? container.closest('.turn-ai') : null;
        if (turn) turn.classList.add('ai-live');
      }

      /** 处理一个框架事件 */
      function process(event) {
        if (!event || !event.type) return;
        switch (event.type) {
          case 'Thinking': {
            const last = items[items.length - 1];
            if (last && last.kind === 'thinking' && last.streaming) {
              // 同一段思考继续吐字
              last.content += event.content || '';
            } else if (event.content) {
              // 上一段已经收口（中间插了工具），这是新一段思考
              sealThinking();
              items.push({ kind: 'thinking', content: event.content, streaming: true });
            }
            break;
          }
          case 'Text': {
            dropEmptyThinking();
            sealThinking();
            const last = items[items.length - 1];
            if (last && last.kind === 'text') { last.content += event.content || ''; last.streaming = true; }
            else if (event.content) { items.push({ kind: 'text', content: event.content, streaming: true }); }
            break;
          }
          case 'ToolStart': {
            if (String(event.toolName || '').toLowerCase() === 'todowrite') break;
            dropEmptyThinking();
            sealThinking();
            items.push({
              kind: 'tool', name: event.toolName || 'unknown', toolCallId: event.toolCallId || '',
              args: event.arguments || '', result: '', status: 'running'
            });
            break;
          }
          case 'ToolEnd': {
            if (String(event.toolName || '').toLowerCase() === 'todowrite') break;
            const id = event.toolCallId || '';
            const found = items.find(i => i.kind === 'tool' && i.toolCallId === id && i.status === 'running');
            if (found) {
              found.status = 'completed';
              found.result = event.result || '';
              if (found.name === 'kbSearch') found.hitsHtml = hitsHtml(found.result);
            } else {
              items.push({
                kind: 'tool', name: event.toolName || 'unknown', toolCallId: id,
                args: '', result: event.result || '', status: 'completed'
              });
            }
            break;
          }
          case 'TodoProgress': {
            items.push({ kind: 'todo', items: (event.items || []).map(t => t.text || t.content || '') });
            break;
          }
          case 'Citations': {
            items.push({ kind: 'sources', citations: event.items || [] });
            break;
          }
          case 'Error': {
            items.push({ kind: 'error', message: event.message || '执行失败' });
            break;
          }
          case 'Stopped': {
            items.push({ kind: 'stopped', message: event.message || '已停止生成' });
            break;
          }
          default:
            return;
        }
        render();
      }

      /** 把 kbSearch 的结果文本拆成 [片段N] 来源：xxx 的卡片 */
      function hitsHtml(result) {
        if (!result) return '';
        const blocks = String(result).split(/\n\n+/).filter(b => b.trim().startsWith('[片段'));
        if (!blocks.length) return '';
        const cards = blocks.map(function (b) {
          const m = b.match(/^\[片段(\d+)\]\s*来源：《([^》]*)》(?:\s*·\s*章节：([^\n]*))?/);
          const no = m ? m[1] : '?';
          const file = m ? m[2] : '未知文档';
          const path = m ? (m[3] || '') : '';
          const body = b.replace(/^\[片段\d+\][^\n]*\n?/, '').trim();
          return '<div class="hit"><div class="hit-t"><span class="num n' + (((Number(no) - 1) % 3) + 1) + '">'
            + no + '</span>' + escapeHtml(file)
            + (path ? '<span class="hit-p">' + escapeHtml(path) + '</span>' : '')
            + '</div><div class="hit-x">' + escapeHtml(body) + '</div></div>';
        }).join('');
        // 一个完整的长方体装全部命中：默认只露一点，点开变成可滚动的窗口
        return '<div class="hits-box"><div class="hits-list">' + cards + '</div>'
          + (blocks.length > 1
              ? '<button type="button" class="hits-more" data-n="' + blocks.length + '">展开全部 '
                + blocks.length + ' 段</button>'
              : '')
          + '</div>';
      }

      /** 流结束：去掉流式光标，放出「复制 / 重新生成」操作条 */
      function finish() {
        // 没吐出思考内容的那一行（只有占位文案）收尾时直接去掉
        items = items.filter(function (i) { return !(i.kind === 'thinking' && !i.content); });
        items.forEach(function (i) {
          if (i.kind === 'text' || i.kind === 'thinking') i.streaming = false;
          // 极端情况下 ToolEnd 没回来，收尾时不要让某一行永远卡在「搜索中」
          if (i.kind === 'tool' && i.status === 'running') i.status = 'completed';
        });
        rebuild();
        const turn = container.closest ? container.closest('.turn-ai') : null;
        if (turn) {
          turn.classList.remove('ai-live');
          // 操作条挂在这一轮（.turn-ai）上，不在 .ai-content 里
          turn.querySelectorAll('.msg-actions').forEach(function (bar) { bar.hidden = false; });
        }
      }

      return { reset, process, startThinking, finish, items: () => items };
    }

    return { create };
  })();

  /** 当前是否有文字选区：有就说明用户在做选择/复制，不要抢点击行为 */
  function hasSelection() {
    try {
      const sel = window.getSelection();
      return !!sel && String(sel.toString()).length > 0;
    } catch (e) { return false; }
  }

  // 点击来源卡片：展开/收起（默认只显示 2 行，长文/图片看不全）
  document.addEventListener('click', function (e) {
    if (hasSelection()) return;   // 拖选文字时不要展开/收起
    const card = e.target && e.target.closest ? e.target.closest('.src') : null;
    if (card && !(e.target.closest('a') || e.target.closest('img'))) {
      card.classList.toggle('open');
    }
  });

  // 行内引用角标 [1][2] 点击 → 滚动到来源卡片
  function bindCitationJump(scope) {
    scope.querySelectorAll('.cite').forEach(function (el) {
      el.onclick = function () {
        if (hasSelection()) return;   // 正在选文字就不跳转
        const no = el.textContent.trim();
        const target = document.getElementById('zw-src-' + no);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      };
    });
  }

  /** 把正文里的 [n] 包成可点击角标（跳过代码块与已有的 html 标签） */
  function decorateCitations(html) {
    return String(html || '').replace(/\[(\d{1,2})\]/g, function (m, n) {
      const cls = 'c' + (((Number(n) - 1) % 4) + 1);
      return '<span class="cite ' + cls + '">' + n + '</span>';
    });
  }

  // ---------------- 最近会话列表（侧栏常驻，多页共用） ----------------
  const ConvList = {
    async render(containerId, opts) {
      opts = opts || {};
      const box = document.getElementById(containerId || 'nav-conv');
      if (!box) return;

      // 先占位，避免加载时列表"跳一下"
      box.innerHTML = '<div style="padding:10px 12px">'
        + '<div class="skel w80" style="margin-bottom:9px"></div>'
        + '<div class="skel w60" style="margin-bottom:9px"></div>'
        + '<div class="skel w40"></div></div>';

      let list = [];
      try {
        const data = await Api.get('/api/sessions?page=0&size=30');
        list = (data && data.conversations) || [];
      } catch (e) { list = []; }

      if (!list.length) {
        box.innerHTML = '<div style="padding:10px 12px;font-size:13px;color:var(--text-3)">还没有会话</div>';
        return;
      }
      const streaming = opts.streamingKeys || new Set();
      box.innerHTML = list.map(function (c) {
        const active = opts.activeCid === c.conversationId;
        const live = streaming.has && streaming.has(c.conversationId);
        return '<button class="item flex' + (active ? ' active' : '') + '" data-cid="'
          + escapeHtml(c.conversationId) + '">'
          + (live ? '<span class="spin" style="margin-right:2px"></span>' : '')
          + '<span class="t">' + escapeHtml(c.title || '新对话') + '</span>'
          + '<span style="font-size:11px;color:var(--text-4);flex:none">' + relativeTime(c.updateTime) + '</span>'
          + '</button>';
      }).join('');

      box.querySelectorAll('[data-cid]').forEach(function (el) {
        el.onclick = function () {
          if (opts.onOpen) opts.onOpen(el.dataset.cid);
        };
      });
    }
  };

  // ---------------- 侧栏导航（多页共用，最近会话常驻） ----------------
  const Nav = (function () {
    const ITEMS = [
      ['chat', '对话', 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z', 'chat.html', false],
      ['knowledge-base', '知识库', 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20|M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z', 'knowledge-base.html', false],
      ['skills', '技能', 'M13 2 4.09 13.14A1 1 0 0 0 4.88 14.8H11l-1 7.2 8.91-11.14A1 1 0 0 0 18.12 9.2H12z', 'skills.html', false],
      ['admin', '管理', '<circle cx="12" cy="8" r="4"/><path d="M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1"/>', 'admin.html', true]
    ];

    function itemHtml(it, active) {
      // 图标两种写法都支持：① 纯 d 字符串，多个用 | 分隔；② 直接给 <circle>/<path> 等原始标签
      const raw = it[2].trim();
      const paths = raw.startsWith('<')
        ? raw
        : raw.split('|').map(d => '<path d="' + d + '"/>').join('');
      return '<a class="item flex' + (active === it[0] ? ' active' : '') + '" href="' + it[3] + '">'
        + '<span class="ico"><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + paths + '</svg></span>'
        + '<span class="t">' + it[1] + '</span></a>';
    }

    function render(active, opts) {
      opts = opts || {};
      const box = document.getElementById('nav');
      if (!box) return;

      box.innerHTML =
        '<button class="newchat" id="nav-new">'
        + '<span class="ring">'
        + '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
        + 'stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/>'
        + '<path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>'
        + '</span>新对话</button>'
        + '<div class="group-lbl">导航</div>'
        + ITEMS.filter(it => !it[4] || Auth.isAdmin()).map(it => itemHtml(it, active)).join('')
        + '<div class="group-lbl">最近会话</div>'
        + '<div id="nav-conv"></div>';

      // 「新对话」常驻：对话页原地开新会话，其他页面跳过去
      const newBtn = box.querySelector('#nav-new');
      newBtn.onclick = function () {
        if (active === 'chat' && opts.onNewChat) {
          opts.onNewChat();
        } else {
          location.href = 'chat.html';
        }
      };

      // 最近会话常驻：非对话页点会话直接跳过去
      ConvList.render('nav-conv', {
        activeCid: opts.activeCid,
        streamingKeys: opts.streamingKeys,
        onOpen: opts.onOpenConv || function (cid) {
          location.href = 'chat.html?conversationId=' + encodeURIComponent(cid);
        }
      });
    }

    return { render };
  })();

  window.ZW = {
    Api, Auth, Theme, Timeline, Nav, ConvList,
    escapeHtml, formatTime, relativeTime, toast,
    renderMarkdown, openStream,
    bindCitationJump, decorateCitations
  };
})();
