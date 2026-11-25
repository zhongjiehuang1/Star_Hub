[CmdletBinding()]
param([string]$PreviousInstaller)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$installer = Join-Path $PSScriptRoot 'out/release/StarHub-Windows-x64-Setup.exe'
$data = Join-Path $env:LOCALAPPDATA 'StarHub'
$db = Join-Path $data 'starhub.db'
$installRegistry = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{DBCF9DCE-8C6E-4A9B-88C3-AD65099013B1}_is1'
$runKey = 'HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run'
if ((Test-Path $data) -or (Test-Path $installRegistry)) {
    throw 'Run this test under a disposable Windows profile with no existing Star Hub data or installation.'
}
$testTemp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$testId = [guid]::NewGuid().ToString('N')
$installed = Join-Path $testTemp ('Star Hub installation test ' + $testId)
$setupLog = Join-Path $testTemp ("starhub-setup-$testId.log")
$workerProcess = $providerJob = $app = $null

function Run-Tool($file, $arguments) {
    $process = Start-Process -FilePath $file -ArgumentList $arguments -PassThru
    if (!$process.WaitForExit(75000)) {
        $process.Kill($true)
        throw "$file did not finish in 75 seconds."
    }
    if ($process.ExitCode -ne 0) { throw "$file exited with code $($process.ExitCode)." }
}

function Wait-Window($app) {
    for ($i = 0; $i -lt 40; $i++) {
        $app.Refresh()
        if ($app.HasExited) {
            Get-WinEvent -FilterHashtable @{LogName='Application'; StartTime=(Get-Date).AddMinutes(-2); Level=2} -ErrorAction SilentlyContinue |
                Select-Object -First 4 -ExpandProperty Message | Write-Host
            throw "StarHub exited with code $($app.ExitCode)."
        }
        if ($app.MainWindowHandle -ne 0 -and $app.MainWindowTitle -eq 'Star Hub') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'StarHub did not create its main window.'
}

function Close-Window($app) {
    if (!$app.CloseMainWindow() -or !$app.WaitForExit(10000)) { throw 'The desktop did not close gracefully.' }
}

function Wait-InstalledProcess($file) {
    $name = [IO.Path]::GetFileNameWithoutExtension($file)
    for ($i = 0; $i -lt 30; $i++) {
        $running = Get-Process -Name $name -ErrorAction SilentlyContinue |
            Where-Object Path -eq $file | Select-Object -First 1
        if ($running) { return $running }
        Start-Sleep -Milliseconds 500
    }
    throw "The installed $name process did not launch automatically."
}

function Wait-ForRow($table, $reply, $prompt = '') {
    python -c @'
import sqlite3, sys, time
path, table, text, prompt = sys.argv[1:]
column = {"turns": "reply", "runs": "result"}[table]
where, parameters = ("where prompt=?", (prompt,)) if prompt else ("", ())
with sqlite3.connect(path, timeout=5) as db:
    for _ in range(40):
        row = db.execute(f"select state, {column}, detail from {table} {where} order by rowid desc limit 1", parameters).fetchone()
        if row and row[0] != 0:
            assert row[0] == 1 and row[1] == text, row
            break
        time.sleep(0.5)
    else:
        raise AssertionError(f"{table} did not confirm a completed response.")
'@ $db $table $reply $prompt
}

$silent = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/DIR="' + $installed + '"'))
$upgrade = [bool]$PreviousInstaller
$starhub = Join-Path $installed 'StarHub.exe'
$worker = Join-Path $installed 'worker/StarHub.Worker.exe'
try {
    if ($upgrade) {
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $PreviousInstaller).Hash -ne '333217038c6dd2ad761024ce86a66bd8bbd761b6b5089227a44bf20fcbe5dfe8') {
            throw 'The earlier test installer did not match its verified checksum.'
        }
        Write-Host 'Installing the earlier test package to exercise the upgrade.'
        Run-Tool $PreviousInstaller $silent
        $app = Start-Process $starhub -PassThru
        Wait-Window $app
        Close-Window $app
        python -c @'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute("pragma user_version").fetchone() == (1,)
    db.execute("insert into conversations values ('legacy', 'Existing conversation', 1)")
    db.execute("insert into turns (id, conversation_id, created, prompt, reply, model, state) values ('legacy-turn', 'legacy', 1, 'Old prompt', 'Original answer', 'test', 1)")
'@ $db
    }

    Write-Host 'Opening setup to verify automatic installation and launch.'
    try {
        Run-Tool $installer @('/NORESTART', ('/DIR="' + $installed + '"'), ('/LOG="' + $setupLog + '"'))
    }
    catch {
        if (Test-Path $setupLog) { Get-Content -LiteralPath $setupLog -Tail 30 }
        throw
    }
    $app = Wait-InstalledProcess $starhub
    Wait-Window $app
    $workerProcess = Wait-InstalledProcess $worker
    $loginCommand = [Microsoft.Win32.Registry]::GetValue($runKey, 'StarHubWorker', $null)
    if ($loginCommand -ne ('"' + $worker + '"')) { throw 'The installer did not register the background worker for this Windows user.' }
    Write-Host 'The installer registered the per-user Windows sign-in command.'
    dotnet run --project (Join-Path $PSScriptRoot 'StarHub.LiveChecks') -c Release -f net10.0-windows10.0.22000.0 -- device-alert
    if (!(Test-Path $db)) { throw 'The desktop did not open its local store.' }
    Close-Window $app
    python -c @'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute("pragma user_version").fetchone() == (4,)
    if sys.argv[2] == "1":
        assert db.execute("select reply from turns where id = 'legacy-turn'").fetchone() == ('Original answer',)
