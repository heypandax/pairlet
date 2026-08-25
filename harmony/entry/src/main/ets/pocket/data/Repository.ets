import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { image } from '@kit.ImageKit';
import { common } from '@kit.AbilityKit';
import { E2EKeyPair } from '../crypto/E2ECrypto';
import { RelayConnection } from '../net/RelayConnection';
import { Push } from '../net/Push';
import { DEFAULT_RELAY, Pairing } from '../net/Pairing';
import { PairedDaemon, Store } from '../store/Store';
import { DEMO_PLAIN_CHUNKS, DEMO_REPLY_CHUNKS, demoAsk, demoDirs, demoHistory, demoSessions } from './DemoData';
import { mergeHistory } from './TranscriptMerge';
import { ThemeMode, Tok, fmtSize } from '../ui/Theme';
import { b64Encode, b64Len } from '../util/B64';
import {
  compressImage, pickFiles, pickImages, readFileInChunks,
  FILE_CHUNK_RAW, MAX_UPLOAD_BYTES, PickedFileRef,
} from '../media/Attach';
import {
  ChangedFile, DirectoryEntry, FileContent, FileUploaded, HistoryMessage, ImageData, ModelsList,
  PermissionAsk, SessionSummary, SlashCommand,
  controlRegisterPush,
  frameCancelTurn, frameClientCaps, frameCloseSession, frameCommand, frameFetchModels,
  frameFileCancel, frameFileChunk, frameListSessionFiles,
  frameListDirectories, frameListPendingApprovals, frameListSessions, frameModeSwitch, frameOpenSession,
  framePermissionVerdict, frameReadFile, frameRuleClear, frameSendPrompt,
  ja, jb, jo, js, jstr, jnum,
} from '../protocol/Protocol';

/**
 * 应用状态仓库（ArkTS 移植 PocketRepository 的 M1 子集）。
 * V1 状态管理约定：@Observed 类的数组字段一律"改后重赋"（不可变式更新），
 * ChatMessage 自身是 @Observed，流式追加直接改 text 即可驱动 UI。
 */

const TAG = 'ccpocket';

export type Screen = 'pairing' | 'projects' | 'sessions' | 'chat' | 'settings';
export type ConnState = 'connecting' | 'online' | 'offline' | 'auth_failed';

/** 已落盘文件的展示模型（随用户消息气泡渲染，对齐 SentFile）。 */
export class SentFile {
  name: string = '';
  size: number = 0;
  path: string = '';
  mediaType: string = '';
}

@Observed
export class ChatMessage {
  id: string = '';
  role: 'user' | 'assistant' | 'tool' | 'thinking' | 'error' = 'assistant';
  text: string = '';
  tool: string | null = null;
  streaming: boolean = false;
  delivered: boolean = true; // user 消息的 PromptAck 回执
  promptId: string | null = null;
  ok: boolean | null = null;
  images: image.PixelMap[] = []; // user 消息内联图片（本会话内有效；历史回放不带图）
  files: SentFile[] = [];        // user 消息附带的落盘文件 chip
}

// ── 附件（对齐 PocketRepository 的 pendingImages / pendingFiles 语义与常量） ──

export type ImgState = 'compressing' | 'ready' | 'rejected';
export type FileUpState = 'queued' | 'uploading' | 'landed' | 'failed';

const MAX_IMAGES = 4;
const IMAGE_BUDGET_B64 = 240_000; // 一条 SendPrompt 帧内全部图片的 base64 预算
const MAX_FILES = 6;
const UPLOAD_ACK_TIMEOUT_MS = 20_000;
const CHANGED_FILES_TIMEOUT_MS = 15_000; // files.list / file.read 回执守卫（老 daemon 丢帧不答）

@Observed
export class PendingImage {
  id: number = 0;
  bytes: Uint8Array = new Uint8Array(0); // 压缩后的 JPEG
  preview: image.PixelMap | null = null;
  state: ImgState = 'compressing';
}

@Observed
export class PendingFile {
  id: number = 0;
  uri: string = '';
  name: string = '';
  size: number = 0;
  mediaType: string = '';
  state: FileUpState = 'queued';
  progress: number = 0;
  error: string = '';
  captureId: string = '';
  path: string | null = null;   // 落盘后 daemon 回报的 @path
  landedName: string = '';
}

/**
 * session.gone 恢复用的"最近一条 prompt"暂存（对齐 PocketRepository.PromptRetry）。
 * daemon 丢了会话（断线期被回收 / daemon 重启）时，自动 resume 同一会话后把这条 prompt
 * 补发一次；promptId 随行，若原发其实已落地，daemon 会去重（issue #66）。
 */
class PendingPrompt {
  text: string = '';
  images: ImageData[] = [];
  workdir: string = '';
  promptId: string = '';
}

@Observed
export class Repository {
  static readonly instance = new Repository();

  // ── 导航与连接状态 ──
  screen: Screen = 'pairing';
  connState: ConnState = 'connecting';
  statusLine: string = '';
  peerOnline: boolean = false;

  // ── 数据 ──
  paired: PairedDaemon | null = null;
  bindings: PairedDaemon[] = []; // 全部已配对电脑（多机切换）
  directories: DirectoryEntry[] = [];
  sessions: SessionSummary[] = [];
  currentWorkdir: string = '';
  messages: ChatMessage[] = [];
  executing: boolean = false;
  activity: string = ''; // 回合内当前活动（思考中 / 工具名 / 回复中），空 = 静默
  currentAsk: PermissionAsk | null = null;
  selfTestLine: string = ''; // 开发期自检结果（配对页展示）
  demo: boolean = false;     // 免配对演示模式（应用商店审核 + 首次体验预览）

  // ── 设置 ──
  themeMode: ThemeMode = 'dark';
  themeVersion: number = 0;    // 主题切换时 +1，Index 用它整树重建（Tok 是 static，build 时读取）
  allowRules: string[] = [];   // 本会话"始终允许"的规则（本地镜像，对齐 PocketRepository.allowRules）
  defaultMode: string = 'default'; // 新会话默认权限模式（'default'|'auto'|'acceptEdits'|'plan'|'bypassPermissions'）

  // ── 会话权限模式（session.live 同步；显示在聊天顶栏） ──
  mode: string = 'default';
  permissionMode: string = ''; // 后端原生模式（'auto'），空 = 无
  model: string = '';   // session.live 同步的当前模型（空 = daemon 默认）
  effort: string = '';  // 当前思考深度（空 = 默认）
  chatTitle: string = ''; // 聊天顶栏标题（会话标题或首条 prompt 截断，对齐 Android）

