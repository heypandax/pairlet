import assert from "node:assert/strict";
import test from "node:test";

import {
  EN_ESCALATION,
  ZH_ESCALATION,
  revisionFor,
  sanitizeFinalText,
  shouldSuppressDelivery,
} from "./index.js";

test("accepts exact escalation templates", () => {
  assert.equal(revisionFor(ZH_ESCALATION), null);
  assert.equal(revisionFor(EN_ESCALATION), null);
});

test("revises escalation with internal preface", () => {
  const revision = revisionFor(`I've now exhaustively searched the codebase.\n\n${ZH_ESCALATION}`);
  assert.equal(revision?.key, "cc-pocket-support-exact-zh-escalation");
});

test("revises standalone internal narration", () => {
  const revision = revisionFor("I now have sufficient evidence. Here is the answer.");
  assert.equal(revision?.key, "cc-pocket-support-remove-internal-narration");
});

test("revises Chinese retrieval narration", () => {
  const revision = revisionFor("这次搜到了手册中的正式隐私说明，回答更准确：不会记录正文。");
  assert.equal(revision?.key, "cc-pocket-support-remove-internal-narration");
});

test("revises an overstated credential fallback", () => {
  const revision = revisionFor(
    "如果隔离准备失败，daemon 会直接使用你终端 Claude 的凭据。",
  );
  assert.equal(revision?.key, "cc-pocket-support-preserve-credential-fallback-boundary");
});

test("accepts the qualified credential fallback", () => {
  assert.equal(
    revisionFor(
      "如果隔离准备失败，daemon 会告警并以未隔离模式运行；此时不能假定 CC Pocket 的登录或退出与终端 Claude 登录相互独立。",
    ),
    null,
  );
});

test("suppresses tool diagnostics only", () => {
  assert.equal(shouldSuppressDelivery("⚠️ 🛠️ `rg` failed"), true);
  assert.equal(shouldSuppressDelivery("请先打开 CC Pocket。"), false);
});

test("deterministically strips prose around escalation", () => {
  assert.equal(
    sanitizeFinalText(`Internal search narration.\n\n${ZH_ESCALATION}`),
    ZH_ESCALATION,
  );
  assert.equal(sanitizeFinalText(`Preface\n\n${EN_ESCALATION}`), EN_ESCALATION);
  assert.equal(sanitizeFinalText("正常的用户答案。"), "正常的用户答案。");
});
