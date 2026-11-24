/* 登录（注册入口已关闭：账号由管理员在「管理」页创建） */
(function () {
  'use strict';
  const { Api, Auth, toast } = window.ZW;

  // 已登录直接进对话页
  if (Auth.getToken()) {
    location.replace('chat.html');
    return;
  }

  function afterLogin(data) {
    Auth.setToken(data.token);
    Auth.setUser(data.user);
    toast('欢迎回来，' + (data.user.nickname || data.user.username), 'ok');
    location.replace('chat.html');
  }

  document.getElementById('form-login').onsubmit = async function (e) {
    e.preventDefault();
    const errEl = document.getElementById('login-err');
    errEl.textContent = '';
    const username = document.getElementById('login-username').value.trim();
    const password = document.getElementById('login-password').value;
    if (!username || !password) { errEl.textContent = '请填写用户名和密码'; return; }
    try {
      afterLogin(await Api.post('/api/auth/login', { username, password }));
    } catch (err) {
      errEl.textContent = err.message;
    }
  };
})();
