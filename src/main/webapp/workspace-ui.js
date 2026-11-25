/* Browser-local workspace layout and navigation. */
(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const download = byId('desktopDownload'), downloadDialog = byId('desktopDownloadDialog');
  if (download && typeof downloadDialog?.showModal === 'function') {
    download.addEventListener('click', event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || downloadDialog.open) return;
      event.preventDefault();
      downloadDialog.showModal();
    });
  }
  const search = byId('historySearch'), history = byId('history');
  const noMatches = byId('historyNoMatches'), menu = byId('menuToggle');
  const sidebar = byId('sidebar'), closeButton = byId('sidebarClose'), picker = byId('quickModel'), library = byId('browseModels');
  const layout = byId('workspaceLayout'), actions = byId('workspaceActions');
  if (!search || !history || !noMatches || !menu || !sidebar || !closeButton || !picker || !library || !layout || !actions) return;
  const wide = matchMedia('(min-width: 761px)');
  const docked = () => wide.matches && document.body.dataset.layout === 'docked';
  const sidebarOpen = () => docked() ? !document.body.classList.contains('sidebar-collapsed') : sidebar.classList.contains('open');
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
    const visible = sidebarOpen();
    sidebar.inert = !visible;
    menu.setAttribute('aria-expanded', String(visible));
    menu.setAttribute('aria-label', isChinese() ? (visible ? '收起侧边栏' : '打开侧边栏') : (visible ? 'Close sidebar' : 'Open sidebar'));
  };
  const setSidebar = visible => {
    if (docked()) document.body.classList.toggle('sidebar-collapsed', !visible);
    else sidebar.classList.toggle('open', visible);
    syncSidebar();
  };
  const closeOverlay = () => {
    if (!docked() && sidebarOpen()) setSidebar(false);
  };
  const applyLayout = value => {
    layout.value = document.body.dataset.layout = value === 'docked' ? 'docked' : 'floating';
    sidebar.classList.remove('open');
    document.body.classList.remove('sidebar-collapsed');
    syncSidebar();
  };
  search.addEventListener('input', filterHistory);
  new MutationObserver(filterHistory).observe(history, {childList: true});
  new MutationObserver(syncSidebar).observe(sidebar, {attributes: true, attributeFilter: ['class']});
  picker.addEventListener('click', () => { if (!library.disabled) library.click(); });
  new MutationObserver(updatePicker).observe(library, {attributes: true, attributeFilter: ['disabled']});
  document.addEventListener('starhub:modelchanged', updatePicker);
  document.addEventListener('starhub:languagechanged', translate);
  document.addEventListener('starhub:sidebar-open', () => setSidebar(true));
  layout.addEventListener('change', () => {
    applyLayout(layout.value);
    if (!StarStore.write('workspaceLayout', layout.value)) showError(t('无法保存设置，请检查浏览器存储空间。'));
  });
  menu.addEventListener('click', () => {
    setSidebar(!sidebarOpen());
    if (!docked() && sidebarOpen()) closeButton.focus();
  });
  closeButton.addEventListener('click', () => { setSidebar(false); menu.focus(); });
  history.addEventListener('click', event => {
    if (event.target.closest('.history-chat')) closeOverlay();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('dialog[open]')) return;
    if (actions.open) {
      actions.open = false;
      actions.querySelector('summary').focus();
    } else if (!docked() && sidebarOpen()) {
      closeOverlay();
      menu.focus();
    }
  });
  document.addEventListener('pointerdown', event => {
    if (!sidebar.contains(event.target) && !menu.contains(event.target)) closeOverlay();
    if (actions.open && !actions.contains(event.target)) actions.open = false;
  });
  wide.addEventListener('change', () => { sidebar.classList.remove('open'); syncSidebar(); });
  applyLayout(StarStore.preference('workspaceLayout', 'floating'));
  document.addEventListener('DOMContentLoaded', () => { translate(); filterHistory(); });
})();
