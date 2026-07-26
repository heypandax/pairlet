(function () {
  'use strict';

  const root = document.documentElement;
  const home = document.querySelector('[data-help-home]');
  const chatView = document.querySelector('[data-chat-view]');
  const homeForm = document.getElementById('home-question-form');
  const homeInput = document.getElementById('home-question');
  const homeStatus = document.getElementById('home-status');
  const form = document.getElementById('support-chat-form');
  const input = document.getElementById('support-message');
  const send = document.getElementById('support-send');
  const log = document.getElementById('chat-log');
  const welcome = document.getElementById('chat-welcome');
  const directNote = document.getElementById('direct-entry-note');
  const related = document.getElementById('related-guides');
  const back = document.getElementById('back-to-help');
  const chatStatus = document.getElementById('chat-status');
  if (!home || !chatView || !homeForm || !homeInput || !form || !input || !send || !log || !related || !back) return;

  const localPreview = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost';
  const apiBase = window.location.hostname === 'pocket.ark-nexus.cc' || localPreview
    ? '/support-api/chat'
    : 'https://pocket.ark-nexus.cc/support-api/chat';
  const params = new URLSearchParams(window.location.search);
  const directEntry = params.get('mode') === 'chat' && params.get('source') === 'app';

  const copy = {
    en: {
      homePlaceholder: 'For example: How do I see which files changed?',
      chatPlaceholder: 'Tell me what you want to do, or where you’re stuck…',
      required: 'Enter a question first.',
      you: 'YOU',
      support: 'SUPPORT',
      waiting: 'Searching the verified manual · complex questions may take about 1 minute',
      rate_limitedTitle: 'Public support is rate-limited',
      rate_limited: 'Several questions were sent recently. Your question is preserved; retry in a moment or use the closest verified guide.',
      busyTitle: 'Smart support is busy',
      busy: 'The service is available but currently at capacity. This is not a problem with your network.',
      timeoutTitle: 'The answer timed out',
      timeout: 'The service did not answer in the expected time. Retry the same question, or make it a little smaller.',
      unavailableTitle: 'Smart support is temporarily unavailable',
      unavailable: 'Your question is preserved. Retry it or continue with the closest verified manual guide.',
      question: 'Your question',
      matched: 'Closest verified guides',
      retry: 'Retry the same question',
      report: 'Report a reproducible issue',
    },
    zh: {
      homePlaceholder: '例如：我想看看这次会话改了哪些文件',
      chatPlaceholder: '说出你想完成的事，或哪里卡住了…',
      required: '请先输入你的问题。',
      you: '你',
      support: '客服',
      waiting: '正在检索已核验手册 · 复杂问题可能需要约 1 分钟',
      rate_limitedTitle: '公开客服请求已限流',
      rate_limited: '最近发送的问题较多。你的问题已保留，可以稍后重试，或先看最贴近的已核验指南。',
      busyTitle: '智能客服当前较忙',
      busy: '服务仍可用，但当前已满载。这不是你的网络问题。',
      timeoutTitle: '这次回答超时了',
      timeout: '服务没能在预期时间内返回。可以重试同一个问题，或把问题拆小一点。',
      unavailableTitle: '智能客服暂时不可用',
      unavailable: '你的问题已保留。可以直接重试，或继续查看最贴近的已核验手册指南。',
      question: '你的问题',
      matched: '最贴近的已核验指南',
      retry: '重试同一个问题',
      report: '报告可复现问题',
    },
  };

  const guides = [
    {
      id: 'changed-files', query: 'changed files',
      en: 'See files changed in this session', zh: '查看本次会话改过的文件',
      words: ['change', 'changed', 'file', 'diff', 'write', '改', '文件', '改动', '差异'],
    },
    {
      id: 'terminal', query: 'take over terminal',
      en: 'Take over a terminal session', zh: '无分叉接管终端会话',
      words: ['terminal', 'shell', 'fork', 'resume', 'take over', '终端', '接管', '分叉', '会话'],
    },
    {
      id: 'approvals', query: 'approve',
      en: 'Review and answer tool requests', zh: '核对并处理工具请求',
      words: ['approve', 'deny', 'permission', 'tool request', 'request', '批准', '拒绝', '审批', '权限', '工具请求'],
    },
    {
      id: 'schedule', query: 'schedule prompt',
      en: 'Schedule a prompt for later', zh: '预约稍后发送提示词',
      words: ['schedule', 'later', 'repeat', 'prompt', '预约', '定时', '稍后', '重复', '提示词'],
    },
    {
      id: 'agent-model', query: 'choose agent model',
      en: 'Choose an agent and model', zh: '选择 Agent 与模型',
      words: ['agent', 'model', 'reasoning', 'claude', 'codex', '模型', '推理', '智能体'],
    },
    {
      id: 'offline', query: 'offline',
      en: 'Troubleshoot an offline computer', zh: '排查电脑没有上线',
      words: ['offline', 'connect', 'pair', 'daemon', '离线', '连接', '配对', '上线'],
    },
  ];

  function lang() {
    return root.getAttribute('data-lang') === 'zh' ? 'zh' : 'en';
  }

  function guideUrl(guide) {
    return '../manual/?q=' + encodeURIComponent(guide.query);
  }

  function matchGuides(question, count) {
    const value = question.toLowerCase();
    const ranked = guides.map(function (guide, index) {
      const score = guide.words.reduce(function (sum, word) {
        return sum + (value.includes(word.toLowerCase()) ? (word.includes(' ') ? 3 : 1) : 0);
      }, 0);
      return { guide: guide, score: score, index: index };
    }).sort(function (left, right) {
      return right.score - left.score || left.index - right.index;
    });
    const matches = ranked.filter(function (item) { return item.score > 0; });
    const result = (matches.length ? matches : ranked).slice(0, count || 3).map(function (item) { return item.guide; });
    if (result.length < (count || 3)) {
      ranked.forEach(function (item) {
        if (result.length < (count || 3) && !result.includes(item.guide)) result.push(item.guide);
      });
    }
    return result;
  }

  function renderGuideLink(guide, className) {
    const link = document.createElement('a');
    link.className = className || 'related-guide';
    link.href = guideUrl(guide);
    link.target = '_blank';
    link.rel = 'noopener';
    const title = document.createElement('span');
    title.textContent = guide[lang()];
    const arrow = document.createElement('span');
    arrow.setAttribute('aria-hidden', 'true');
    arrow.textContent = '↗';
    link.append(title, arrow);
    return link;
  }

  let currentQuestion = '';
  function renderRelated(question) {
    related.replaceChildren();
    matchGuides(question || '', 3).forEach(function (guide) {
      related.appendChild(renderGuideLink(guide));
    });
  }

  function updateLanguage() {
    const text = copy[lang()];
    homeInput.placeholder = text.homePlaceholder;
    input.placeholder = text.chatPlaceholder;
    renderRelated(currentQuestion);
  }

  function makeSessionId() {
    try {
      const saved = window.sessionStorage.getItem('ccp-support-session');
      if (saved && /^[A-Za-z0-9_-]{16,64}$/.test(saved)) return saved;
      const raw = window.crypto && window.crypto.randomUUID
        ? window.crypto.randomUUID()
        : String(Date.now()) + Math.random().toString(36);
      const created = raw.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 64);
      window.sessionStorage.setItem('ccp-support-session', created);
      return created;
    } catch (error) {
      return ('session_' + Date.now() + '_' + Math.random().toString(36).slice(2)).slice(0, 64);
    }
  }
  const sessionId = makeSessionId();

  function showHome() {
    home.hidden = false;
    chatView.hidden = true;
    document.body.classList.remove('support-chat-active', 'support-direct');
  }

  function showChat(options) {
    const settings = options || {};
    home.hidden = true;
    chatView.hidden = false;
    document.body.classList.add('support-chat-active');
    document.body.classList.toggle('support-direct', Boolean(settings.direct));
    directNote.hidden = !settings.direct;
    if (settings.pushHistory) {
      const next = window.location.pathname + window.location.search + '#chat';
      window.history.pushState({ ccpSupportView: 'chat' }, '', next);
    }
    // App-direct mode deliberately does not focus: no surprise keyboard or automatic submit.
  }

  function setBusy(busy) {
    input.readOnly = busy;
    send.disabled = busy;
    form.setAttribute('aria-busy', busy ? 'true' : 'false');
  }

  function hideWelcome() {
    if (welcome) welcome.hidden = true;
  }

  function appendLinkedText(element, value) {
    const urlPattern = /https:\/\/[^\s<>\])}]+/g;
    let cursor = 0;
    let match;
    while ((match = urlPattern.exec(value)) !== null) {
      element.appendChild(document.createTextNode(value.slice(cursor, match.index)));
      const link = document.createElement('a');
      link.href = match[0];
      link.textContent = match[0];
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      element.appendChild(link);
      cursor = match.index + match[0].length;
    }
    element.appendChild(document.createTextNode(value.slice(cursor)));
  }

  function addMessage(role, value) {
    hideWelcome();
    const item = document.createElement('div');
    item.className = 'chat-message ' + role;
    const label = document.createElement('span');
    label.className = 'chat-role mono';
    label.textContent = role === 'user' ? copy[lang()].you : copy[lang()].support;
    const body = document.createElement('p');
    appendLinkedText(body, value);
    item.append(label, body);
    log.appendChild(item);
    log.scrollTop = log.scrollHeight;
    return item;
  }

  function addWaiting() {
    hideWelcome();
    const item = document.createElement('div');
    item.className = 'chat-message assistant waiting-message';
    item.setAttribute('role', 'status');
    const spinner = document.createElement('span');
    spinner.className = 'waiting-spinner';
    spinner.setAttribute('aria-hidden', 'true');
    const body = document.createElement('span');
    body.textContent = copy[lang()].waiting;
    item.append(spinner, body);
    log.appendChild(item);
    log.scrollTop = log.scrollHeight;
    return item;
  }

  function failureCopy(code) {
    const text = copy[lang()];
    if (code === 'rate_limited') return { title: text.rate_limitedTitle, body: text.rate_limited };
    if (code === 'busy') return { title: text.busyTitle, body: text.busy };
    if (code === 'timeout') return { title: text.timeoutTitle, body: text.timeout };
    return { title: text.unavailableTitle, body: text.unavailable };
  }

  function addFailure(code, question) {
    const text = copy[lang()];
    const message = failureCopy(code);
    const item = document.createElement('div');
    item.className = 'chat-message assistant failure-message';

    const head = document.createElement('div');
    head.className = 'failure-head';
    const icon = document.createElement('span');
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = code === 'timeout' ? '↻' : '!';
    const title = document.createElement('strong');
    title.textContent = message.title;
    head.append(icon, title);

    const body = document.createElement('p');
    body.textContent = message.body;
    const kept = document.createElement('div');
    kept.className = 'failure-question';
    const keptLabel = document.createElement('b');
    keptLabel.textContent = text.question;
    kept.append(keptLabel, document.createTextNode(question));

    const matches = document.createElement('div');
    matches.className = 'failure-guides';
    const matchesLabel = document.createElement('span');
    matchesLabel.textContent = text.matched;
    matches.appendChild(matchesLabel);
    matchGuides(question, 2).forEach(function (guide) {
      matches.appendChild(renderGuideLink(guide, 'failure-guide'));
    });

    const actions = document.createElement('div');
    actions.className = 'failure-actions';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'retry-button';
    retry.textContent = text.retry;
    retry.addEventListener('click', function () {
      item.remove();
      requestAnswer(question, false);
    });
    const report = document.createElement('a');
    report.href = 'https://github.com/heypandax/cc-pocket/issues/new/choose';
    report.target = '_blank';
    report.rel = 'noopener';
    report.textContent = text.report;
    actions.append(retry, report);
    item.append(head, body, kept, matches, actions);
    log.appendChild(item);
    log.scrollTop = log.scrollHeight;
    return item;
  }

  function normalizedError(response, data) {
    if (data && data.error === 'rate_limited') return 'rate_limited';
    if (data && data.error === 'busy') return 'busy';
    if (data && data.error === 'timeout') return 'timeout';
    if (response && response.status === 429) return 'rate_limited';
    if (response && response.status === 503) return 'busy';
    if (response && response.status === 504) return 'timeout';
    return 'unavailable';
  }

  async function requestAnswer(question, addUser) {
    currentQuestion = question;
    input.value = question;
    renderRelated(question);
    if (addUser) addMessage('user', question);
    const pending = addWaiting();
    setBusy(true);
    chatStatus.textContent = copy[lang()].waiting;
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timeout = controller ? window.setTimeout(function () { controller.abort(); }, 135000) : 0;
    try {
      const response = await fetch(apiBase, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: question, sessionId: sessionId }),
        signal: controller ? controller.signal : undefined,
      });
      const data = await response.json().catch(function () { return {}; });
      pending.remove();
      if (!response.ok || typeof data.answer !== 'string') {
        addFailure(normalizedError(response, data), question);
        input.value = question;
      } else {
        addMessage('assistant', data.answer);
        input.value = '';
      }
    } catch (error) {
      pending.remove();
      addFailure(error && error.name === 'AbortError' ? 'timeout' : 'unavailable', question);
      input.value = question;
    } finally {
      if (timeout) window.clearTimeout(timeout);
      setBusy(false);
      chatStatus.textContent = '';
    }
  }

  function submitChat(question) {
    const value = (question == null ? input.value : question).trim();
    if (send.disabled) return;
    if (!value) {
      input.setAttribute('aria-invalid', 'true');
      chatStatus.textContent = copy[lang()].required;
      input.focus();
      return;
    }
    input.removeAttribute('aria-invalid');
    requestAnswer(value, true);
  }

  function startFromHome(question) {
    const value = (question == null ? homeInput.value : question).trim();
    if (!value) {
      homeInput.setAttribute('aria-invalid', 'true');
      homeStatus.textContent = copy[lang()].required;
      homeInput.focus();
      return;
    }
    homeInput.removeAttribute('aria-invalid');
    homeStatus.textContent = '';
    showChat({ pushHistory: true, direct: false });
    submitChat(value);
  }

  homeForm.addEventListener('submit', function (event) {
    event.preventDefault();
    startFromHome();
  });
  form.addEventListener('submit', function (event) {
    event.preventDefault();
    submitChat();
  });
  [homeInput, input].forEach(function (field) {
    field.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        if (field === homeInput) startFromHome(); else submitChat();
      }
    });
    field.addEventListener('input', function () {
      if (field.value.trim()) {
        field.removeAttribute('aria-invalid');
        if (field === homeInput) homeStatus.textContent = ''; else chatStatus.textContent = '';
      }
    });
  });

  document.querySelectorAll('[data-question-en]').forEach(function (button) {
    button.addEventListener('click', function () {
      const question = button.getAttribute('data-question-' + lang());
      if (button.closest('[data-help-home]')) startFromHome(question);
      else submitChat(question);
    });
  });

  back.addEventListener('click', function () {
    if (window.location.hash === '#chat' && window.history.state && window.history.state.ccpSupportView === 'chat') {
      window.history.back();
    } else {
      window.location.href = './';
    }
  });
  window.addEventListener('popstate', function () {
    if (window.location.hash === '#chat') showChat({ direct: false }); else showHome();
  });
  document.querySelectorAll('[data-setlang]').forEach(function (button) {
    button.addEventListener('click', updateLanguage);
  });

  renderRelated('');
  updateLanguage();
  if (directEntry) {
    showChat({ direct: true });
  } else if (window.location.hash === '#chat') {
    showChat({ direct: false });
  }

}());
