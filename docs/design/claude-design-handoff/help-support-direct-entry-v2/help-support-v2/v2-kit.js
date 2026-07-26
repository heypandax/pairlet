// Help Support Direct Entry v2 — shared builders (layered on help/help-core.js)
(function () {
  // five verified tasks — identical set in App and on the web
  window.V2_TASKS = [
    ['diff', '查看本次会话改过的文件', 'Changed files in this session'],
    ['term', '无分叉接管终端会话', 'Take over a terminal session'],
    ['shield', '核对并处理工具请求', 'Review and answer tool requests'],
    ['clock', '预约稍后发送提示词', 'Schedule a prompt for later'],
    ['compass', '选择 Agent 与模型', 'Choose an agent and model']
  ];
  window.V2_SUGGEST = ['怎么看 Agent 改了哪些文件？', '怎么接管终端而不分叉？', '工具请求该批准还是拒绝？', '怎么让它稍后再发这条提示词？'];

  // composer — no uploads, no mic. Privacy line sits inside.
  window.v2comp = (ph, o = {}) => `<div class="v2c ${o.focus ? 'focus' : ''}" ${o.id ? `data-comp="${o.id}"` : ''}>
${o.chips ? `<div class="chips">${o.chips}</div>` : ''}
<div class="phtxt ${o.typed ? 'typed' : ''}">${ph}</div>
<div class="bar"><span class="warn">${ic('lock', 13)}<span>${o.warn || '别粘贴密钥、令牌、配对码或私有路径'}</span></span><span class="go ${o.typed ? '' : 'off'}" ${o.send ? `data-act="${o.send}"` : ''}>${ic('send', 18)}</span></div>
</div>`;

  window.v2task = (t, o = {}) => `<div class="trow tap" tabindex="0" ${o.act ? `data-act="${o.act}"` : ''}>
<span class="li">${ic(t[0], 16)}</span>
<span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${t[1]}</b><span class="t-s mono" style="font-size:10.5px">${t[2]}</span></span>
<span style="color:var(--muted)">${ic(o.icon || 'right', 16)}</span></div>`;

  // teaching-grammar answer: direct answer → numbered steps → verified source → related
  window.v2answer = (o = {}) => `<div class="col gap12">
<div class="qbub">${o.q}</div>
<div class="abub">
  <p class="lead">${o.lead}</p>
  <div class="col gap8">${o.steps.map((s, i) => `<div class="step"><span class="n">${i + 1}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${s[0]}</b><span class="t-s">${s[1]}</span></span></div>`).join('')}</div>
  <a class="srclink tap" tabindex="0">${ic('book', 15)}<span class="grow">手册 · <b class="u">${o.src[0]}</b><br><span style="color:var(--muted)">最近核验 ${o.src[1]} · 在新标签页打开</span></span>${ic('ext', 14)}</a>
  <div class="col gap6"><span class="t-eyebrow">相关指南 Related</span>${o.rel.map(r => `<div class="rel tap" tabindex="0">${ic(r[0], 15)}<span class="grow">${r[1]}</span>${ic('right', 14)}</div>`).join('')}</div>
</div></div>`;

  window.V2_ANSWER = {
    q: '怎么看 Agent 改了哪些文件？',
    lead: '在会话正文上方有一张「N 个文件已改动」的卡片，它就是本次会话改过文件的完整列表；点文件名可以展开逐行 diff，确认无误后再批准写入。',
    steps: [
      ['打开那条会话', '侧栏「会话」里带绿点的一条就是正在跑的会话。'],
      ['点开「N 个文件已改动」', '在会话正文上方，按文件聚合，显示 +/− 行数。'],
      ['展开逐行 diff 再批准', '点文件名展开；确认后回到工具请求里放行写入。']
    ],
    src: ['查看本次会话改过的文件', '2026-07-24'],
    rel: [['shield', '核对并处理工具请求'], ['term', '无分叉接管终端会话']]
  };

  window.v2wait = () => `<div class="col gap12">
<div class="qbub">${V2_ANSWER.q}</div>
<div class="wait"><span class="spin"></span><span class="grow">正在检索已核验手册 · 复杂问题可能需要约 1 分钟</span></div>
<p class="mini" style="padding-left:2px">回答一次性返回，不逐字输出。Whole-response API — no fake token streaming.</p></div>`;

  window.v2greet = (compact) => `<div class="col gap${compact ? '8' : '10'}">
<b class="t-h2" style="font-size:${compact ? '16' : '17'}px">你想解决什么？</b>
<p class="t-s" style="line-height:1.6">告诉我你想完成什么，或哪里卡住了。回答只基于已核验的 CC Pocket 用户手册。<br><span style="color:var(--sec)">本页不会连接你的电脑，也读不到 App 里的会话内容。</span></p></div>`;

  window.v2chips = (list) => `<div class="chips">${list.map(t => `<span class="chip tap" tabindex="0" data-act="ask">${t}</span>`).join('')}</div>`;

  // compact support header used by every conversation surface
  window.v2suphead = (right = '') => `<div class="abar" style="height:48px"><span class="li" style="width:28px;height:28px;border-radius:8px;display:grid;place-items:center;background:var(--accent-bg);color:var(--accent);flex:none">${ic('mark', 16)}</span><h2 style="font-size:15px">智能客服</h2><span class="pill p-mut">公开 · 无需登录</span>${right}</div>`;
})();
