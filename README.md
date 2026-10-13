# kotlin-runtime-mcp (Phases 1–2)

MCP server that reads a **running native Kotlin Android app** over adb/JDWP.
No app modification. See `IMPLEMENTATION_PLAN.md` (phases 1–9) and
`kotlin-mcp-commandline.md` (spec).

Phase 1 tools: `list_debuggable_apps`, `read_state`, `threads`.
Phase 2 tools: `set_field`, `call_method`.

## Requirements

- JDK 21 (JDI ships in-JDK)
- `adb` on PATH (Android SDK platform-tools)
- One emulator or physical device + a **debug** build in the foreground
  (`assembleDebug`; release/Play builds are refused by design)

## Build

```bat
.\gradlew.bat check        :: compile + unit tests (no device needed)
.\gradlew.bat installDist  :: -> build\install\kotlin-runtime-mcp\
```

## Run (stdio MCP)

```bat
.\build\install\kotlin-runtime-mcp\bin\kotlin-runtime-mcp.bat
```

Register with the MCP client:

```json
{
  "mcpServers": {
    "kotlin-runtime-mcp": {
      "command": "C:\\path\\to\\kotlin-mcp\\build\\install\\kotlin-runtime-mcp\\bin\\kotlin-runtime-mcp.bat",
      "args": []
    }
  }
}
```

## Manual proof (needs device + sample app)

Phase 1:

1. Launch sample debug app with a `ViewModel.count`, leave in foreground.
2. `list_debuggable_apps` → note pid.
3. `read_state {"pid": <pid>}` → stack + locals.
4. `read_state {"pid": <pid>, "className": "...", "field": "count"}` → number matches screen.
5. Install release variant → pid absent from list, tools return `isError:true`.
6. No file in the sample app changed.

Phase 2:

7. `set_field {"pid": <pid>, "className": "...", "field": "count", "value": 42}` → read-back shows 42.
8. `read_state` again → new value visible.
9. `call_method {"pid": <pid>, "className": "...", "method": "load"}` → result or app exception, transport stays up.
10. `set_field` on a `val`/`final` field → clean refusal naming `call_method`.
11. Release variant still refuses everything.
