/* 对话页：侧栏最近会话常驻 / 流式输出 / 断点续传 / 停止 / 检索范围切换 */
(function () {
  'use strict';
  const ZW = window.ZW;
  const { Api, Auth, Nav, Timeline, openStream, escapeHtml, toast,
    renderMarkdown, decorateCitations, Theme } = ZW;

  if (!Auth.require()) return;

  const $ = id => document.getElementById(id);
  const seqKey = cid => 'zw-stream-seq-' + cid;
  const qKey = cid => 'zw-q-' + cid;

  /** 浏览器端生成会话 id（http 非安全上下文没有 randomUUID，兜底一个） */
  function newId() {
    try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch (e) { /* ignore */ }
    return 'c-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }

  /** 进行中的流：cid -> {timeline, controller, lastSeq, retries} */
  const liveStreams = new Map();

  let conversationId = new URLSearchParams(location.search).get('conversationId') || '';

  // ==================== 侧栏 ====================

  function streamingKeys() {
    return { has: cid => liveStreams.has(cid) };
  }

  function refreshNav() {
    Nav.render('chat', {
      activeCid: conversationId,
      streamingKeys: streamingKeys(),
      onNewChat: newConversation,
      onOpenConv: openConversation
    });
  }

  // ==================== 检索范围（知识库可多选，也可不选） ====================

  let kbList = [];
  const kbSel = new Set();

  async function loadScope() {
    try { kbList = (await Api.get('/api/rag/knowledge-base')) || []; } catch (e) { kbList = []; }
    kbSel.clear();
    renderKbMenu();
    syncKbLabel();
  }

  function renderKbMenu() {
    const box = $('kb-list');
    if (!box) return;
    box.innerHTML = kbList.length
      ? kbList.map(function (k) {
          const on = kbSel.has(String(k.id));
          return '<button type="button" class="kb-item' + (on ? ' on' : '') + '" data-id="' + k.id + '">'
            + '<span class="ck"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#fff" '
            + 'stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg></span>'
            + '<span class="t">' + escapeHtml(k.name) + '</span></button>';
        }).join('')
      : '<div class="kb-empty">还没有知识库</div>';
    box.querySelectorAll('.kb-item').forEach(function (el) {
      el.onclick = function () {
        const id = el.dataset.id;
        if (kbSel.has(id)) { kbSel.delete(id); el.classList.remove('on'); }
        else { kbSel.add(id); el.classList.add('on'); }
        syncKbLabel();
      };
    });
  }

  function syncKbLabel() {
    const label = $('kb-label');
    if (!label) return;
    const n = kbSel.size;
    if (n === 0) label.textContent = '知识库';
    else if (n === kbList.length) label.textContent = '全部 ' + n + ' 个库';
    else label.textContent = '已选 ' + n + ' 个库';
  }

  /** 勾选的知识库 id；空数组 = 不启用知识库（纯联网） */
  function scopeIds() {
    return Array.from(kbSel).map(Number);
  }

  function toggleKbMenu(force) {
    const menu = $('kb-menu');
    if (!menu) return;
    const willOpen = force !== undefined ? force : menu.hidden;
    menu.hidden = !willOpen;
    if (willOpen) { renderKbMenu(); }
  }

  // ==================== 对话附件（最多 5 个 / 总 5MB / 点发送才解析） ====================

  const MAX_FILES = 5;
  const MAX_TOTAL = 5 * 1024 * 1024;
  let attachments = [];   // [{ id, fileName, file, size }]
  let attSeq = 0;

  function attSize() {
    return attachments.reduce(function (n, a) { return n + (a.size || 0); }, 0);
  }

  function renderAttachments() {
    const box = $('att-chips');
    if (!box) return;
    if (!attachments.length) { box.hidden = true; box.innerHTML = ''; return; }
    box.hidden = false;
    box.innerHTML = attachments.map(function (a) {
      return '<span class="att-chip">'
        + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" '
        + 'stroke-linecap="round" stroke-linejoin="round"><path d="m21 11-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 6"/></svg>'
        + '<span class="t">' + escapeHtml(a.fileName) + '</span>'
        + '<button type="button" class="x" data-id="' + a.id + '" title="移除">×</button></span>';
    }).join('');
    box.querySelectorAll('.x').forEach(function (btn) {
      btn.onclick = function () {
        const id = Number(btn.dataset.id);
        attachments = attachments.filter(function (a) { return a.id !== id; });
        renderAttachments();
      };
    });
  }

  function handleFiles(fileList) {
    const files = Array.prototype.slice.call(fileList || []);
    console.log('[att] 选中文件', files.map(function (f) { return f.name; }));
    if (!files.length) return;
    if (attachments.length + files.length > MAX_FILES) {
      toast('最多 ' + MAX_FILES + ' 个文件', 'error');
      return;
    }
    const total = attSize() + files.reduce(function (n, f) { return n + f.size; }, 0);
    if (total > MAX_TOTAL) {
      toast('文件总大小不能超过 5MB', 'error');
      return;
    }
    files.forEach(function (f) {
      attachments.push({ id: ++attSeq, fileName: f.name, file: f, size: f.size });
    });
    console.log('[att] 待发送附件', attachments.length);
    renderAttachments();
  }

  /** 点发送时才解析：逐个上传 → 拼成一段文本 */
  async function parseAttachments(items, timeline, toolCallId) {
    const parts = [];
    let okCount = 0;
    for (let i = 0; i < items.length; i++) {
      const a = items[i];
      try {
        const fd = new FormData();
        fd.append('file', a.file);
        const resp = await fetch('/api/chat/attach', {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + Auth.getToken() },
          body: fd
        });
        const j = await resp.json();
        if (!resp.ok || j.code !== 0) { throw new Error((j && j.msg) || '解析失败'); }
        okCount++;
        parts.push('【文件：' + j.data.fileName
          + (j.data.kind === 'image' ? '（图片识别结果）' : '') + '】\n' + j.data.text);
      } catch (e) {
        parts.push('【文件：' + a.fileName + '】解析失败：' + e.message);
        toast(a.fileName + '：' + e.message, 'error');
      }
    }
    const name = items.length === 1 ? items[0].fileName : (items[0].fileName + ' 等 ' + items.length + ' 个文件');
    return {
      name: items.length ? name : null,
      text: parts.length ? parts.join('\n\n') : null,
      summary: '已解析 ' + okCount + '/' + items.length + ' 个文件：' + items.map(function (a) { return a.fileName; }).join('、')
    };
  }

  // 文件选择：document 级委托，哪怕 bind() 中途出错也能收到
  document.addEventListener('change', function (e) {
    if (!e.target || e.target.id !== 'file-input') return;
    // 注意：e.target.files 是"活的" FileList，先复制成数组再清空 input，
    // 否则 value='' 会把 files 一起清掉 ⇒ handleFiles 收到 0 个文件（之前的坑）
    const files = Array.prototype.slice.call(e.target.files || []);
    e.target.value = '';
    handleFiles(files);
  });

  // ==================== 会话 ====================

  function helloNode() {
    const div = document.createElement('div');
    div.className = 'hello';
    div.innerHTML = '<svg viewBox="0 0 24 24" style="width:40px;height:40px;margin-bottom:16px">'
      + '<path fill="url(#gstar)" d="M12 1.8c.62 5 3.3 7.68 8.3 8.3-5 .62-7.68 3.3-8.3 8.3-.62-5-3.3-7.68-8.3-8.3 5-.62 7.68-3.3 8.3-8.3z"/></svg>'
      + '<h1><span class="grad">有什么可以帮你的？</span></h1>'
      + '<div class="sub">直接提问，需要查资料时点「知识库」勾选</div>';
    return div;
  }

  /** 空态（问候 + 居中输入框）与普通会话视图切换；问候是静态 HTML，这里只切类名 */
  function setChatEmpty(on) {
    document.body.classList.toggle('chat-empty', !!on);
  }

  /** 清掉所有进行中的流 + 复位发送态：切会话/新建对话时必做，否则会残留"发送中"卡死 */
  function resetStreams() {
    liveStreams.forEach(function (entry) {
      try { if (entry && entry.controller && entry.controller.abort) entry.controller.abort(true); } catch (e) { /* ignore */ }
    });
    liveStreams.clear();
    setSending(false);
  }

  function newConversation() {
    resetStreams();
    conversationId = '';
    history.replaceState({}, '', 'chat.html');
    $('thread').innerHTML = '';
    setChatEmpty(true);
    refreshNav();
  }

  async function openConversation(cid) {
    if (conversationId !== cid) resetStreams();
    conversationId = cid;
    history.replaceState({}, '', 'chat.html?conversationId=' + encodeURIComponent(cid));
    const thread = $('thread');
    thread.innerHTML = '';
    setChatEmpty(false);
    // 优先走服务端回放：agentx_trace（思考+工具）+ zw_citation（来源）
    try {
      const data = await Api.get('/api/chat/replay/' + encodeURIComponent(cid));
      const messages = (data && data.messages) || [];
      const turns = (data && data.turns) || [];

      // 一条流可能落多条 assistant 消息（中间叙述「我来联网搜一下」+ 最终正文），
      // 所以不能拿 messages 和 turns 按下标硬对齐 —— 那样一轮会被画成两个「星标」。
      // 改成按 user 消息切轮：每轮后面所有 assistant 片段合并成一段回答。
      const rounds = [];
      messages.forEach(function (m) {
        if (m.role === 'user') {
          rounds.push({ q: m.content || '', a: [] });
        } else if (m.role === 'assistant' && rounds.length) {
          if (m.content) rounds[rounds.length - 1].a.push(m.content);
        }
      });

      if (!rounds.length && !turns.length) {
        setChatEmpty(true);
        scrollToBottom(true);
      } else {
        const total = Math.max(rounds.length, turns.length);
        for (let i = 0; i < total; i++) {
          const round = rounds[i];
          if (round) appendUserTurn(round.q);
          const t = turns[i];
          const ans = round ? round.a.join('\n\n') : '';
          if (t && t.rounds && t.rounds.length) {
            // 一轮问答：思考 → 工具 → 回答 → 来源
            const { timeline } = appendAssistantTurn();
            t.rounds.forEach(function (r) {
              if (r.think) timeline.process({ type: 'Thinking', content: r.think });
              (r.tools || []).forEach(function (tool) {
                timeline.process({
                  type: 'ToolStart', toolName: tool.name,
                  toolCallId: tool.toolCallId, arguments: tool.args
                });
                timeline.process({
                  type: 'ToolEnd', toolName: tool.name, toolCallId: tool.toolCallId,
                  result: tool.success === false ? ('执行失败：' + (tool.error || '')) : (tool.result || '')
                });
              });
            });
            if (ans) timeline.process({ type: 'Text', content: ans });
            if (t.citations && t.citations.length) {
              timeline.process({ type: 'Citations', items: t.citations });
            }
            timeline.finish();
          } else if (ans) {
            appendHistoryAssistant(ans);
          }
        }
        scrollToBottom(true);
      }
    } catch (e) {
      // 回放失败（老会话没有 trace）→ 退回本地快照，再退回纯消息回放
      const snap = localStorage.getItem('zw-tl-' + cid);
      if (snap) {
        thread.innerHTML = snap;
        try { window.ZW.bindCitationJump(thread); } catch (e2) { /* 忽略 */ }
      } else {
        try {
          const data = await Api.get('/api/sessions/' + encodeURIComponent(cid));
          const messages = (data && data.messages) || [];
          // 同样按轮合并：一条流可能有多段 assistant 文本
          const rounds = [];
          messages.forEach(function (m) {
            if (m.role === 'user') rounds.push({ q: m.content || '', a: [] });
            else if (m.role === 'assistant' && rounds.length && m.content) rounds[rounds.length - 1].a.push(m.content);
          });
          rounds.forEach(function (r) {
            appendUserTurn(r.q);
            if (r.a.length) appendHistoryAssistant(r.a.join('\n\n'));
          });
          if (!rounds.length) setChatEmpty(true);
        } catch (e3) {
          toast(e3.message, 'error');
          setChatEmpty(true);
        }
      }
      scrollToBottom(true);
    }
    await tryResumeOnLoad(cid);
    refreshNav();
  }

  /** 把整条时间线（含思考/工具/来源）存到本地，刷新后能原样恢复 */
  function saveSnapshot(cid) {
    if (!cid) return;
    try { localStorage.setItem('zw-tl-' + cid, $('thread').innerHTML); } catch (e) { /* 超限忽略 */ }
  }

  // ==================== 渲染 ====================

  function starSvg() {
    return '<svg class="ai-star" viewBox="0 0 24 24"><path fill="url(#gstar)" '
      + 'd="M12 1.8c.62 5 3.3 7.68 8.3 8.3-5 .62-7.68 3.3-8.3 8.3-.62-5-3.3-7.68-8.3-8.3 5-.62 7.68-3.3 8.3-8.3z"/></svg>';
  }

  function appendUserTurn(text) {
    const div = document.createElement('div');
    div.className = 'turn-user';
    div.innerHTML = '<div class="bubble">' + escapeHtml(text) + '</div>'
      + '<div class="msg-actions user-actions">'
      + '<button class="act act-copy" type="button" title="复制">' + ICON_COPY + '</button>'
      + '</div>';
    $('thread').appendChild(div);
  }

  function appendHistoryAssistant(text) {
    const div = document.createElement('div');
    div.className = 'turn-ai';
    div.innerHTML = '<div class="ai-wrap">' + starSvg()
      + '<div class="ai-content"><div class="ai-body">'
      + decorateCitations(renderMarkdown(text)) + '</div></div></div>';
    $('thread').appendChild(div);
  }

  var ICON_COPY = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">'
    + '<rect x="9" y="9" width="11" height="11" rx="2.5"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/></svg>';
  var ICON_CHECK = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg>';
  var ICON_RETRY = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">'
    + '<path d="M21 12a9 9 0 1 1-3.5-7.1"/><path d="M21 3v6h-6"/></svg>';

  function appendAssistantTurn() {
    const div = document.createElement('div');
    div.className = 'turn-ai';
    div.innerHTML = '<div class="ai-wrap">' + starSvg() + '<div class="ai-content"></div></div>'
      + '<div class="msg-actions" hidden>'
      + '<button class="act act-copy" type="button" title="复制">' + ICON_COPY + '</button>'
      + '<button class="act act-retry" type="button" title="重新生成">' + ICON_RETRY + '</button>'
      + '</div>';
    $('thread').appendChild(div);
    const content = div.querySelector('.ai-content');
    const timeline = Timeline.create(content);
    timeline.startThinking();   // 发送即占位「思考中」，不等第一个事件
    return { content, timeline };
  }

  /** 复制这一轮的回答正文（只取 .ai-body，不含思考/工具） */
  function copyTurn(turnEl) {
    const parts = [];
    const bubble = turnEl.querySelector('.bubble');
    if (bubble) {
      parts.push((bubble.innerText || '').trim());
    } else {
      turnEl.querySelectorAll('.ai-body').forEach(function (el) {
        const t = (el.innerText || '').trim();
        if (t) parts.push(t);
      });
    }
    const text = parts.join('\n\n').trim();
    if (!text) { toast('没有可复制的内容', 'error'); return; }
    const btn = turnEl.querySelector('.act-copy');
    const done = function () {
      if (btn) {
        const old = btn.innerHTML;
        btn.innerHTML = ICON_CHECK;
        btn.classList.add('ok');
        setTimeout(function () { btn.innerHTML = old; btn.classList.remove('ok'); }, 1400);
      }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(function () { fallbackCopy(text, done); });
    } else {
      fallbackCopy(text, done);
    }
  }

  function fallbackCopy(text, done) {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
      done();
    } catch (e) { toast('复制失败，请手动选择', 'error'); }
  }

  /** 重新回答：找到这一轮对应的用户提问，删掉本轮再发一次 */
  function retryTurn(turnEl) {
    if (liveStreams.size) { toast('正在生成中，稍后再试', 'error'); return; }
    const userEl = turnEl.previousElementSibling;
    if (!userEl || !userEl.classList.contains('turn-user')) { toast('找不到对应的问题', 'error'); return; }
    const q = (userEl.querySelector('.bubble') || {}).textContent || '';
    if (!q.trim()) { toast('找不到对应的问题', 'error'); return; }
    turnEl.remove();
    userEl.remove();
    send(q);
  }

  /** 判断用户当前是否贴在底部（允许 80px 容差，避免亚像素误差误判） */
  function isNearBottom() {
    const sc = $('scroll');
    if (!sc) return true;
    return sc.scrollHeight - sc.scrollTop - sc.clientHeight < 80;
  }

  function scrollToBottom(force) {
    // 用户正在选中文字时不要强制滚到底（否则选区会被打断，像"复制不了"）
    try {
      const sel = window.getSelection();
      if (sel && String(sel.toString()).length > 0) return;
    } catch (e) { /* ignore */ }
    // 流式输出期间：用户往上翻看历史时不能把他拽回底部。
    // 只有「本来就在底部」或显式 force（发新消息、切换会话）时才跟随。
    if (!force && !isNearBottom()) return;
    const sc = $('scroll');
    if (!sc) return;
    sc.scrollTop = sc.scrollHeight;
    // 到底了就把「回到底部」按钮收起来
    const btn = $('to-bottom');
    if (btn) btn.hidden = true;
  }

  // ==================== 发送 / 流式 ====================

  function setSending(streaming) {
    $('btn-stop').style.display = streaming ? '' : 'none';
    $('btn-send').classList.toggle('off', streaming);
  }

  function persistSeq(cid, seq) {
    try { localStorage.setItem(seqKey(cid), String(seq)); } catch (e) { /* ignore */ }
  }
  function clearSeq(cid) {
    try { localStorage.removeItem(seqKey(cid)); } catch (e) { /* ignore */ }
  }

  function finishStream(key) {
    const entry = liveStreams.get(key);
    liveStreams.delete(key);
    clearSeq(key);
    // 问句副本不删：极端情况下流结束了但消息没落库，刷新时还得靠它补用户气泡
    if (entry) entry.timeline.finish();
    setSending(false);
    refreshNav();
  }

  async function send(presetText) {
    setChatEmpty(false);
    const input = $('input');
    const text = (presetText !== undefined ? presetText : input.value).toString().trim();
    if (!text || liveStreams.size) return;

    input.value = '';
    input.style.height = '';   // 清掉内联高度，交回 CSS 的一行高度
    const hello = $('hello');
    if (hello) hello.remove();

    // 会话 id 由前端先生成并写进 URL：后端 start() 认请求里带的 cid。
    // 不这么做的话，cid 只能等 Complete 事件回来才拿到，中途刷新/关页 URL 里没有 cid，
    // 刷新后就再也找不到这条进行中的流 —— 断点续传等于失效。
    if (!conversationId) {
      conversationId = newId();
      history.replaceState({}, '', 'chat.html?conversationId=' + encodeURIComponent(conversationId));
    }

    appendUserTurn(text);
    const { timeline } = appendAssistantTurn();
    scrollToBottom(true);
    setSending(true);

    const key = conversationId;
    const entry = { timeline, controller: null, lastSeq: 0, retries: 0 };
    liveStreams.set(key, entry);
    // 流跑完之前这轮问句可能还没落库，本地留一份，刷新接回时用来补用户气泡
    try { localStorage.setItem(qKey(key), text); } catch (e) { /* ignore */ }

    // 附件：点发送才开始解析，过程以「解析文件」步骤展示
    let att = { name: null, text: null };
    if (attachments.length) {
      // 先把待发送的附件取走并立刻清空输入框上的 chips：
      // 解析（尤其图片识别）要几十秒，若等解析完再清，用户会看到「文件已带进去但还挂在输入框上」。
      const pending = attachments.slice();
      attachments = [];
      renderAttachments();

      const tcId = 'files-' + Date.now();
      timeline.process({
        type: 'ToolStart', toolName: 'fileParse', toolCallId: tcId,
        arguments: JSON.stringify({ files: pending.map(function (a) { return a.fileName; }) })
      });
      scrollToBottom();
      att = await parseAttachments(pending, timeline, tcId);
      timeline.process({ type: 'ToolEnd', toolName: 'fileParse', toolCallId: tcId, result: att.summary });
    }

    await runStream('/api/chat/stream', {
      query: text,
      conversationId: conversationId || null,
      knowledgeBaseIds: scopeIds(),
      model: currentModel(),
      attachmentName: att.name,
      attachmentText: att.text
    }, key);

    // 服务端在开流前就把会话写库了，稍等一下刷侧栏，让「生成中」那一行立刻出现
    setTimeout(refreshNav, 600);
  }

  function currentModel() {
    return window.__zwModel || 'opus55';
  }

  async function runStream(url, body, key) {
    const entry = liveStreams.get(key);
    if (!entry) return;

    const handle = openStream(url, body, {
      onEvent: function (event, seq) {
        // 服务端会回传 conversationId，第一次拿到就接管会话
        if (event && event.type === 'Complete' && event.conversationId && !conversationId) {
          conversationId = event.conversationId;
          history.replaceState({}, '', 'chat.html?conversationId=' + encodeURIComponent(conversationId));
          liveStreams.delete(key);
          liveStreams.set(conversationId, entry);
          key = conversationId;
          refreshNav();
        }
        // 第一个事件到 = 服务端已经把这个会话写进库了，这时候刷侧栏才刷得出「生成中」那一行
        if (event && !entry.navSynced) { entry.navSynced = true; refreshNav(); }
        if (seq) { entry.lastSeq = seq; persistSeq(key, seq); }
        entry.timeline.process(event);
        scrollToBottom();
      },
      onError: function (err, seq) {
        if (seq > 0 && entry.retries < 3 && conversationId) {
          entry.retries++;
          setTimeout(() => resumeStream(conversationId, entry), 1000 * entry.retries);
          return;
        }
        entry.timeline.process({ type: 'Error', message: err.message || '连接中断' });
        finishStream(key);
      },
      onDone: function () {
        finishStream(conversationId || key);
        // 等引用卡片/finish 渲染完再存快照
        setTimeout(function () { saveSnapshot(conversationId || key); }, 300);
      }
    });
    entry.controller = handle;
  }

  /** 断线后按游标增量续传 */
  async function resumeStream(cid, entry) {
    await runStream('/api/chat/recover',
      { conversationId: cid, recovery: true, startSeq: entry.lastSeq + 1 }, cid);
  }

  /** 进页面 / 点历史会话时探测：还在跑的接回来，停在中断快照上的给「继续生成」 */
  async function tryResumeOnLoad(cid) {
    if (liveStreams.has(cid)) return;
    let st = null;
    try { st = await Api.get('/api/chat/status?conversationId=' + encodeURIComponent(cid)); } catch (e) { return; }
    if (st && st.active) { await attachLive(cid); return; }
    if (st && st.interrupted) { showResumeHint(cid); return; }
    clearSeq(cid);
  }

  /**
   * 接回进行中的流（从第 1 个事件全量补发，重画这一轮）。
   * 历史回放里那条「半截回答」要先扔掉，否则会被画两遍；
   * 同理，流还没跑完时用户那句提问可能根本没落库 —— 那就在本地留一份，接回时补上。
   */
  async function attachLive(cid) {
    clearSeq(cid);
    const thread = $('thread');
    const lastTurn = thread.lastElementChild;
    if (lastTurn && lastTurn.classList.contains('turn-ai')) lastTurn.remove();
    // 接回来的这一轮前面必须是用户那句问；历史里没有就用发问时留下的副本补
    const tail = thread.lastElementChild;
    const stash = (function () { try { return localStorage.getItem(qKey(cid)) || ''; } catch (e) { return ''; } })();
    if (stash && !(tail && tail.classList.contains('turn-user'))) appendUserTurn(stash);

    setChatEmpty(false);
    const { timeline } = appendAssistantTurn();
    const entry = { timeline, controller: null, lastSeq: 0, retries: 0 };
    liveStreams.set(cid, entry);
    setSending(true);
    scrollToBottom(true);
    refreshNav();
    // 看门狗：中断的会话可能永远收不到事件，10 秒无进展就放弃，避免整页卡在"发送中"
    const watchdog = setTimeout(function () {
      if (entry.lastSeq === 0) {
        try { if (entry.controller && entry.controller.abort) entry.controller.abort(true); } catch (e) { /* ignore */ }
        liveStreams.delete(cid);
        entry.timeline.process({ type: 'Error', message: '该会话已中断，未能恢复' });
        setSending(false);
        refreshNav();
      }
    }, 10000);
    try {
      await runStream('/api/chat/recover', { conversationId: cid, recovery: true, startSeq: 1 }, cid);
    } finally {
      clearTimeout(watchdog);
    }
  }

  /**
   * 后端留着中断快照（用户点了停止 / 流被掐断）时，给一个「继续生成」。
   * 之前这里什么都没做，所以点会话看着"没反应" —— /api/chat/resume 一直没被前端调用过。
   */
  function showResumeHint(cid) {
    const thread = $('thread');
    if (thread.querySelector('.resume-bar')) return;
    const bar = document.createElement('div');
    bar.className = 'resume-bar';
    bar.innerHTML = '<span class="rs-t">上次的回答中断了</span>'
      + '<button type="button" class="btn line rs-btn">继续生成</button>';
    thread.appendChild(bar);
    bar.querySelector('.rs-btn').onclick = function () { resumePaused(cid, bar); };
    scrollToBottom();
  }

  async function resumePaused(cid, bar) {
    if (liveStreams.has(cid)) return;
    if (bar) bar.remove();
    setChatEmpty(false);
    const { timeline } = appendAssistantTurn();
    const entry = { timeline, controller: null, lastSeq: 0, retries: 0 };
    liveStreams.set(cid, entry);
    setSending(true);
    scrollToBottom(true);
    refreshNav();
    try {
      await runStream('/api/chat/resume', { conversationId: cid, model: currentModel() }, cid);
    } catch (e) {
      timeline.process({ type: 'Error', message: '恢复失败：' + (e.message || '未知错误') });
      finishStream(cid);
    }
  }

  async function stop() {
    const key = conversationId || '__pending__';
    const entry = liveStreams.get(key);
    if (entry && entry.controller) entry.controller.abort(true);
    if (entry) entry.timeline.process({ type: 'Stopped', message: '已停止生成' });
    try {
      if (conversationId) await Api.post('/api/chat/stop?conversationId=' + encodeURIComponent(conversationId), {});
    } catch (e) { /* 幂等，忽略 */ }
    finishStream(key);
    // 停完立刻给出「继续生成」，不用刷新页面
    if (conversationId) showResumeHint(conversationId);
  }

  // ==================== 事件绑定 ====================

  function bind() {
    // 先定好布局（空态居中），后面任何绑定出错也不影响首屏形态
    setChatEmpty(true);
    $('btn-send').onclick = () => send();
    $('btn-stop').onclick = stop;

    $('btn-theme').onclick = () => Theme.toggle();
    $('btn-logout').onclick = () => Auth.logout();

    // 知识库下拉
    $('kb-btn').onclick = function (e) { e.stopPropagation(); toggleKbMenu(); };
    $('kb-done').onclick = function () { toggleKbMenu(false); };
    $('kb-all').onclick = function () {
      if (kbSel.size === kbList.length) kbSel.clear();
      else kbList.forEach(function (k) { kbSel.add(String(k.id)); });
      renderKbMenu();
      syncKbLabel();
    };
    $('kb-menu').onclick = function (e) { e.stopPropagation(); };
    document.addEventListener('click', function () { toggleKbMenu(false); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') toggleKbMenu(false); });

    const toggleSide = () => $('side').classList.toggle('collapsed');
    $('btn-collapse').onclick = toggleSide;
    $('btn-collapse-2').onclick = toggleSide;

    const input = $('input');
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        send();
      }
    });
    input.addEventListener('input', function () {
      // 按「行数 × 行高(24) + 上下内边距(4+6=10)」精确算高度，
      // 不能直接用 scrollHeight（会带亚像素误差，一打字就多出一行的高度）
      var cs = window.getComputedStyle(input);
      var lh = parseFloat(cs.lineHeight) || 24;
      var pad = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
      input.style.height = 'auto';
      var rows = Math.max(1, Math.round((input.scrollHeight - pad) / lh));
      input.style.height = Math.min(rows * lh + pad, 200) + 'px';
    });

    const u = Auth.getUser() || {};
    const name = u.nickname || u.username || '未登录';
    $('user-name').textContent = name;
    $('user-avatar').textContent = name.slice(0, 1);

    setChatEmpty(true);
    setSending(false);
  }

  $('thread').addEventListener('click', function (e) {
    const btn = e.target.closest ? e.target.closest('.act') : null;
    if (!btn) return;
    const turnEl = btn.closest('.turn-ai, .turn-user');
    if (!turnEl) return;
    if (btn.classList.contains('act-copy')) copyTurn(turnEl);
    else if (btn.classList.contains('act-retry') && turnEl.classList.contains('turn-ai')) retryTurn(turnEl);
  });

  window.addEventListener('beforeunload', function () { saveSnapshot(conversationId); });

  /**
   * 回到底部按钮：滚出底部区域时浮出，点一下恢复跟随。
   * 滚动条位置只在这里读，流式输出时不改它 —— 否则用户向上翻看会被一直拽下来。
   */
  (function bindToBottom() {
    const sc = $('scroll');
    const btn = $('to-bottom');
    if (!sc || !btn) return;
    function sync() {
      btn.hidden = isNearBottom();
    }
    sc.addEventListener('scroll', sync, { passive: true });
    btn.addEventListener('click', function () {
      scrollToBottom(true);
      sync();
    });
    window.addEventListener('resize', sync);
    sync();
  })();

  // ==================== 启动 ====================

  (async function main() {
    // 登录态闸门：没 token 直接回登录页，避免渲染半截卡在那儿
    if (!Auth.getToken()) {
      location.replace('login.html');
      return;
    }
    bind();
    await loadScope();
    refreshNav();
    if (conversationId) {
      await openConversation(conversationId);
    }
  })();
})();

