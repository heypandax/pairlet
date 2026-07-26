// FRAME 1 — Experience logic board (learning & discovery)
(function () {
  const arrow = (t) => `<div class="col" style="align-items:center;justify-content:center;gap:4px;color:#5E646A;font:400 10.5px/1 'JetBrains Mono',monospace;width:78px">${t ? `<span>${t}</span>` : ''}<svg class="ic ic-20" style="stroke:#4A4F55"><use href="#i-right"/></svg></div>`;

  const mode = (tag, name, en, when, items, accent) => `<div class="bx" style="flex:1;min-width:0;border-color:${accent}55;background:#141618">
<div class="row gap8" style="margin-bottom:8px"><span class="pill" style="background:${accent}22;color:${accent}">MODE ${tag}</span><b style="font-size:14px;font-weight:600">${name}</b></div>
<p style="color:#7A8086;margin-bottom:9px">${en}</p>
<div class="bx" style="padding:8px 10px;background:#101113;margin-bottom:9px"><p style="color:#9BA1A6"><b style="color:#5E646A">进入条件</b> ${when}</p></div>
<div class="col gap6">${items.map(i => `<div class="row gap8" style="align-items:flex-start"><span style="color:${accent};font:400 11px/1.6 'JetBrains Mono',monospace">·</span><span style="font-size:12.5px;line-height:1.5;color:#C9CED3">${i}</span></div>`).join('')}</div></div>`;

  const html = `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:18px">
<div class="row gap16" style="align-items:flex-start">
  <div class="bx acc" style="width:330px;flex:none">
    <h5>触发优先级 Trigger priority</h5>
    <p style="margin-bottom:10px">同一时刻只有一个模式获得首屏优先权，按顺序求值。</p>
    <div class="col gap6">
      <div class="row gap8"><span class="pill p-new">1</span><span style="font-size:12.5px;color:#ECEDEE">首次使用尚未完成 <span class="mono" style="color:#6B7177;font-size:11px">incomplete first-use setup</span></span></div>
      <div class="row gap8"><span class="pill p-info">2</span><span style="font-size:12.5px;color:#ECEDEE">明确的学习意图 <span class="mono" style="color:#6B7177;font-size:11px">「我该怎么做…」</span></span></div>
      <div class="row gap8"><span class="pill p-mut">3</span><span style="font-size:12.5px;color:#ECEDEE">主动的能力发现 <span class="mono" style="color:#6B7177;font-size:11px">proactive discovery</span></span></div>
    </div>
    <hr class="hair" style="background:#2A2E33;margin:12px 0">
    <p><b>核心问题不是排障</b><br>用户不知道 CC Pocket 怎么用，也感知不到它的多数能力。运行时故障只在失败现场保留一个就近的小逃生口，不进入帮助中心的主旅程。</p>
    <hr class="hair" style="background:#2A2E33;margin:12px 0">
    <p><b>入口不变</b><br>Projects / Sessions 头部 help-circle、桌面侧栏页脚 Help、设置内「帮助中心」一行。全部先打开原生帮助中心，不直接跳浏览器。</p>
  </div>
  <div class="bx" style="flex:1;min-width:0">
    <h5>一个目的地，三种自适应模式 One destination · three adaptive modes</h5>
    <p style="margin-bottom:12px">没有 Manual / FAQ / Troubleshooting 这类导航标签——它们是背后的内容源，不是用户要先做的选择。</p>
    <div class="row gap12" style="align-items:stretch">
      ${mode('A', '开始使用', 'Get started', '未配对，或尚未完成首次成功会话', ['设置进度 + 下一个未完成步骤', '「电脑端已经安装 / 还没有安装」显式分叉', '按系统给出的安装命令', '配对动作（扫码 / 六位码）', '首次成功清单', '「先体验 Demo」作为安静的次要项'], '#D97757')}
      ${mode('B', '学会一件事', 'Learn a task', '用户表达了具体任务意图，或在某个界面问「这里怎么用」', ['用任务命名，而不是用功能命名', '这件事能带来什么结果', '一个下一步动作 + 静态「显示在哪里」', '可选的书面步骤，不追踪进度、不检测点击', '已核验的手册来源'], '#5B9BD5')}
      ${mode('C', '发现更多能力', 'Discover capabilities', '已完成首次成功，且没有进行中的学习任务', ['按结果组织的能力目录', '为什么值得用（价值先于机制）', '前置条件与可用性', '已体验 / 未体验状态', '最近新增，最多三条', '被忽略过的提示永久留在 Explore'], '#4FB477')}
    </div>
  </div>
</div>
<div class="bx" style="flex:1;min-height:0">
  <h5 style="margin-bottom:12px">状态迁移 State transitions</h5>
  <div class="row" style="align-items:stretch;gap:0">
    <div class="col gap8" style="width:200px;flex:none">
      <div class="bx" style="padding:9px 11px;background:#101113"><p style="color:#ECEDEE;font-size:12px">未配对 / 未首次成功</p></div>
      <div class="bx" style="padding:9px 11px;background:#101113"><p style="color:#ECEDEE;font-size:12px">有学习意图 learning intent</p></div>
      <div class="bx" style="padding:9px 11px;background:#101113"><p style="color:#ECEDEE;font-size:12px">已激活，空闲 activated</p></div>
    </div>
    ${arrow('求值')}
    <div class="col gap8" style="width:196px;flex:none">
      <div class="bx" style="padding:9px 11px;border-color:#D9775766"><p style="color:#D97757;font-size:12px">MODE A 开始使用</p></div>
      <div class="bx" style="padding:9px 11px;border-color:#5B9BD566"><p style="color:#5B9BD5;font-size:12px">MODE B 学会一件事</p></div>
      <div class="bx" style="padding:9px 11px;border-color:#4FB47766"><p style="color:#4FB477;font-size:12px">MODE C 发现更多能力</p></div>
    </div>
    ${arrow('任意模式')}
    <div class="bx" style="width:238px;flex:none;align-self:center">
      <h5 style="font-size:13px">问问 CC Pocket 助手</h5>
      <p>教学式回答：这个能力做什么 → 什么时候有用 → 一个下一步动作 → 静态「显示在哪里」→ 可选书面步骤 → 手册来源 → 我会用了 / 还没明白。</p>
    </div>
    ${arrow('还没明白')}
    <div class="bx" style="width:230px;flex:none;align-self:center;border-color:#5B9BD555">
      <h5 style="font-size:13px">换一种讲法</h5>
      <p>先问一个澄清问题（你现在屏幕上看到什么），再给更简单的解释与更精确的位置说明。不是诊断分支。</p>
    </div>
    ${arrow('我会用了')}
    <div class="bx" style="width:216px;flex:none;align-self:center;border-color:#4FB47755">
      <h5 style="font-size:13px">标记为已学会</h5>
      <p>回到原任务；该能力在 Explore 标记「已体验」，不再主动提示。</p>
    </div>
  </div>
  <hr class="hair" style="background:#2A2E33;margin:14px 0 12px">
  <div class="row gap16" style="align-items:flex-start">
    <div style="flex:1"><p><b>成功指标 Success metrics</b><br>首次使用激活率 first-use activation · 指南跳转打开率 guide-to-destination open rate · 能力「发现→使用」转化率 discovery-to-use · 新学会能力的 7 日复用率 7-day reuse。不使用工单转化率或问题解决率。</p></div>
    <div style="flex:1"><p><b>故障的位置</b><br>运行时失败（离线、重连、配对失效、定时未执行）只在失败现场给一个就近的小恢复入口，命名状态 + 一个动作。帮助中心不以排障为模式、导航、推荐或红线。</p></div>
    <div style="flex:1"><p><b>永不自动读取</b><br>仓库内容与完整路径 · 会话记录与 prompt · 源码与文件内容 · token / API key / 配对码 / 私钥 · 原始日志与环境变量。</p></div>
  </div>
</div></div>`;

  F({ label: 'FRAME 01 · 体验逻辑板', sub: 'Experience logic board — learning & discovery decision logic', x: 80, y: 200, w: 1560, h: 830, html });
})();
