// Decision / implementation notes panel (design handoff, not production code)
(function () {
  const rows = [
    ['App 支持行', '一张可整块点击的行 = 唯一动作', '替换「标题 + 重复整宽按钮」的高卡片；保持视觉主要但压缩到 ~64pt，让「按任务学习」在第一屏出现。'],
    ['URL 契约', '/support/?mode=chat&source=app', '稳定契约由网页侧负责解析；App 只拼两个非敏感参数。mode 缺省 = help（任务优先首页）。'],
    ['落地即可问', '第一屏内：客服头 + 问候 + 建议 + 输入框', '不自动聚焦、不自动弹键盘、不自动提交。成功判据 = 用户下一步就是打字。'],
    ['上下文', '零传输', '网页读不到 App 状态；因此回答里只有书面步骤与手册链接，没有「打开改动」这类原生直达按钮。'],
    ['公开首页', '输入框是第一个可交互元素', '没有 hero、没有第二个客服 CTA、没有四张等权渠道卡。提交后原地转换为同一个对话工作区。'],
    ['内容组织', '按意图，不按渠道', '最常做的事（与 App 同一批 5 个已核验任务）→ 按场景浏览（手册分类合并为 5 组）→ 出现问题？（2 条，压缩）→ 页脚出口。'],
    ['等待文案', '如实描述整体响应接口', '「正在检索已核验手册 · 复杂问题可能需要约 1 分钟」；不做假的逐字流式、不显示百分比。'],
    ['回退', '保留问题 + Retry + 手册匹配', '限流 / 繁忙 / 超时共用一套结构，作为对话里的一条消息，不是整页错误页。'],
    ['隐私', '输入框旁一句短警示', '别粘贴密钥、令牌、配对码、私有代码路径或未脱敏日志；详细安全说明放次级/可折叠。'],
    ['会话', '公开、无账号、无历史', '连续性仅限当前浏览器标签；右侧「相关手册指南」按当前问题匹配，不是历史列表。'],
    ['手册', '深度参考，不是并列旅程', '答案中的手册链接使用可访问的下划线 + 外链图标，与正文明显区分。'],
    ['GitHub', '自助之后的升级路径', '出现在回退动作与页脚，不占首页四分之一。']
  ];
  const checks = [
    ['第一屏层级', 'FRAME 2 输入框与 4 条建议全部在 844 内；FRAME 6 输入框 + 3 条任务在折线以上'],
    ['裁切与重叠', '所有移动帧 .scr 固定高度 + grow 撑开，无内部滚动陷阱；桌面 wwrap 840 内容高度 < 帧高'],
    ['安全区', '底部 dock 使用 env(safe-area-inset-bottom)；home indicator 区域不压内容'],
    ['深浅一致', 'FRAME 6 浅色使用同一批组件与间距，仅令牌切换；accent #C75A38 保证 4.5:1'],
    ['触控目标', '支持行 ≥64pt，任务行 ≥52pt，chips 32pt 但含 8pt 外边距命中区，发送键 36pt + padding'],
    ['键盘可达', '所有可点元素 tabindex=0 + :focus-visible 描边；Enter 触发'],
    ['动效', '仅一个检索转圈；prefers-reduced-motion 下停止旋转'],
    ['旧模式清除', '无 hero、无第二个客服 CTA、无四宫格渠道卡、无「把手册交给 AI」、无吉祥物 / 渐变装饰 / 进度徽章 / 引导浮层 / 上传 / 账号 / 历史侧栏']
  ];

  F({
    label: '决策与实现备注 · 自查清单', sub: 'Decision notes + inspection checklist — design-only handoff', x: 1600, y: 3360, w: 1000, h: 980,
    html: `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:14px">
<div><b style="font-size:15px;font-weight:600">决策备注 Decision notes</b><p class="mono" style="font-size:11.5px;line-height:1.6;color:#8A9096;margin:6px 0 0">仅涉及入口流程、交互 UI、内容层级与响应式状态。智能客服后端、模型、检索管线、三层知识治理与服务器架构不在本次范围内，未做任何改动。</p></div>
<div class="bx" style="flex:1;min-height:0;overflow:hidden">
<table class="tbl"><thead><tr><th style="width:110px">决策</th><th style="width:250px">结论</th><th>理由与约束</th></tr></thead>
<tbody>${rows.map(r => `<tr><td><b>${r[0]}</b></td><td class="m">${r[1]}</td><td>${r[2]}</td></tr>`).join('')}</tbody></table></div>
<div class="bx"><h5>已检查 Inspected</h5><div style="display:grid;grid-template-columns:1fr 1fr;gap:7px 16px;margin-top:9px">
${checks.map(c => `<p style="display:flex;gap:8px"><span style="color:#4FB477">${ic('check', 14)}</span><span><b>${c[0]}</b> — ${c[1]}</span></p>`).join('')}
</div></div>
<div class="bx acc"><p><b>实现假设</b> ① 网页侧解析 mode/source，未知值退回 help 模式；② App 内不使用内嵌 WebView 的自定义返回，走系统浏览器与原生返回；③ 五个已核验任务的标题在 App 与网页共用同一份内容源；④ 手册链接在新标签页打开并带 rel=noopener；⑤ 回退状态由前端在请求失败/429/超时时渲染，不需要新接口。</p></div>
</div>`
  });
})();