// 模型选择器：WorkBuddy 风格自绘弹出菜单（数据来自 /api/chat/models，yml 单一真理源）
(function () {
  // 这个 IIFE 在主页 IIFE 之外，Api 得自己取（之前漏了 → Uncaught ReferenceError）
  const Api = window.ZW.Api;
  // 拉取失败时的兜底，保证选择器永远可用
  const FALLBACK = [
    { key: 'opus55',     label: 'Opus 5.5' },
    { key: 'sonnet55',   label: 'Sonnet 5.5' },
    { key: 'haiku55',    label: 'Haiku 5.5' },
    { key: 'gpt6astra',  label: 'GPT-6 Astra' },
    { key: 'gpt61sol',   label: 'GPT-6.1 Sol' },
    { key: 'gpt6luna',   label: 'GPT-6 Luna' },
    { key: 'ds41flash',  label: 'DeepSeek V4.1 Flash' },
    { key: 'ds4pro',     label: 'DeepSeek V4 Pro' },
    { key: 'glm53',      label: 'GLM-5.3' },
    { key: 'glm53flash', label: 'GLM-5.3 Flash' },
    { key: 'kimik3',     label: 'Kimi K3' },
    { key: 'kimik26',    label: 'Kimi K2.6' }
  ];

  let models = FALLBACK.slice();
  let current = localStorage.getItem('zw-model') || 'opus55';

  const btn = document.getElementById('model-btn');
  const menu = document.getElementById('model-menu');
  const label = document.getElementById('model-cur');
  if (!btn || !menu || !label) return;

  function render() {
    const cur = models.find(function (m) { return m.key === current; });
    if (!cur) { current = models[0].key; }
    label.textContent = models.find(function (m) { return m.key === current; }).label;
    menu.innerHTML = models.map(function (m) {
      return '<div class="model-item' + (m.key === current ? ' on' : '') + '" data-key="' + m.key + '">'
        + '<span>' + m.label + '</span>'
        + (m.key === current ? '<span class="check">✓</span>' : '')
        + '</div>';
    }).join('');
    window.__zwModel = current;
  }

  function open() { menu.hidden = false; }
  function close() { menu.hidden = true; }

  btn.addEventListener('click', function (e) {
    e.stopPropagation();
    menu.hidden ? open() : close();
  });
  menu.addEventListener('click', function (e) {
    const item = e.target.closest('.model-item');
    if (!item) return;
    current = item.dataset.key;
    localStorage.setItem('zw-model', current);
    render();
    close();
  });
  document.addEventListener('click', function (e) {
    if (!menu.hidden && !e.target.closest('#model-picker')) close();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') close();
  });

  // 先用兜底列表立即渲染（用户在接口返回前点开也不是空的），拉到数据后再覆盖
  render();

  Api.get('/api/chat/models').then(function (list) {
    if (Array.isArray(list) && list.length) models = list;
    if (!models.find(function (m) { return m.key === current; })) {
      current = models[0].key;
    }
    localStorage.setItem('zw-model', current);
    render();
  }).catch(function () { render(); });
})();
