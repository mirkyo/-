/* 知识库管理页：知识库 CRUD / 文档列表 / 上传文档（含切块预览与确认入库） */
(function () {
  'use strict';
  const { Api, Auth, Nav, escapeHtml, toast, Theme, formatTime, Dialog, renderMarkdown } = window.ZW;
  if (!Auth.require()) return;

  const $ = id => document.getElementById(id);
  const ICON_COLORS = ['fi-blue', 'fi-cyan', 'fi-slate', 'fi-amber'];

  let kbs = [];
  let docs = [];
  let currentKb = null;
  let preview = null;
  let previewRole = 'child';
  let chosenFile = null;

  const SPLITTER_LABEL = { SLIDING: '滑动窗口', RECURSIVE: '递归字符', HEADING: '标题层级' };

  // ==================== 知识库列表 ====================

  async function load() {
    $('kb-list').innerHTML = skeletonRows(2);
    try {
      kbs = (await Api.get('/api/rag/knowledge-base')) || [];
    } catch (e) {
      kbs = [];
      toast(e.message, 'error');
    }
    renderRows();
  }

  function skeletonRows(n) {
    let html = '';
    for (let i = 0; i < n; i++) {
      html += '<div class="kb-row" style="pointer-events:none">'
        + '<div class="skel" style="width:34px;height:34px;border-radius:10px;flex:none"></div>'
        + '<div style="flex:1"><div class="skel w40" style="margin-bottom:9px"></div>'
        + '<div class="skel w60"></div></div></div>';
    }
    return html;
  }

  function renderRows() {
    $('total-pill').textContent = kbs.length + ' 个知识库';
    const countPill = $('kb-count');
    countPill.style.display = kbs.length ? '' : 'none';
    countPill.textContent = kbs.length;

    const box = $('kb-list');
    if (!kbs.length) {
      box.innerHTML = '<div class="empty" style="border:1px dashed var(--line);padding:46px 20px">'
        + '<div class="big"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>'
        + '<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg></div>'
        + '还没有知识库<br>点右上角「新建知识库」开始，然后上传文档</div>';
      return;
    }

    box.innerHTML = kbs.map((k, i) => {
      return '<div class="kb-row" data-id="' + k.id + '">'
        + '<span class="fileico ' + ICON_COLORS[i % 4] + '" style="width:34px;height:34px;border-radius:10px">'
        + '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg></span>'
        + '<div class="meta">'
        + '<div class="nm">' + escapeHtml(k.name)
        + (k.id === (currentKb && currentKb.id) ? ' <span class="pill blue" style="font-size:11px">最近使用</span>' : '')
        + '</div>'
        + '<div class="ds">' + escapeHtml(k.description || '（没有写说明）') + '</div>'
        + '</div>'
        + '<div class="acts">'
        + '<button class="btn tt sm" data-act="edit">编辑</button>'
        + '<button class="btn tt sm" data-act="upload">上传文档</button>'
        + '<button class="btn tt sm" data-act="docs">文档</button>'
        + '<button class="btn tt sm danger" data-act="del">删除</button>'
        + '</div></div>';
    }).join('');

    box.querySelectorAll('.kb-row').forEach(row => {
      const id = Number(row.dataset.id);
      row.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = () => {
          const act = btn.dataset.act;
          if (act === 'edit') editKb(id);
          else if (act === 'upload') openUpload(id);
          else if (act === 'docs') openDocs(id);
          else if (act === 'del') removeKb(id);
        };
      });
    });
  }

  function findKb(id) {
    return kbs.find(k => k.id === id);
  }

  async function createKb() {
    const v = await Dialog.form({
      title: '新建知识库',
      okText: '创建',
      fields: [
        { key: 'name', label: '知识库名称', required: true, placeholder: '例如：深度学习笔记' },
        { key: 'description', label: '说明', type: 'textarea', rows: 3, placeholder: '可空，写清这个库放什么' }
      ]
    });
    if (!v) return;
    try {
      const id = await Api.post('/api/rag/knowledge-base', { name: v.name, description: v.description });
      toast('知识库已创建', 'ok');
      await load();
      openUpload(id);
    } catch (e) { toast(e.message, 'error'); }
  }

  async function editKb(id) {
    const kb = findKb(id);
    if (!kb) return;
    const v = await Dialog.form({
      title: '编辑知识库',
      okText: '保存',
      fields: [
        { key: 'name', label: '知识库名称', required: true, value: kb.name },
        { key: 'description', label: '说明', type: 'textarea', rows: 3, value: kb.description || '' }
      ]
    });
    if (!v) return;
    try {
      await Api.put('/api/rag/knowledge-base/' + id, { name: v.name, description: v.description });
      toast('已保存', 'ok');
      await load();
    } catch (e) { toast(e.message, 'error'); }
  }

  async function removeKb(id) {
    const kb = findKb(id);
    if (!await Dialog.confirm('删除知识库「' + (kb ? kb.name : '') + '」会同时清掉里面的文档与向量，且不可恢复。', '删除知识库', true)) return;
    try {
      await Api.del('/api/rag/knowledge-base/' + id);
      toast('已删除', 'ok');
      if (currentKb && currentKb.id === id) currentKb = null;
      await load();
    } catch (e) { toast(e.message, 'error'); }
  }

  // ==================== 文档列表弹窗 ====================

  async function openDocs(id) {
    currentKb = findKb(id);
    if (!currentKb) return;
    $('doc-modal-title').textContent = '文档列表 · ' + currentKb.name;
    $('doc-list').innerHTML = '<div class="loading"><span class="dot"></span><span class="dot"></span>'
      + '<span class="dot"></span>正在读取文档…</div>';
    $('doc-modal').style.display = 'flex';
    try {
      docs = (await Api.get('/api/rag/knowledge-base/' + id + '/document')) || [];
    } catch (e) {
      docs = [];
      toast(e.message, 'error');
    }
    renderDocs();
  }

  function renderDocs() {
    if (!docs.length) {
      $('doc-list').innerHTML = '<div class="empty" style="padding:40px 20px">'
        + '<div class="big"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>'
        + '<path d="M14 2v6h6"/></svg></div>这个知识库还没有文档</div>';
      return;
    }
    $('doc-list').innerHTML = docs.map(d =>
      '<div class="file-row">'
      + '<span class="fileico fi-blue" style="width:30px;height:30px;border-radius:9px">'
      + '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg></span>'
      + '<div class="meta"><div class="nm">' + escapeHtml(d.fileName) + '</div>'
      + '<div class="sub">' + escapeHtml(SPLITTER_LABEL[d.splitterType] || d.splitterType || '') + ' · '
      + (d.chunkCount || 0) + ' 块 · ' + (d.createTime ? formatTime(d.createTime) : '') + '</div></div>'
      + (d.status === 'SUCCESS' ? '<span class="pill green">已入库</span>' : '<span class="pill bad">解析失败</span>')
      + '<button class="btn tt sm danger" data-del="' + d.id + '">删除</button>'
      + '</div>').join('');

    $('doc-list').querySelectorAll('[data-del]').forEach(b => {
      b.onclick = async () => {
        if (!await Dialog.confirm('删除该文档会同时清掉它在向量库里的数据，且不可恢复。', '删除文档', true)) return;
        try {
          await Api.del('/api/rag/document/' + b.dataset.del);
          toast('已删除', 'ok');
          docs = (await Api.get('/api/rag/knowledge-base/' + currentKb.id + '/document')) || [];
          renderDocs();
        } catch (e) { toast(e.message, 'error'); }
      };
    });
  }

  // ==================== 上传文档弹窗 ====================

  function setStep(n) {
    document.querySelectorAll('#steps .st').forEach(el => {
      const s = Number(el.dataset.s);
      el.classList.toggle('on', s === n);
      el.classList.toggle('done', s < n);
    });
  }

  function openUpload(id) {
    currentKb = findKb(id) || currentKb;
    if (!currentKb) { toast('请先新建知识库', 'error'); return; }
    preview = null;
    chosenFile = null;
    $('up-title').textContent = '上传文档（知识库：' + currentKb.name + '）';
    $('up-filename').textContent = '未选择任何文件';
    $('up-preview').style.display = 'none';
    $('up-commit').disabled = true;
    $('doc-modal').style.display = 'none';
    $('up-modal').style.display = 'flex';
    setStep(1);
  }

  function closeUpload() {
    $('up-modal').style.display = 'none';
    setStep(1);
  }

  function setFile(file) {
    chosenFile = file;
    $('up-filename').textContent = file.name + '（' + (file.size / 1024).toFixed(0) + ' KB）';
    preview = null;
    $('up-preview').style.display = 'none';
    $('up-commit').disabled = true;
  }

  function segSelected(id) {
    return $(id).classList.contains('on');
  }

  function syncHeadingExtra() {
    const heading = $('up-splitter').value === 'HEADING';
    $('heading-extra').style.display = heading ? '' : 'none';
    // 图片语义理解只有 MinerU 支持
    const mineru = $('up-parser').value === 'MINERU';
    $('image-extra').style.display = mineru ? '' : 'none';
    if (!mineru) $('up-sw-img').classList.remove('on');
  }

  async function doPreview() {
    if (!chosenFile) { toast('请先选择文件', 'error'); return; }

    const form = new FormData();
    form.append('file', chosenFile);
    form.append('parserType', $('up-parser').value);
    form.append('splitterType', $('up-splitter').value);
    form.append('chunkSize', $('up-chunk').value || '500');
    form.append('overlap', $('up-overlap').value || '50');
    form.append('headingLevel', $('up-level').value || '2');
    form.append('maxParentSize', $('up-parent').value || '5000');
    form.append('parentChild', segSelected('up-sw') ? 'true' : 'false');
    form.append('enableImageUnderstanding', $('up-sw-img').classList.contains('on') ? 'true' : 'false');

    const btn = $('up-preview-btn');
    btn.disabled = true;
    const old = btn.innerHTML;
    btn.textContent = '解析中…';
    setStep(2);
    try {
      preview = await Api.upload('/api/rag/document/preview', form);
      renderPreview();
      $('up-commit').disabled = false;
      setStep(3);
      toast('解析完成，共 ' + preview.totalChunks + ' 块', 'ok');
    } catch (e) {
      preview = null;
      $('up-preview').style.display = 'none';
      setStep(1);
      toast(e.message, 'error');
    } finally {
      btn.disabled = false;
      btn.innerHTML = old;
    }
  }

  function renderPreview() {
    if (!preview) return;
    $('up-preview').style.display = '';
    $('up-count').textContent = preview.totalChunks + ' 块（父 ' + preview.parentChunks + ' / 子 ' + preview.childChunks + '）';
    $('up-docid').textContent = 'documentId ' + String(preview.documentId).slice(0, 14) + '…';

    $('up-tabs').innerHTML = '<button class="' + (previewRole === 'child' ? 'on' : '') + '" data-v="child">子块</button>'
      + '<button class="' + (previewRole === 'parent' ? 'on' : '') + '" data-v="parent">父块</button>';
    $('up-tabs').querySelectorAll('button').forEach(b => {
      b.onclick = () => { previewRole = b.dataset.v; renderPreview(); };
    });

    const wantParent = previewRole === 'parent';
    const list = (preview.chunks || [])
      .filter(c => wantParent ? c.role === 'parent' : c.role !== 'parent')
      .slice(0, 40);

    const cards = list.map(c => '<div class="chunk"><div class="chunk-hd">'
      + '<span class="mk ' + (c.role === 'parent' ? 'p' : 'c') + '">#' + c.index + '</span>'
      + (c.role === 'parent' ? '父块 · 完整小节' : '子块')
      + (c.assetUrl ? ' · 图片' : '') + '</div>'
      + (c.assetUrl
          ? '<div class="chunk-img"><img src="' + c.assetUrl + '" alt="图片" loading="lazy"></div>'
          : '')
      + '<div class="chunk-x">'
      + ((c.text || '').indexOf('![') >= 0
          ? renderMarkdown(c.text)
          : escapeHtml(c.text || '').slice(0, 500))
      + '</div></div>').join('');

    // 切块预览也收进一个长方体：默认露出前几块，点开变滚动窗口（和对话页的命中片段一致）
    $('up-list').innerHTML = list.length === 0
      ? '<div class="empty" style="padding:24px">该类型没有切块</div>'
      : '<div class="chunks-box"><div class="chunks-list">' + cards + '</div>'
        + (list.length > 3
            ? '<button type="button" class="hits-more" data-n="' + list.length + '">展开全部 '
              + list.length + ' 块</button>'
            : '')
        + '</div>';

    const more = $('up-list').querySelector('.hits-more');
    if (more) {
      more.onclick = function () {
        const box = more.closest('.chunks-box');
        const open = box.classList.toggle('open');
        more.textContent = open ? '收起' : ('展开全部 ' + more.dataset.n + ' 块');
      };
    }
  }

  async function doCommit() {
    if (!preview || !currentKb) return;
    const btn = $('up-commit');
    btn.disabled = true;
    btn.textContent = '入库中…';
    try {
      await Api.post('/api/rag/document/commit', {
        knowledgeBaseId: currentKb.id,
        documentId: preview.documentId,
        fileName: preview.fileName,
        fileSize: chosenFile ? chosenFile.size : 0,
        parserType: $('up-parser').value,
        splitterType: $('up-splitter').value,
        chunkSize: Number($('up-chunk').value || 500),
        overlap: Number($('up-overlap').value || 50),
        maxParentSize: Number($('up-parent').value || 5000),
        parentChild: segSelected('up-sw')
      });
      toast('入库完成', 'ok');
      setStep(4);
      closeUpload();
      await load();
      openDocs(currentKb.id);
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      btn.textContent = '确认入库';
    }
  }

  // ==================== 绑定 ====================

  function bind() {
    Nav.render('knowledge-base');
    $('btn-create-kb').onclick = createKb;
    $('btn-theme').onclick = () => Theme.toggle();
    $('btn-logout').onclick = () => Auth.logout();

    const toggle = () => $('side').classList.toggle('collapsed');
    $('btn-collapse').onclick = toggle;
    $('btn-collapse-2').onclick = toggle;

    // 文档弹窗
    $('doc-close').onclick = () => { $('doc-modal').style.display = 'none'; };
    $('doc-ok').onclick = () => { $('doc-modal').style.display = 'none'; };
    $('doc-upload').onclick = () => openUpload(currentKb && currentKb.id);

    // 上传弹窗
    $('up-close').onclick = closeUpload;
    $('up-cancel').onclick = closeUpload;
    $('up-preview-btn').onclick = doPreview;
    $('up-commit').onclick = doCommit;
    $('up-sw').onclick = () => $('up-sw').classList.toggle('on');
    $('up-sw-img').onclick = () => $('up-sw-img').classList.toggle('on');
    $('up-splitter').onchange = syncHeadingExtra;
    $('up-parser').onchange = syncHeadingExtra;

    const pick = $('up-pick');
    const file = $('up-file');
    pick.onclick = () => file.click();
    file.onchange = () => { if (file.files.length) setFile(file.files[0]); };

    // 弹窗外部点击关闭
    [$('doc-modal'), $('up-modal')].forEach(mask => {
      mask.onclick = e => { if (e.target === mask) mask.style.display = 'none'; };
    });

    const u = Auth.getUser() || {};
    const name = u.nickname || u.username || '-';
    $('user-name').textContent = name;
    $('user-avatar').textContent = name.slice(0, 1);
  }

  (async function main() {
    bind();
    syncHeadingExtra();
    await load();
  })();
})();