  // ── 模型与指令目录（daemon 推送；模型选择层用） ──
  models: string[] = [];          // 标准别名（sonnet/opus/…）
  gatewayModels: string[] = [];   // 网关 /v1/models 原始 id（自定义网关用户看到的就是这些）
  supportedEfforts: string[] = [];// 后端支持的思考深度档位（空 = 用内置兜底表）
  modelsError: string = '';       // models.list 携带的 error（空 = 正常/未拉取）
  slashCommands: string[] = [];   // 本会话可用的斜杠指令名（pocket/commands）

  // ── 上下文占用（session.live 同步；输入栏圈圈） ──
  contextWindow: number = 0; // 0 = 未知（老 daemon 或首回合前）
  contextUsed: number = 0;

  // ── 改动文件与文件查看器 ──
  changedFiles: ChangedFile[] = [];
  changedFilesError: string = ''; // '' = 正常；非空 = 提示文案
  viewerPath: string = '';        // 空 = 查看器关闭
  viewerText: string = '';
  viewerMeta: string = '';        // 截断提示等
  viewerError: string = '';

  // ── 附件（输入条上方的暂存区） ──
  pendingImages: PendingImage[] = [];
  pendingFiles: PendingFile[] = [];

  // ── 内部 ──
  private conn: RelayConnection | null = null;
  private keys: E2EKeyPair | null = null;
  private convoId: string | null = null;
  private currentSessionId: string | null = null;
  private retryTimer = -1;
  private retryDelay = 2000;
  private intentionalDown = false;
  private initialized = false;
  private pendingIdSeq = 1;
  private uploadRunning = false;      // 一次只传一个文件（对齐 Kotlin：chunks 不饿死心跳）
  private uploadCancelCap: string | null = null;
  private fileAckTimer = -1;
  private pendingDefaultMode = '';    // 新会话待应用的默认模式（首个 session.live 后补 mode.switch）
  private changedFilesTimer = -1;
  private viewerTimer = -1;
  private pendingPrompt: PendingPrompt | null = null; // session.gone 恢复：最近一条待补发的 prompt
  private promptResendArmed = false;  // session.gone 已触发重开、等下一个匹配的 session.live 补发

  // ═══ 生命周期 ═══

  async init(): Promise<void> {
    if (this.initialized) return;
    this.initialized = true;
    // 主题：首帧渲染前应用（Tok 是 static，build 时读取）
    this.themeMode = (Store.loadThemeMode() ?? 'dark') as ThemeMode;
    Tok.apply(Tok.resolveDark(this.themeMode));
    this.defaultMode = Store.loadDefaultMode() ?? 'default';
    await Store.migrateToVault(); // 老版本明文敏感数据搬进 Asset Store（幂等）
    this.bindings = Store.loadBindings();
    this.paired = Store.loadActive();
    this.keys = await Store.deviceKeys(); // 提前生成，配对时直接用
    if (this.paired === null) {
      this.screen = 'pairing';
      this.connState = 'offline';
    } else {
      this.screen = 'projects';
      this.connect();
    }
  }

  /** 切换主题：换 token + 整树重建（themeVersion 驱动 Index 的 if 分支销毁重建）。 */
  setThemeMode(mode: ThemeMode): void {
    if (mode === this.themeMode) return;
    this.themeMode = mode;
    Store.saveThemeMode(mode);
    Tok.apply(Tok.resolveDark(mode));
    this.themeVersion = this.themeVersion + 1;
  }

  // ═══ 权限模式 ═══

  /** 当前会话模式的统一显示键（'auto' 优先于枚举 mode）。 */
  currentModeKey(): string {
    return this.permissionMode.length > 0 ? this.permissionMode : this.mode;
  }

  /** 会话内切换权限模式（对齐 switchMode：下个回合生效，绝不打断进行中的回合）。 */
  switchMode(modeKey: string): void {
    const convoId = this.convoId;
    if (convoId === null || modeKey === this.currentModeKey()) return;
    // 乐观更新；daemon 的 session.live 重播报会校正
    this.mode = modeKey === 'auto' ? 'default' : modeKey;
    this.permissionMode = modeKey === 'auto' ? 'auto' : '';
    this.conn?.send(frameModeSwitch(convoId, modeKey));
  }

  /** 新会话默认模式（设置页）；持久化，开新会话时经 OpenSession + 首个 session.live 校正应用。 */
  setDefaultMode(modeKey: string): void {
    this.defaultMode = modeKey;
    Store.saveDefaultMode(modeKey);
  }

  /** 回前台：掉线则立即重连（对齐 OnAppForeground 语义）。 */
  onAppForeground(): void {
    if (this.paired !== null && this.connState !== 'online' && this.connState !== 'connecting') {
      this.retryDelay = 2000;
      this.connect();
    }
  }

  // ═══ 配对 ═══

  async pair(code: string): Promise<string> {
    try {
      const keys = this.keys ?? await Store.deviceKeys();
      this.keys = keys;
      const resolved = await Pairing.resolveCode(code.trim());
      this.paired = await Pairing.redeem(DEFAULT_RELAY, resolved.accountId, resolved.daemonPub, resolved.ticket, keys);
      this.bindings = Store.loadBindings();
      this.screen = 'projects';
      this.connect();
      return '';
    } catch (e) {
      return `配对失败：${(e as Error).message}`;
    }
  }

  /**
   * 扫码/粘贴的 ccpocket://pair?... 链接（对齐 PocketRepository.handlePairUrl）：
   * ?code=123456 短链走 code 路径；完整 ?relay=&acct=&dpk=&ticket= 直接 redeem（relay 以二维码为准）。
   */
  async pairLink(link: string): Promise<string> {
    const codeMatch = link.trim().match(/[?&]code=([0-9]{6})/);
    if (codeMatch !== null) return this.pair(codeMatch[1]);
    const info = Pairing.parseLink(link.trim());
    if (info === null) return '无效的配对链接';
    try {
      const keys = this.keys ?? await Store.deviceKeys();
      this.keys = keys;
      this.paired = await Pairing.redeem(info.relay, info.accountId, info.daemonPub, info.ticket, keys);
      this.bindings = Store.loadBindings();
      this.screen = 'projects';
      this.connect();
      return '';
    } catch (e) {
      return `配对失败：${(e as Error).message}`;
    }
  }

  // ═══ 多电脑 ═══

