package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.DaemonInfo

/** Both transports build their own [DaemonInfo]; the memo fields are stamped from one place so they cannot drift. */
fun DaemonInfo.withVoiceMemo(capability: MemoCapability): DaemonInfo = copy(
    voiceMemoVersion = capability.version,
    voiceMemoAgents = capability.agents,
    voiceMemoStatus = capability.status,
)
