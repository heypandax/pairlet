# Provenance & clean-room statement

cc-pocket is an **independent, clean-room implementation**. It interoperates with Anthropic's public `claude` CLI surface; it does not derive from any other project's source.

## What we deliberately interoperate with (interface facts, not original expression)

These are public Anthropic interface/format facts, required for interoperability, and are used freely:

- The `claude` CLI flags and their semantics (`-p`, `--output-format stream-json`, `--input-format stream-json`, `--permission-prompt-tool stdio`, `--replay-user-messages`, `--verbose`, `--permission-mode`, `--resume`, `--model`, `--append-system-prompt`).
- The `stream-json` event shapes emitted on stdout (`system`/`assistant`/`user`/`result`/`control_request`/`control_cancel_request`).
- The tool-permission handshake (`control_request` with `subtype:"can_use_tool"` ⇄ `control_response` with `behavior:"allow"|"deny"`).
- The on-disk transcript layout `~/.claude/projects/<dir-key>/<sessionId>.jsonl` and its `dir-key` encoding.

Common techniques are likewise used freely: setting the child's `cwd` to switch working directory, killing the process tree cleanly, filtering `CLAUDECODE` from the child environment, deriving a session title from the `.jsonl` head, and the general "device ↔ cloud-relay ↔ outbound-WS daemon" three-tier topology.

Legally, interoperability formats are protected under merger doctrine / *Google v. Oracle*; a different language plus clean-room separation reduces residual risk further.

## What is original to cc-pocket

The **wire protocol is entirely our own**: `Envelope`, the sealed `Frame` hierarchy, every `pocket/*` message name, and the `classDiscriminator = "t"` serialization. These intentionally differ from any reference project's naming and structure.

## Working rule for implementers

All Anthropic-schema knowledge lives **only** in the daemon's `StreamParser`/`StreamWire` and `PermissionBridge`. When implementing, consult: this repo's design/mapping notes, the official Anthropic docs, and your own `claude --help` output. Do **not** read another project's non-protocol source while writing cc-pocket code.