  /** 切换活跃电脑：断当前连接 → 换绑定 → 清会话状态 → 重连。 */
  switchComputer(accountId: string): void {
    if (this.paired !== null && this.paired.accountId === accountId) return;
    this.intentionalDown = true;
    this.conn?.disconnect();
    this.conn = null;
    Store.setActive(accountId);
    this.bindings = Store.loadBindings();
    this.paired = Store.loadActive();
    this.directories = [];
    this.sessions = [];
    this.messages = [];
    this.convoId = null;
    this.currentSessionId = null;
    this.currentAsk = null;
    this.executing = false;
    this.activity = '';
    this.pendingPrompt = null;
    this.promptResendArmed = false;
    this.clearAttachments();
    this.screen = 'projects';
    this.connect();
  }

  /** 进入"添加电脑"（保留现有绑定，配对页提供返回）。 */
  addComputer(): void {
    this.screen = 'pairing';
  }

  // ═══ 演示模式（免配对；对齐 mobile 端 Demo 模式立意） ═══

  /** 进入演示：示例数据直接喂状态机，全程无 relay / daemon 连接（conn 保持 null）。 */
  enterDemo(): void {
    this.demo = true;
    this.demoTurn = 0;
    this.connState = 'online';
    this.peerOnline = true;
    this.statusLine = '';
    this.directories = demoDirs();
    this.screen = 'projects';
  }

  /** 退出演示：清空演示态，回配对页（或已有绑定时回项目页）。 */
  exitDemo(): void {
    if (!this.demo) return;
    this.demo = false;
    this.directories = [];
    this.sessions = [];
    this.messages = [];
    this.convoId = null;
    this.currentSessionId = null;
    this.currentAsk = null;
    this.executing = false;
    this.activity = '';
    if (this.paired === null) {
      this.screen = 'pairing';
      this.connState = 'offline';
      this.peerOnline = false;
    } else {
      this.screen = 'projects';
    }
  }

  /** 系统返回键：应用内导航优先（chat→sessions→projects），项目页/首配页才放行退出。 */
  handleBack(): boolean {
    if (this.screen === 'chat') {
      this.closeSession();
      return true;
    }
    if (this.screen === 'sessions' || this.screen === 'settings') {
      this.backToProjects();
      return true;
    }
    if (this.screen === 'pairing' && this.bindings.length > 0) {
      this.screen = 'projects';
      return true;
    }
    return false;
  }

  /** 解配当前电脑：还有别的绑定就切过去，没有才回配对页。 */
  unpair(): void {
    if (this.demo) {
      this.exitDemo();
      return;
    }
    this.intentionalDown = true;
    this.conn?.disconnect();
    this.conn = null;
    Store.unpair();
    this.bindings = Store.loadBindings();
    this.paired = Store.loadActive();
    this.directories = [];
    this.sessions = [];
    this.messages = [];
    this.convoId = null;
    this.currentSessionId = null;
    this.pendingPrompt = null;
    this.promptResendArmed = false;
    this.clearAttachments();
    if (this.paired === null) {
      this.screen = 'pairing';
      this.connState = 'offline';
    } else {
      this.screen = 'projects';
      this.connect();
    }
  }

  // ═══ 连接 ═══

  connect(): void {
    const paired = this.paired;
    const keys = this.keys;
    if (paired === null || keys === null) return;
    this.cancelRetry();
    this.intentionalDown = false;
    this.conn?.disconnect();
    this.connState = 'connecting';
    this.statusLine = '连接中…';

    const repo = this;
    const conn = new RelayConnection({
      onReady: () => {
        repo.connState = 'online';
        repo.statusLine = '';
        repo.retryDelay = 2000;
        conn.send(frameClientCaps());
        conn.send(frameListDirectories());
        conn.send(frameListPendingApprovals());
        // 推送 token 注册：每次（重）连重发（对齐 RegisterPush 协议注释）
        Push.token().then((t) => {
          if (t.length > 0 && repo.conn === conn) {
            conn.sendControl(controlRegisterPush('huawei', t));
          }
        });
        // 重连后恢复打开的会话（对齐 restoreAfterReconnect 的最小版）；带当前模式避免重置。
        // 必须同时握有 convoId 与具体 sessionId 才自动 resume（对齐 mobile 的
        // `convo != null && sid != null && wd != null`）：新会话在 session.live 回填 sessionId
        // 前断线重连时，resumeId=null 会被 daemon 当成"开全新会话" → fork（本仓库修过的
        // "冗余会话=fork"老病）。宁可不自动重开、让用户手动重进，也不制造分叉。
        if (repo.convoId !== null && repo.currentSessionId !== null && repo.currentWorkdir.length > 0) {
          conn.send(frameOpenSession(repo.currentWorkdir, repo.currentSessionId, null, repo.mode));
        }
      },
      onFrame: (body) => repo.handleFrame(body),
      onControl: () => {},
      onPeerPresence: (online) => {
        repo.peerOnline = online;
      },
      onDown: (reason) => repo.onDown(reason),
    });
    this.conn = conn;
    // credential / 首连票据都在 Asset Store：异步补齐后再点火（防竞态：连接已被取代则丢弃）
    Store.credentialOf(paired).then(async (cred) => {
      const firstTicket = await Store.takeFirstTicket();
      if (repo.conn !== conn) return;
      conn.connect(paired.withCredential(cred), keys, firstTicket)
        .catch((e: Error) => repo.onDown(`connect:${e.message}`));
    }).catch((e: Error) => repo.onDown(`credential:${(e as Error).message}`));
  }

  private onDown(reason: string): void {
    if (this.intentionalDown) return;
    hilog.info(0x0000, TAG, `link down: ${reason}`);
    if (reason.startsWith('auth:')) {
      // 凭证被吊销/失效——只能重新配对（对齐 RelayAuthException 语义）
      this.connState = 'auth_failed';
      this.statusLine = `配对凭证已失效（${reason}），请重新配对`;
      return;
    }
    this.connState = 'offline';
    this.statusLine = `连接断开（${reason}），重连中…`;
    this.scheduleRetry();
  }

  private scheduleRetry(): void {
    this.cancelRetry();
    const delay = this.retryDelay;
    this.retryDelay = Math.min(this.retryDelay * 2, 30000);
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }

  private cancelRetry(): void {
    if (this.retryTimer >= 0) {
      clearTimeout(this.retryTimer);
      this.retryTimer = -1;
    }
  }

  // ═══ 导航动作 ═══

