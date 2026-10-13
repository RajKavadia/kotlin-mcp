package mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mcp.adb.AdbClient
import mcp.adb.AdbResult
import mcp.adb.AdbRunner
import mcp.adb.DiscoveryMemory
import mcp.adb.RealAdbRunner
import mcp.mutate.argsList
import mcp.tools.ToolEnv
import mcp.tools.handleTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fake adb: scripted outputs per command line. No hardware needed. */
class FakeAdb(private val handlers: Map<String, AdbResult>) : AdbRunner {
    val seen = mutableListOf<List<String>>()
    override fun run(vararg args: String): AdbResult {
        seen.add(args.toList())
        val key = args.toList().let { argv ->
            // strip optional "-s serial" prefix for matching
            if (argv.size >= 2 && argv[0] == "-s") argv.drop(2).joinToString(" ") else argv.joinToString(" ")
        }
        return handlers[key] ?: AdbResult(1, "", "unexpected adb call: $key")
    }
}

private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
}

class AdbClientTest {

    private val psOutput = """
        USER           PID   PPID     VSZ    RSS WCHAN            ADDR S NAME
        u0_a123      12341   1234 1234567 123456 0                   0 S com.example.app
        u0_a124      12342   1234 1234567 123456 0                   0 S com.other.app
    """.trimIndent()

