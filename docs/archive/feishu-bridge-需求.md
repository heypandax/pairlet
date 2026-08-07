# 飞书 Bridge 需求上下文（→ ccpocket）

> 场景：用户在飞书群里通过 Bridge 驱动 Claude Code 会话。
> 本文档只客观陈述上下文与期望结果，具体方案由 ccpocket 侧设计。

---

## 需求 1：一次授权，跑完全程

### 当前情况
- Claude Code 默认权限模式下，工具调用会逐条弹权限确认，**Bash 命令尤其是每条都问**。
- 在飞书这种异步 IM 通道里，每弹一次确认就需要用户回一条消息，一个多步任务会被打断很多次，实际不可用。

### 相关事实（Claude Code 权限模式）
- 存在多种权限模式：`default`（按规则逐条弹）、`acceptEdits`（只自动放行文件 Edit/Write，**Bash 等仍弹框**）、`plan`（只读）、`bypassPermissions`（跳过所有权限检查）。
- 模式可通过 CLI（`--permission-mode`）、SDK（`permissionMode`）或 `settings.json` 设置；SDK 另有 `canUseTool` 审批回调、`permissions.allow/deny` 白名单机制。
- 关键点：`acceptEdits` 不能消除 Bash 的逐步询问；只有 `bypassPermissions` 或显式白名单能让 Bash 类命令不再逐条弹。

### 期望结果
- 发起会话时**一次性授权**，之后该会话内的工具调用不再逐步询问，任务跑到完成。

---

## 需求 2：引用消息未被带入会话

### 当前情况
- 用户在群里**引用（回复）了某条已有消息**，再由这条引用发起 Claude 会话。
- 发起会话时，**被引用消息的内容没有一起带过来**，Claude 只收到用户本次新输入的那句话，看不到用户实际想处理的原文。

### 相关事实（飞书开放平台）
- `im.message.receive_v1` 事件中，回复/引用消息的 `message` 对象带有 **`parent_id`**（被回复消息的 message_id）与 **`root_id`**（话题根）；普通新消息没有这两个字段。
- 被引用消息的正文可通过消息查询接口（按 message_id）获取，`body.content` 需按 `msg_type` 解析（text / post / 图片 / 文件 等）。
- 拉取历史消息需要机器人对该会话有读消息权限（`im:message` 相关 scope）。

### 期望结果
- 当发起消息是引用/回复时，把**被引用的那条消息**内容作为上下文带入会话。
- **同时带上所在话题**（顺 `root_id`）；有需要时再进一步查询关联消息。
- 让 Claude 同时看到「用户引用/话题的原文 + 用户本次输入」。