  openProject(dir: DirectoryEntry): void {
    this.currentWorkdir = dir.path;
    this.sessions = [];
    this.screen = 'sessions';
    if (this.demo) {
      this.sessions = demoSessions(dir.path);
      return;
    }
    this.conn?.send(frameListSessions(dir.path));
  }

  openSession(sessionId: string | null, title: string = ''): void {
    this.currentSessionId = sessionId;
    this.convoId = null;
    this.messages = [];
    this.currentAsk = null;
    this.executing = false;
    this.activity = '';
    this.allowRules = []; // 规则是会话级的（对齐：换会话即清空本地镜像）
    this.slashCommands = []; // 指令目录随会话重推（pocket/commands）
    this.contextWindow = 0;
    this.contextUsed = 0;
    this.changedFiles = [];
    this.changedFilesError = '';
    this.closeFileViewer();
    this.pendingPrompt = null; // 换会话即丢弃上个会话的补发暂存（否则同 workdir 换会话会误投）
    this.promptResendArmed = false;
    this.clearAttachments();
    this.chatTitle = title.length > 0 ? title : '新会话';
    this.model = '';
    this.effort = '';
    // 新会话带默认模式；OpenSession 无原生模式字段，'auto' 经首个 session.live 后补 mode.switch
    this.pendingDefaultMode = sessionId === null ? this.defaultMode : '';
    this.mode = sessionId === null ? (this.defaultMode === 'auto' ? 'default' : this.defaultMode) : 'default';
    this.permissionMode = sessionId === null && this.defaultMode === 'auto' ? 'auto' : '';
    this.screen = 'chat';
    if (this.demo) {
      this.convoId = 'demo-convo';
      this.currentSessionId = sessionId ?? 'demo-live-0001';
      this.messages = demoHistory();
      this.model = 'sonnet';
      this.contextWindow = 200000;
      this.contextUsed = 62000;
      return;
    }
    this.conn?.send(frameOpenSession(this.currentWorkdir, sessionId, null, this.mode));
  }

  backToProjects(): void {
    this.screen = 'projects';
    this.conn?.send(frameListDirectories());
  }

  backToSessions(): void {
    this.screen = 'sessions';
    if (this.currentWorkdir.length > 0) {
      this.conn?.send(frameListSessions(this.currentWorkdir));
    }
  }

  openSettings(): void {
    this.screen = 'settings';
  }

  // ═══ 聊天动作 ═══

  sendPrompt(text: string): void {
    const convoId = this.convoId;
    if (convoId === null) return;
    const ready = this.pendingImages.filter((p: PendingImage) => p.state === 'ready');
    const landed = this.pendingFiles.filter((p: PendingFile) => p.state === 'landed' && p.path !== null);
    if (text.trim().length === 0 && ready.length === 0 && landed.length === 0) return;
    if (this.uploadsBusy()) return; // 还有文件在传——发送键在 UI 层已禁用，这里双保险
    const images = ready.map((p: PendingImage): ImageData => {
      const d: ImageData = { mediaType: 'image/jpeg', base64: b64Encode(p.bytes) };
      return d;
    });
    // 落盘文件以 @path 引用折进 prompt 文本（issue #75 机制：agent 按路径 Read）
    const refs = landed.map((p: PendingFile) => `@${p.path}`).join('\n');
    const outText = landed.length === 0 ? text : (text.trim().length === 0 ? refs : `${text}\n\n${refs}`);
    const promptId = util.generateRandomUUID();
    const m = new ChatMessage();
    m.id = promptId;
    m.role = 'user';
    m.text = text;
    m.delivered = false;
    m.promptId = promptId;
    m.images = ready.map((p: PendingImage) => p.preview).filter((pm: image.PixelMap | null) => pm !== null) as image.PixelMap[];
    m.files = landed.map((p: PendingFile): SentFile => {
      const f = new SentFile();
      f.name = p.landedName.length > 0 ? p.landedName : p.name;
      f.size = p.size;
      f.path = p.path ?? '';
      f.mediaType = p.mediaType;
      return f;
    });
    this.messages = [...this.messages, m];
    this.executing = true;
    if (this.chatTitle === '新会话' && text.trim().length > 0) {
      this.chatTitle = text.trim().substring(0, 48); // 新会话首条 prompt 成为标题（对齐 Android）
    }
    this.pendingImages = [];
    this.pendingFiles = [];
    // session.gone 恢复暂存：这条 prompt 打进了活会话，若 daemon 已把会话回收，收到
    // session.gone 后自动 resume 并补发这份（单次）。首个真·回合证据（chunk/tool/turn.done）到达即清。
    const pending = new PendingPrompt();
    pending.text = outText;
    pending.images = images;
    pending.workdir = this.currentWorkdir;
    pending.promptId = promptId;
    this.pendingPrompt = pending;
    this.promptResendArmed = false;
    if (this.demo) {
      m.delivered = true;
      this.pendingPrompt = null;
      this.demoRespond();
      return;
    }
    this.conn?.send(frameSendPrompt(convoId, outText, promptId, images));
  }

  // ── 演示回合（假流式：thinking → 授权卡（首回合）→ 分片回复） ──

  private demoTurn = 0;

  private demoRespond(): void {
    this.activity = '思考中…';
    const first = this.demoTurn === 0;
    this.demoTurn = this.demoTurn + 1;
    setTimeout(() => {
      if (!this.demo) return;
      if (first) {
        this.currentAsk = demoAsk();
        this.activity = '等待授权';
      } else {
        this.demoStream(DEMO_PLAIN_CHUNKS);
      }
    }, 700);
  }

  private demoStream(chunks: string[]): void {
    const m = new ChatMessage();
    m.id = `demo-r${this.pendingIdSeq++}`;
    m.role = 'assistant';
    m.streaming = true;
    this.activity = '回复中…';
    this.messages = [...this.messages, m];
    let i = 0;
    const step = (): void => {
      if (!this.demo) return;
      if (i < chunks.length) {
        m.text = m.text + chunks[i];
        i = i + 1;
        setTimeout(step, 350);
      } else {
        m.streaming = false;
        this.executing = false;
        this.activity = '';
      }
    };
    setTimeout(step, 300);
  }

  // ═══ 会话指令（/model /effort /compact /simplify /clear——daemon 拦截或透传给 agent） ═══

  /** 发一条斜杠指令：不产生本地用户气泡（对齐 switchViaCommand/clearConversation）。 */
  sendCommand(text: string): void {
    const convoId = this.convoId;
    if (convoId === null) return;
    this.conn?.send(frameCommand(convoId, text));
  }