    @Test
    fun `jdwp pids map to packages`() {
        val adb = AdbClient(FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "12341\n12342\n", ""),
            "shell ps -A" to AdbResult(0, psOutput, ""),
        )))
        val apps = adb.listDebuggableApps().getOrThrow()
        assertEquals(2, apps.size)
        assertEquals(12341, apps[0].pid)
        assertEquals("com.example.app", apps[0].packageName)
    }

    @Test
    fun `empty jdwp is a clear error not an empty list`() {
        val adb = AdbClient(FakeAdb(mapOf("jdwp" to AdbResult(0, "\n", ""))))
        val r = adb.listDebuggableApps()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("debug"))
    }

    @Test
    fun `wakeLooper posts trim-memory for the package`() {
        val fake = FakeAdb(mapOf(
            "shell am send-trim-memory com.example.app RUNNING_MODERATE" to AdbResult(0, "", ""),
        ))
        AdbClient(fake).wakeLooper("com.example.app")
        assertEquals(
            listOf("shell", "am", "send-trim-memory", "com.example.app", "RUNNING_MODERATE"),
            fake.seen.single(),
        )
    }

    @Test
    fun `pid with no ps row keeps null package`() {
        val adb = AdbClient(FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "99999\n", ""),
            "shell ps -A" to AdbResult(0, psOutput, ""),
        )))
        val apps = adb.listDebuggableApps().getOrThrow()
        assertEquals(1, apps.size)
        assertEquals(null, apps[0].packageName)
    }

    @Test
    fun `serial is forwarded to adb`() {
        val fake = FakeAdb(mapOf(
            "devices" to AdbResult(0, "List of devices attached\nemulator-5554\tdevice\n", ""),
            "jdwp" to AdbResult(0, "12341\n", ""),
            "shell ps -A" to AdbResult(0, psOutput, ""),
        ))
        val adb = AdbClient(fake, "emulator-5554")
        adb.devices()
        adb.listDebuggableApps()
        assertTrue(fake.seen.all { it[0] == "-s" && it[1] == "emulator-5554" })
    }

    @Test
    fun `large output does not deadlock the runner`() {
        // 3000 lines >> OS pipe buffer: wait-then-read would wedge forever.
        val dir = java.nio.file.Files.createTempDirectory("fake-adb")
        val script = dir.resolve("adb-large.bat").toFile()
        script.writeText("@echo off\r\nfor /L %%i in (1,1,3000) do echo line-%%i-data-padding-0123456789\r\n")
        val r = RealAdbRunner(timeoutSeconds = 20, executable = script.absolutePath)
            .run("shell", "ps", "-A")
        assertTrue(r.ok, r.stderr)
        assertEquals(3000, r.stdout.lines().filter { it.isNotBlank() }.size)
    }

    private val psMixed = """
        USER           PID   PPID     VSZ    RSS WCHAN            ADDR S NAME
        root             1      0  123456   1234 0                   0 S init
        u0_a123      12341   1234 1234567 123456 0                   0 S com.example.app
        u0_a124      12342   1234 1234567 123456 0                   0 S com.release.app
    """.trimIndent()

    private fun fallbackFakes(jdwpResult: AdbResult) = FakeAdb(mapOf(
        "jdwp" to jdwpResult,
        "shell ps -A" to AdbResult(0, psMixed, ""),
        "shell dumpsys package com.example.app" to AdbResult(0, "flags=[ DEBUGGABLE HAS_CODE ]", ""),
        "shell dumpsys package com.release.app" to AdbResult(0, "flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]", ""),
    ))

    @Test
    fun `wedged jdwp listing falls back to ps plus dumpsys`() {
        val adb = AdbClient(fallbackFakes(AdbResult(-2, "", "adb jdwp timed out after 10s.")))
        val apps = adb.listDebuggableApps().getOrThrow()
        assertEquals(1, apps.size)
        assertEquals(12341, apps[0].pid)
        assertEquals("com.example.app", apps[0].packageName)
        assertTrue(adb.lastDiscovery.startsWith("fallback"), adb.lastDiscovery)
    }

    @Test
    fun `failed jdwp also falls back`() {
        val adb = AdbClient(fallbackFakes(AdbResult(1, "", "protocol fault")))
        val apps = adb.listDebuggableApps().getOrThrow()
        assertEquals(listOf(12341), apps.map { it.pid })
    }

    @Test
    fun `fallback with no debuggable app is a clear error`() {
        val fake = FakeAdb(mapOf(
            "jdwp" to AdbResult(-2, "", "timeout"),
            "shell ps -A" to AdbResult(0, psMixed, ""),
            "shell dumpsys package com.example.app" to AdbResult(0, "flags=[ HAS_CODE ]", ""),
            "shell dumpsys package com.release.app" to AdbResult(0, "flags=[ HAS_CODE ]", ""),
        ))
        val err = AdbClient(fake).listDebuggableApps().exceptionOrNull()!!.message!!
        assertTrue(err.contains("none DEBUGGABLE") || err.contains("No debuggable"), err)
    }

    @Test
    fun `fallback with dead adb is a devices hint`() {
        val fake = FakeAdb(mapOf(
            "jdwp" to AdbResult(-2, "", "timeout"),
            "shell ps -A" to AdbResult(1, "", "error: no devices/emulators found"),
        ))
        val err = AdbClient(fake).listDebuggableApps().exceptionOrNull()!!.message!!
        assertTrue(err.contains("adb devices"), err)
    }

    @Test
    fun `a jdwp timeout is skipped on the next listing`() {
        val memory = DiscoveryMemory()
        val fake = fallbackFakes(AdbResult(-2, "", "adb jdwp timed out after 2s."))
        AdbClient(fake, memory = memory).listDebuggableApps().getOrThrow()
        AdbClient(fake, memory = memory).listDebuggableApps().getOrThrow()
        assertEquals(1, fake.seen.count { it == listOf("jdwp") })
    }

    @Test
    fun `a fast jdwp failure is still tried next time`() {
        val memory = DiscoveryMemory()
        val fake = fallbackFakes(AdbResult(1, "", "protocol fault"))
        AdbClient(fake, memory = memory).listDebuggableApps().getOrThrow()
        AdbClient(fake, memory = memory).listDebuggableApps().getOrThrow()
        assertEquals(2, fake.seen.count { it == listOf("jdwp") })
    }

    @Test
    fun `jdwp listing goes to the quick runner`() {
        val quick = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "7\n", ""),
            "shell ps -A" to AdbResult(0, psOutput, ""),
        ))
        val slow = FakeAdb(mapOf("shell ps -A" to AdbResult(0, psOutput, "")))
        val apps = AdbClient(slow, null, quick).listDebuggableApps().getOrThrow()
        assertEquals(listOf(7), apps.map { it.pid })
        assertTrue(quick.seen.any { it == listOf("jdwp") })
        assertTrue(slow.seen.none { it == listOf("jdwp") })
    }
}

class ToolsTest {

    private fun envWith(fake: FakeAdb, serial: String? = null) =
        ToolEnv(adbFor = { AdbClient(fake, serial ?: it) })

    @Test
    fun `unknown tool is error value not throw`() {
        val r = handleTool(envWith(FakeAdb(emptyMap())), "nope", JsonObject(emptyMap()))
        assertTrue(r.isError)
        assertTrue(r.text.contains("Unknown tool"))
    }

