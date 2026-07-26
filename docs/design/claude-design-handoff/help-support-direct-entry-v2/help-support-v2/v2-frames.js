// Frames 0–4 · entry contract, native app help, app-direct chat, answer, fallbacks
(function () {
  const Y0 = 200, Y1 = 1090, H = 844;

  // ── FRAME 0 · ENTRY + IA CONTRACT ───────────────────────────
  const node = (t, m) => `<div class="node"><span class="grow col" style="gap:2px"><span>${t}</span>${m ? `<span class="m">${m}</span>` : ''}</span></div>`;
  const arw = '<div class="arrow">↓</div>';

  F({
    label: 'FRAME 0 · 入口与信息架构契约', sub: 'Entry + IA contract — one support surface, two entry modes', x: 80, y: Y0, w: 2200, h: 640,
    html: `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:14px">
<div class="row gap16" style="align-items:flex-start">
  <div style="flex:1"><b style="font-size:15px;font-weight:600">一个支持界面，两种进入方式 One support surface, two entry modes</b>
  <p class="mono" style="font-size:11.5px;line-height:1.6;color:#8A9096;margin:6px 0 0">两条入口最终落在同一个对话工作区。App 侧省掉的是「再点一次才能问」的那一步，而不是省掉网页本身。</p></div>
  <div class="lane acc" style="width:660px"><h6>URL 责任边界 URL responsibility</h6>
  <p><b style="color:#ECEDEE">只允许</b> 非敏感的入口标识：<span class="mono" style="color:#D97757">mode</span>（chat / help）与 <span class="mono" style="color:#D97757">source</span>（app / web）。</p>
  <p><b style="color:#ECEDEE">禁止携带</b> 路径、仓库、会话内容、日志、当前 Agent/模型、机器名、配对码、任何 token。URL 不预填、不自动提交。</p></div>
</div>
<div class="row gap14" style="align-items:stretch;flex:1;min-height:0">
  <div class="lane" style="flex:1"><h6>A · App 直达模式 App direct mode</h6>
    ${node('App「帮助与支持」· 一张可整块点击的支持行', '不再是标题 + 重复的整宽按钮')}
    ${arw}
    ${node('系统浏览器打开 pocket.ark-nexus.cc/support/?mode=chat&source=app', '原生行明确标注：将打开公开网页')}
    ${arw}
    ${node('落地即可提问：紧凑客服头 + 问候 + 建议问题 + 输入框', '全部位于第一屏 390×844，无第二次激活点击')}
    <p style="margin-top:2px">不假设 iOS 自动弹键盘；成功的定义是「输入框已就绪，下一步就是打字」。返回走浏览器原生返回。</p>
  </div>
  <div class="lane" style="flex:1"><h6>B · 公开网页模式 Public web mode</h6>
    ${node('直接访问 pocket.ark-nexus.cc/support/', '任务优先的帮助首页，不是营销落地页')}
    ${arw}
    ${node('唯一输入框：「今天想解决什么？」', '没有第二个「咨询智能客服」卡片或 CTA')}
    ${arw}
    ${node('提交后原地转换为同一个对话工作区', 'in-place transition · 与 A 完全相同的组件')}
    <p style="margin-top:2px">首页其余部分按用户意图组织：最常做的事 → 按场景浏览 → 出现问题？→ 安静的次要出口。</p>
  </div>
  <div class="lane" style="flex:1"><h6>被移除的旧结构 Removed</h6>
    <div class="ban no">${ic('x', 16)}<span><b style="color:#ECEDEE">营销 hero + 第二个「咨询智能客服」CTA</b><br>App 用户点一次落地后还要再点一次才能问。</span></div>
    <div class="ban no">${ic('x', 16)}<span><b style="color:#ECEDEE">四张等权路径卡</b><br>智能客服 / 用户手册 / 把手册交给 AI / GitHub Issue —— 逼用户先选渠道再说目标。</span></div>
    <div class="ban no">${ic('x', 16)}<span><b style="color:#ECEDEE">「把手册交给 AI」</b><br>第一方智能客服已在页面上，重复且更弱。</span></div>
    <div class="ban ok2">${ic('check', 16)}<span><b style="color:#ECEDEE">保留但降级</b><br>完整手册 = 深度参考；GitHub Issue = 自助之后的升级路径或页脚工具。</span></div>
  </div>
  <div class="lane" style="width:430px;flex:none"><h6>不传输任何上下文 No context transfer</h6>
    <p>网页拿不到、也不会声称能拿到：App 页面状态、当前路径 / 仓库、会话内容、日志、当前 Agent 与模型、机器名。</p>
    <div class="node" style="border-color:rgba(217,119,87,.4)"><span class="grow col" style="gap:3px"><span class="mono" style="font-size:11px;color:#D97757">/support/?mode=chat&amp;source=app</span><span class="m">唯一被传递的东西：这两个非敏感参数</span></span></div>
    <p><b style="color:#ECEDEE">因此</b> 公开网页上不出现任何「假的」原生直达按钮（打开改动 / 打开定时发送）。回答里只给书面步骤 + 手册链接。</p>
    <p><b style="color:#ECEDEE">会话</b> 公开、无账号、无登录、无持久历史；连续性仅限当前浏览器会话。</p>
  </div>
</div></div>`
  });

  // ── FRAME 1 · NATIVE APP HELP ───────────────────────────────
  const f1 = F({
    label: 'FRAME 1 · 原生 App · 帮助与支持', sub: '390×844 dark — one tappable support row, no nested duplicate CTA', x: 80, y: Y1, w: 390, h: H,
    html: `<div class="ph" style="height:100%">
${sb()}
${abar('帮助与支持', `<span style="color:var(--muted)">${ic('search', 20)}</span>`)}
<div class="scr" style="gap:14px">
  <div class="suprow tap" tabindex="0" data-act="open-web">
    <span class="lead">${ic('mark', 20)}</span>
    <span class="grow col" style="gap:3px">
      <b class="t-h3" style="font-size:15.5px">问智能客服</b>
      <span class="t-s" style="font-size:11.5px;line-height:1.5">直接打开公开对话 · 基于已核验手册 · 无需登录</span>
    </span>
    <span style="color:var(--accent)">${ic('ext', 18)}</span>
  </div>
  <p class="mini" style="margin:-6px 0 0">在浏览器中打开公开网页 pocket.ark-nexus.cc · 不会附带你的会话内容</p>
  <div class="sechead" style="margin-top:2px"><span class="t-eyebrow">按任务学习 Learn by task</span><span class="mini">5 篇 · 已核验</span></div>
  <div class="col gap8" data-tasks>${V2_TASKS.map((t, i) => v2task(t, { act: 'task:' + i, icon: 'down' })).join('')}</div>
  <div class="grow"></div>
  <div class="disc"><span class="row gap8" style="color:var(--sec)">${ic('book', 16)}完整用户手册</span><span style="color:var(--muted)">${ic('ext', 16)}</span></div>
</div>
${hbar()}</div>`
  });
  f1.id = 'f1';

  // ── FRAME 2 · APP-OPENED DIRECT CHAT ────────────────────────
  const chrome = (url) => `<div class="bchrome"><span style="color:var(--muted)">${ic('back', 18)}</span><span class="url">${ic('lock', 12)}<span class="grow" style="overflow:hidden;text-overflow:ellipsis">${url}</span></span><span style="color:var(--muted)">${ic('refresh', 16)}</span></div>`;

  const emptyChat = `<div class="scr" style="gap:14px;padding-bottom:8px" data-body>
  <div class="ban ok2">${ic('check', 16)}<span>从 App 打开 · 只传递了入口来源，<b style="color:var(--text)">未附带任何会话、路径或日志</b>。</span></div>
  ${v2greet(true)}
  <span class="t-eyebrow">可以直接点一个开始</span>
  ${v2chips(V2_SUGGEST)}
  <div class="grow"></div>
</div>
<div class="dock">${v2comp('说出你想完成的事，或哪里卡住了…', { focus: 1, send: 'ask' })}</div>`;

  const f2 = F({
    label: 'FRAME 2 · App 直达 · 落地即可提问', sub: '390×844 dark — /support/?mode=chat&source=app · composer ready in the first viewport', x: 560, y: Y1, w: 390, h: H,
    html: `<div class="ph" style="height:100%">
${sb()}
${chrome('pocket.ark-nexus.cc/support/?mode=chat&amp;source=app')}
${v2suphead()}
${emptyChat}
${hbar()}</div>`
  });
  f2.id = 'f2';
  f2.dataset.empty = emptyChat;

  // ── FRAME 3 · MOBILE ANSWER ─────────────────────────────────
  const f3 = F({
    label: 'FRAME 3 · 移动端 · 回答', sub: '390×844 dark — direct answer → numbered steps → verified source → related', x: 1040, y: Y1, w: 390, h: H,
    html: `<div class="ph" style="height:100%">
${sb()}
${chrome('pocket.ark-nexus.cc/support/?mode=chat&amp;source=app')}
${v2suphead(`<span class="pill p-mut">${ic('x', 12)}结束</span>`)}
<div class="scr" style="gap:12px;padding-bottom:8px">${v2answer(V2_ANSWER)}<div class="grow"></div></div>
<div class="dock">${v2comp('接着问…', { send: 'ask' })}</div>
${hbar()}</div>`
  });
  f3.id = 'f3';

  // ── FRAME 4 · FAILURE / FALLBACK BOARD ──────────────────────
  const guides = [['查看本次会话改过的文件', '2026-07-24'], ['核对并处理工具请求', '2026-07-18']];
  const keepQ = `<div class="card row gap10" style="padding:10px 12px"><span class="t-eyebrow" style="flex:none">你的问题</span><span class="grow" style="font-size:13.5px">${V2_ANSWER.q}</span></div>`;
  const matches = `<div class="col gap6"><span class="t-eyebrow">同一套任务分类下最贴近的已核验指南</span>${guides.map(([t, d]) => `<a class="srclink tap" tabindex="0">${ic('book', 15)}<span class="grow"><b class="u">${t}</b> · 最近核验 ${d}</span>${ic('ext', 14)}</a>`).join('')}</div>`;
  const cell = (tone, icn, title, en, body, act) => `<div class="card col gap10" style="border-color:color-mix(in oklab,var(--${tone}) 40%,var(--hair))">
<div class="row gap10"><span style="color:var(--${tone})">${ic(icn, 18)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${title}</b><span class="mini">${en}</span></span>${act || ''}</div>${body}</div>`;

  const f4 = F({
    label: 'FRAME 4 · 等待与回退状态', sub: 'Waiting · rate-limited · busy · timeout — question preserved, retry, verified matches', x: 1520, y: Y1, w: 1180, h: H,
    theme: 'dark',
    html: `<div style="height:100%;padding:22px;display:flex;flex-direction:column;gap:14px;background:var(--base)">
<div class="row gap16" style="align-items:flex-start">
  <div class="grow"><b class="t-h2">等待与回退 Waiting &amp; fallback</b><p class="t-s" style="margin-top:5px;line-height:1.6">四种状态共用一条规则：保留用户已输入的问题 → 说明现在发生了什么 → 给一个 Retry → 在同一套任务分类下给出最贴近的已核验手册指南。它们是对话里的一条消息，不是整页错误页。</p></div>
  <span class="pill p-mut" style="height:24px">component board</span>
</div>
<div class="grid2" style="flex:1;min-height:0;align-content:start">
  ${cell('info', 'clock', '等待中 Waiting', 'whole-response API · 不做假的逐字流式', `<div class="wait"><span class="spin"></span><span class="grow">正在检索已核验手册 · 复杂问题可能需要约 1 分钟</span></div><p class="mini">文案如实描述一次性返回的接口；不显示进度百分比、不显示 token 计数。</p>`)}
  ${cell('warning', 'alert', '被限流 Rate-limited', '公开页共享额度 · 问题已保留', `${keepQ}<p class="t-s">当前公开对话请求较多，暂时排队。你的问题已保留，可以稍后重试，或先看下面的手册指南。</p>${matches}<div class="row gap8"><span class="btn sm b-pri tap" data-act="retry">${ic('refresh', 16)}重试</span><span class="btn sm b-quiet">先看指南</span></div>`, '<span class="pill p-warn">429</span>')}
  ${cell('warning', 'layers', '服务繁忙 Busy', '后端可用但排队 · 不呈现为故障', `${keepQ}<p class="t-s">助手正忙，通常一两分钟后恢复。这不是你的网络问题，也不需要刷新页面。</p>${matches}<div class="row gap8"><span class="btn sm b-pri tap" data-act="retry">${ic('refresh', 16)}重试</span><span class="btn sm b-quiet">报告可复现问题</span></div>`)}
  ${cell('danger', 'refresh', '超时 Timeout', '超过 ~60s 未返回', `${keepQ}<p class="t-s">这次没能在预期时间内返回。可以直接重试同一个问题；把问题拆小通常更快。</p>${matches}<div class="row gap8"><span class="btn sm b-pri tap" data-act="retry">${ic('refresh', 16)}重试同一个问题</span><span class="btn sm b-quiet">换一种问法</span></div>`)}
</div>
<div class="row gap10" style="border-top:1px solid var(--hair);padding-top:11px">
  <span class="mini grow">禁止：整页 error 状态 · 清空输入框 · 「请稍后再试」而不给替代 · 把回退包装成智能客服的回答</span>
  <span class="pill p-ok">Retry 可在原型中演示 →</span>
</div></div>`
  });
  f4.id = 'f4';
})();