  /** 切换模型（下个回合生效，不打断进行中的回合；session.live 重播报校正显示）。 */
  switchModel(modelId: string): void {
    if (this.convoId === null || modelId === this.model) return;
    this.model = modelId; // 乐观更新
    this.sendCommand(`/model ${modelId}`);
  }

  /** 思考深度：'' = 恢复默认（/effort default）。 */
  switchEffort(level: string): void {
    if (this.convoId === null || level === this.effort) return;
    this.effort = level;
    this.sendCommand(level.length > 0 ? `/effort ${level}` : '/effort default');
  }

  /** 清空会话：daemon 在原地开新会话（保留模型/深度/模式），本地消息清空（对齐 clearConversation）。 */
  clearConversation(): void {
    this.messages = [];
    this.chatTitle = '新会话';
    this.sendCommand('/clear');
  }

  // ═══ 模型目录 ═══

  /** 拉取模型列表（模型选择层打开时调；daemon 回 pocket/models.list）。 */
  fetchModels(): void {
    if (this.currentWorkdir.length === 0) return;
    this.conn?.send(frameFetchModels(this.currentWorkdir));
  }

  /** 选择层展示用模型表：网关模型优先（自定义网关的真实 id），否则标准别名表。 */
  modelChoices(): string[] {
    if (this.gatewayModels.length > 0) return this.gatewayModels;
    if (this.models.length > 0) return this.models;
    return ['sonnet', 'opus', 'haiku']; // daemon 未答时的兜底（对齐 Android CLAUDE_MODEL_OPTIONS 别名）
  }

  /** 思考深度档位：daemon 下发优先，空则用内置兜底（对齐 LEGACY_EFFORT_OPTIONS）。 */
  effortOptions(): string[] {
    return this.supportedEfforts.length > 0 ? this.supportedEfforts : ['low', 'medium', 'high', 'xhigh', 'max'];
  }

  /** /simplify 是否可用（指令目录里有才显示该行，对齐 Android hasSimplify）。 */
  hasSimplify(): boolean {
    return this.slashCommands.includes('simplify');
  }

  // ═══ 改动文件（pocket/files.list → pocket/files；老 daemon 丢帧，15s 无回执提示升级） ═══

  fetchChangedFiles(): void {
    const sid = this.currentSessionId;
    if (sid === null || sid.length === 0) {
      this.changedFiles = [];
      this.changedFilesError = '新会话还没有可回放的记录';
      return;
    }
    this.changedFiles = [];
    this.changedFilesError = '';
    this.armChangedFilesDeadline(sid);
    this.conn?.send(frameListSessionFiles(this.currentWorkdir, sid));
  }

  /** 打开文件查看器（pocket/file.read → pocket/file.content）。 */
  openChangedFile(path: string): void {
    const sid = this.currentSessionId;
    if (sid === null || sid.length === 0) return;
    this.viewerPath = path;
    this.viewerText = '';
    this.viewerMeta = '加载中…';
    this.viewerError = '';
    this.armViewerDeadline(sid, path);
    this.conn?.send(frameReadFile(this.currentWorkdir, sid, path));
  }

  closeFileViewer(): void {
    this.viewerPath = '';
    this.viewerText = '';
    this.viewerMeta = '';
    this.viewerError = '';
    this.cancelFileDeadlines();
  }

  private armChangedFilesDeadline(sid: string): void {
    if (this.changedFilesTimer >= 0) clearTimeout(this.changedFilesTimer);
    this.changedFilesTimer = setTimeout(() => {
      if (this.changedFiles.length === 0 && this.changedFilesError.length === 0) {
        this.changedFilesError = '电脑端无回执——daemon 版本可能太旧，不支持改动文件';
      }
    }, CHANGED_FILES_TIMEOUT_MS);
  }

  private armViewerDeadline(sid: string, path: string): void {
    if (this.viewerTimer >= 0) clearTimeout(this.viewerTimer);
    this.viewerTimer = setTimeout(() => {
      if (this.viewerPath === path && this.viewerText.length === 0 && this.viewerError.length === 0) {
        this.viewerMeta = '';
        this.viewerError = '电脑端无回执——daemon 版本可能太旧，不支持文件查看';
      }
    }, CHANGED_FILES_TIMEOUT_MS);
  }

  private cancelFileDeadlines(): void {
    if (this.changedFilesTimer >= 0) {
      clearTimeout(this.changedFilesTimer);
      this.changedFilesTimer = -1;
    }
    if (this.viewerTimer >= 0) {
      clearTimeout(this.viewerTimer);
      this.viewerTimer = -1;
    }
  }

  /** 真·回合证据到达（chunk/tool/turn.done）：prompt 已被处理，丢掉补发暂存（对齐 promptEvidence）。 */
  private promptEvidence(): void {
    this.pendingPrompt = null;
  }

  /**
   * 会话域帧 convoId 门（跨会话转录污染防线，对齐 PocketRepository 各 `f.convoId == convoId.value`）：
   * 当前无活跃会话，或帧的 convoId 与当前会话不符，一律忽略。session.live 是"确立 convoId"的帧、
   * 不走此门；列表/审批清单/usage 等非会话域帧同样不受影响。
   */
  private forCurrentConvo(body: Record<string, Object>): boolean {
    const c = this.convoId;
    return c !== null && js(body['convoId']) === c;
  }

  // ═══ 附件 ═══

  uploadsBusy(): boolean {
    return this.pendingFiles.some((p: PendingFile) => p.state === 'uploading' || p.state === 'queued');
  }

  /** 相册选图 → 暂存（压缩中）→ 压缩完按预算标 ready/rejected。 */
  async attachImages(ctx: common.UIAbilityContext): Promise<void> {
    const room = MAX_IMAGES - this.pendingImages.length;
    if (room <= 0) return;
    const uris = await pickImages(ctx, room);
    for (const uri of uris) {
      const p = new PendingImage();
      p.id = this.pendingIdSeq++;
      this.pendingImages = [...this.pendingImages, p];
      compressImage(uri).then((c) => {
        p.bytes = c.bytes;
        p.preview = c.preview;
        p.state = 'ready';
        this.revalidateImageBudget();
      }).catch(() => {
        this.pendingImages = this.pendingImages.filter((x: PendingImage) => x.id !== p.id);
      });
    }
  }

