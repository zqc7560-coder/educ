# 按命令行特征精确停止本项目进程（不按进程名杀）：
#   java.exe  命令行含 lf-server.jar
#   node.exe  命令行含「毕业设计」且含 frontend（vite dev server）
$killed = @()
$targets = @(
    @{ Proc = "java.exe"; Match = "lf-server\.jar" },
    @{ Proc = "node.exe"; Match = "毕业设计.*frontend" }
)
foreach ($t in $targets) {
    Get-CimInstance Win32_Process -Filter "Name='$($t.Proc)'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match $t.Match } |
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $killed += "{0} pid={1}" -f $t.Proc, $_.ProcessId
        }
}
if ($killed.Count -gt 0) { "killed: " + ($killed -join "; ") } else { "no matching processes" }
foreach ($port in 8081, 5174) {
    $c = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue
    "port $port listening: " + $c.TcpTestSucceeded
}
