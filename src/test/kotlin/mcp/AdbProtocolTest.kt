package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mcp.adb.AdbClient
import mcp.adb.AdbResult
import mcp.adb.AdbRunner
import mcp.session.SessionManager
import mcp.tools.TOOL_DEFS
import mcp.tools.ToolEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Shared offline env: adb reports no devices, so discovery fails cleanly. */
fun testEnv() = ToolEnv(
    adbFor = {
        AdbClient(
            FakeAdb(mapOf("devices" to AdbResult(0, "List of devices attached\n\n", ""))),
            it,
        )
    },
)

class AdbParsingTest {

    @Test
    fun `devices parses states and skips header`() {
        val runner = FakeAdb(mapOf(
            "devices" to AdbResult(0, "List of devices attached\nemulator-5554\tdevice\nABCD1234\tunauthorized\nXYZ\t\toffline\n", ""),
        ))
        val adb = AdbClient(runner)
        assertEquals(listOf("emulator-5554", "ABCD1234", "XYZ"), adb.devices())
    }

    @Test
    fun `devices empty when adb fails`() {
        val adb = AdbClient(FakeAdb(mapOf("devices" to AdbResult(1, "", "daemon dead"))))
        assertEquals(emptyList<String>(), adb.devices())
    }

    @Test
    fun `jdwp ignores garbage lines keeps numeric pids`() {
        val runner = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "123\nnot-a-pid\n\n 456 \n", ""),
            "shell ps -A" to AdbResult(0, "", ""),
        ))
        val apps = AdbClient(runner).listDebuggableApps().getOrThrow()
        assertEquals(listOf(123, 456), apps.map { it.pid })
    }

    @Test
    fun `jdwp failure names the fix`() {
        val adb = AdbClient(FakeAdb(mapOf("jdwp" to AdbResult(1, "", "error: no devices/emulators found"))))
        val err = adb.listDebuggableApps().exceptionOrNull()!!.message!!
        assertTrue(err.contains("adb devices"), err)
    }

    @Test
    fun `ps parses pid to package on last column`() {
        val ps = "USER PID PPID NAME\nroot 1 0 init\napp 777 1 com.sample.app\n"
        val runner = FakeAdb(mapOf(
            "jdwp" to AdbResult(0, "777\n", ""),
            "shell ps -A" to AdbResult(0, ps, ""),
        ))
        val apps = AdbClient(runner).listDebuggableApps().getOrThrow()
        assertEquals("com.sample.app", apps.single().packageName)
    }

    @Test
    fun `forward returns port and removeForward cleans it`() {
        val seen = mutableListOf<List<String>>()
        var capturedPort = -1
        val runner = object : AdbRunner {
            override fun run(vararg args: String): AdbResult {
                seen.add(args.toList())
                return AdbResult(0, "", "")
            }
        }
        val adb = AdbClient(runner)
        val port = adb.forward(4242).getOrThrow()
        assertTrue(port in 1..65535, "port=$port")
        capturedPort = port
        assertTrue(seen.any { it == listOf("forward", "tcp:$port", "jdwp:4242") }, seen.toString())
        adb.removeForward(port)
        assertTrue(seen.any { it == listOf("forward", "--remove", "tcp:$capturedPort") }, seen.toString())
    }

    @Test
    fun `forward failure carries pid and recovery hint`() {
        val runner = object : AdbRunner {
            override fun run(vararg args: String) = AdbResult(1, "", "already forwarded")
        }
        val err = AdbClient(runner).forward(4242).exceptionOrNull()!!.message!!
        assertTrue(err.contains("4242") && err.contains("list_debuggable_apps"), err)
    }

    @Test
    fun `serial prefixes every adb call including forward`() {
        val seen = mutableListOf<List<String>>()
        val runner = object : AdbRunner {
            override fun run(vararg args: String): AdbResult {
                seen.add(args.toList())
                return AdbResult(0, "", "")
            }
        }
        val adb = AdbClient(runner, "SER123")
        adb.forward(1)
        assertTrue(seen.all { it[0] == "-s" && it[1] == "SER123" }, seen.toString())
    }
}

class SessionRulesTest {

    @Test
    fun `main thread matches name main only`() {
        assertTrue(SessionManager.matchesMain(fakeThread("main")))
        assertTrue(!SessionManager.matchesMain(fakeThread("JDWP Event Helper")))
    }

    @Test
    fun `main match is false when name unreadable`() {
        assertTrue(!SessionManager.matchesMain(fakeThread("x", failName = true)))
    }
}

class ToolDefsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `tool names are unique and cover phases 1-2`() {
        val names = TOOL_DEFS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(
            setOf("list_debuggable_apps", "read_state", "threads", "set_field", "call_method"),
            names.toSet(),
        )
    }

    @Test
    fun `every schema is valid JSON with required where promised`() {
        for (t in TOOL_DEFS) {
            val schema = json.parseToJsonElement(t.inputSchema).jsonObject
            assertEquals("object", schema["type"]?.jsonPrimitive?.content, t.name)
            assertNotNull(schema["properties"]?.jsonObject, t.name)
        }
        val byName = TOOL_DEFS.associateBy { it.name }
        fun required(name: String) =
            json.parseToJsonElement(byName.getValue(name).inputSchema)
                .jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertTrue(required("read_state").contains("pid"))
        assertTrue(required("threads").contains("pid"))
        assertTrue(required("set_field").containsAll(listOf("pid", "className", "field", "value")))
        assertTrue(required("call_method").containsAll(listOf("pid", "className", "method")))
        assertEquals(emptyList<String>(), required("list_debuggable_apps"))
    }

    @Test
    fun `every tool has a non-blank description for the agent`() {
        for (t in TOOL_DEFS) assertTrue(t.description.length > 20, t.name)
    }

    @Test
    fun `dispatch tools-list mirrors registry`() {
        val reply = dispatch(testEnv(), """{"jsonrpc":"2.0","id":9,"method":"tools/list"}""")
        assertNotNull(reply)
        val names = reply!!["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(TOOL_DEFS.map { it.name }, names)
    }

    @Test
    fun `dispatch rejects bad json unknown method and missing method`() {
        val notJson = dispatch(testEnv(), "this is not json!!")
        assertEquals(-32700, notJson!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val unknown = dispatch(testEnv(), """{"jsonrpc":"2.0","id":1,"method":"made_up"}""")
        assertEquals(-32601, unknown!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val noMethod = dispatch(testEnv(), """{"jsonrpc":"2.0","id":2,"params":{}}""")
        assertEquals(-32600, noMethod!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `dispatch notification gets no reply`() {
        assertNull(dispatch(testEnv(), """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `dispatch initialize and ping`() {
        val init = dispatch(
            testEnv(),
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val result = init!!["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("kotlin-runtime-mcp", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val ping = dispatch(testEnv(), """{"jsonrpc":"2.0","id":2,"method":"ping"}""")
        assertEquals(JsonObject(emptyMap()), ping!!["result"]!!.jsonObject)
    }

    @Test
    fun `dispatch tools-call surfaces isError content not transport error`() {
        val reply = dispatch(
            testEnv(),
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_state","arguments":{}}}""",
        )
        val result = reply!!["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("pid"))
    }
}