    @Test
    fun `list with no devices names adb devices`() {
        val fake = FakeAdb(mapOf("devices" to AdbResult(0, "List of devices attached\n\n", "")))
        val r = handleTool(envWith(fake), "list_debuggable_apps", JsonObject(emptyMap()))
        assertTrue(r.isError)
        assertTrue(r.text.contains("adb devices"))
    }

    @Test
    fun `read_state without pid is actionable`() {
        val r = handleTool(envWith(FakeAdb(emptyMap())), "read_state", JsonObject(emptyMap()))
        assertTrue(r.isError)
        assertTrue(r.text.contains("pid"))
    }

    @Test
    fun `read_state accepts pid as string`() {
        // pid "12341" must parse; then discovery fails cleanly (no real device).
        val fake = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "55555\n", ""),
            "shell ps -A" to AdbResult(0, "", ""),
        ))
        val r = handleTool(envWith(fake), "read_state", args("pid" to "12341"))
        assertTrue(r.isError)
        // pid parsed fine (12341), but not on allowlist (only 55555) -> allowlist error, not arg error.
        assertTrue(r.text.contains("not debuggable") || r.text.contains("12341"))
    }

    @Test
    fun `className without field is rejected`() {
        val r = handleTool(
            envWith(FakeAdb(emptyMap())), "read_state",
            args("pid" to "1", "className" to "com.Foo")
        )
        assertTrue(r.isError)
        assertTrue(r.text.contains("both"))
    }

    @Test
    fun `threads without pid is actionable`() {
        val r = handleTool(envWith(FakeAdb(emptyMap())), "threads", JsonObject(emptyMap()))
        assertTrue(r.isError)
        assertFalse(r.text.isBlank())
    }
}

class MutateToolsTest {

    private fun envWith(fake: FakeAdb, serial: String? = null) =
        ToolEnv(adbFor = { AdbClient(fake, serial ?: it) })

    @Test
    fun `set_field requires pid className field value`() {
        val env = envWith(FakeAdb(emptyMap()))
        var r = handleTool(env, "set_field", JsonObject(emptyMap()))
        assertTrue(r.isError && r.text.contains("pid"))
        r = handleTool(env, "set_field", args("pid" to "1"))
        assertTrue(r.isError && r.text.contains("className"))
        r = handleTool(env, "set_field", args("pid" to "1", "className" to "C"))
        assertTrue(r.isError && r.text.contains("field"))
        r = handleTool(env, "set_field", args("pid" to "1", "className" to "C", "field" to "f"))
        assertTrue(r.isError && r.text.contains("value"))
    }

    @Test
    fun `call_method requires pid className method`() {
        val env = envWith(FakeAdb(emptyMap()))
        var r = handleTool(env, "call_method", JsonObject(emptyMap()))
        assertTrue(r.isError && r.text.contains("pid"))
        r = handleTool(env, "call_method", args("pid" to "1"))
        assertTrue(r.isError && r.text.contains("className"))
        r = handleTool(env, "call_method", args("pid" to "1", "className" to "C"))
        assertTrue(r.isError && r.text.contains("method"))
    }

    @Test
    fun `set_field unknown pid hits allowlist not arg error`() {
        val fake = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "55555\n", ""),
            "shell ps -A" to AdbResult(0, "", ""),
        ))
        val a = buildJsonObject {
            put("pid", JsonPrimitive(12341))
            put("className", JsonPrimitive("com.Foo"))
            put("field", JsonPrimitive("count"))
            put("value", JsonPrimitive(7))
        }
        val r = handleTool(envWith(fake), "set_field", a)
        assertTrue(r.isError)
        assertTrue(r.text.contains("not debuggable") || r.text.contains("12341"), r.text)
    }

    @Test
    fun `call_method unknown pid hits allowlist`() {
        val fake = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "55555\n", ""),
            "shell ps -A" to AdbResult(0, "", ""),
        ))
        val a = buildJsonObject {
            put("pid", JsonPrimitive("12341"))
            put("className", JsonPrimitive("com.Foo"))
            put("method", JsonPrimitive("load"))
        }
        val r = handleTool(envWith(fake), "call_method", a)
        assertTrue(r.isError)
        assertTrue(r.text.contains("not debuggable") || r.text.contains("12341"), r.text)
    }

    @Test
    fun `argsList is empty when missing and passes arrays through`() {
        assertEquals(0, argsList(null).size)
        assertEquals(0, argsList(JsonPrimitive(5)).size)
        val arr = buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive(1)) }
        assertEquals(2, argsList(arr).size)
    }
}
