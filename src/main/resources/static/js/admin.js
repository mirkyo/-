/* 管理页：用户管理（已去掉多租户，只有管理员 / 普通用户两种角色） */
(function () {
  'use strict';
  const { Api, Auth, escapeHtml, toast, Theme, Nav, formatTime, Dialog } = window.ZW;
  if (!Auth.require()) return;

  const $ = id => document.getElementById(id);
  const isSuper = Auth.isSuperAdmin();

  let users = [];

  const ROLE_LABEL = { SUPER_ADMIN: '超级管理员', ADMIN: '管理员', USER: '普通用户' };

  // ---------------- 用户 ----------------

  async function loadUsers() {
    $('user-table').innerHTML = '<tr><td colspan="6"><div class="loading"><span class="dot"></span>'
      + '<span class="dot"></span><span class="dot"></span>正在读取用户…</div></td></tr>';
    const data = await Api.get('/api/admin/user?page=0&size=100');
    users = (data && data.list) || [];
    renderUsers();
  }

  function renderUsers() {
    const tbody = $('user-table');
    if (!users.length) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty" style="padding:36px">还没有用户</td></tr>';
      return;
    }
    tbody.innerHTML = users.map(u =>
      '<tr>'
      + '<td><div class="name">' + escapeHtml(u.username) + '</div></td>'
      + '<td>' + escapeHtml(u.nickname || '—') + '</td>'
      + '<td><span class="pill ' + (u.role === 'SUPER_ADMIN' ? 'grad' : u.role === 'ADMIN' ? 'blue' : '') + '">'
      + (ROLE_LABEL[u.role] || u.role) + '</span></td>'
      + '<td>' + (u.status === 1 ? '<span class="pill bad">已禁用</span>' : '<span class="pill green">正常</span>') + '</td>'
      + '<td style="color:var(--text-3);font-size:13px">' + (u.lastLoginAt ? formatTime(u.lastLoginAt) : '—') + '</td>'
      + '<td><button class="btn line sm" data-reset="' + u.id + '">改密</button>'
      + ' <button class="btn tt sm" data-del="' + u.id + '">删除</button></td></tr>').join('');

    tbody.querySelectorAll('[data-reset]').forEach(b => {
      b.onclick = async () => {
        const v = await Dialog.form({
          title: '重置密码', okText: '保存',
          fields: [{ key: 'password', label: '新密码', type: 'password', required: true, placeholder: '至少 6 位' }],
          onValidate: x => (!x.password || x.password.length < 6) ? '密码至少 6 位' : null
        });
        if (!v) return;
        try {
          await Api.put('/api/admin/user/' + b.dataset.reset, { password: v.password });
          toast('密码已更新', 'ok');
        } catch (e) { toast(e.message, 'error'); }
      };
    });
    tbody.querySelectorAll('[data-del]').forEach(b => {
      b.onclick = async () => {
        if (!await Dialog.confirm('删除后该账号无法恢复，确定删除？', '删除用户', true)) return;
        try {
          await Api.del('/api/admin/user/' + b.dataset.del);
          toast('已删除', 'ok');
          await loadUsers();
        } catch (e) { toast(e.message, 'error'); }
      };
    });
  }

  async function addUser() {
    const v = await Dialog.form({
      title: '新建用户', okText: '创建',
      fields: [
        { key: 'username', label: '用户名', required: true, placeholder: '登录账号' },
        { key: 'password', label: '初始密码', type: 'password', required: true, placeholder: '至少 6 位' },
        { key: 'nickname', label: '昵称', placeholder: '可空' },
        { key: 'role', label: '角色', type: 'select', value: 'USER', options: [
          { value: 'USER', label: '普通用户' },
          { value: 'ADMIN', label: '管理员' }
        ] }
      ],
      onValidate: x => (!x.password || x.password.length < 6) ? '密码至少 6 位' : null
    });
    if (!v) return;
    try {
      await Api.post('/api/admin/user', v);
      toast('用户已创建', 'ok');
      await loadUsers();
    } catch (e) { toast(e.message, 'error'); }
  }

  // ---------------- 绑定 ----------------

  function bind() {
    Nav.render('admin');
    $('btn-theme').onclick = () => Theme.toggle();
    const toggle = () => $('side').classList.toggle('collapsed');
    $('btn-collapse').onclick = toggle;
    $('btn-collapse-2').onclick = toggle;

    $('btn-add-user').onclick = addUser;
    $('scope-desc').textContent = '管理员可以新建用户、重置密码、删除账号；普通用户只能查看自己的信息。';

    const u = Auth.getUser() || {};
    const name = u.nickname || u.username || '-';
    $('user-name').textContent = name;
    $('user-avatar').textContent = name.slice(0, 1);
  }

  (async function main() {
    bind();
    try {
      await loadUsers();
    } catch (e) { toast(e.message || '加载失败', 'error'); }
  })();
})();
