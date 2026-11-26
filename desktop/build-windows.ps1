[CmdletBinding()]
param(
    [ValidatePattern('^[A-Fa-f0-9]{40}$')][string]$SigningThumbprint,
    [uri]$TimestampUrl,
    [string]$SignToolPath = 'signtool.exe',
    [string]$QwenRuntimeArchive
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Push-Location $PSScriptRoot
try {
    if ($SigningThumbprint) {
        if (!$TimestampUrl -or !$TimestampUrl.IsAbsoluteUri -or $TimestampUrl.Scheme -ne 'https') {
            throw 'Provide an HTTPS RFC 3161 timestamp URL for the signing certificate.'
        }
        $signTool = (Get-Command $SignToolPath -CommandType Application).Source
        $certificate = Get-Item -LiteralPath "Cert:\CurrentUser\My\$SigningThumbprint"
        if (!$certificate.HasPrivateKey) { throw 'The signing certificate has no accessible private key.' }
        $signArguments = @('sign', '/sha1', $SigningThumbprint, '/fd', 'SHA256', '/tr', $TimestampUrl.AbsoluteUri, '/td', 'SHA256')
    }
    $version = (Get-Content version.txt -Raw).Trim()
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid desktop version.' }

    $output = Join-Path $PSScriptRoot 'out'
    $app = Join-Path $output 'app'
    $release = Join-Path $output 'release'
    foreach ($directory in @($app, $release)) {
        if (Test-Path $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    dotnet publish StarHub.Windows/StarHub.Windows.csproj -c Release -p:Platform=x64 "-p:Version=$version" -o $app
    dotnet publish StarHub.Worker/StarHub.Worker.csproj -c Release "-p:Version=$version" -o (Join-Path $app 'worker')
    $minimumWindowsVersion = [version](dotnet msbuild StarHub.Windows/StarHub.Windows.csproj -nologo -getProperty:TargetPlatformMinVersion)
    foreach ($required in @('StarHub.exe', 'starhub.ico', 'worker/StarHub.Worker.exe')) {
        if (!(Test-Path (Join-Path $app $required))) { throw "Published file is missing: $required" }
    }

    # The installer carries the CPU runtime; the optional model is downloaded by the app.
    if (!$QwenRuntimeArchive) {
        $QwenRuntimeArchive = Join-Path $output 'llama-b12753-bin-win-cpu-x64.zip'
        if (!(Test-Path -LiteralPath $QwenRuntimeArchive)) {
            Invoke-WebRequest 'https://github.com/ggml-org/llama.cpp/releases/download/b12753/llama-b12753-bin-win-cpu-x64.zip' -OutFile "$QwenRuntimeArchive.part"
            Move-Item -Force -LiteralPath "$QwenRuntimeArchive.part" -Destination $QwenRuntimeArchive
        }
    }
    if ((Get-FileHash -LiteralPath $QwenRuntimeArchive -Algorithm SHA256).Hash -ne 'c18d187d435fe90392e98a7ad1b60cce13d5e6483f80b09154b218fd0cc1bead') {
        throw 'The llama.cpp CPU runtime differs from the pinned release.'
    }
    $runtimeDir = Join-Path $app 'offline-runtime'
    $archive = [IO.Compression.ZipFile]::OpenRead($QwenRuntimeArchive)
    try {
        $required = @('llama-server.exe','llama-server-impl.dll','llama-common.dll','llama.dll','mtmd.dll',
            'ggml.dll','ggml-base.dll','ggml-cpu-x64.dll','libomp.dll','LICENSE-LLVM-OpenMP')
        foreach ($name in @($required + @($archive.Entries.FullName | Where-Object { $_ -match '^ggml-cpu-[a-z0-9]+\.dll$' }) | Sort-Object -Unique)) {
            $entry = $archive.GetEntry($name)
            if (!$entry) { throw "Missing inference runtime component: $name" }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $runtimeDir $name), $true)
        }
    } finally { $archive.Dispose() }

    # Deploy the x64 Microsoft redistributable beside the optional inference runtime.
    $crt = if ($env:VCToolsRedistDir) {
        (Get-ChildItem (Join-Path $env:VCToolsRedistDir 'x64') -Directory -Filter 'Microsoft.VC*.CRT' | Select-Object -First 1).FullName
    } else { [Environment]::SystemDirectory }
    foreach ($name in 'msvcp140.dll','vcruntime140.dll','vcruntime140_1.dll') {
        $library = Join-Path $crt $name
        $signed = Get-AuthenticodeSignature -FilePath $library
        if ($signed.Status -ne 'Valid' -or $signed.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
            throw "A Microsoft-signed x64 Visual C++ Redistributable is required to package $name."
        }
        Copy-Item -LiteralPath $library -Destination $runtimeDir
    }

    $compiler = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
    if (!$compiler) {
        $compiler = Join-Path ([Environment]::GetEnvironmentVariable('ProgramFiles(x86)')) 'Inno Setup 6/ISCC.exe'
    }
    $innoArguments = @("/DVersion=$version", "/DMinimumWindowsBuild=$($minimumWindowsVersion.Build)",
        "/DAppDir=$app", "/DReleaseDir=$release")
    if ($SigningThumbprint) {
        $payload = Get-ChildItem -LiteralPath $app -Recurse -File | Where-Object {
            $_.Name -match '^StarHub(?:\..+)?\.(?:exe|dll)$'
        }
        foreach ($file in $payload) { & $signTool @signArguments $file.FullName }
        if ($signTool.Contains('$') -or $TimestampUrl.AbsoluteUri -match '[\s"$]') {
            throw 'The signing tool path or timestamp URL contains unsupported Inno Setup command characters.'
        }
        $innoArguments += '/DSignStarHub', ('/SStarHub=$q{0}$q {1} $f' -f $signTool, ($signArguments -join ' '))
    }
    & $compiler @innoArguments 'install/StarHub.iss'
    $installer = Get-Item (Join-Path $release 'StarHub-Windows-x64-Setup.exe')
    $signature = Get-AuthenticodeSignature $installer.FullName
    if ($SigningThumbprint) {
        foreach ($file in @($payload) + @($installer)) {
            $check = Get-AuthenticodeSignature $file.FullName
            if ($check.Status -ne 'Valid' -or $check.SignerCertificate.Thumbprint -ne $SigningThumbprint -or !$check.TimeStamperCertificate) {
                throw "Trusted, timestamped signing verification failed for $($file.Name)."
            }
        }
    }
    $digest = (Get-FileHash -Algorithm SHA256 $installer.FullName).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$($installer.FullName).sha256", "$digest  $($installer.Name)" + [char]10,
        [Text.Encoding]::ASCII)
    $sourceStamp = Join-Path $PSScriptRoot '../SOURCE-COMMIT.txt'
    $commit = if (Test-Path -LiteralPath $sourceStamp) {
        (Get-Content -LiteralPath $sourceStamp -Raw).Trim()
    } else { (git rev-parse HEAD).Trim() }
    if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'Invalid source commit.' }
    [ordered]@{
        version = $version
        platform = 'windows'
        architecture = 'x64'
        minimumBuild = $minimumWindowsVersion.Build
        asset = $installer.Name
        sha256 = $digest
        bytes = $installer.Length
        codeSigned = ($signature.Status -eq 'Valid')
        commit = $commit
    } | ConvertTo-Json | Set-Content (Join-Path $release 'starhub-windows.json') -Encoding utf8
}
finally {
    Pop-Location
}
