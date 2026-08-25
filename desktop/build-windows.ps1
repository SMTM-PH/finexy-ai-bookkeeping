param(
    [switch]$PackOnly
)

$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$workspaceRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $projectRoot))
$stagePath = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot '.finexy-desktop-stage'))
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'release\windows'))
$expectedStagePath = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot '.finexy-desktop-stage'))
$expectedOutputPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'release\windows'))

if ($stagePath -ne $expectedStagePath -or $outputPath -ne $expectedOutputPath) {
    throw 'Windows build paths did not resolve to the expected locations.'
}

if (Test-Path -LiteralPath $stagePath) {
    Remove-Item -LiteralPath $stagePath -Recurse -Force
}

if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Recurse -Force
}

New-Item -ItemType Directory -Path $stagePath | Out-Null
New-Item -ItemType Directory -Path $outputPath | Out-Null

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.cjs') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'preload.cjs') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline.html') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'windows.css') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package.json') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $projectRoot 'public\img\ezbookkeeping-512.png') -Destination (Join-Path $stagePath 'icon.png')

if (-not $env:ELECTRON_BUILDER_BINARIES_MIRROR) {
    $env:ELECTRON_BUILDER_BINARIES_MIRROR = 'https://npmmirror.com/mirrors/electron-builder-binaries/'
}

if (-not $env:ELECTRON_BUILDER_CACHE) {
    $env:ELECTRON_BUILDER_CACHE = Join-Path $projectRoot '.electron-builder-cache'
}

$builderCli = Join-Path $projectRoot 'node_modules\electron-builder\out\cli\cli.js'
$electronDist = Join-Path $projectRoot 'node_modules\electron\dist'
if ($PackOnly) {
    $builderArguments = @($builderCli, '--projectDir', $stagePath, "--config.electronDist=$electronDist", '--win', '--x64', '--dir')
} else {
    $builderArguments = @($builderCli, '--projectDir', $stagePath, "--config.electronDist=$electronDist", '--win', 'nsis', 'portable', '--x64')
}

& node @builderArguments
if ($LASTEXITCODE -ne 0) {
    throw "electron-builder failed with exit code $LASTEXITCODE."
}

Copy-Item -Path (Join-Path $stagePath 'dist\*') -Destination $outputPath -Recurse -Force

Write-Host "Windows build output: $outputPath"
