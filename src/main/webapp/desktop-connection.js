'use strict';

window.StarHubDesktopConnection = (() => {
  const params = new URLSearchParams(location.search);
  const port = Number(params.get('desktopPort'));
  const state = params.get('desktopState') || '';
  const challenge = params.get('desktopChallenge') || '';
  const token = /^[A-Za-z0-9_-]{43}$/;
  const pending = Number.isInteger(port) && port >= 1024 && port <= 65535 && token.test(state) && token.test(challenge);
  let dialog, message, status, action, email = '';
  const text = (en, zh) => language === 'zh' ? zh : en;
  const node = (tag, label) => Object.assign(document.createElement(tag), {textContent: label});

  function returnToDesktop(fields) {
    const callback = new URL(`http://127.0.0.1:${port}/starhub/connect`);
    callback.search = new URLSearchParams({state, ...fields}).toString();
    location.replace(callback);
  }

  function start() {
    if (!pending) return false;
    dialog = document.createElement('dialog');
    dialog.id = 'desktopConnectionDialog';
    dialog.setAttribute('aria-labelledby', 'desktop-connection-title');
    const title = node('h2', text('Connect Star Hub for Windows', '连接 Star Hub Windows 版'));
    title.id = 'desktop-connection-title';
    message = node('p', '');
    status = node('p', '');
    status.setAttribute('role', 'status');
    action = node('button', '');
    action.className = 'desktop-install';
    const cancel = node('button', text('Cancel', '取消'));
    cancel.className = 'text-button';
    cancel.addEventListener('click', () => returnToDesktop({error: 'cancelled'}));
    dialog.addEventListener('cancel', event => { event.preventDefault(); returnToDesktop({error: 'cancelled'}); });
    $('accountDialog').addEventListener('close', () => { if (!dialog.open) dialog.showModal(); });
    const actions = document.createElement('div');
    actions.className = 'desktop-download-actions';
    actions.append(action, cancel);
    dialog.append(title, message, status, actions);
    document.body.append(dialog);
    dialog.showModal();
    void refresh();
    return true;
  }

  async function refresh() {
    action.disabled = true;
    action.textContent = text('Continue', '继续');
    status.textContent = text('Checking your Star Hub sign-in…', '正在检查 Star Hub 登录状态…');
    try {
      const response = await fetch('api/account?desktopIdentity=1', {cache: 'no-store'});
      if (response.status === 401) email = '';
      else {
        if (!response.ok) throw Error();
        const identity = await response.json();
        email = identity.signedIn && typeof identity.email === 'string' ? identity.email : '';
      }
      status.textContent = '';
      message.textContent = email
        ? text(`Connect as ${email}. The desktop app will receive your saved OpenAI key and your chat model from this browser. Desktop conversations and files stay on this PC.`,
               `以 ${email} 连接。桌面应用会读取你已保存的 OpenAI 密钥及此浏览器选用的对话模型。桌面对话和文件仍保存在本机。`)
        : text('Sign in with your Star Hub account, then approve this connection.', '请登录 Star Hub 账号，然后确认连接。');
      action.textContent = email ? text('Connect this PC', '连接这台电脑') : text('Sign in', '登录');
      action.onclick = email ? approve : () => {
        dialog.close();
        $('accountButton').click();
      };
    } catch {
      status.textContent = text('Could not check your account. Please retry.', '暂时无法检查账号，请重试。');
      action.textContent = text('Retry', '重试');
      action.onclick = refresh;
    } finally { action.disabled = false; }
  }

  async function approve() {
    action.disabled = true;
    try {
      const model = directionSettings.get('openai|general') || settings.get('openai') || '';
      const response = await fetch('api/account', {
        method: 'POST', cache: 'no-store',
        headers: {'Content-Type': 'application/json', 'X-Starhub-Client': 'web'},
        body: JSON.stringify({action: 'desktopGrant', expectedEmail: email, challenge, model})
      });
      if (response.status === 401 || response.status === 409) { await refresh(); return; }
      const result = await response.json();
      if (!response.ok || !/^[A-Za-z0-9_-]{40,2048}$/.test(result.code || '')) throw Error();
      returnToDesktop({code: result.code});
    } catch {
      status.textContent = text('Could not connect. Please retry.', '暂时无法连接，请重试。');
    } finally { action.disabled = false; }
  }

  return {pending, start, refresh};
})();
