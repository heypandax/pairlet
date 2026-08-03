/**
 * cc-pocket wire 协议（ArkTS 移植）。
 * 对齐 protocol/ 模块：Envelope{id, ts, to, body}，body 多态由 "t" 判别（PocketJson 的
 * classDiscriminator="t"）。encodeDefaults=true / explicitNulls=false —— 发送端只放非默认、
 * 非 null 字段即可被 Kotlin 端正确解码（缺的走声明默认值，unknown keys 被忽略）。
 *
 * 接收端只做宽松解析（JSON.parse + 字段提取），天然等价 ignoreUnknownKeys。
 */

// ── JSON 字段提取小工具（宽松解析的核心） ──

type Json = Record<string, Object>;

export function jo(v: Object | null | undefined): Json {
  return (v ?? {}) as Json;
}

export function js(v: Object | null | undefined, d: string = ''): string {
  return typeof v === 'string' ? v : d;
}

export function jstr(v: Object | null | undefined): string | null {
  return typeof v === 'string' ? v : null;
}

export function jn(v: Object | null | undefined, d: number = 0): number {
  return typeof v === 'number' ? v : d;
}

export function jnum(v: Object | null | undefined): number | null {
  return typeof v === 'number' ? v : null;
}

export function jb(v: Object | null | undefined, d: boolean = false): boolean {
  return typeof v === 'boolean' ? v : d;
}

export function ja(v: Object | null | undefined): Object[] {
  return Array.isArray(v) ? (v as Object[]) : [];
}

// ── Envelope ──

export interface EnvelopeOut {
  id: string;
  ts: number;
  to: string; // "PEER" | "RELAY"
  body: Object;
}

export function envelopeJson(body: Object, to: string = 'PEER', id: string = '0'): string {
  const env: EnvelopeOut = { id, ts: 0, to, body };
  return JSON.stringify(env);
}

export function parseEnvelope(text: string): Json | null {
  try {
    const env = jo(JSON.parse(text));
    return jo(env['body']);
  } catch (e) {
    return null;
  }
}

// ── phone -> daemon 帧构造（M1 子集；t 值逐字对齐 Messages.kt 的 @SerialName） ──
// ArkTS 要求对象字面量对应显式声明的 interface（arkts-no-untyped-obj-literals），
// 每种帧一个 interface；可选字段对应 Kotlin 端的默认值（缺省即默认）。

export interface FrameClientCaps {
  t: string;
  supportsAgents: string[];
}

export interface FrameDirsList {
  t: string;
  root?: string;
}

export interface FrameApprovalsList {
  t: string;
}

export interface FrameSessionsList {
  t: string;
  workdir: string;
}

export interface FrameSessionOpen {
  t: string;
  workdir: string;
  mode: string;
  takeOver: boolean;
  agent: string;
  resumeId?: string;
  lastEventSeq?: number;
}

export interface ImageData {
  mediaType: string;
  base64: string;
}

export interface FramePrompt {
  t: string;
  convoId: string;
  text: string;
  images: ImageData[];
  promptId: string;
}

/** pocket/file.chunk（issue #90）：分块上传，daemon 落盘到 <cwd>/.ccpocket/inbox/。 */
export interface FrameFileChunk {
  t: string;
  convoId: string;
  captureId: string;
  idx: number;
  last: boolean;
  name: string;
  mediaType: string;
  base64: string;
  totalBytes: number;
}

export interface FrameFileCancel {
  t: string;
  convoId: string;
  captureId: string;
}

export interface FrameVerdict {
  t: string;
  convoId: string;
  askId: string;
  decision: string;
  remember: boolean;
  answers?: Record<string, string>;
  response?: string;
}

export interface FrameTurnCancel {
  t: string;
  convoId: string;
}

export interface FrameSessionClose {
  t: string;
  convoId: string;
  force: boolean;
}

export interface FrameRuleClear {
  t: string;
  convoId: string;
  rule?: string; // 缺省 = 清空全部（对齐 ClearAllowRule(rule: String? = null)）
}

export interface FrameModeSwitch {
  t: string;
  convoId: string;
  mode: string;            // PermissionMode 枚举值：default/acceptEdits/plan/bypassPermissions
  permissionMode?: string; // 后端原生模式（Claude "auto"）；与 mode=default 搭配
}

export interface BodyDeviceHello {
  t: string;
  deviceId: string;
  secret: string;
  protoV: number;
}

export interface BodyRegisterPush {
  t: string;
  platform: string;
  token: string;
}

export interface BodyPing {
  t: string;
  ts: number;
}

