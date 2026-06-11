// cc-pocket — zh-CN localization layer.
// Applies after React renders: replaces matching text nodes & placeholders.
// Technical content (paths, commands, code, branch names, mode ids) stays English.
(function(){
  const DICT = {
    // ── shared chrome ──
    'You': '你',
    'YOU': '你',

    // ── Permission sheet ──
    'Refactor auth module': '重构 auth 模块',
    'clean the build and re-run the protocol tests': '清掉构建产物，重新跑 protocol 测试',
    'I’ll wipe the stale build output and run a clean Gradle build before the test suite.': '我会先清除旧的构建输出，跑一次干净的 Gradle 构建，再执行测试套件。',
    'Claude needs permission': 'Claude 请求权限',
    'Run command': '运行命令',
    'auto-deny': '自动拒绝',
    'expand': '展开',
    'Remember for this session': '本次会话记住此选择',
    'Deny': '拒绝',
    'Allow': '允许',

    // ── Chat ──
    'where does the SSE stream get assembled?': 'SSE 流是在哪里组装的？',
    'It’s in': '在',
    'in the protocol module — partial frames buffer there until a blank line terminates the event.': '里（protocol 模块）— 不完整的帧会先缓冲，直到空行结束该事件。',
    'add a unit test for the stream parser': '给流解析器加一个单元测试',
    'Thought for 5s': '思考了 5 秒',
    'I’ll add a focused test for': '我会为',
    'that feeds a chunked SSE response and asserts the reassembled events. Two cases to start with:': '写一个针对性测试：喂入分块的 SSE 响应并断言重组后的事件。先覆盖两种情况：',
    'a happy path where one message arrives across several chunks, and': '正常路径：一条消息跨多个分块到达；',
    'a split-token case where a JSON delimiter straddles a chunk boundary.': '分裂情况：JSON 分隔符正好骑在分块边界上。',
    'Running the suite now to confirm the new test fails before I wire up the fix': '正在跑测试套件，先确认新测试在修复前会失败',

    // ── Pairing ──
    'Connect your computer': '连接你的电脑',
    'Pair this phone with the cc-pocket daemon on your computer.': '将这台手机与电脑上的 cc-pocket 守护进程配对。',
    'scanning…': '扫描中…',
    'or enter the pairing code': '或输入配对码',
    'Run': '在电脑上运行',
    'on your computer to get a code.': '获取配对码。',
    'Connect': '连接',

    // ── Sessions ──
    'Sessions': '会话',
    'New session': '新建会话',
    'Start Claude in ~/proj/app/cc-pocket': '在 ~/proj/app/cc-pocket 启动 Claude',
    'Recent': '最近',
    'active': '进行中',
    'Fix stream parser test': '修复流解析器测试',
    'the parser drops the last token on EOF': '解析器在 EOF 时丢掉最后一个 token',
    'Add relay websocket client': '添加 relay WebSocket 客户端',
    'scaffold the Ktor WS client with reconnect': '搭建带重连的 Ktor WS 客户端',
    'Wire up pairing flow': '接通配对流程',
    'generate a 6-digit pairing code on the daemon': '在守护进程生成 6 位配对码',
    '2h ago': '2 小时前',
    '5h ago': '5 小时前',
    'yesterday': '昨天',
    '2d ago': '2 天前',

    // ── Execution mode sheet ──
    'Ask each step': '逐步确认',
    'I’ll check the working tree before I change anything. Send a message and I’ll get started.': '改动之前我会先检查工作区。发条消息，我就开始。',
    'Execution mode': '执行模式',
    'How much should Claude ask before acting?': 'Claude 动手前要问到什么程度？',
    'I’m watching · ask each step': '我盯着 · 每步都问',
    'auto-allows': '自动允许',
    'nothing automatically': '不自动放行任何操作',
    '· still asks': '· 仍会询问',
    'every sensitive tool': '所有敏感工具',
    'Auto-edit files, ask before commands': '自动改文件，命令前询问',
    'file edits': '文件编辑',
    'commands': '命令',
    'Plan first, I’ll approve': '先出计划，我来批准',
    'read-only inspection': '只读检查',
    'everything until you approve': '一切操作，直到你批准',
    'Full auto · trust it': '全自动 · 信任它',
    'everything': '所有操作',
    'nothing': '什么都不问',
    'New sessions always start at “Ask each step”.': '新会话总是从「逐步确认」开始。',

    // ── Image attachment chat ──
    'I’m looking at the relay client. Send a screenshot of the error if you have one and I’ll match it to the stack trace.': '我在看 relay 客户端。有报错截图就发过来，我来对照堆栈定位。',
    'Got the': '收到这',
    'image': '张图',
    '2 images': '2 张图',
    '. The traceback points at': '。回溯指向',
    '— the socket closes before the backoff timer fires. Want me to add a guard?': '— socket 在退避计时器触发前就被关闭了。要我加个保护吗？',
  };

  const PLACEHOLDERS = {
    'Message Claude…': '发消息给 Claude…',
    'Message Claude': '发消息给 Claude',
  };

  // CJK font fallback so Inter falls through to a proper Chinese sans
  const style = document.createElement('style');
  style.textContent = "body, body * { font-family: 'Inter','PingFang SC','Hiragino Sans GB','Noto Sans SC','Microsoft YaHei',-apple-system,system-ui,sans-serif; } " +
    "[style*='JetBrains'] { font-family: 'JetBrains Mono','PingFang SC','Noto Sans SC',ui-monospace,monospace !important; }";
  document.head.appendChild(style);

  function apply(root){
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (w.nextNode()){
      const n = w.currentNode;
      const t = n.textContent;
      const trimmed = t.trim();
      if (trimmed && DICT[trimmed] !== undefined && trimmed !== DICT[trimmed]){
        n.textContent = t.replace(trimmed, DICT[trimmed]);
      }
    }
    // root itself may be an input/textarea (React swaps single nodes)
    if (root.tagName === 'INPUT' || root.tagName === 'TEXTAREA'){
      if (root.placeholder && PLACEHOLDERS[root.placeholder]) root.placeholder = PLACEHOLDERS[root.placeholder];
    }
    root.querySelectorAll && root.querySelectorAll('input,textarea').forEach(i => {
      if (i.placeholder && PLACEHOLDERS[i.placeholder]) i.placeholder = PLACEHOLDERS[i.placeholder];
    });
  }

  // initial passes (React renders async) + observer for later updates
  let passes = 0;
  const iv = setInterval(() => { apply(document.body); if (++passes > 16) clearInterval(iv); }, 250);
  const mo = new MutationObserver(muts => {
    for (const m of muts){
      if (m.type === 'characterData') { const p = m.target.parentElement; if (p) apply(p); }
      if (m.type === 'attributes') { apply(m.target); }
      m.addedNodes && m.addedNodes.forEach(n => { if (n.nodeType === 1) apply(n); else if (n.nodeType === 3 && n.parentElement) apply(n.parentElement); });
    }
  });
  mo.observe(document.body, { childList:true, subtree:true, characterData:true, attributes:true, attributeFilter:['placeholder'] });
})();