  removePendingImage(id: number): void {
    this.pendingImages = this.pendingImages.filter((p: PendingImage) => p.id !== id);
    this.revalidateImageBudget(); // 释放预算可能让之前超预算的图回到 ready
  }

  /** 累计 base64 超 IMAGE_BUDGET_B64 的图标 rejected（对齐 Kotlin revalidatePending）。 */
  private revalidateImageBudget(): void {
    let used = 0;
    for (const p of this.pendingImages) {
      if (p.state === 'compressing') continue;
      const cost = b64Len(p.bytes.length);
      const ok = used + cost <= IMAGE_BUDGET_B64;
      if (ok) used += cost;
      p.state = ok ? 'ready' : 'rejected';
    }
    this.pendingImages = [...this.pendingImages];
  }

  /** 文档选择 → 暂存排队 → 启动上传泵。 */
  async attachFiles(ctx: common.UIAbilityContext): Promise<void> {
    const room = MAX_FILES - this.pendingFiles.length;
    if (room <= 0) return;
    const picked = await pickFiles(ctx, room);
    for (const ref of picked) {
      const p = new PendingFile();
      p.id = this.pendingIdSeq++;
      p.uri = ref.uri;
      p.name = ref.name;
      p.size = ref.size;
      p.mediaType = ref.mediaType;
      if (ref.size > MAX_UPLOAD_BYTES) {
        p.state = 'failed';
        p.error = '超过 200 MB 上限';
      }
      this.pendingFiles = [...this.pendingFiles, p];
    }
    this.pumpFileUploads();
  }

  removePendingFile(id: number): void {
    const victim = this.pendingFiles.find((p: PendingFile) => p.id === id);
    if (victim === undefined) return;
    if (victim.state === 'uploading') {
      this.uploadCancelCap = victim.captureId;
      const convoId = this.convoId;
      if (convoId !== null && victim.captureId.length > 0) {
        this.conn?.send(frameFileCancel(convoId, victim.captureId));
      }
    }
    this.pendingFiles = this.pendingFiles.filter((p: PendingFile) => p.id !== id);
    this.pumpFileUploads();
  }

  retryPendingFile(id: number): void {
    const p = this.pendingFiles.find((f: PendingFile) => f.id === id);
    if (p === undefined || p.state !== 'failed') return;
    if (p.uri.length === 0) return; // 超限文件没有可重传的来源
    p.state = 'queued';
    p.error = '';
    p.progress = 0;
    p.captureId = '';
    this.pendingFiles = [...this.pendingFiles];
    this.pumpFileUploads();
  }

  /**
   * 上传泵：一次一个文件，768KB 原始字节一块，每块独立 base64（daemon 边收边写盘）。
   * 最后一块发出后等 pocket/file.uploaded 回执，20s 无回执判失败（daemon 太旧不认这个帧）。
   */
  private pumpFileUploads(): void {
    if (this.uploadRunning) return;
    const next = this.pendingFiles.find((p: PendingFile) => p.state === 'queued');
    if (next === undefined) return;
    const convoId = this.convoId;
    if (convoId === null) {
      next.state = 'failed';
      next.error = '没有活跃会话';
      this.pendingFiles = [...this.pendingFiles];
      return;
    }
    this.uploadRunning = true;
    this.uploadCancelCap = null;
    next.state = 'uploading';
    next.captureId = util.generateRandomUUID();
    next.progress = 0;
    this.pendingFiles = [...this.pendingFiles];
    const capId = next.captureId;
    const total = next.size;
    const parts = Math.max(1, Math.ceil(total / FILE_CHUNK_RAW));
    readFileInChunks(next.uri, async (idx: number, bytes: Uint8Array, last: boolean) => {
      if (this.uploadCancelCap === capId) throw new Error('cancelled');
      this.conn?.send(frameFileChunk(
        convoId, capId, idx, last, next.name, next.mediaType,
        b64Encode(bytes), idx === 0 ? total : 0));
      next.progress = (idx + 1) / parts;
      // 每块让出一次事件循环，避免大文件 base64 卡住 UI
      await new Promise<void>((r) => setTimeout(r, 0));
    }).then((ok) => {
      if (this.uploadCancelCap === capId) {
        this.finishUpload(capId);
        return;
      }
      if (!ok) {
        next.state = 'failed';
        next.error = '文件读取失败';
        this.pendingFiles = [...this.pendingFiles];
        this.finishUpload(capId);
        return;
      }
      // 等回执
      this.armFileAckDeadline(capId);
    });
  }

  private armFileAckDeadline(capId: string): void {
    if (this.fileAckTimer >= 0) clearTimeout(this.fileAckTimer);
    this.fileAckTimer = setTimeout(() => {
      const p = this.pendingFiles.find((f: PendingFile) => f.captureId === capId && f.state === 'uploading');
      if (p !== undefined) {
        p.state = 'failed';
        p.error = '电脑端无回执——daemon 版本可能太旧不支持文件上传';
        this.pendingFiles = [...this.pendingFiles];
      }
      this.finishUpload(capId);
    }, UPLOAD_ACK_TIMEOUT_MS);
  }

  private onFileUploaded(f: FileUploaded): void {
    const p = this.pendingFiles.find((x: PendingFile) => x.captureId === f.captureId && x.state === 'uploading');
    if (p === undefined) return; // 已被取消/消费
    if (f.ok && f.path !== null) {
      p.state = 'landed';
      p.progress = 1;
      p.path = f.path;
      p.landedName = f.name ?? p.name;
    } else {
      p.state = 'failed';
      p.error = f.error ?? '上传失败';
    }
    this.pendingFiles = [...this.pendingFiles];
    this.finishUpload(f.captureId);
  }

  /** 当前上传收尾（无论成败），启动队列里的下一个。 */
  private finishUpload(capId: string): void {
    if (this.fileAckTimer >= 0) {
      clearTimeout(this.fileAckTimer);
      this.fileAckTimer = -1;
    }
    if (this.uploadCancelCap === capId) this.uploadCancelCap = null;
    this.uploadRunning = false;
    this.pumpFileUploads();
  }

  /** 会话切换/关闭：丢掉暂存附件（已落盘的副本留在旧会话 inbox，无害）。 */
  private clearAttachments(): void {
    this.uploadCancelCap = this.pendingFiles.find((p: PendingFile) => p.state === 'uploading')?.captureId ?? null;
    if (this.fileAckTimer >= 0) {
      clearTimeout(this.fileAckTimer);
      this.fileAckTimer = -1;
    }
    this.pendingImages = [];
    this.pendingFiles = [];
    this.uploadRunning = false;
  }

