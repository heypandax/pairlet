(function () {
  const buttons = [document.getElementById('copy-ai')].filter(Boolean);
  const status = document.getElementById('copy-status');
  const prompts = {
    en: 'Use this public, maintained CC Pocket manual to answer my question. Prefer the verified steps in the manual, state any limits, and do not ask me to share keys, tokens, pairing codes, or private repository content: https://heypandax.github.io/cc-pocket/manual/llms-full.txt',
    zh: '请检索这份公开且持续维护的 CC Pocket 用户手册来回答我的问题。优先采用手册中已核验的步骤，说明适用限制，不要让我提供密钥、令牌、配对码或私有仓库内容：https://heypandax.github.io/cc-pocket/manual/llms-full.txt',
  };

  buttons.forEach(function (button) {
    button.addEventListener('click', async function () {
      const lang = document.documentElement.getAttribute('data-lang') === 'zh' ? 'zh' : 'en';
      try {
        await navigator.clipboard.writeText(prompts[lang]);
        status.textContent = lang === 'zh' ? '已复制，可直接发给你的 AI 助手。' : 'Copied. Paste it into your AI assistant.';
      } catch (error) {
        status.textContent = lang === 'zh' ? '复制失败，请打开用户手册后复制链接。' : 'Could not copy. Open the manual and copy its link instead.';
      }
      window.setTimeout(function () { status.textContent = ''; }, 3200);
    });
  });

  const form = document.getElementById('support-chat-form');
  const input = document.getElementById('support-message');
  const send = document.getElementById('support-send');
  const log = document.getElementById('chat-log');
  if (!form || !input || !send || !log) return;

  const apiBase = window.location.hostname === 'pocket.ark-nexus.cc'
    ? '/support-api/chat'
    : 'https://pocket.ark-nexus.cc/support-api/chat';
  const translations = {
    en: {
      placeholder: 'Ask about setup, pairing, offline status…',
      you: 'YOU',
      support: 'SUPPORT',
      thinking: 'Searching the CC Pocket manual',
      rate_limited: 'You have sent several questions recently. Please wait a moment and try again.',
      busy: 'Support is busy right now. Please try again in a moment.',
      timeout: 'That answer took too long. Please shorten the question and try again.',
      defaultError: 'Smart support is temporarily unavailable. You can still use the public manual below.',
    },
    zh: {
      placeholder: '询问安装、配对、离线状态等问题…',
      you: '你',
      support: '客服',
      thinking: '正在检索 CC Pocket 用户手册',
      rate_limited: '你最近发送的问题较多，请稍等片刻后再试。',
      busy: '智能客服当前较忙，请稍后再试。',
      timeout: '回答超时，请缩短问题后重试。',
      defaultError: '智能客服暂时不可用，你仍可使用下方的公开用户手册。',
    },
  };

  function lang() {
    return document.documentElement.getAttribute('data-lang') === 'zh' ? 'zh' : 'en';
  }

  function makeSessionId() {
    try {
      const saved = window.sessionStorage.getItem('ccp-support-session');
      if (saved && /^[A-Za-z0-9_-]{16,64}$/.test(saved)) return saved;
      const created = (window.crypto.randomUUID ? window.crypto.randomUUID() : String(Date.now()) + Math.random().toString(36)).replace(/[^A-Za-z0-9_-]/g, '_');
      window.sessionStorage.setItem('ccp-support-session', created);
      return created;
    } catch (error) {
      return ('session_' + Date.now() + '_' + Math.random().toString(36).slice(2)).slice(0, 64);
    }
  }

  const sessionId = makeSessionId();

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

  function addMessage(role, value, kind) {
    const item = document.createElement('div');
    item.className = 'chat-message ' + role + (kind ? ' ' + kind : '');
    const label = document.createElement('span');
    label.className = 'chat-role mono';
    label.textContent = role === 'user' ? translations[lang()].you : translations[lang()].support;
    const body = document.createElement('p');
    appendLinkedText(body, value);
    item.appendChild(label);
    item.appendChild(body);
    log.appendChild(item);
    log.scrollTop = log.scrollHeight;
    return item;
  }

  function setBusy(busy) {
    input.disabled = busy;
    send.disabled = busy;
    send.setAttribute('aria-busy', busy ? 'true' : 'false');
  }

  function errorText(code) {
    const copy = translations[lang()];
    if (code === 'rate_limited') return copy.rate_limited;
    if (code === 'busy') return copy.busy;
    if (code === 'timeout') return copy.timeout;
    return copy.defaultError;
  }

  async function submitQuestion() {
    const message = input.value.trim();
    if (!message || send.disabled) return;
    addMessage('user', message);
    input.value = '';
    setBusy(true);
    const pending = addMessage('bot', translations[lang()].thinking, 'pending');
    try {
      const response = await fetch(apiBase, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message, sessionId: sessionId }),
      });
      const data = await response.json().catch(function () { return {}; });
      pending.remove();
      if (!response.ok || typeof data.answer !== 'string') {
        addMessage('bot', errorText(data.error), 'error');
      } else {
        addMessage('bot', data.answer);
      }
    } catch (error) {
      pending.remove();
      addMessage('bot', translations[lang()].defaultError, 'error');
    } finally {
      setBusy(false);
      input.focus();
    }
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    submitQuestion();
  });
  input.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submitQuestion();
    }
  });
  document.querySelectorAll('[data-setlang]').forEach(function (button) {
    button.addEventListener('click', function () {
      input.placeholder = translations[lang()].placeholder;
    });
  });
  input.placeholder = translations[lang()].placeholder;
}());
