package dev.ccpocket.daemon.disk

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import com.sun.jna.ptr.IntByReference

/**
 * The working directory of ANOTHER process, on the platforms where an in-process read is possible.
 *
 * On macOS/Linux the caller already has `lsof -d cwd` (see [LiveProcesses]); this exists for Windows,
 * which has no lsof, so the pre-#302 code returned a blanket UNKNOWN for every Windows external-writer
 * probe. That verdict is safe but coarse — with ONE terminal codex anywhere on the machine, every
 * Codex session degraded to read-only/fork-only (issue #302). Reading the target's PEB narrows the
 * probe from "any codex exists" to "a codex in THIS workdir exists".
 *
 * ── The one iron rule for callers ──
 * A null return means "could not determine" and MUST keep the caller's safe fallback (assume held /
 * UNKNOWN). This is a BLIND port (issue #302: no Windows machine was available to test it), so it is
 * built to fail only in the safe direction: every failure path returns null, never a wrong path that
 * could be read as "not here → ABSENT → take over → second writer". A caller may use a NON-null,
 * confidently-mismatched cwd to EXCLUDE a process, but must never turn an unread pid into ABSENT.
 * [selfCheck] lets a Windows CI run prove the read actually works (see LiveProcessesWinCwdSelfCheck).
 */
object ProcessCwd {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** The current directory of [pid], or null if it can't be read (non-Windows, access denied, a bitness
     *  we don't decode, or any error). Null is "unknown", never "no cwd". */
    fun of(pid: Long): String? {
        if (!isWindows) return null
        return runCatching { windowsCwd(pid) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Self-verification for a Windows CI: read OUR OWN pid's cwd and confirm it matches `user.dir`.
     *  Returns null on non-Windows (nothing to check). A false here means the blind PEB offsets are wrong
     *  on the runner — the signal issue #302 asks CI to surface. Path compare is case-insensitive and
     *  separator-normalized (Windows). */
    fun selfCheck(): Boolean? {
        if (!isWindows) return null
        val mine = of(ProcessHandle.current().pid()) ?: return false
        val expected = System.getProperty("user.dir") ?: return false
        fun norm(p: String) = p.replace('/', '\\').trimEnd('\\').lowercase()
        return norm(mine) == norm(expected)
    }

    // ── Windows PEB walk (x64 only; a WOW64/32-bit target returns null → caller stays safe) ──
    //
    // Offsets are the documented x64 layout, stable across NT versions:
    //   PROCESS_BASIC_INFORMATION.PebBaseAddress            @ +0x08
    //   PEB.ProcessParameters                               @ +0x20
    //   RTL_USER_PROCESS_PARAMETERS.CurrentDirectory.DosPath (UNICODE_STRING):
    //       .Length (USHORT)                                @ +0x38
    //       .Buffer (PWSTR)                                 @ +0x40
    private const val PROCESS_QUERY_INFORMATION = 0x0400
    private const val PROCESS_VM_READ = 0x0010
    private const val OFF_PEB_IN_PBI = 0x08L
    private const val OFF_PARAMS_IN_PEB = 0x20L
    private const val OFF_CURDIR_LEN = 0x38L
    private const val OFF_CURDIR_BUF = 0x40L
    private const val PTR = 8 // x64 pointer width

    private interface Kernel32 : com.sun.jna.Library {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun ReadProcessMemory(proc: Pointer, addr: Pointer, buf: Pointer, size: Int, read: IntByReference?): Boolean
        fun CloseHandle(h: Pointer): Boolean
    }

    private interface NtDll : com.sun.jna.Library {
        // NtQueryInformationProcess(handle, ProcessBasicInformation=0, buf, len, retLen) → NTSTATUS
        fun NtQueryInformationProcess(proc: Pointer, cls: Int, buf: Pointer, len: Int, ret: IntByReference?): Int
    }

    private val k32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val nt by lazy { Native.load("ntdll", NtDll::class.java) }

    private fun windowsCwd(pid: Long): String? {
        val handle = k32.OpenProcess(PROCESS_QUERY_INFORMATION or PROCESS_VM_READ, false, pid.toInt()) ?: return null
        try {
            // 1) PROCESS_BASIC_INFORMATION → PebBaseAddress
            val pbi = Memory(48)
            if (nt.NtQueryInformationProcess(handle, 0, pbi, 48, null) != 0) return null
            val pebBase = pbi.getPointer(OFF_PEB_IN_PBI) ?: return null

            // 2) PEB.ProcessParameters
            val params = readPointer(handle, pebBase, OFF_PARAMS_IN_PEB) ?: return null

            // 3) CurrentDirectory.DosPath: length (bytes) + buffer pointer
            val lenBuf = Memory(2)
            if (!k32.ReadProcessMemory(handle, params.share(OFF_CURDIR_LEN), lenBuf, 2, null)) return null
            val len = lenBuf.getShort(0).toInt() and 0xFFFF
            if (len <= 0 || len > 0x8000) return null // sanity bound — a real DosPath is short
            val bufPtr = readPointer(handle, params, OFF_CURDIR_BUF) ?: return null

            // 4) the UTF-16LE path itself
            val strBuf = Memory(len.toLong())
            if (!k32.ReadProcessMemory(handle, bufPtr, strBuf, len, null)) return null
            return String(strBuf.getByteArray(0, len), Charsets.UTF_16LE)
        } finally {
            runCatching { k32.CloseHandle(handle) }
        }
    }

    /** Read a pointer-sized value from the target at [base]+[offset]. */
    private fun readPointer(handle: Pointer, base: Pointer, offset: Long): Pointer? {
        val buf = Memory(PTR.toLong())
        if (!k32.ReadProcessMemory(handle, base.share(offset), buf, PTR, null)) return null
        return buf.getPointer(0)
    }
}