  answerAsk(decision: 'allow' | 'deny', remember: boolean = false, answers: Record<string, string> | null = null): void {
    const ask = this.currentAsk;
    const convoId = this.convoId;
    if (ask === null || convoId === null) return;
    // "始终允许"落账：本地镜像规则（对齐 PocketRepository.resolve 的 allowRules.add）
    if (decision === 'allow' && remember && ask.rule !== null && !this.allowRules.includes(ask.rule)) {
      this.allowRules = [...this.allowRules, ask.rule];
    }
    this.currentAsk = null;
    if (this.demo) {
      if (decision === 'allow') {
        const t = new ChatMessage();
        t.id = `demo-t${this.pendingIdSeq++}`;
        t.role = 'tool';
        t.tool = ask.tool;
        t.text = ask.inputPreview;
        this.messages = [...this.messages, t];
        this.demoStream(DEMO_REPLY_CHUNKS);
      } else {
        this.demoStream(['好的，先跳过这一步；需要时再告诉我。']);
      }
      return;
    }
    this.conn?.send(framePermissionVerdict(convoId, ask.askId, decision, remember, answers));
  }

  /** 删除一条"始终允许"规则（设置页）；daemon 侧同步遗忘，下次同类操作重新询问。 */
  clearRule(rule: string): void {
    const convoId = this.convoId;
    this.allowRules = this.allowRules.filter((r: string) => r !== rule);
    if (convoId !== null) {
      this.conn?.send(frameRuleClear(convoId, rule));
    }
  }

  clearAllRules(): void {
    const convoId = this.convoId;
    this.allowRules = [];
    if (convoId !== null) {
      this.conn?.send(frameRuleClear(convoId, null));
    }
  }

  cancelTurn(): void {
    const convoId = this.convoId;
    if (convoId !== null) {
      this.conn?.send(frameCancelTurn(convoId));
    }
  }

  closeSession(): void {
    const convoId = this.convoId;
    if (convoId !== null) {
      this.conn?.send(frameCloseSession(convoId));
    }
    this.convoId = null;
    this.pendingPrompt = null;
    this.promptResendArmed = false;
    this.clearAttachments();
    this.backToSessions();
  }

  // ═══ 入站帧分发 ═══

