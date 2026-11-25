(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const dialog = byId('intelligenceDialog');
  const launches = [byId('openIntelligence'), byId('quickIntelligence')];
  if (!dialog || launches.some(button => !button)) return;
  const main = byId('intelligenceAction');
  const title = byId('intelligenceTitle');
  const message = byId('intelligenceMessage');
  const form = byId('intelligenceFeedback');
  const feedback = byId('intelligenceFeedbackText');
  const submit = byId('intelligenceSendFeedback');
  const detail = byId('intelligenceDetails');
  let state, pending, reading, mutating = false, accountEpoch = 0;
  let wanted = new URL(location.href).searchParams.get('intelligence') === '1';
  const localize = (zh, en) => document.documentElement.lang.toLowerCase().startsWith('zh') ? zh : en;
  const node = (tag, text, css) => {
    const element = document.createElement(tag);
    if (text) element.textContent = text;
    if (css) element.className = css;
    return element;
  };
  const time = value => value > 0 ? new Intl.DateTimeFormat(localize('zh-CN', 'en-GB'), {
    timeZone: 'Europe/London', dateStyle: 'medium', timeStyle: 'short'
  }).format(new Date(value)) : '—';
  const funds = value => new Intl.NumberFormat('en', {
    style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4
  }).format((Number(value) || 0) / 1000000);
  const states = {
    READY: ['已完成复盘', 'Review complete'], RUNNING: ['正在复盘', 'Review in progress'],
    WAITING: ['等待下一次后台检查', 'Waiting for background review'], PAUSED: ['已暂停', 'Paused'],
    BUDGET: ['预算或调用上限已到', 'Budget or call limit reached'], MODEL_KEY: ['已选密钥已改变或缺失', 'Selected model key missing or changed'],
    MODEL_RESPONSE: ['模型请求未完成', 'Model request did not complete'],
    MODEL_UNCERTAIN: ['上次模型调用结果待确认', 'Last model request unconfirmed'],
    BUSY: ['模型通道忙，稍后重试', 'Model gateway busy'], DECISION_INVALID: ['模型输出未通过校验', 'Model output failed validation'],
    UNAVAILABLE: ['暂不可用', 'Unavailable'], SEARCH_UNCONFIRMED: ['检索结果缺少已确认的来源', 'Search evidence unconfirmed'],
    QUEUED: ['待发送', 'Queued'], DISPATCHING: ['发送请求处理中', 'Submitting'], RETRY: ['等待重试', 'Retry scheduled'],
    ACCEPTED: ['发信服务已接受，送达待确认', 'Accepted by sender, delivery pending'],
    VERIFYING: ['提交结果待确认，正在核查 QQ 收件箱', 'Submission uncertain, checking the QQ inbox'],
    RECEIVED: ['已在 QQ 收件箱核验', 'Verified in the QQ inbox'],
    AUTH_FAILED: ['QQ 发信授权失败', 'QQ mail authorization failed'],
    DELIVERED: ['发信服务报告已投递', 'Delivery reported by sender'], DELAYED: ['投递延迟', 'Delivery delayed'],
    BOUNCED: ['退信或被拒收', 'Bounced or rejected'], CANCELLED: ['已取消', 'Cancelled'], FAILED: ['发送失败', 'Failed'],
    UNKNOWN: ['发送结果未知', 'Submission unconfirmed'], UNCONFIRMED: ['送达未确认', 'Delivery unconfirmed'],
    OK: ['已更新', 'Updated'], keep: ['继续当前行动', 'Continue the current action'],
    change: ['更换行动', 'New action'], defer: ['补充证据后再判断', 'Awaiting more evidence'],
    fact: ['有来源的陈述', 'Sourced claim'], inference: ['推断', 'Inference'], forecast: ['预测', 'Forecast'], unknown: ['未知', 'Unknown'],
    completed: ['已完成', 'Complete'], blocked: ['遇到阻碍', 'Blocked'], progress: ['有进展', 'In progress'], correction: ['更正', 'Correction'],
    low: ['低', 'Low'], medium: ['中', 'Medium'], high: ['高', 'High']
  };
  const stateText = code => states[code] ? localize(...states[code]) : code || '—';

  async function request(action, data = {}) {
    const email = accountEmail;
    const identity = accountIdentityRevision;
    const epoch = accountEpoch;
    const response = await fetch('api/intelligence', {
      method: 'POST', cache: 'no-store', headers: {'Content-Type': 'application/json', 'X-Starhub-Client': 'web'},
      body: JSON.stringify({action, expectedEmail: email, ...data})
    });
    if (epoch !== accountEpoch || identity !== accountIdentityRevision || email !== accountEmail) {
      throw Object.assign(Error(), {discard: true});
    }
    const value = await response.json();
    if (!response.ok) throw Object.assign(Error(value.error), {code: value.code, status: response.status});
    return value;
  }
  function showError(error) {
    if (error.discard) return;
    message.textContent = error.code === 'ACTION_CHANGED'
      ? localize('行动已更新。刷新后可重新提交反馈。', 'The action changed. Refresh to submit feedback on the current action.')
      : localize(error.message || '暂时无法加载，请重试。', 'This request could not be confirmed. Your draft remains on this page.');
  }
  async function refresh() {
    if (reading || mutating) return;
    const flight = request('status');
    reading = flight;
    try {
      const next = await flight;
      if (reading === flight && dialog.open) { state = next; render(); }
    } catch (error) { if (reading === flight && dialog.open) showError(error); }
    finally { if (reading === flight) reading = null; }
  }
  async function mutate(action, payload) {
    if (mutating) return false;
    mutating = true;
    const epoch = accountEpoch;
    message.textContent = '';
    try {
      state = await request(action, payload);
      render();
      return true;
    } catch (error) {
      showError(error);
      return false;
    } finally { if (epoch === accountEpoch) mutating = false; }
  }
  function open() {
    if (!accountEmail) {
      byId('accountButton').click();
      return;
    }
    if (!dialog.open) dialog.showModal();
    title.focus();
    render();
    refresh();
  }
  function section(id, label, parent) {
    const value = node('details');
    value.dataset.section = id;
    value.open = parent.has(id);
    value.append(node('summary', label));
    detail.append(value);
    return value;
  }
  function field(container, label, value) {
    if (!value) return;
    container.append(node('h4', label), node('p', value));
  }
  function controls(container) {
    const box = node('div', '', 'intelligence-controls');
    container.append(box);
    return box;
  }
  function button(label, action) {
    const value = node('button', label);
    value.type = 'button';
    value.addEventListener('click', action);
    return value;
  }
  function sources(container, records) {
    if (!records?.length) return;
    const list = node('ul');
    for (const evidence of records) {
      const item = node('li');
      let url;
      try { url = new URL(evidence.url); } catch {}
      if (!url || url.protocol !== 'https:' || url.username || url.password || url.port || !url.hostname.includes('.')) {
        item.textContent = evidence.title;
      } else {
        const link = node('a', evidence.title || url.hostname);
        link.href = url.href;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        item.append(link);
      }
      if (evidence.at) item.append(document.createTextNode(' · ' + time(evidence.at)));
      list.append(item);
    }
    container.append(list);
  }
  function drawAction(action) {
    if (!action?.id) {
      const runtime = state?.runtime;
      const text = state && !state.settings.enabled ? localize('在授权设置中启用后台复盘。', 'Enable background review in permissions.')
        : state?.assessment?.reason || (runtime?.error ? stateText(runtime.error)
          : localize('后台正在收集证据，行动会在复盘完成后显示。', 'The action will appear after the background review has enough evidence.'));
      main.append(node('p', text, 'intelligence-muted'));
      return;
    }
    const card = node('article', '', 'intelligence-action');
    card.append(node('h3', action.title), node('p', action.why));
    const steps = node('ol');
    for (const step of action.steps || []) steps.append(node('li', step));
    card.append(steps);
    field(card, localize('时间与资源', 'Time and resources'), action.resources);
    field(card, localize('截止日期', 'Deadline'), action.deadline);
    field(card, localize('完成标准', 'Done when'), action.done);
    field(card, localize('完成后反馈', 'What to report'), action.feedback);
    if (action.evidence?.length) {
      const citations = node('details');
      citations.append(node('summary', localize('行动依据', 'Evidence for this action')));
      sources(citations, action.evidence);
      card.append(citations);
    }
    main.append(card);
  }
  function drawSettings(seen) {
    const panel = section('settings', localize('授权与运行设置', 'Permissions'), seen);
    const settings = state.settings;
    const box = node('form');
    const fields = [
      ['enabled', '保持后台运行', 'Keep running in the background'],
      ['memory', '使用已开启的账户记忆', 'Use enabled account memory'],
      ['chats', '使用近期已保存对话中的用户消息', 'Use user messages from recent saved chats'],
      ['files', '使用「Star Intelligence」文件夹中的短文本资料', 'Use short text files in the Star Intelligence folder'],
      ['email', '向已授权邮箱发送重要通知', 'Email important updates to the authorized address']
    ];
    panel.append(node('p', localize(
      '授权的个人资料会发给所选 OpenAI 模型进行判断。公开检索单独运行。启用时使用当前选中的 API 密钥。',
      'The selected OpenAI model receives the personal sources you authorize for review. Public research runs separately. Enabling uses the currently selected API key.'
    ), 'intelligence-muted'));
    const options = new Map();
    for (const [key, zh, en] of fields) {
      const label = node('label');
      const checkbox = node('input');
      checkbox.type = 'checkbox';
      checkbox.checked = settings[key];
      options.set(key, checkbox);
      label.append(checkbox, document.createTextNode(localize(zh, en)));
      box.append(label);
    }
    const save = node('button', localize('保存设置', 'Save settings'));
    save.type = 'submit';
    controls(box).append(save);
    box.addEventListener('submit', async event => {
      event.preventDefault();
      save.disabled = true;
      const data = Object.fromEntries([...options].map(([key, input]) => [key, input.checked]));
      await mutate('settings', {...data, revision: settings.revision});
      save.disabled = false;
    });
    panel.append(box);
  }
  function drawDetails(seen) {
    const assessment = state.assessment;
    if (assessment && Object.keys(assessment).length) {
      const reasoning = section('reasoning', localize('个人与世界认知', 'Personal and world context'), seen);
      reasoning.append(node('p', assessment.reason), node('p', localize('信心', 'Confidence') + ' · ' + stateText(assessment.confidence), 'intelligence-muted'));
      for (const scope of ['personal', 'world']) {
        const signal = (assessment.observations || []).filter(item => item.scope === scope);
        if (!signal.length) continue;
        reasoning.append(node('h4', scope === 'personal' ? localize('个人', 'Personal') : localize('外部环境', 'External environment')));
        const list = node('ul');
        for (const item of signal) {
          const line = node('li', stateText(item.kind) + ' · ' + item.text);
          sources(line, item.evidence);
          list.append(line);
        }
        reasoning.append(list);
      }
      const strategy = section('strategy', localize('取舍、反馈与策略', 'Trade-offs, feedback and strategy'), seen);
      for (const option of assessment.candidates || []) {
        field(strategy, option.path, [option.value, option.tradeoff].filter(Boolean).join(' '));
      }
      if (assessment.reportedOutcome?.kind) field(strategy, localize('用户报告的结果', 'Reported outcome'), stateText(assessment.reportedOutcome.kind));
      field(strategy, localize('复盘所得', 'What the system learned'), assessment.learning);
      for (const prior of state.policies || []) {
        if (prior.id === assessment.policyVersion) continue;
        const row = node('div');
        row.append(node('p', time(prior.at) + ' · ' + (prior.learning || localize('初始策略', 'Initial strategy'))));
        controls(row).append(button(localize('恢复此策略', 'Restore this strategy'), async () => {
          if (await mutate('restorePolicy', {policyId: prior.id, revision: state.settings.revision})) {
            message.textContent = localize('已恢复策略，后台将重新复盘。', 'Strategy restored. A background review will follow.');
          }
        }));
        strategy.append(row);
      }
    }
    const records = section('records', localize('近期决策、反馈与来源', 'Recent decisions, feedback and sources'), seen);
    for (const item of state.history || []) {
      records.append(node('p', time(item.at) + ' · ' + [stateText(item.assessment?.verdict), item.title].filter(Boolean).join(' · ')));
    }
    for (const item of state.feedback || []) records.append(node('p', time(item.at) + ' · ' + item.text));
    sources(records, state.evidence);
    const runtime = section('runtime', localize('运行、费用与邮件', 'Runtime, cost and email'), seen);
    const live = state.runtime;
    const online = live.heartbeat && state.at - live.heartbeat < 12 * 60 * 1000;
    runtime.append(node('p', stateText(live.status) + (live.error ? ' · ' + stateText(live.error) : '')));
    runtime.append(node('p', localize('后台心跳', 'Worker heartbeat') + ' · ' + time(live.heartbeat)
      + (live.heartbeat ? online ? '' : localize(' · 已过期', ' · Stale') : ''), 'intelligence-muted'));
    runtime.append(node('p', localize('上次复盘 / 下次计划', 'Last review / Next scheduled') + ' · ' + time(live.lastReview) + ' / ' + time(live.due)));
    runtime.append(node('p', localize('公开资料 / 联网检索', 'Public profile / Web research') + ' · '
      + stateText(live.profileState) + ' ' + time(live.profileAt) + ' / ' + stateText(live.searchState) + ' ' + time(live.searchAt)));
    if (!live.modelAvailable) runtime.append(node('p', localize('请在账户中选择 OpenAI 密钥并重新保存授权。', 'Select an OpenAI key and save permissions again.')));
    runtime.append(node('p', localize('今日估算 / 已占用预算 / 上限', 'Today’s estimate / Budget held or used / Limit')
      + ' · ' + [live.estimatedMicros, live.usedMicros, live.budgetMicros].map(funds).join(' / ')));
    runtime.append(node('p', localize('今日模型调用 / 检索', 'Today’s model calls / Searches') + ' · ' + live.calls + ' / ' + live.searchCalls));
    if (!live.mailConfigured) runtime.append(node('p', localize('邮件服务尚未配置。', 'Email delivery credentials are pending.')));
    for (const mail of state.mail || []) runtime.append(node('p', time(mail.firstAttempt || mail.created) + ' · ' + stateText(mail.state)));
    drawSettings(seen);
  }
  function render() {
    title.textContent = localize('现在最值得做什么？', 'What’s worth doing now?');
    byId('closeIntelligence').setAttribute('aria-label', localize('关闭', 'Close'));
    form.querySelector('label').textContent = localize('反馈', 'Feedback');
    feedback.placeholder = localize('说说你的进展、限制或需要更正的地方', 'Share progress, constraints or a correction');
    submit.textContent = localize('保存反馈', 'Save feedback');
    const seen = new Set([...detail.querySelectorAll('details[open]')].map(value => value.dataset.section));
    main.replaceChildren();
    detail.replaceChildren();
    form.hidden = state?.settings.revision <= 0 || !state;
    if (state) {
      drawAction(state.action);
      drawDetails(seen);
    }
    controls(main).append(button(localize('刷新', 'Refresh'), () => { message.textContent = ''; refresh(); }));
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const text = feedback.value.trim();
    if (!text || mutating) return;
    const actionId = state?.action?.id || '';
    if (!pending || pending.text !== text || pending.actionId !== actionId) {
      pending = {text, actionId, operationId: crypto.randomUUID()};
    }
    submit.disabled = true;
    const saved = await mutate('feedback', pending);
    submit.disabled = false;
    if (saved) {
      pending = null;
      feedback.value = '';
      message.textContent = localize('反馈已保存。后台会重新评估。', 'Feedback saved. The background worker will review it.');
    }
  });
  async function accountChanged() {
    accountEpoch++;
    state = null;
    pending = null;
    reading = null;
    mutating = false;
    feedback.value = '';
    submit.disabled = false;
    message.textContent = '';
    main.replaceChildren();
    detail.replaceChildren();
    if (dialog.open) dialog.close();
    for (const trigger of launches) trigger.hidden = !wanted || Boolean(accountEmail);
    if (!accountEmail) return;
    try {
      await request('available');
      for (const trigger of launches) trigger.hidden = false;
      if (wanted) { wanted = false; open(); }
    } catch {}
  }
  for (const trigger of launches) trigger.addEventListener('click', open);
  byId('closeIntelligence').addEventListener('click', () => dialog.close());
  document.addEventListener('starhub:account-changed', accountChanged);
  document.addEventListener('starhub:languagechanged', () => { if (dialog.open) render(); });
  setInterval(() => {
    if (dialog.open && !document.hidden && !feedback.value && (!state || !state.action?.id)
        && !detail.querySelector('details[open]')) refresh();
  }, 15000);
  accountChanged();
})();
