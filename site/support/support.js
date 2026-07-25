(function () {
  const buttons = [document.getElementById('copy-ai')].filter(Boolean);
  const status = document.getElementById('copy-status');
  if (!buttons.length || !status) return;

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
}());
