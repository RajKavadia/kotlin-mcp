package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mcp.tools.CallResult
import mcp.tools.TOOL_DEFS
import mcp.tools.ToolEnv
import mcp.tools.handleTool

const val SERVER_NAME = "kotlin-runtime-mcp"
const val SERVER_VERSION = "0.1.0"
const val PROTOCOL_VERSION = "2024-11-05"

val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * MCP stdio entry (bin/ layer). Newline-delimited JSON-RPC 2.0 on stdin/stdout.
 * NEVER logs to stdout — stdout is the protocol. Diagnostics go to stderr.
 */
fun main() {
    val env = ToolEnv()
    System.err.println("$SERVER_NAME $SERVER_VERSION listening on stdio")
    val stdin = System.`in`.bufferedReader()
    val stdout = System.out.bufferedWriter()
    while (true) {
        val line = try {
            stdin.readLine() ?: break
        } catch (_: Exception) {
            break
        }
        if (line.isBlank()) continue
        val reply = try {
            dispatch(env, line)
        } catch (e: Exception) {
            errorReply(null, -32603, "Internal error: ${e.message}")
        }
        if (reply != null) {
            stdout.write(json.encodeToString(JsonElement.serializer(), reply))
            stdout.newLine()
            stdout.flush()
        }
    }
}

internal fun dispatch(env: ToolEnv, line: String): JsonObject? {
    val msg = try {
        json.parseToJsonElement(line).jsonObject
    } catch (_: Exception) {
        return errorReply(null, -32700, "Parse error: not JSON.")
    }
    if (msg["jsonrpc"]?.jsonPrimitive?.content != "2.0") {
        return errorReply(idOf(msg), -32600, "Invalid Request: want JSON-RPC 2.0.")
    }
    val id = idOf(msg) // null = notification, no reply
    val method = msg["method"]?.jsonPrimitive?.content
        ?: return errorReply(id, -32600, "Invalid Request: missing method.")
    val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())

    // Notifications: no response, ever.
    if (id == null) return null

    return when (method) {
        "initialize" -> okReply(
            id, buildJsonObject {
                put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put("serverInfo", buildJsonObject {
                    put("name", JsonPrimitive(SERVER_NAME))
                    put("version", JsonPrimitive(SERVER_VERSION))
                })
            }
        )
        "ping" -> okReply(id, buildJsonObject {})
        "tools/list" -> okReply(
            id, buildJsonObject {
                put("tools", JsonArray(TOOL_DEFS.map { t ->
                    buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("inputSchema", json.parseToJsonElement(t.inputSchema))
                    }
                }))
            }
        )
        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.content
                ?: return errorReply(id, -32602, "tools/call needs `name`.")
            val args = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
            val r: CallResult = handleTool(env, name, args)
            okReply(
                id, buildJsonObject {
                    put("content", JsonArray(listOf(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(r.text))
                    })))
                    if (r.isError) put("isError", JsonPrimitive(true))
                }
            )
        }
        else -> errorReply(id, -32601, "Method not found: $method")
    }
}

private fun idOf(msg: JsonObject): JsonElement? {
    val id = msg["id"] ?: return null
    return if (id is JsonNull) null else id
}

private fun okReply(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    put("id", id)
    put("result", result)
}

private fun errorReply(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    if (id != null) put("id", id)
    put("error", buildJsonObject {
        put("code", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    })
}

internal fun JsonElement.intLenient(): Int? {
    val p = this as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.content.toIntOrNull()
}
