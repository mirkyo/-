/* 技能页：列表 / zip 上传 / 启停 / 删除 */
(function () {
  'use strict';
  const { Api, Auth, escapeHtml, toast, Theme, Nav, Dialog } = window.ZW;
  if (!Auth.require()) return;

  const $ = id => document.getElementById(id);
  let skills = [];

  async function load() {
    $('skill-grid').innerHTML = '<div class="loading"><span class="dot"></span><span class="dot"></span>'
      + '<span class="dot"></span>正在读取技能…</div>';
    skills = (await Api.get('/api/skills')) || [];
    render();
  }

  function render() {
    $('skill-count').textContent = skills.length;
    const grid = $('skill-grid');
    if (!skills.length) {
      grid.innerHTML = '<div class="empty" style="grid-column:1/-1;padding:48px 20px">'
        + '<div class="big"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
        + '<path d="M13 2 4.09 13.14A1 1 0 0 0 4.88 14.8H11l-1 7.2 8.91-11.14A1 1 0 0 0 18.12 9.2H12z"/></svg></div>'
        + '还没有技能<br>把含 SKILL.md 的 zip 拖上去就行</div>';
      return;
    }
    grid.innerHTML = skills.map((s, i) => {
      const cls = 't' + ((i % 3) + 1);
      const ico = ['ti-blue', 'ti-purple', 'ti-teal'][i % 3];
      return '<div class="tile ' + cls + '">'
        + '<div class="tile-hd"><span class="tile-ico ' + ico + '">'
        + '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
        + '<path d="M13 2 4.09 13.14A1 1 0 0 0 4.88 14.8H11l-1 7.2 8.91-11.14A1 1 0 0 0 18.12 9.2H12z"/></svg></span>'
        + '<div style="min-width:0;flex:1"><div class="tile-name" title="' + escapeHtml(s.name) + '">' + escapeHtml(s.name) + '</div>'
        + '<div style="font-size:12px;color:var(--text-3)">' + (s.enabled ? '已启用' : '已停用') + '</div></div></div>'
        + '<div class="tile-x">' + escapeHtml(s.description || '（SKILL.md 里没有写 description）') + '</div>'
        + '<div class="tile-ft">'
        + '<div class="sw ' + (s.enabled ? 'on' : '') + '" data-toggle="' + escapeHtml(s.name) + '"></div>'
        + '<span style="font-size:13px;color:var(--text-2)">启用</span>'
        + '<button class="btn tt sm" style="margin-left:auto" data-del="' + escapeHtml(s.name) + '">删除</button>'
        + '</div></div>';
    }).join('');

    grid.querySelectorAll('[data-toggle]').forEach(el => {
      el.onclick = async () => {
        const name = el.dataset.toggle;
        const next = !el.classList.contains('on');
        try {
          await Api.put('/api/skills/' + encodeURIComponent(name) + '/enabled?enabled=' + next, {});
          toast(next ? '已启用' : '已停用', 'ok');
          await load();
        } catch (e) { toast(e.message, 'error'); }
      };
    });
    grid.querySelectorAll('[data-del]').forEach(el => {
      el.onclick = async () => {
        const name = el.dataset.del;
        if (!await Dialog.confirm('删除技能「' + name + '」会同时删掉磁盘上的目录，且不可恢复。', '删除技能', true)) return;
        try {
          await Api.del('/api/skills/' + encodeURIComponent(name));
          toast('已删除', 'ok');
          await load();
        } catch (e) { toast(e.message, 'error'); }
      };
    });
  }

  async function upload(file) {
    if (!file.name.toLowerCase().endsWith('.zip')) {
      toast('只支持 zip 包', 'error');
      return;
    }
    const form = new FormData();
    form.append('file', file);
    toast('正在上传解析…');
    try {
      const info = await Api.upload('/api/skills/upload', form);
      toast('技能「' + info.name + '」已安装', 'ok');
      await load();
    } catch (e) { toast(e.message, 'error'); }
  }

  function bind() {
    Nav.render('skills');
    $('btn-theme').onclick = () => Theme.toggle();
    const toggle = () => $('side').classList.toggle('collapsed');
    $('btn-collapse').onclick = toggle;
    $('btn-collapse-2').onclick = toggle;

    const drop = $('drop-area');
    const input = $('file-input');
    drop.onclick = () => input.click();
    drop.ondragover = e => { e.preventDefault(); drop.style.borderColor = 'var(--blue)'; };
    drop.ondragleave = () => { drop.style.borderColor = ''; };
    drop.ondrop = e => {
      e.preventDefault();
      drop.style.borderColor = '';
      if (e.dataTransfer.files.length) upload(e.dataTransfer.files[0]);
    };
    input.onchange = () => { if (input.files.length) upload(input.files[0]); };

    const u = Auth.getUser() || {};
    const name = u.nickname || u.username || '-';
    $('user-name').textContent = name;
    $('user-avatar').textContent = name.slice(0, 1);
  }

  (async function main() {
    bind();
    try { await load(); } catch (e) { toast(e.message || '加载失败', 'error'); }
  })();
})();