'@ $db ([int]$upgrade)
    if ($upgrade) { Write-Host 'Upgrade preserved the conversation from the earlier test installation.' }

    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $probe.Start()
    $port = $probe.LocalEndpoint.Port
    $probe.Stop()
    $providerJob = Start-Job -ArgumentList $port -ScriptBlock {
        param($port)
        $ErrorActionPreference = 'Stop'
        $server = [System.Net.HttpListener]::new()
        $server.Prefixes.Add("http://127.0.0.1:$port/")
        $server.Start()
        while ($true) {
            $request = $server.GetContext()
            if ($request.Request.Url.AbsolutePath -eq '/shutdown') {
                $request.Response.Close()
                break
            }
            if ($request.Request.Url.AbsolutePath -eq '/health') {
                $request.Response.Close()
                continue
            }
            $reader = [System.IO.StreamReader]::new($request.Request.InputStream)
            $document = $reader.ReadToEnd() | ConvertFrom-Json
            $reader.Dispose()
            $deviceContext = $document.messages | Where-Object { $_.content -match 'Why is my computer slow|Local slowdown diagnosis' }
            if ($request.Request.Url.AbsolutePath -ne '/api/chat' -or $document.model -ne 'starhub-smoke' -or $deviceContext) {
                $request.Response.StatusCode = 400
                $request.Response.Close()
                continue
            }
            $text = if ($document.messages[-1].content -eq 'Show the native test reply.') { 'Native reply' }
                    elseif ($document.messages[-1].content -eq 'Run the fixture.') { 'Worker reply' }
                    else { 'Unexpected prompt' }
            $request.Response.ContentType = 'application/x-ndjson'
            $request.Response.SendChunked = $true
            foreach ($line in @(
                (@{ message = @{ content = $text }; done = $false } | ConvertTo-Json -Depth 4 -Compress),
                '{"message":{"content":""},"done":true,"eval_count":2}'
            )) {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($line + [char]10)
                $request.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                $request.Response.OutputStream.Flush()
                Start-Sleep -Milliseconds 150
            }
            $request.Response.Close()
        }
        $server.Close()
    }
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $ready = (Invoke-WebRequest -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1).StatusCode -eq 200
            if ($ready) { break }
        }
        catch { Start-Sleep -Milliseconds 300 }
    }
    if (!$ready) { throw 'Loopback provider fixture did not start.' }

    python -c @'
import json, sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    db.execute("insert or replace into preferences values (1, ?)",
               (json.dumps({"Provider": 2, "OllamaModel": "starhub-smoke", "OllamaPort": int(sys.argv[2])}),))
'@ $db $port
    $app = Start-Process $starhub -PassThru
    Wait-Window $app
    $windowHandle = $app.MainWindowHandle.ToInt64()
    $nativeProbe = Join-Path $PSScriptRoot 'install/invoke-native-chat.ps1'
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File $nativeProbe -WindowHandle $windowHandle -TestDevice
    python -c @'
import sqlite3, sys, time
with sqlite3.connect(sys.argv[1], timeout=5) as db:
    for _ in range(40):
        row = db.execute("select state, reply, local_only from turns where prompt = 'Why is my computer slow?' order by rowid desc limit 1").fetchone()
        if row and row[0] != 0:
            assert row[0] == 1 and row[2] == 1 and '**Local slowdown diagnosis**' in row[1], row
            assert 'Live sample taken' in row[1] and 'samples in the last' in row[1] and 'CPU ·' in row[1], row
            break
        time.sleep(0.5)
    else:
        raise AssertionError('The native chat did not complete the local device analysis.')
