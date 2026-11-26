(() => {
  const key = 'starhub.download.language';
  const toggle = document.getElementById('download-language');
  const fields = [...document.querySelectorAll('[data-text]')];
  const title = document.title;
  const build = document.getElementById('download-build');
  let version = '';
  const english = Object.fromEntries(fields.map(field => [field.dataset.text, field.textContent]));
  const chinese = {
    title: 'Star Hub Windows 版',
    requirements: '仅适用于 Windows · Windows 10 22H2 或 Windows 11 · x64',
    description: '用语音或键盘处理文件、执行任务，也可以用自己的资料学习。桌面版将对话和任务记录保存在本机。',
    download: '下载 Windows 安装程序',
    checksum: '安装程序 SHA-256',
    install: '安装 Star Hub',
    stepOpen: '在 Windows 10 22H2（内部版本 19045）或 Windows 11 上运行安装程序。所需运行组件已打包，安装完成后会启动 Star Hub。',
    stepAccount: '登录后可以使用官网已保存的 OpenAI 连接。开启“长期记忆”，即可与网页版共享记忆。',
    stepLesson: '想试试跟表学习，可在开启“操作这台电脑”后说“教我制造业成本核算”，也可以在生成的成本表下选择“跟着这张表学核算”。',
    update: '更新时，请先完成正在运行的任务并关闭 Star Hub，再运行新下载的安装程序。本机数据会保留。已登录的用户请点自己的名字，选择一次“从官网刷新”，即可连接账号记忆。',
    offline: '使用本地千问离线聊天',
    ollama: '安装 Windows 版 Ollama',
    pull: '联网时，在 PowerShell 中运行下方命令一次。保持 Ollama 运行，Star Hub 选用“自动”即可。',
    notice: '安装程序未经代码签名，Windows SmartScreen 可能显示发布者提示。请用公开的 SHA-256 校验值核对文件。',
    sourceTitle: 'Windows 桌面版源代码',
    sourceHelp: '源码压缩包的构建目标仅为 Windows x64。直接使用 Star Hub 时，请下载上面的安装程序。',
    sourceZip: '下载源码 ZIP',
    sourceChecksum: '源码 SHA-256'
  };
  function render(locale) {
    const useChinese = locale === 'zh-CN';
    const strings = useChinese ? chinese : english;
    document.documentElement.lang = locale;
    document.title = useChinese ? '下载 Star Hub' : title;
    for (const field of fields) field.textContent = strings[field.dataset.text];
    if (version) {
      build.textContent = (useChinese ? '当前安装包 ' : 'Current installer ') + version;
      build.hidden = false;
    }
    toggle.lang = useChinese ? 'en' : 'zh-CN';
    toggle.textContent = useChinese ? 'English' : '中文';
  }
  let locale = 'en';
  try { if (localStorage.getItem(key) === 'zh-CN') locale = 'zh-CN'; } catch { /* Browsing still works without storage. */ }
  render(locale);
  toggle.hidden = false;
  fetch('windows/preview/starhub-windows.json', {cache: 'no-store'})
    .then(response => response.ok ? response.json() : null)
    .then(manifest => {
      if (manifest?.platform === 'windows' && /^\d+\.\d+\.\d+$/.test(manifest.version)) {
        version = manifest.version;
        render(locale);
      }
    }).catch(() => { /* The static download links also work without metadata. */ });
  toggle.addEventListener('click', () => {
    locale = locale === 'en' ? 'zh-CN' : 'en';
    render(locale);
    try { localStorage.setItem(key, locale); } catch { /* Keep the selection for this page. */ }
  });
})();