export function frameClientCaps(): FrameClientCaps {
  return { t: 'pocket/client.caps', supportsAgents: ['opencode'] };
}

export function frameListDirectories(root: string | null = null): FrameDirsList {
  const f: FrameDirsList = { t: 'pocket/dirs.list' };
  if (root !== null) f.root = root;
  return f;
}

export function frameListPendingApprovals(): FrameApprovalsList {
  return { t: 'pocket/approvals.list' };
}

export function frameListSessions(workdir: string): FrameSessionsList {
  return { t: 'pocket/sessions.list', workdir };
}

export function frameOpenSession(workdir: string, resumeId: string | null, lastEventSeq: number | null = null, mode: string = 'default'): FrameSessionOpen {
  const f: FrameSessionOpen = {
    t: 'pocket/session.open',
    workdir,
    mode,
    takeOver: false,
    agent: 'claude',
  };
  if (resumeId !== null) f.resumeId = resumeId;
  if (lastEventSeq !== null) f.lastEventSeq = lastEventSeq;
  return f;
}

export function frameSendPrompt(convoId: string, text: string, promptId: string, images: ImageData[] = []): FramePrompt {
  return { t: 'pocket/prompt', convoId, text, images, promptId };
}

export function frameFileChunk(
  convoId: string,
  captureId: string,
  idx: number,
  last: boolean,
  name: string,
  mediaType: string,
  base64: string,
  totalBytes: number,
): FrameFileChunk {
  return { t: 'pocket/file.chunk', convoId, captureId, idx, last, name, mediaType, base64, totalBytes };
}

export function frameFileCancel(convoId: string, captureId: string): FrameFileCancel {
  return { t: 'pocket/file.cancel', convoId, captureId };
}

/** pocket/file.uploaded 回执（ToPhone）。 */
export class FileUploaded {
  captureId: string = '';
  ok: boolean = true;
  path: string | null = null;
  name: string | null = null;
  size: number = 0;
  error: string | null = null;

  static from(body: Record<string, Object>): FileUploaded {
    const f = new FileUploaded();
    f.captureId = js(body['captureId']);
    f.ok = jb(body['ok'], true);
    f.path = jstr(body['path']);
    f.name = jstr(body['name']);
    f.size = jn(body['size'], 0);
    f.error = jstr(body['error']);
    return f;
  }
}

export function framePermissionVerdict(
  convoId: string,
  askId: string,
  decision: 'allow' | 'deny',
  remember: boolean = false,
  answers: Record<string, string> | null = null,
  response: string | null = null,
): FrameVerdict {
  const f: FrameVerdict = { t: 'pocket/verdict', convoId, askId, decision, remember };
  if (answers !== null) f.answers = answers;
  if (response !== null) f.response = response;
  return f;
}

export function frameCancelTurn(convoId: string): FrameTurnCancel {
  return { t: 'pocket/turn.cancel', convoId };
}

export function frameCloseSession(convoId: string, force: boolean = false): FrameSessionClose {
  return { t: 'pocket/session.close', convoId, force };
}

/** 删除本会话一条"始终允许"规则；rule 传 null 清空全部（对齐 ClearAllowRule）。 */
export function frameRuleClear(convoId: string, rule: string | null): FrameRuleClear {
  const f: FrameRuleClear = { t: 'pocket/rule.clear', convoId };
  if (rule !== null) f.rule = rule;
  return f;
}

/**
 * 切换权限模式（对齐 SwitchMode）。modeKey 是客户端统一键：
 * 'default'|'acceptEdits'|'plan'|'bypassPermissions' 直出枚举；
 * 'auto' = mode:default + permissionMode:'auto'（Claude 分类器驱动的原生模式）。
 */
export function frameModeSwitch(convoId: string, modeKey: string): FrameModeSwitch {
  if (modeKey === 'auto') {
    return { t: 'pocket/mode.switch', convoId, mode: 'default', permissionMode: 'auto' };
  }
  return { t: 'pocket/mode.switch', convoId, mode: modeKey };
}

// ── relay 控制面（TEXT 帧，明文；t 值对齐 Messages.kt ToRelay 部分） ──

export function controlDeviceHello(deviceId: string, secret: string): string {
  const body: BodyDeviceHello = { t: 'pocket/device.hello', deviceId, secret, protoV: 1 };
  return envelopeJson(body, 'RELAY', 'h');
}

export function controlPing(ts: number): string {
  const body: BodyPing = { t: 'pocket/ping', ts };
  return envelopeJson(body, 'RELAY', 'h');
}

