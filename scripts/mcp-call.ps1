# One MCP call against the installed kotlin-runtime-mcp server.
# The tools already take a class and a field or method. This script is the stdio client.
#
#   .\scripts\mcp-call.ps1 -ProcessId 29363 -ClassName com.example.counterproof.CounterViewModel -Field count -Value 20
#   .\scripts\mcp-call.ps1 -ProcessId 29363 -ClassName com.example.counterproof.HomeFragment -Method onResume
#   .\scripts\mcp-call.ps1 -ProcessId 29363 -ClassName com.example.counterproof.CounterViewModel -Method load
param(
    [Parameter(Mandatory = $true)][string]$ClassName,
    [int]$ProcessId = 0,
    [string]$Field,
    [string]$Method,
    [object]$Value,
    [string]$ArgumentsJson = "[]"
)

$ErrorActionPreference = "Stop"
if (-not $Field -and -not $Method) { throw "Pass -Field and -Value, or -Method." }
if ($Field -and $Method) { throw "Pass -Field or -Method, not both." }
if ($Field -and $null -eq $Value) { throw "Pass -Value with -Field." }

function ConvertTo-JsonString([string]$s) {
    '"' + ($s -replace '\\', '\\' -replace '"', '\"') + '"'
}

$lib = Join-Path $PSScriptRoot "..\build\install\kotlin-runtime-mcp\lib"
if (-not (Test-Path $lib)) { throw "Missing $lib. Run .\gradlew.bat installDist first." }
$cp = (Get-ChildItem $lib -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "java"
$psi.Arguments = "-classpath `"$cp`" mcp.MainKt"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
[void]$proc.Start()

function Send-Mcp([string]$line) {
    $proc.StandardInput.WriteLine($line)
    $proc.StandardInput.Flush()
    $task = $proc.StandardOutput.ReadLineAsync()
    if (-not $task.Wait(90000)) { throw "MCP server did not reply within 90s." }
    return $task.Result
}

function Tool-Text([string]$reply) {
    $msg = $reply | ConvertFrom-Json
    if ($msg.error) { throw $msg.error.message }
    $text = $msg.result.content[0].text
    if ($msg.result.isError) { throw $text }
    return $text
}

try {
    [void](Send-Mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"mcp-call","version":"1"}}}')
    if ($ProcessId -eq 0) {
        $listed = Tool-Text (Send-Mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_debuggable_apps","arguments":{}}}')
        $pids = [regex]::Matches($listed, 'pid=(\d+)') | ForEach-Object { $_.Groups[1].Value }
        if ($pids.Count -ne 1) { throw "Pass -ProcessId. Debuggable processes:`n$listed" }
        $ProcessId = [int]$pids[0]
    }
    $classJson = ConvertTo-JsonString $ClassName
    if ($Field) {
        $valueJson = if ($Value -is [bool]) {
            $(if ($Value) { "true" } else { "false" })
        } elseif ($Value -is [int] -or $Value -is [long] -or $Value -is [double]) {
            "$Value"
        } else {
            ConvertTo-JsonString "$Value"
        }
        $fieldJson = ConvertTo-JsonString $Field
        $call = "{`"jsonrpc`":`"2.0`",`"id`":3,`"method`":`"tools/call`",`"params`":{`"name`":`"set_field`",`"arguments`":{`"pid`":$ProcessId,`"className`":$classJson,`"field`":$fieldJson,`"value`":$valueJson}}}"
    } else {
        $methodJson = ConvertTo-JsonString $Method
        $call = "{`"jsonrpc`":`"2.0`",`"id`":3,`"method`":`"tools/call`",`"params`":{`"name`":`"call_method`",`"arguments`":{`"pid`":$ProcessId,`"className`":$classJson,`"method`":$methodJson,`"arguments`":$ArgumentsJson}}}"
    }
    Write-Output (Tool-Text (Send-Mcp $call))
} finally {
    try { $proc.StandardInput.Close() } catch {}
    if (-not $proc.WaitForExit(5000)) { $proc.Kill() }
}
