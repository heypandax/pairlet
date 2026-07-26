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
  const directNoteText = document.getElementById('direct-entry-note-text');
  const contextRemove = document.getElementById('context-remove');
  const related = document.getElementById('related-guides');
  const back = document.getElementById('back-to-help');
  const chatStatus = document.getElementById('chat-status');
  const securityCheck = document.getElementById('support-security');
  const turnstileWidget = document.getElementById('turnstile-widget');
  if (!home || !chatView || !homeForm || !homeInput || !form || !input || !send || !log || !directNote || !directNoteText || !contextRemove || !related || !back || !securityCheck || !turnstileWidget) return;

  const localPreview = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost';
  const apiRoot = window.location.hostname === 'pocket.ark-nexus.cc' || localPreview
    ? '/support-api'
    : 'https://pocket.ark-nexus.cc/support-api';
  const chatApi = apiRoot + '/chat';
  const configApi = apiRoot + '/config';
  const params = new URLSearchParams(window.location.search);
  const directEntry = params.get('mode') === 'chat' && params.get('source') === 'app';
  const allowedControls = ['composer', 'quick_actions', 'changed_files', 'terminal', 'model_picker'];

  function safeContextToken(value, pattern, max) {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim().slice(0, max);
    return pattern.test(trimmed) ? trimmed : null;
  }

  function sanitizeAppContext(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value) || value.schemaVersion !== 1) return null;
    if (!['chat', 'projects', 'sessions', 'settings'].includes(value.screen)) return null;
    const context = {
      schemaVersion: 1,
      screen: value.screen,
      platform: safeContextToken(value.platform, /^[A-Za-z0-9 ._()·/-]+$/, 64),
      appVersion: safeContextToken(value.appVersion, /^[A-Za-z0-9._+-]+$/, 32),
      agent: ['claude', 'codex', 'opencode'].includes(value.agent) ? value.agent : null,
      model: safeContextToken(value.model, /^[A-Za-z0-9._:+/@-]+$/, 96),
      state: ['idle', 'generating', 'observing', 'disconnected'].includes(value.state) ? value.state : null,
      controls: Array.isArray(value.controls)
        ? value.controls.filter(function (item, index, items) {
          return allowedControls.includes(item) && items.indexOf(item) === index;
        }).slice(0, allowedControls.length)
        : [],
    };
    return context;
  }

  function readAppContext() {
    const match = window.location.hash.match(/^#ctx=([A-Za-z0-9_-]{1,4096})$/);
    if (!match) return null;
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
    try {
      const padded = match[1].replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - match[1].length % 4) % 4);
      const bytes = Uint8Array.from(window.atob(padded), function (character) { return character.charCodeAt(0); });
      return sanitizeAppContext(JSON.parse(new TextDecoder().decode(bytes)));
    } catch (error) {
      return null;
    }
  }

  const appContext = readAppContext();
  let attachAppContext = Boolean(appContext);

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
      budgetTitle: 'Today’s public support budget is used up',
      budget: 'No more AI calls will start today. Your question is preserved; use the closest verified guide or try again tomorrow.',
      busyTitle: 'Smart support is busy',
      busy: 'The service is available but currently at capacity. This is not a problem with your network.',
      timeoutTitle: 'The answer timed out',
      timeout: 'The service did not answer in the expected time. Retry the same question, or make it a little smaller.',
      unavailableTitle: 'Smart support is temporarily unavailable',
      unavailable: 'Your question is preserved. Retry it or continue with the closest verified manual guide.',
      verificationLoading: 'Preparing a quick security check…',
      verificationFailed: 'That check expired or could not be verified. Please try it once more.',
      verificationUnavailable: 'The security check is temporarily unavailable. Your question is preserved.',
      question: 'Your question',
      matched: 'Closest verified guides',
      retry: 'Retry the same question',
      report: 'Report a reproducible issue',
      appContextReady: 'Session environment ready: %s. It will be sent with your question; no conversation, path, or logs.',
      appDirect: 'Opened from the App. You can ask right away.',
      appContextRemoved: 'Opened from the App. Session environment will not be attached.',
      removeContext: 'Don’t attach',
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
      budgetTitle: '今天的公开客服额度已用完',
      budget: '今天不会再启动新的 AI 请求。你的问题已保留，可以先看最贴近的指南，或明天再试。',
      busyTitle: '智能客服当前较忙',
      busy: '服务仍可用，但当前已满载。这不是你的网络问题。',
      timeoutTitle: '这次回答超时了',
      timeout: '服务没能在预期时间内返回。可以重试同一个问题，或把问题拆小一点。',
      unavailableTitle: '智能客服暂时不可用',
      unavailable: '你的问题已保留。可以直接重试，或继续查看最贴近的已核验手册指南。',
      verificationLoading: '正在准备一次安全验证…',
      verificationFailed: '验证已过期或未通过，请再完成一次。',
      verificationUnavailable: '安全验证暂时不可用，你的问题已保留。',
      question: '你的问题',
      matched: '最贴近的已核验指南',
      retry: '重试同一个问题',
      report: '报告可复现问题',
      appContextReady: '已准备会话环境：%s。提问时一并发送；不含对话内容、路径或日志。',
      appDirect: '从 App 打开，可以直接提问。',
      appContextRemoved: '从 App 打开；本次不会附带会话环境。',
      removeContext: '不附带',
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
    renderDirectEntryNote(document.body.classList.contains('support-direct'));
  }

  function contextSummary(context) {
    const agentNames = { claude: 'Claude Code', codex: 'Codex', opencode: 'OpenCode' };
    const values = [context.platform, context.agent ? agentNames[context.agent] : null, context.model].filter(Boolean);
    return values.join(' · ') || (lang() === 'zh' ? '当前会话页' : 'current session screen');
  }

  function renderDirectEntryNote(show) {
    directNote.hidden = !show;
    if (!show) return;
    const text = copy[lang()];
    if (appContext && attachAppContext) {
      directNoteText.textContent = text.appContextReady.replace('%s', contextSummary(appContext));
      contextRemove.textContent = text.removeContext;
      contextRemove.hidden = false;
    } else if (appContext) {
      directNoteText.textContent = text.appContextRemoved;
      contextRemove.hidden = true;
    } else {
      directNoteText.textContent = text.appDirect;
      contextRemove.hidden = true;
    }
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
  let supportPass = '';
  let turnstileToken = '';
  let securityConfig = null;
  let securityConfigPromise = null;
  let turnstileScriptPromise = null;
  let turnstileWidgetId = null;
  let pendingVerification = null;

  function loadSecurityConfig(force) {
    if (force) securityConfigPromise = null;
    if (securityConfigPromise) return securityConfigPromise;
    securityConfigPromise = fetch(configApi, {
      method: 'GET',
      headers: { 'Accept': 'application/json' },
      cache: 'no-store',
    }).then(function (response) {
      if (!response.ok) throw new Error('support config unavailable');
      return response.json();
    }).then(function (data) {
      const candidate = data && data.turnstile;
      if (candidate && candidate.enabled === true && typeof candidate.siteKey === 'string' && typeof candidate.action === 'string') {
        securityConfig = { enabled: true, siteKey: candidate.siteKey, action: candidate.action };
      } else {
        securityConfig = { enabled: false };
      }
      return securityConfig;
    }).catch(function (error) {
      securityConfigPromise = null;
      throw error;
    });
    return securityConfigPromise;
  }

  function loadTurnstileScript() {
    if (window.turnstile) return Promise.resolve(window.turnstile);
    if (turnstileScriptPromise) return turnstileScriptPromise;
    turnstileScriptPromise = new Promise(function (resolve, reject) {
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.async = true;
      script.defer = true;
      script.addEventListener('load', function () {
        if (window.turnstile) resolve(window.turnstile); else reject(new Error('Turnstile missing'));
      }, { once: true });
      script.addEventListener('error', function () { reject(new Error('Turnstile blocked')); }, { once: true });
      document.head.appendChild(script);
    }).catch(function (error) {
      turnstileScriptPromise = null;
      throw error;
    });
    return turnstileScriptPromise;
  }

  async function beginVerification(question, addUser) {
    if (addUser) addMessage('user', question);
    pendingVerification = { question: question };
    input.value = question;
    setBusy(true);
    chatStatus.textContent = copy[lang()].verificationLoading;
    try {
      const config = await loadSecurityConfig(true);
      if (!config.enabled) throw new Error('Turnstile is not configured');
      const turnstile = await loadTurnstileScript();
      securityCheck.hidden = false;
      if (turnstileWidgetId == null) {
        turnstileWidgetId = turnstile.render(turnstileWidget, {
          sitekey: config.siteKey,
          action: config.action,
          size: 'flexible',
          appearance: 'interaction-only',
          callback: function (token) {
            const pending = pendingVerification;
            if (!pending || typeof token !== 'string') return;
            pendingVerification = null;
            turnstileToken = token;
            securityCheck.hidden = true;
            chatStatus.textContent = '';
            requestAnswer(pending.question, false);
          },
          'expired-callback': function () {
            turnstileToken = '';
            chatStatus.textContent = copy[lang()].verificationFailed;
            turnstile.reset(turnstileWidgetId);
          },
          'error-callback': function () {
            turnstileToken = '';
            chatStatus.textContent = copy[lang()].verificationFailed;
            return true;
          },
        });
      } else {
        turnstile.reset(turnstileWidgetId);
      }
      chatStatus.textContent = '';
    } catch (error) {
      pendingVerification = null;
      securityCheck.hidden = true;
      setBusy(false);
      chatStatus.textContent = copy[lang()].verificationUnavailable;
      addFailure('verification_unavailable', question);
    }
  }

  function cancelPendingVerification() {
    pendingVerification = null;
    turnstileToken = '';
    securityCheck.hidden = true;
    if (window.turnstile && turnstileWidgetId != null) {
      window.turnstile.reset(turnstileWidgetId);
    }
  }

  function showHome() {
    cancelPendingVerification();
    setBusy(false);
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
    renderDirectEntryNote(Boolean(settings.direct));
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
      if (isTrustedSupportUrl(match[0])) {
        const link = document.createElement('a');
        link.href = match[0];
        link.textContent = match[0];
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        element.appendChild(link);
      } else {
        element.appendChild(document.createTextNode(match[0]));
      }
      cursor = match.index + match[0].length;
    }
    element.appendChild(document.createTextNode(value.slice(cursor)));
  }

  function isTrustedSupportUrl(value) {
    try {
      const parsed = new URL(value);
      if (parsed.protocol !== 'https:' || parsed.username || parsed.password || (parsed.port && parsed.port !== '443')) return false;
      if (parsed.hostname === 'heypandax.github.io') return parsed.pathname.startsWith('/cc-pocket/');
      if (parsed.hostname === 'github.com') return parsed.pathname === '/heypandax/cc-pocket' || parsed.pathname.startsWith('/heypandax/cc-pocket/');
      return false;
    } catch (error) {
      return false;
    }
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
    if (code === 'daily_budget_exhausted') return { title: text.budgetTitle, body: text.budget };
    if (code === 'verification_unavailable') return { title: text.unavailableTitle, body: text.verificationUnavailable };
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
    if (data && data.error === 'verification_rate_limited') return 'rate_limited';
    if (data && data.error === 'daily_budget_exhausted') return 'daily_budget_exhausted';
    if (data && data.error === 'verification_unavailable') return 'verification_unavailable';
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
    let verificationStarted = false;
    try {
      const requestBody = { message: question, sessionId: sessionId };
      if (appContext && attachAppContext) requestBody.context = appContext;
      if (supportPass) requestBody.supportPass = supportPass;
      if (!supportPass && turnstileToken) requestBody.turnstileToken = turnstileToken;
      const response = await fetch(chatApi, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal: controller ? controller.signal : undefined,
      });
      const data = await response.json().catch(function () { return {}; });
      turnstileToken = '';
      if (data && typeof data.supportPass === 'string') supportPass = data.supportPass;
      pending.remove();
      if (data && (data.error === 'human_verification_required' || data.error === 'human_verification_failed')) {
        supportPass = '';
        verificationStarted = true;
        await beginVerification(question, false);
        return;
      }
      if (!response.ok || typeof data.answer !== 'string') {
        addFailure(normalizedError(response, data), question);
        input.value = question;
      } else {
        addMessage('assistant', data.answer);
        input.value = '';
      }
    } catch (error) {
      turnstileToken = '';
      pending.remove();
      addFailure(error && error.name === 'AbortError' ? 'timeout' : 'unavailable', question);
      input.value = question;
    } finally {
      if (timeout) window.clearTimeout(timeout);
      if (!verificationStarted && !pendingVerification) {
        setBusy(false);
        chatStatus.textContent = '';
      }
    }
  }

  async function submitChat(question) {
    const value = (question == null ? input.value : question).trim();
    if (send.disabled) return;
    if (!value) {
      input.setAttribute('aria-invalid', 'true');
      chatStatus.textContent = copy[lang()].required;
      input.focus();
      return;
    }
    input.removeAttribute('aria-invalid');
    if (!supportPass) {
      setBusy(true);
      chatStatus.textContent = copy[lang()].verificationLoading;
      try {
        const config = await loadSecurityConfig(false);
        if (config.enabled) {
          await beginVerification(value, true);
          return;
        }
      } catch (error) {
        // The chat boundary remains fail-closed. It will return a verification
        // requirement if the local config fetch was transiently unavailable.
      }
      setBusy(false);
      chatStatus.textContent = '';
    }
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
  contextRemove.addEventListener('click', function () {
    attachAppContext = false;
    renderDirectEntryNote(true);
  });

  renderRelated('');
  updateLanguage();
  if (directEntry) {
    showChat({ direct: true });
  } else if (window.location.hash === '#chat') {
    showChat({ direct: false });
  }

}());
