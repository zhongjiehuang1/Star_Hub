/* Local presentation controls only. No account, network, or storage mutations. */
(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const search = byId('historySearch'), history = byId('history');
  const noMatches = byId('historyNoMatches'), menu = byId('menuToggle');
  const sidebar = byId('sidebar'), closeButton = byId('sidebarClose'), picker = byId('quickModel'), library = byId('browseModels');
  if (!search || !history || !noMatches || !menu || !sidebar || !closeButton || !picker || !library) return;
  const wide = matchMedia('(min-width: 761px)');
  const isChinese = () => document.documentElement.lang.toLowerCase().startsWith('zh');
  const filterHistory = () => {
    const needle = search.value.trim().toLocaleLowerCase();
    let total = 0, shown = 0;
    history.querySelectorAll('.history-row').forEach(row => {
      const title = row.querySelector('.history-chat');
      if (!title) return;
      total++;
      const match = !needle || title.textContent.toLocaleLowerCase().includes(needle);
      row.hidden = !match;
      if (match) shown++;
    });
    const empty = history.querySelector('.history-empty');
    if (empty) empty.hidden = Boolean(needle);
    noMatches.hidden = !needle || shown > 0 || total === 0;
    if (needle && total === 0) noMatches.hidden = false;
  };
  const translate = () => {
    const zh = isChinese();
    byId('historySearchLabel').textContent = zh ? '你的对话' : 'Your chats';
    search.placeholder = zh ? '搜索已加载的对话标题' : 'Search loaded chat titles';
    byId('historySearchHelp').textContent = zh ? '搜索此设备上已经加载的对话标题。' : 'Search titles in chats already loaded on this device.';
    noMatches.textContent = zh ? '没有匹配的已加载对话。' : 'No matching loaded chats.';
    picker.setAttribute('aria-label', zh ? '选择模型' : 'Choose a model');
    closeButton.setAttribute('aria-label', zh ? '收起侧边栏' : 'Close sidebar');
    updatePicker();
    syncSidebar();
  };
  const updatePicker = () => {
    const model = byId('model');
    const selected = model?.selectedOptions?.[0];
    byId('quickModelName').textContent = model && model.value && selected ? selected.textContent : (isChinese() ? '选择模型' : 'Choose model');
    picker.disabled = library.disabled;
  };
  const syncSidebar = () => {
    const visible = wide.matches ? !document.body.classList.contains('sidebar-collapsed') : sidebar.classList.contains('open');
    sidebar.inert = !visible;
    menu.setAttribute('aria-expanded', String(visible));
    menu.setAttribute('aria-label', isChinese() ? (visible ? '收起侧边栏' : '打开侧边栏') : (visible ? 'Close sidebar' : 'Open sidebar'));
  };
  search.addEventListener('input', filterHistory);
  new MutationObserver(filterHistory).observe(history, {childList: true});
  new MutationObserver(syncSidebar).observe(sidebar, {attributes: true, attributeFilter: ['class']});
  picker.addEventListener('click', () => { if (!library.disabled) library.click(); });
  new MutationObserver(updatePicker).observe(library, {attributes: true, attributeFilter: ['disabled']});
  document.addEventListener('starhub:modelchanged', updatePicker);
  document.addEventListener('starhub:languagechanged', translate);
  // app.js owns the mobile open class; this listener adds the wide-screen state.
  menu.addEventListener('click', () => {
    if (wide.matches) document.body.classList.toggle('sidebar-collapsed');
    syncSidebar();
  });
  const closeMobile = () => {
    if (!wide.matches && sidebar.classList.contains('open')) {
      sidebar.classList.remove('open');
      syncSidebar();
    }
  };
  closeButton.addEventListener('click', () => { closeMobile(); menu.focus(); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !wide.matches && sidebar.classList.contains('open')) { closeMobile(); menu.focus(); }
  });
  document.addEventListener('pointerdown', event => {
    if (!wide.matches && !sidebar.contains(event.target) && !menu.contains(event.target)) closeMobile();
  });
  wide.addEventListener('change', () => { sidebar.classList.remove('open'); syncSidebar(); });
  document.addEventListener('DOMContentLoaded', () => { translate(); filterHistory(); });
})();
