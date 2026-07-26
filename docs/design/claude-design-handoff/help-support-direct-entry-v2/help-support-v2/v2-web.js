// Frames 5–7 · public help home (desktop dark / mobile light) + desktop conversation
(function () {
  const Y = 2080, Y2 = 3360;

  const wnav = (pad = 40) => `<div class="wnav" style="padding:0 ${pad}px">
<span class="row gap8" style="margin-right:8px">${ic('mark', 20)}<b style="font-size:15px;font-weight:600;letter-spacing:-.02em">CC Pocket</b></span>
<a>功能</a><a>下载</a><a>手册</a><a class="on">帮助</a><span class="grow"></span><span class="chip mono">EN / 中文</span></div>`;

  const SIT = [
    ['download', '开始使用', '安装电脑端 · 配对手机', 'Getting started'],
    ['term', '继续工作', '恢复会话 · 查看改动文件', 'Keep working'],
    ['clock', '对话与控制', '预约发送 · 工具请求审批', 'Talk & control'],
    ['compass', 'Agent 与模型', '选择 Agent、模型与推理强度', 'Agents & models'],
    ['lock', '共享与隐私', '共享能力 · 本地与中继的数据边界', 'Sharing & privacy']
  ];
  const TROUBLE = [['monitoroff', '电脑没有上线', '手机看不到这台电脑时怎么排查'], ['refresh', 'daemon 需要更新', '版本过旧导致功能不可用时怎么升级']];

  const situGrid = () => `<div class="grid3">${SIT.map(([i, t, s, en]) => `<div class="card row gap10 tap" tabindex="0" style="padding:12px 13px"><span style="color:var(--sec)">${ic(i, 17)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${t}</b><span class="t-s" style="font-size:11.5px">${s}</span><span class="mini" style="font-size:10px">${en}</span></span></div>`).join('')}</div>`;

  const troubleCard = () => `<div class="card" style="padding:2px 14px">${TROUBLE.map(([i, t, s]) => `<div class="lrow tap" tabindex="0"><span class="li">${ic(i, 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${t}</b><span class="t-s" style="font-size:11.5px">${s}</span></span><span style="color:var(--muted)">${ic('right', 16)}</span></div>`).join('')}</div>`;

  const wfoot = () => `<div class="row gap16" style="border-top:1px solid var(--hair);padding-top:13px;margin-top:2px">
<span class="row gap8 tap" style="color:var(--sec);font-size:13px">${ic('book', 16)}完整用户手册</span>
<span class="row gap8 tap" style="color:var(--muted);font-size:13px">${ic('github', 16)}报告可复现问题</span>
<span class="row gap8 tap" style="color:var(--muted);font-size:13px">${ic('shield', 16)}隐私与安全</span>
<span class="grow"></span><span class="mini">pocket.ark-nexus.cc/support/</span></div>`;

  // ── home body (desktop) ─────────────────────────────────────
  const homeBody = () => `<div class="wbody" data-body><div class="wwrap">
  <div class="col gap10">
    <h1 class="t-h1" style="font-size:26px">今天想解决什么？</h1>
    <p class="t-b" style="font-size:14.5px;max-width:640px">告诉我你想完成什么，或哪里卡住了。回答只基于已核验的 CC Pocket 用户手册；本页不会连接你的电脑，也读不到 App 里的会话内容。</p>
  </div>
  ${v2comp('例如：我想看看这次会话改了哪些文件', { send: 'ask' })}
  ${v2chips(V2_SUGGEST.slice(0, 3))}
  <div class="col gap10">
    <div class="sechead"><span class="t-eyebrow">最常做的事 Common tasks</span><span class="mini">与 App 内同一批已核验任务</span></div>
    <div class="grid2">${V2_TASKS.map(t => v2task(t)).join('')}</div>
  </div>
  <div class="col gap10">
    <div class="sechead"><span class="t-eyebrow">按场景浏览 Browse by situation</span><span class="mini">手册分类已合并为用户尺度的分组</span></div>
    ${situGrid()}
  </div>
  <div class="col gap8">
    <div class="sechead"><span class="t-eyebrow">出现问题？ Something stopped working?</span></div>
    ${troubleCard()}
  </div>
  ${wfoot()}
</div></div>`;

  // ── conversation workspace (desktop, shared by 5→submit and 7) ──
  const railGuides = [['diff', '查看本次会话改过的文件'], ['shield', '核对并处理工具请求'], ['term', '无分叉接管终端会话']];
  const convBody = (o = {}) => `<div class="wbody" data-body style="padding:0;display:flex;overflow:hidden">
<div style="flex:1;min-width:0;display:flex;flex-direction:column">
  <div class="row gap10" style="height:46px;flex:none;padding:0 24px;border-bottom:1px solid var(--hair)">
    <span class="row gap8 tap" style="color:var(--sec);font-size:13px">${ic('back', 16)}返回帮助首页</span>
    <span class="grow"></span><span class="pill p-mut">公开对话 · 无账号 · 不保存历史</span>
  </div>
  <div style="flex:1;min-height:0;padding:22px 24px;display:flex;flex-direction:column;gap:14px;overflow:hidden">
    <div style="max-width:720px;width:100%;margin:0 auto;display:flex;flex-direction:column;gap:14px">${o.wait ? v2wait() : v2answer(V2_ANSWER)}</div>
    <div class="grow"></div>
    <div style="max-width:720px;width:100%;margin:0 auto">${v2comp('接着问…', { send: 'ask' })}</div>
  </div>
</div>
<div style="width:280px;flex:none;border-left:1px solid var(--hair);background:var(--surface);padding:18px 16px;display:flex;flex-direction:column;gap:12px">
  <span class="t-eyebrow">相关手册指南 Related guides</span>
  <div class="col gap8">${railGuides.map(([i, t]) => `<div class="rel tap" tabindex="0">${ic(i, 15)}<span class="grow" style="font-size:12.5px">${t}</span>${ic('ext', 13)}</div>`).join('')}</div>
  <div class="grow"></div>
  <p class="mini" style="line-height:1.6">这一栏只按当前问题匹配手册，不是对话历史。关闭标签页后本次对话即结束。</p>
</div></div>`;

  // ── FRAME 5 · public help home · desktop 1440 dark ──────────
  const f5 = F({
    label: 'FRAME 5 · 公开帮助首页 · 桌面 1440', sub: 'task-first help home — unified composer first, no hero, no four channel cards', x: 80, y: Y, w: 1440, h: 1120,
    html: `<div class="web" style="height:100%">${wnav()}${homeBody()}</div>`
  });
  f5.id = 'f5';
  f5.dataset.home = homeBody();
  f5.dataset.wait = convBody({ wait: 1 });
  f5.dataset.conv = convBody();

  // ── FRAME 6 · public help home · mobile 390 light ───────────
  F({
    label: 'FRAME 6 · 公开帮助首页 · 移动 390 · 浅色', sub: 'composer + 3 common tasks in the first viewport — warm-cream light variant', x: 1600, y: Y, w: 390, h: 844, theme: 'light',
    html: `<div class="ph" style="height:100%">
${sb()}
<div class="wnav" style="height:52px;padding:0 16px;gap:12px"><span class="row gap8 grow">${ic('mark', 18)}<b style="font-size:14.5px;font-weight:600">CC Pocket</b></span><span class="chip mono" style="height:26px">中文</span><span style="color:var(--muted)">${ic('search', 18)}</span></div>
<div class="scr" style="gap:13px;padding:16px 16px 10px">
  <div class="col gap6"><h1 class="t-h1" style="font-size:21px">今天想解决什么？</h1><p class="t-s" style="line-height:1.6">告诉我你想完成什么，或哪里卡住了。</p></div>
  ${v2comp('说出你想完成的事…', { warn: '别粘贴密钥、令牌或私有路径' })}
  <div class="col gap8">
    <div class="sechead"><span class="t-eyebrow">最常做的事</span></div>
    <div class="col gap8">${V2_TASKS.slice(0, 3).map(t => v2task(t)).join('')}</div>
  </div>
  <div class="col gap8">
    <div class="sechead"><span class="t-eyebrow">按场景浏览</span></div>
    <div class="chips">${SIT.map(([, t]) => chip(t, { tap: 1 })).join('')}</div>
  </div>
  <div class="col gap8">
    <div class="sechead"><span class="t-eyebrow">出现问题？</span></div>
    ${troubleCard()}
  </div>
  <div class="row gap10" style="border-top:1px solid var(--hair);padding-top:10px;color:var(--sec);font-size:12.5px">${ic('book', 15)}<span class="grow">完整用户手册</span><span class="mini">报告问题 · 隐私与安全</span></div>
</div>
${hbar()}</div>`
  });

  ANN(2070, Y, `<h4>响应式层级 Responsive</h4>
<dl><dt>390</dt><dd>第一屏：标题 + 输入框 + 3 条最常做的事；按场景浏览退化为 chips，故障排查与页脚在折线以下</dd>
<dt>1440</dt><dd>同一顺序展开为 2 列任务 + 3 列场景；wwrap 840px 保持可读行长</dd>
<dt>不变</dt><dd>输入框永远是第一个可交互元素；任何断点都不出现第二个「咨询智能客服」CTA</dd>
<dt>浅色</dt><dd>暖奶油变体沿用 --base #FAF9F7 / accent #C75A38，对比度 ≥4.5:1</dd></dl>
<p style="margin-top:10px"><b>L10N</b><br>今天想解决什么？ → What do you need help with?<br>告诉我你想完成什么，或哪里卡住了。 → Tell me what you want to do, or where you're stuck.<br>问智能客服 → Ask Smart Support<br>最常做的事 → Common tasks<br>按场景浏览 → Browse by situation<br>出现问题？ → Something stopped working?<br>正在检索已核验手册 → Searching the verified manual<br>完整用户手册 / 报告可复现问题 / 隐私与安全 → Full manual / Report a reproducible issue / Privacy &amp; security</p>`, 260);

  // ── FRAME 7 · desktop conversation ──────────────────────────
  const f7 = F({
    label: 'FRAME 7 · 桌面对话工作区 · 1440', sub: 'same public workspace after submit — centered column + quiet related-guide rail', x: 80, y: Y2, w: 1440, h: 900,
    html: `<div class="web" style="height:100%">${wnav()}${convBody()}</div>`
  });
  f7.id = 'f7';
})();
