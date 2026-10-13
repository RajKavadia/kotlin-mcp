# Agents — kotlin-runtime-mcp

Use this server to read and change a **running debug** Kotlin/Android process over adb and JDWP. Do not modify the app to make a tool call work. A release or Play build is refused on purpose.

The server attaches for one tool call, then resumes the process and disconnects. It never leaves the app suspended.

## Before any tool

1. `adb devices` shows one authorized device.
2. The app is a debug build (`assembleDebug`) and is in the foreground.
3. Android Studio is not attached. Only one debugger can attach.
4. Call `list_debuggable_apps` and use that pid. A pid from an earlier session is stale after the process restarts.

`pid` may be a number or a numeric string. Pass `serial` only when more than one device is attached.

## Tools

| Tool | Use it to |
| --- | --- |
| `list_debuggable_apps` | Get pid and package. Call this first. |
| `read_state` | Read the main-thread stack and, with `className` + `field`, one field. |
| `threads` | See who is stuck when the UI is frozen. Read-only. |
| `set_field` | Write one existing field, then read it back. |
| `call_method` | Run one method that already exists in the loaded app. |

There is no `navigate_to_screen`, `watch_field`, or `screenshot` tool yet. Those are planned in `IMPLEMENTATION_PLAN.md`. Open a screen by calling a method the app already has.

## Change a value, then show it

`set_field` changes memory only. The screen updates when the app paints that field.

On the sample app, `com.example.counterproof`:

1. `set_field` on `com.example.counterproof.CounterViewModel`, field `count`.
2. `call_method` on `com.example.counterproof.HomeFragment`, method `onResume`, no arguments.

`onResume` copies `count` into the label. `CounterViewModel.load` returns `"count=<n>"` and does not paint. `increment` and `assignCount` change `count` and also do not paint.

A successful method call ends with `(event-suspended)`. That means ART rejected a plain pause, the server stopped the main thread on a looper event, and then the method ran.

## Pick the right instance

Several live objects are common (three buttons, one view model).

- Pass `instanceField` and `instanceValue` on **this** call. Example: click Settings with class `androidx.appcompat.widget.AppCompatButton`, method `performClick`, `instanceField` `mText`, `instanceValue` `Open settings`.
- An object id from a previous tool call is not valid. Each call attaches and detaches, and JDWP ids are not reused.
- If you omit both and more than one instance exists, the tool lists ids. Those ids are only good for a follow-up in the same server process if the process has not been restarted; prefer `instanceField`.

`call_method` arguments are strings, numbers, booleans, or null. It cannot take an `Intent`, `Bundle`, or `Uri`. That is why `startActivity` and `navigate(Uri)` are not direct calls.

## Sample app map

Package `com.example.counterproof`. Home is a fragment. Settings is a separate activity, not a navigation route.

| Goal | Call |
| --- | --- |
| Read the counter | `read_state` field `count` on `CounterViewModel` |
| Set the counter | `set_field` `count`, then `HomeFragment.onResume` |
| Increment | `CounterViewModel.increment`, then `onResume` |
| Open details | `performClick` on the button whose `mText` is `Open details` |
| Open settings | `performClick` on the button whose `mText` is `Open settings` |
| Leave settings | `com.example.counterproof.SettingsActivity.finish` |

Details is the only navigation destination: deep link `app://counter/details/{itemId}`. There is no settings route.

## What a failure means

Failures are tool text with `isError: true`. The process is resumed anyway.

- `not debuggable` — wrong pid, or the running build is release. List again.
- `final` / `val` — `set_field` refused the field. Call the setter with `call_method`.
- `No live instances` — that class is not on screen. Open the screen that creates it.
- `did not stop on a debugger event` — the main thread never left `nativePollOnce`. Leave the app in the foreground and retry.
- `Another tool call is attached` — wait, then retry. Do not send a second call in parallel.
- App exception text — the method ran and the app threw. The transport is still up.

## Timing

The first listing tries `adb jdwp` for up to 2 seconds. If that listing times out, later calls in the same server process skip it and use `ps` plus a `DEBUGGABLE` check. A method call still attaches and waits for a looper event, so it is slower than `set_field`.

After changing server code, run `.\gradlew.bat installDist` and restart the MCP server. A running server keeps the old jar.

## Do not

- Edit the sample app so a tool has something to call.
- Attach to a release build, or claim a missing pid is a server bug.
- Invent a class, field, or method. Use the name from the debug build.
- Call two tools at once against the same device.
- Treat a field write as a UI update until a paint method has run.
