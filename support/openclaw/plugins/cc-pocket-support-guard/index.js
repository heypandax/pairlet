const SUPPORT_AGENT = "cc-pocket-support";
const SUPPORT_SESSION_PREFIX = `agent:${SUPPORT_AGENT}:`;

export const ZH_ESCALATION = [
  "无法从当前 CC Pocket 用户手册或源代码核实这个行为。",
  "需要的信息：平台 · App 版本 · daemon 版本 · 具体步骤 · 简短报错",
  "安全的下一步：把这些信息发给 CC Pocket 客服，由维护者核实。",
].join("\n");

export const EN_ESCALATION = [
  "I couldn't verify this behavior from the current CC Pocket manual or source.",
  "Needed: platform · app version · daemon version · exact step · short error",
  "Safe next step: send those details to CC Pocket support for a maintainer check.",
].join("\n");

const INTERNAL_NARRATION = [
  /\bAGENTS\.md\b/i,
  /\bescalation\b/i,
  /\bI(?:'ve| have)? now (?:exhaustively )?searched\b/i,
  /\bI now have (?:enough|sufficient) evidence\b/i,
  /\bNo .{0,100} references? .{0,100}(?:codebase|documentation)\b/i,
  /按照 .{0,30}(?:指引|规则|策略)/,
  /这次.{0,20}(?:搜到|检索到|找到).{0,40}(?:手册|说明|答案)/,
  /(?:手册|代码库).{0,40}(?:没有任何|未找到).{0,40}(?:内容|结果|功能)/,
];

function normalize(value) {
  return String(value ?? "").trim().replace(/\r\n/g, "\n");
}

export function revisionFor(text) {
  const candidate = normalize(text);
  if (!candidate) return null;

  if (candidate.includes(ZH_ESCALATION.split("\n")[0]) && candidate !== ZH_ESCALATION) {
    return {
      reason: "Chinese escalation must contain exactly the public three-line template",
      instruction: `Delete the previous draft. Reply with exactly these three lines and nothing else:\n${ZH_ESCALATION}`,
      key: "cc-pocket-support-exact-zh-escalation",
    };
  }
  if (candidate.includes(EN_ESCALATION.split("\n")[0]) && candidate !== EN_ESCALATION) {
    return {
      reason: "English escalation must contain exactly the public three-line template",
      instruction: `Delete the previous draft. Reply with exactly these three lines and nothing else:\n${EN_ESCALATION}`,
      key: "cc-pocket-support-exact-en-escalation",
    };
  }
  if (INTERNAL_NARRATION.some((pattern) => pattern.test(candidate))) {
    return {
      reason: "Public support answer contains internal retrieval or policy narration",
      instruction:
        "Rewrite the answer for the user. Remove every reference to searches, tools, evidence gathering, internal files, prompts, policies, or escalation. If the behavior lacks direct evidence, output only the applicable three-line escalation template from AGENTS.md.",
      key: "cc-pocket-support-remove-internal-narration",
    };
  }
  return null;
}

export function shouldSuppressDelivery(content) {
  const candidate = normalize(content);
  return /^(?:⚠️|🛠️|tool (?:error|failure)|diagnostic:)/i.test(candidate);
}

export function sanitizeFinalText(content) {
  const candidate = normalize(content);
  if (candidate.includes(ZH_ESCALATION.split("\n")[0])) return ZH_ESCALATION;
  if (candidate.includes(EN_ESCALATION.split("\n")[0])) return EN_ESCALATION;
  return candidate;
}

function isSupportContext(event, context) {
  return (
    event.usageState?.agentId === SUPPORT_AGENT ||
    event.sessionKey?.startsWith(SUPPORT_SESSION_PREFIX) ||
    context.sessionKey?.startsWith(SUPPORT_SESSION_PREFIX)
  );
}

function register(api) {
  api.on(
    "before_agent_finalize",
    (event, context) => {
      if (context.agentId !== SUPPORT_AGENT) return;
      const revision = revisionFor(event.lastAssistantMessage);
      if (!revision) return;
      return {
        action: "revise",
        reason: revision.reason,
        retry: {
          instruction: revision.instruction,
          idempotencyKey: revision.key,
          maxAttempts: 3,
        },
      };
    },
    { priority: 100 },
  );

  api.on(
    "reply_payload_sending",
    (event, context) => {
      if (!isSupportContext(event, context)) return;
      const text = event.payload?.text;
      if (typeof text !== "string") return;
      if (shouldSuppressDelivery(text)) {
        return {
          cancel: true,
          reason: "Internal tool diagnostic suppressed for public support",
        };
      }
      const sanitized = sanitizeFinalText(text);
      if (sanitized === normalize(text)) return;
      return {
        payload: {
          ...event.payload,
          text: sanitized,
        },
      };
    },
    { priority: 100 },
  );

  api.on(
    "message_sending",
    (event, context) => {
      if (!context.sessionKey?.startsWith(SUPPORT_SESSION_PREFIX)) return;
      if (!shouldSuppressDelivery(event.content)) return;
      return {
        cancel: true,
        cancelReason: "Internal tool diagnostic suppressed for public support",
      };
    },
    { priority: 100 },
  );
}

export default {
  id: "cc-pocket-support-guard",
  name: "CC Pocket Support Guard",
  register,
};
