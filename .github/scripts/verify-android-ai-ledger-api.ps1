param(
    [string]$DockerPath = 'docker.exe',
    [string]$EvidenceDirectory = 'artifacts/ai-review-android-api-20261010/guarded-run',
    [switch]$PreflightOnly
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repository
$evidence = [IO.Path]::GetFullPath((Join-Path $repository $EvidenceDirectory))
if (-not $evidence.StartsWith((Join-Path $repository 'artifacts') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must stay within repository artifacts' }
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

# Existing no-volume fixtures only. Never start, stop or modify user services.
function Require-Fixture([string]$Name, [string]$Image, [string]$ImageId, [string]$Port) {
    $raw = & $DockerPath inspect $Name
    if ($LASTEXITCODE -ne 0) { throw 'Disposable fixture missing' }
    $info = ($raw | ConvertFrom-Json)[0]
    $binding = $info.HostConfig.PortBindings.'8080/tcp'
    if ($info.Name -ne "/$Name" -or $info.Config.Image -ne $Image -or $info.Image -ne $ImageId -or
        -not $info.State.Running -or $info.Mounts.Count -ne 0 -or $binding.Count -ne 1 -or
        $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne $Port) {
        throw 'Disposable image, port or volume guard rejected fixture'
    }
    if ($Port -eq '18139' -and ($info.Config.Env -notcontains 'EBK_UUID_SERVER_ID=255' -or
            $info.Config.Env -notcontains 'EBK_AUTH_ENABLE_REGISTER=true')) { throw 'Fixture environment guard failed' }
}

Require-Fixture 'finexy-ai-android-api-e2e' 'finexy-ai-mobile-source:20261007-root2' `
    'sha256:0549baad737ffb95f06fcd2abd1ed05e97664391fc9ce7ef4952564f1b429fc3' '18139'
Require-Fixture 'finexy-ai-android-api-mock' 'finexy-ai-recognition-mock:20261005-root1' `
    'sha256:30ad4a77748749840966cb84820259ec631fe479131bff899f5f6e82d8eb8ae2' '18140'
$configRaw = & $DockerPath exec finexy-ai-android-api-e2e cat /ezbookkeeping/data/ai-configuration.json
if ($LASTEXITCODE -ne 0) { throw 'Synthetic model configuration missing' }
$config = $configRaw | ConvertFrom-Json
if ($config.baseUrl -ne 'http://finexy-ai-android-api-mock:8080/v1' -or
        $config.apiKey -ne 'synthetic-fixture-only' -or $config.modelId -ne 'synthetic' -or
        $config.enabled -ne $true -or $config.imageEnabled -ne $true) { throw 'External model configuration rejected' }
& adb -s emulator-5554 get-state
if ($LASTEXITCODE -ne 0) { throw 'Required emulator unavailable' }
$installed = & adb -s emulator-5554 shell pm list packages com.finexy.mobile.qa
if ($LASTEXITCODE -ne 0 -or $installed -notcontains 'package:com.finexy.mobile.qa' -or
        $installed -notcontains 'package:com.finexy.mobile.qa.test') { throw 'Independent QA app/test package missing' }
if ($PreflightOnly) { 'Isolation preflight passed'; return }

foreach ($scope in @('ai-ledger-api', 'ai-ledger-api-e2e', 'ai-ledger-api-regression')) {
    $classes = & python .github/scripts/verify-android-instrumentation.py --scope $scope --print-classes
    if ($LASTEXITCODE -ne 0) { throw 'Source-bound instrumentation scope invalid' }
    $log = Join-Path $evidence "$scope.log"
    if (Test-Path -LiteralPath $log) { throw 'Choose a fresh evidence directory; retain previous results' }
    $arguments = @('-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $classes)
    if ($scope -eq 'ai-ledger-api-e2e') { $arguments += @('-e', 'finexy.e2e.url', 'http://10.0.2.2:18139') }
    $arguments += 'com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner'
    & adb @arguments *> $log
    if ($LASTEXITCODE -ne 0) { throw 'ADB instrumentation failed' }
    & python .github/scripts/verify-android-instrumentation.py $log --scope $scope
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation results failed strict verification' }
}
