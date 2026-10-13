package mcp.session

import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.IllegalConnectorArgumentsException
import mcp.adb.AdbClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Owns the Phase 1 safety invariant:
 *  1. Attach ONLY to a pid listed by `adb jdwp` (checked fresh per call).
 *  2. Resume the VM + dispose + remove the forward on EVERY exit path.
 *  3. One attach at a time (ART rejects a second debugger anyway).
 */
class SessionManager(
    private val adbFor: (serial: String?) -> AdbClient = { AdbClient(mcp.adb.RealAdbRunner(), it) },
    private val attachTimeoutMs: Int = 10_000,
) {
    private val lock = ReentrantLock()

    /**
     * Forward + attach + suspend main thread, run [block], then always resume/dispose.
     * Returns the block's result, or a failure naming the fix. Never throws.
     */
    fun <T> withMainThread(
        pid: Int,
        serial: String?,
        block: (vm: VirtualMachine, main: ThreadReference, packageName: String?) -> T,
    ): Result<T> {
        if (!lock.tryLock(30, TimeUnit.SECONDS)) {
            return Result.failure(
                IllegalStateException(
                    "Another tool call is attached to the app. Wait for it to finish and retry. " +
                        "(Only one debugger may attach at a time.)"
                )
            )
        }
        try {
            return withMainThreadLocked(pid, serial, block)
        } finally {
            lock.unlock()
        }
    }

    private fun <T> withMainThreadLocked(
        pid: Int,
        serial: String?,
        block: (VirtualMachine, ThreadReference, String?) -> T,
    ): Result<T> {
        val adb = adbFor(serial)

        // 1. Discover: pid must be on the allowlist RIGHT NOW.
        val apps = adb.listDebuggableApps().getOrElse { return Result.failure(it) }
        if (apps.none { it.pid == pid }) {
            return Result.failure(
                IllegalStateException(
                    "pid $pid is not debuggable right now (`adb jdwp` does not list it). " +
                        "Re-run list_debuggable_apps and use a fresh pid. " +
                        "Release/Play builds never appear here by design."
                )
            )
        }

        // 2. Forward localhost only.
        val port = adb.forward(pid).getOrElse { return Result.failure(it) }
        var vm: VirtualMachine? = null
        var main: ThreadReference? = null
        var suspended = false
        try {
            // 3. Attach via JDI SocketAttach.
            vm = attach("127.0.0.1", port).getOrElse { return Result.failure(it) }

            // 4. Suspend the whole VM so frames and locals are readable.
            //    invokeMethod is illegal on this pause (it needs a debugger event).
            //    call_method converts to an event suspend before it invokes.
            try {
                vm.suspend()
                suspended = true
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException("Could not suspend VM for pid $pid: ${e.message}")
                )
            }
            val threads = try {
                vm.allThreads()
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException("Could not list threads on pid $pid: ${e.message}")
                )
            }
            main = threads.firstOrNull { matchesMain(it) }
                ?: return Result.failure(
                    IllegalStateException(
                        "No main/looper thread on pid $pid. Threads: " +
                            threads.take(20).joinToString { "'${it.name()}'" } +
                            ". Is the app in the foreground?"
                    )
                )

            // 5. Act.
            return try {
                Result.success(block(vm, main, apps.first { it.pid == pid }.packageName))
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Action failed: ${e.message}"))
            }
        } finally {
            // 6. Resume on EVERY exit path, then dispose + remove forward.
            try {
                if (suspended) vm?.resume()
            } catch (_: Exception) { /* resume must not mask the real result */ }
            try {
                vm?.dispose()
            } catch (_: Exception) { }
            try {
                adb.removeForward(port)
            } catch (_: Exception) { }
        }
    }

    private fun attach(host: String, port: Int): Result<VirtualMachine> {
        return try {
            val vmm = com.sun.jdi.Bootstrap.virtualMachineManager()
            val connector = vmm.attachingConnectors()
                .firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
                ?: return Result.failure(
                    IllegalStateException("JDI SocketAttach connector missing from this JDK.")
                )
            val args = connector.defaultArguments()
            args["hostname"]?.setValue(host)
            args["port"]?.setValue(port.toString())
            args["timeout"]?.setValue(attachTimeoutMs.toString())
            Result.success(connector.attach(args))
        } catch (e: IllegalConnectorArgumentsException) {
            Result.failure(IllegalStateException("Bad JDWP attach args: ${e.message}"))
        } catch (e: java.io.IOException) {
            Result.failure(
                IllegalStateException(
                    "JDWP attach to 127.0.0.1:$port failed: ${e.message}. " +
                        "If Android Studio is attached, detach it first (only one debugger at a time)."
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("JDWP attach failed: ${e.message}"))
        }
    }

    companion object {
        /** "main" first; else the looper thread typical of Android UI processes. */
        fun matchesMain(t: ThreadReference): Boolean = try {
            t.name() == "main"
        } catch (_: Exception) {
            false
        }
    }
}