'@ $db
    Write-Host 'Native chat answered the device question using local history and live readings.'
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File $nativeProbe -WindowHandle $windowHandle
    Wait-ForRow 'turns' 'Native reply' 'Show the native test reply.'
    Write-Host 'Native UI sent a message and SQLite recorded the streamed reply.'
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File $nativeProbe -WindowHandle $windowHandle -Prompt 'Enable desktop startup' -TestCloseWarning
    Wait-ForRow 'turns' 'Star Hub will open when you sign in.' 'Enable desktop startup'
    if ([Microsoft.Win32.Registry]::GetValue($runKey, 'StarHubDesktop', $null) -ne ('"' + $starhub + '"')) {
        throw 'The Windows chat task did not register desktop sign-in startup.'
    }
    Write-Host 'The Windows chat task verified sign-in startup through the registry.'

    Close-Window $app
    python -c @'
import sqlite3, sys, time
with sqlite3.connect(sys.argv[1]) as db:
    db.execute("insert into schedules values ('ci', 'CI fixture', 'Run the fixture.', 2, 0, 15, ?, 1)",
               (int(time.time() * 1000),))
'@ $db
    Wait-ForRow 'runs' 'Worker reply' 'Run the fixture.'
    python -c @'
import sqlite3, sys, time
with sqlite3.connect(sys.argv[1], timeout=5) as db:
    last_slot = db.execute('select max(slot) from device_history').fetchone()[0]
    assert last_slot is not None
    for _ in range(55):
        if db.execute('select max(slot) from device_history').fetchone()[0] > last_slot:
            break
        time.sleep(0.5)
    else:
        raise AssertionError('Background device history did not advance after the main window closed.')
'@ $db
    Write-Host 'The installed worker completed a due task and recorded more device history after the UI closed.'

    Run-Tool $worker @('--stop')
    if (!$workerProcess.WaitForExit(5000)) { throw 'The worker did not stop before verifying the Windows sign-in command.' }
    $before = python -c @'
import json, sqlite3, sys
with sqlite3.connect(sys.argv[1], timeout=5) as db:
    print(json.loads(db.execute("select content from device_latest where id=1").fetchone()[0])["Sample"]["SampledAt"])
'@ $db
    $workerProcess = Start-Process -FilePath $loginCommand.Trim('"') -PassThru
    python -c @'
import json, sqlite3, sys, time
with sqlite3.connect(sys.argv[1], timeout=5) as db:
    before = sys.argv[2]
    for _ in range(30):
        row = db.execute("select content from device_latest where id=1").fetchone()
        if row and json.loads(row[0])["Sample"]["SampledAt"] != before:
            break
        time.sleep(0.5)
    else:
        raise AssertionError('Running the sign-in command did not start background sampling.')
'@ $db $before
    Write-Host 'The registered sign-in command started device sampling with the main window closed.'
    $uninstaller = Get-ChildItem -LiteralPath $installed -Filter 'unins*.exe' | Select-Object -First 1
    $manifest = Get-Content -Raw (Join-Path $PSScriptRoot 'out/release/starhub-windows.json') | ConvertFrom-Json
    if ($manifest.codeSigned) {
        $setupSignature = Get-AuthenticodeSignature $installer
        $uninstallSignature = Get-AuthenticodeSignature $uninstaller.FullName
        if ($setupSignature.Status -ne 'Valid' -or $uninstallSignature.Status -ne 'Valid' -or
            $uninstallSignature.SignerCertificate.Thumbprint -ne $setupSignature.SignerCertificate.Thumbprint -or
            !$uninstallSignature.TimeStamperCertificate) {
            throw 'The signed package did not install a trusted, timestamped uninstaller from the same publisher.'
        }
    }
    Write-Host 'Uninstalling the application and stopping the worker.'
    Run-Tool $uninstaller.FullName @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
    if (!$workerProcess.WaitForExit(5000)) { throw 'Uninstall did not stop the background worker.' }
    if ($null -ne [Microsoft.Win32.Registry]::GetValue($runKey, 'StarHubWorker', $null) -or
        $null -ne [Microsoft.Win32.Registry]::GetValue($runKey, 'StarHubDesktop', $null)) {
        throw 'Uninstall did not remove Windows sign-in startup.'
    }
    if (Test-Path $starhub) { throw 'Uninstall did not remove StarHub.exe.' }
    if (!(Test-Path $db)) { throw 'Uninstall should preserve local user data.' }
}
finally {
    if ($app -and !$app.HasExited) { Stop-Process -Id $app.Id -ErrorAction SilentlyContinue }
    if ($workerProcess -and !$workerProcess.HasExited) { Stop-Process -Id $workerProcess.Id -ErrorAction SilentlyContinue }
    if ($providerJob) {
        try { Invoke-WebRequest -Uri "http://127.0.0.1:$port/shutdown" -TimeoutSec 3 | Out-Null } catch { }
        Wait-Job $providerJob -Timeout 5 | Out-Null
        Receive-Job $providerJob -ErrorAction Continue
        Remove-Job $providerJob -Force
    }
    foreach ($log in @(Get-ChildItem -LiteralPath $data -Filter '*.log' -ErrorAction SilentlyContinue)) {
        Get-Content $log.FullName | Write-Host
    }
}