/** 推送 token 注册（对齐 RegisterPush：空 token = 注销；每次重连重发）。 */
export function controlRegisterPush(platform: string, token: string): string {
  const body: BodyRegisterPush = { t: 'pocket/push.register', platform, token };
  return envelopeJson(body, 'RELAY', 'h');
}

// ── daemon -> phone 入站模型（M1 渲染子集） ──

export class DirectoryEntry {
  path = '';
  name = '';
  isDir = false;
  hasSessions = false;
  open = false;
  executing = false;
  activeSessionId: string | null = null;
  activeSessionTitle: string | null = null;

  static from(v: Object): DirectoryEntry {
    const o = jo(v);
    const e = new DirectoryEntry();
    e.path = js(o['path']);
    e.name = js(o['name']);
    e.isDir = jb(o['isDir']);
    e.hasSessions = jb(o['hasSessions']);
    e.open = jb(o['open']);
    e.executing = jb(o['executing']);
    e.activeSessionId = jstr(o['activeSessionId']);
    e.activeSessionTitle = jstr(o['activeSessionTitle']);
    return e;
  }
}

export class SessionSummary {
  sessionId = '';
  title = '';
  firstPrompt = '';
  messageCount = 0;
  cwd = '';
  lastModified = 0;
  live = false;
  busy = false;
  agent: string | null = null;

  static from(v: Object): SessionSummary {
    const o = jo(v);
    const s = new SessionSummary();
    s.sessionId = js(o['sessionId']);
    s.title = js(o['title']);
    s.firstPrompt = js(o['firstPrompt']);
    s.messageCount = jn(o['messageCount']);
    s.cwd = js(o['cwd']);
    s.lastModified = jn(o['lastModified']);
    s.live = jb(o['live']);
    s.busy = jb(o['busy']);
    s.agent = jstr(o['agent']);
    return s;
  }
}

export class AskOption {
  label = '';
  description: string | null = null;

  static from(v: Object): AskOption {
    const o = jo(v);
    const r = new AskOption();
    r.label = js(o['label']);
    r.description = jstr(o['description']);
    return r;
  }
}

export class AskQuestion {
  question = '';
  header: string | null = null;
  multiSelect = false;
  options: AskOption[] = [];

  static from(v: Object): AskQuestion {
    const o = jo(v);
    const q = new AskQuestion();
    q.question = js(o['question']);
    q.header = jstr(o['header']);
    q.multiSelect = jb(o['multiSelect']);
    q.options = ja(o['options']).map((x: Object) => AskOption.from(x));
    return q;
  }
}

export class PermissionAsk {
  convoId = '';
  askId = '';
  tool = '';
  inputPreview = '';
  title = '';
  rule: string | null = null;
  danger = false;
  dangerNote: string | null = null;
  questions: AskQuestion[] | null = null;
  neverRemember = false;
  timeoutSec: number | null = null;

  get isQuestion(): boolean {
    return this.questions !== null;
  }

  static from(body: Json): PermissionAsk {
    const a = new PermissionAsk();
    a.convoId = js(body['convoId']);
    a.askId = js(body['askId']);
    a.tool = js(body['tool']);
    a.inputPreview = js(body['inputPreview']);
    a.title = js(body['title']);
    a.rule = jstr(body['rule']);
    a.danger = jb(body['danger']);
    a.dangerNote = jstr(body['dangerNote']);
    const qs = body['questions'];
    a.questions = Array.isArray(qs) ? ja(qs).map((x: Object) => AskQuestion.from(x)) : null;
    a.neverRemember = jb(body['neverRemember']);
    a.timeoutSec = jnum(body['timeoutSec']);
    return a;
  }
}

/** HistoryMessage.role / 流式行的渲染角色。 */
export type ChatRole = 'user' | 'assistant' | 'tool' | 'thinking' | 'error';

export class HistoryMessage {
  role: ChatRole = 'assistant';
  text = '';
  tool: string | null = null;
  error = false;
  ok: boolean | null = null;
  output: string | null = null;

  static from(v: Object): HistoryMessage {
    const o = jo(v);
    const m = new HistoryMessage();
    const r = js(o['role']);
    m.role = (r === 'user' || r === 'tool') ? r : 'assistant';
    m.text = js(o['text']);
    m.tool = jstr(o['tool']);
    m.error = jb(o['error']);
    const ok = o['ok'];
    m.ok = typeof ok === 'boolean' ? ok : null;
    m.output = jstr(o['output']);
    return m;
  }
}
