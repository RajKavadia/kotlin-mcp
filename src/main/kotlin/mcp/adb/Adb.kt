package mcp.adb

import java.net.ServerSocket

/** Raw result of one adb invocation. */
data class AdbResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Runs adb. Injected so unit tests can fake the device without hardware. */
interface AdbRunner {
    fun run(vararg args: String): AdbResult
}

/** Real runner: `adb [-s serial] <args>` on PATH. Never throws; failures are values. */
class RealAdbRunner(
    /** Hard ceiling per adb call: a hung daemon/device must never hang a tool call. */
    private val timeoutSeconds: Long = 30,
    /** Binary name; absolute path in tests to a fake script. */
    private val executable: String = "adb",
) : AdbRunner {
    override fun run(vararg args: String): AdbResult {
        try {
            val proc = ProcessBuilder(listOf(executable) + args.toList())
                .redirectErrorStream(false)
                .start()
            // Drain both pipes on background threads BEFORE waitFor: large outputs
            // (ps -A, dumpsys) fill the OS pipe buffer and would deadlock a
            // wait-then-read sequence.
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread({
                try {
                    proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
                } catch (_: Exception) { }
            }, "adb-stdout")
            val tErr = Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') }
                } catch (_: Exception) { }
            }, "adb-stderr")
            tOut.isDaemon = true
            tErr.isDaemon = true
            tOut.start()
            tErr.start()
            val finished = proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(2000)
            tErr.join(2000)
            if (!finished) {
                try {
                    proc.destroyForcibly()
                } catch (_: Exception) { }
                return AdbResult(
                    -2, "",
                    "adb ${args.firstOrNull() ?: ""} timed out after ${timeoutSeconds}s. " +
                        "The adb daemon or device is unresponsive — run `adb kill-server`, " +
                        "replug/reconnect the device, then retry.",
                )
            }
            return AdbResult(proc.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            return AdbResult(-1, "", "Failed to start adb: ${e.message}. Is adb on PATH?")
        }
    }
}

/** One debuggable process as seen by the agent. */
data class DebuggableApp(val pid: Int, val packageName: String?, val serial: String?)

/**
 * adb discovery layer (Phase 1).
 *
 * Invariants:
 * - Only DEBUGGABLE processes are ever attachable. Primary proof is `adb jdwp`;
 *   when a device's jdwp *listing* wedges (observed on some OEM adbd builds while
 *   `forward jdwp:<pid>` keeps working), discovery falls back to
 *   `ps -A` + per-package `dumpsys` DEBUGGABLE check. The invariant is identical.
 * - Every error names the fix (`adb devices`, cable, USB debugging, release build).
 */
/**
 * Remembers when `adb jdwp` itself fails to answer. A later listing on the
 * same device skips that call. The ps + dumpsys fallback still requires
 * DEBUGGABLE, so a release build stays refused.
 */
class DiscoveryMemory {
    private val skipJdwp = mutableSetOf<String>()

    fun shouldSkipJdwp(serial: String?): Boolean =
        synchronized(skipJdwp) { (serial ?: "") in skipJdwp }

    fun markJdwpUnresponsive(serial: String?) {
        synchronized(skipJdwp) { skipJdwp.add(serial ?: "") }
    }
}