  private handleFrame(body: Record<string, Object>): void {
    const t = js(body['t']);
    switch (t) {
      case 'pocket/dirs': {
        this.directories = ja(body['entries']).map((v: Object) => DirectoryEntry.from(v));
        break;
      }
      case 'pocket/sessions': {
        const workdir = js(body['workdir']);
        if (workdir === this.currentWorkdir) {
          this.sessions = ja(body['items']).map((v: Object) => SessionSummary.from(v));
        }
        break;
      }
      case 'pocket/session.live': {
        this.convoId = js(body['convoId']);
        const sid = jstr(body['sessionId']);
        if (sid !== null) this.currentSessionId = sid;
        const executing = body['executing'];
        this.executing = typeof executing === 'boolean' ? executing : false;
        // 权限模式同步（可选字段：老 daemon 不带则保持现状）
        const liveMode = jstr(body['mode']);
        if (liveMode !== null) this.mode = liveMode;
        const liveNative = jstr(body['permissionMode']);
        this.permissionMode = liveNative ?? '';
        // 上下文占用（输入栏圈圈；0 = 未知不显示）
        const cw = jnum(body['contextWindow']);
        if (cw !== null) this.contextWindow = cw;
        const cu = jnum(body['contextUsed']);
        if (cu !== null) this.contextUsed = cu;
        // 模型 / 思考深度同步（顶栏副标题与快捷操作面板显示）
        const liveModel = jstr(body['model']);
        if (liveModel !== null) this.model = liveModel;
        const liveEffort = jstr(body['effort']);
        if (liveEffort !== null) this.effort = liveEffort;
        // 新会话默认 'auto'：OpenSession 无原生字段，拿到 convoId 后补发 mode.switch
        if (this.pendingDefaultMode.length > 0) {
          const want = this.pendingDefaultMode;
          this.pendingDefaultMode = '';
          if (want !== this.currentModeKey()) {
            this.conn?.send(frameModeSwitch(this.convoId, want));
          }
        }
        // session.gone 恢复（对齐 PocketRepository 的 SessionLive 分支）：重开已落地，补发那条
        // 打进死会话的 prompt。单次——同 promptId 让 daemon 去重；workdir 匹配防止用户中途
        // 导航走后误投。第二次 session.gone 会走诚实报错分支，绝不成环。
        const retry = this.pendingPrompt;
        if (this.promptResendArmed && retry !== null && retry.workdir === this.currentWorkdir) {
          this.pendingPrompt = null;
          this.promptResendArmed = false;
          this.executing = true;
          this.conn?.send(frameSendPrompt(this.convoId, retry.text, retry.promptId, retry.images));
        }
        break;
      }
      case 'pocket/history': {
        if (!this.forCurrentConvo(body)) break; // 跨会话回放会污染/清空当前 transcript
        const delta = jb(body['delta']);
        const rows = ja(body['messages']).map((v: Object) => this.historyToChat(HistoryMessage.from(v)));
        if (delta) {
          this.messages = [...this.messages, ...rows];
        } else {
          // 锚点合并（TranscriptMerge）：不盲目替换——老 scrollback 不被窗口截掉、
          // 排队气泡原地保留、流式泡泡不被回放打断。
          this.messages = mergeHistory(this.messages, rows);
        }
        break;
      }
      case 'pocket/chunk': {
        if (!this.forCurrentConvo(body)) break; // 刚离开的会话的流尾不能渲染进当前会话
        this.promptEvidence();
        const piece = jo(body['piece']);
        const kind = js(piece['t']);
        const text = js(piece['text']);
        if (kind === 'thinking') {
          this.activity = '思考中';
          this.appendStream('thinking', text);
        } else {
          this.activity = '回复中';
          this.appendStream('assistant', text);
        }
        break;
      }
      case 'pocket/tool': {
        if (!this.forCurrentConvo(body)) break;
        this.promptEvidence();
        const phase = js(body['phase']);
        const tool = js(body['tool']);
        const preview = jstr(body['inputPreview']);
        const output = jstr(body['output']);
        const ok = body['ok'];
        if (phase === 'result' && output !== null) {
          const m = this.newMessage('tool');
          m.tool = tool;
          m.text = output;
          m.ok = typeof ok === 'boolean' ? ok : null;
          this.pushMessage(m);
        } else if (phase === 'start') {
          this.activity = `执行 ${tool}`;
          const m = this.newMessage('tool');
          m.tool = tool;
          m.text = preview ?? '';
          this.pushMessage(m);
        }
        break;
      }
      case 'pocket/ask': {
        if (!this.forCurrentConvo(body)) break; // 别的会话的审批不弹进当前会话
        this.currentAsk = PermissionAsk.from(body);
        break;
      }
      case 'pocket/ask.withdrawn': {
        if (!this.forCurrentConvo(body)) break;
        const askId = js(body['askId']);
        if (this.currentAsk !== null && this.currentAsk.askId === askId) {
          this.currentAsk = null;
        }
        break;
      }
      case 'pocket/turn.done': {
        if (!this.forCurrentConvo(body)) break;
        this.promptEvidence();
        this.executing = false;
        this.activity = '';
        this.finalizeStreaming();
        const error = jstr(body['error']);
        if (error !== null) {
          const m = this.newMessage('error');
          m.text = error;
          this.pushMessage(m);
        }
        break;
      }
      case 'pocket/prompt.ack': {
        if (!this.forCurrentConvo(body)) break;
        const promptId = js(body['promptId']);
        this.messages = this.messages.map((m: ChatMessage) => {
          if (m.promptId === promptId) m.delivered = true;
          return m;
        });
        break;
      }
      case 'pocket/error': {
        // convoId 缺省 = 连接级错误，照单展示；带了别的会话的 convoId 则不属于当前会话，忽略
        const ec = jstr(body['convoId']);
        if (ec !== null && ec !== this.convoId) break;
        const m = this.newMessage('error');
        m.text = js(body['message'], js(body['code'], 'error'));
        this.pushMessage(m);
        break;
      }
      case 'pocket/session.gone': {
        // daemon 丢了这个会话（断线期被回收 / daemon 重启）。对齐 PocketRepository 的 SessionGone：
        // 有待补发的 prompt 才自动 resume——重开落地后由 session.live 分支补发那条 prompt（单次）；
        // 没有 pending prompt（或已重试过一次），诚实报错、别空转。convoId 必须匹配（跨会话不处理）。
        if (!this.forCurrentConvo(body)) break;
        const retry = this.pendingPrompt;
        if (retry !== null && !this.promptResendArmed && this.currentSessionId !== null && this.currentWorkdir.length > 0) {
          this.promptResendArmed = true;
          this.conn?.send(frameOpenSession(this.currentWorkdir, this.currentSessionId, null, this.mode));
        } else {
          this.promptResendArmed = false;
          this.pendingPrompt = null;
          this.executing = false;
          this.activity = '';
          const m = this.newMessage('error');
          m.text = '电脑端已回收该会话——再发一条消息可重新拉起';
          this.pushMessage(m);
        }
        break;
      }
      case 'pocket/daemon.info': {
        const host = jstr(body['hostname']);
        if (host !== null && this.paired !== null) {
          this.paired.hostName = host;
          Store.saveHostName(host);
          this.bindings = Store.loadBindings(); // 切换器里的名字同步更新
        }
        break;
      }
      case 'pocket/file.uploaded': {
        if (!this.forCurrentConvo(body)) break;
        this.onFileUploaded(FileUploaded.from(body));
        break;
      }
      case 'pocket/models.list': {
        const ml = ModelsList.from(body);
        this.models = ml.models;
        this.gatewayModels = ml.gatewayModels;
        this.supportedEfforts = ml.supportedEfforts;
        this.modelsError = ml.error ?? '';
        break;
      }
      case 'pocket/commands': {
        this.slashCommands = ja(body['commands']).map((v: Object) => SlashCommand.from(v).name);
        break;
      }
      case 'pocket/files': {
        if (this.changedFilesTimer >= 0) {
          clearTimeout(this.changedFilesTimer);
          this.changedFilesTimer = -1;
        }
        const err = jstr(body['error']);
        this.changedFiles = ja(body['files']).map((v: Object) => ChangedFile.from(v));
        this.changedFilesError = err ?? (this.changedFiles.length === 0 ? '这个会话还没有改动过文件' : '');
        break;
      }
      case 'pocket/file.content': {
        if (this.viewerTimer >= 0) {
          clearTimeout(this.viewerTimer);
          this.viewerTimer = -1;
        }
        const fc = FileContent.from(body);
        if (this.viewerPath !== fc.path) break; // 迟到的回包（已关/已换文件）
        if (fc.ok && fc.text !== null) {
          this.viewerText = fc.text;
          this.viewerMeta = fc.truncated ? `只显示前 ${Math.floor(fc.text.length / 1024)} KB（共 ${fmtSize(fc.totalBytes)}）` : '';
          this.viewerError = '';
        } else {
          this.viewerText = '';
          this.viewerMeta = '';
          this.viewerError = fc.error ?? '读取失败';
        }
        break;
      }
      default:
        break; // 其余帧（usage / jobs / approvals…）不渲染
    }
  }

  // ═══ 消息流组装 ═══

  private newMessage(role: 'user' | 'assistant' | 'tool' | 'thinking' | 'error'): ChatMessage {
    const m = new ChatMessage();
    m.id = util.generateRandomUUID();
    m.role = role;
    return m;
  }

  private pushMessage(m: ChatMessage): void {
    this.finalizeStreaming();
    this.messages = [...this.messages, m];
  }

  /** 流式追加：连续的同类流片并入同一条气泡（对齐 Kotlin 的流式组装语义）。 */
  private appendStream(role: 'assistant' | 'thinking', text: string): void {
    const last = this.messages.length > 0 ? this.messages[this.messages.length - 1] : null;
    if (last !== null && last.streaming && last.role === role) {
      last.text = last.text + text;
    } else {
      const m = this.newMessage(role);
      m.text = text;
      m.streaming = true;
      this.messages = [...this.messages, m];
    }
  }

  private finalizeStreaming(): void {
    let changed = false;
    const next = this.messages.map((m: ChatMessage) => {
      if (m.streaming) {
        m.streaming = false;
        changed = true;
      }
      return m;
    });
    if (changed) this.messages = [...next];
  }

  private historyToChat(h: HistoryMessage): ChatMessage {
    const m = new ChatMessage();
    m.id = util.generateRandomUUID();
    m.role = h.error ? 'error' : h.role;
    m.text = h.text;
    m.tool = h.tool;
    m.ok = h.ok;
    return m;
  }
}