class AdbClient(
    private val runner: AdbRunner,
    private val serial: String? = null,
    /** Short-fuse runner for the jdwp listing: healthy answers in <2s. */
    private val quickRunner: AdbRunner = runner,
    private val memory: DiscoveryMemory = DiscoveryMemory(),
) {

    /** How the last [listDebuggableApps] resolved pids. Shown to the agent. */
    var lastDiscovery: String = "adb jdwp"
        private set

    private fun base(vararg args: String): List<String> =
        if (serial == null) args.toList() else listOf("-s", serial) + args.toList()

    /** `adb devices` state lines, e.g. `emulator-5554\tdevice`. Empty = no device. */
    fun devices(): List<String> {
        val r = runner.run(*base("devices").toTypedArray())
        if (!r.ok) return emptyList()
        return r.stdout.lines()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")).firstOrNull() ?: it }
    }

    /**
     * Debuggable pids, each reverse-mapped to a package via `adb shell ps -A`.
     * A pid with no package row gets `packageName = null` (kept, not dropped).
     * Falls back to ps + dumpsys when the jdwp listing itself is unresponsive.
     */
    fun listDebuggableApps(): Result<List<DebuggableApp>> {
        if (memory.shouldSkipJdwp(serial)) {
            lastDiscovery = "fallback (adb jdwp skipped; the listing timed out earlier on this device)"
            return debuggableFromPs()
        }
        val jdwp = quickRunner.run(*base("jdwp").toTypedArray())
        if (jdwp.ok) {
            lastDiscovery = "adb jdwp"
            return debuggableFromJdwp(jdwp)
        }
        // -2 is the runner's own timeout. Other failures are already fast.
        if (jdwp.exitCode == -2) memory.markJdwpUnresponsive(serial)
        lastDiscovery = "fallback (adb jdwp listing unresponsive: ${shortCause(jdwp)})"
        return debuggableFromPs()
    }

    private fun shortCause(r: AdbResult): String {
        val err = r.stderr.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (err.length > 120) err.take(120) + "…" else err.ifEmpty { "exit ${r.exitCode}" }
    }

    private fun debuggableFromJdwp(jdwp: AdbResult): Result<List<DebuggableApp>> {
        val pids = jdwp.stdout.lines()
            .map { it.trim() }
            .mapNotNull { it.toIntOrNull() }
        if (pids.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "No debuggable processes (`adb jdwp` empty). " +
                        "Launch the *debug* build (assembleDebug) and leave it in the foreground. " +
                        "Release/Play builds never appear here by design."
                )
            )
        }
        val packages = pidToPackage()
        return Result.success(pids.map { DebuggableApp(it, packages[it], serial) })
    }

    /**
     * Fallback discovery: running third-party-looking processes from `ps -A`,
     * each confirmed DEBUGGABLE via `dumpsys package`. Same safety bar as jdwp:
     * a running release build is listed by ps but filtered out here.
     */
    private fun debuggableFromPs(): Result<List<DebuggableApp>> {
        val pidNames = pidToPackage()
        if (pidNames.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Fallback discovery found no processes (`ps -A` failed or empty). " +
                        "Check `adb devices` shows the emulator/phone and USB debugging is on."
                )
            )
        }
        // Dotted names only: skips kernel threads, system daemons, numeric owners.
        val pkgs = pidNames.values.filter { looksLikePackage(it) }.toSet()
        val debuggable = pkgs.filter { isDebuggablePackage(it) }.toSet()
        val apps = pidNames
            .filter { (_, name) -> name in debuggable }
            .map { (pid, name) -> DebuggableApp(pid, name, serial) }
        if (apps.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "No debuggable processes among ${pidNames.size} running processes " +
                        "(${pkgs.size} app packages checked, none DEBUGGABLE). " +
                        "Launch the *debug* build (assembleDebug) and leave it in the foreground."
                )
            )
        }
        return Result.success(apps)
    }

    private fun looksLikePackage(name: String): Boolean =
        '.' in name && name.all { it.isLetterOrDigit() || it == '.' || it == '_' } &&
            !name.startsWith(".") && !name.endsWith(".")

    private fun isDebuggablePackage(pkg: String): Boolean {
        val r = runner.run(*base("shell", "dumpsys", "package", pkg).toTypedArray())
        return r.ok && "DEBUGGABLE" in r.stdout
    }

    /**
     * Exposes `jdwp:<pid>` on localhost. Returns the local TCP port.
     * Caller MUST call [removeForward] in a finally block.
     */
    fun forward(pid: Int): Result<Int> {
        val port = freeLocalPort()
        val r = runner.run(*base("forward", "tcp:$port", "jdwp:$pid").toTypedArray())
        return if (r.ok) Result.success(port)
        else Result.failure(
            IllegalStateException(
                "adb forward failed for pid $pid: ${r.stderr.trim()}. " +
                    "Is the process still alive? Re-run list_debuggable_apps."
            )
        )
    }

    fun removeForward(port: Int) {
        runner.run(*base("forward", "--remove", "tcp:$port").toTypedArray())
    }

    /**
     * Posts a message onto [packageName]'s main looper without navigating.
     * An idle main thread sits in nativePollOnce; a step or breakpoint cannot
     * complete until that poll returns.
     */
    fun wakeLooper(packageName: String) {
        runner.run(*base("shell", "am", "send-trim-memory", packageName, "RUNNING_MODERATE").toTypedArray())
    }

    // -- internals -----------------------------------------------------------

    private fun freeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    /** Parses `adb shell ps -A` (`PID ... NAME` columns) into pid -> package. */
    internal fun pidToPackage(): Map<Int, String> {
        val r = runner.run(*base("shell", "ps", "-A").toTypedArray())
        if (!r.ok) return emptyMap()
        val lines = r.stdout.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyMap()
        val header = lines.first()
        // PID is usually the 2nd column; NAME is the last. Find PID index from header.
        val headerCols = header.trim().split(Regex("\\s+"))
        val pidIdx = headerCols.indexOfFirst { it.equals("PID", ignoreCase = true) }
            .takeIf { it >= 0 } ?: 1
        val out = mutableMapOf<Int, String>()
        for (line in lines.drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size <= pidIdx) continue
            val pid = cols[pidIdx].toIntOrNull() ?: continue
            out[pid] = cols.last()
        }
        return out
    }
}
