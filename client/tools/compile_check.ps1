param(
    [string]$OutputDir = "$env:TEMP\smc_compile_check"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$srcRoot = Join-Path $projectRoot "src"
$appRoot = Join-Path $srcRoot "app"

if (-not (Test-Path $appRoot)) {
    throw "Cannot find source directory: $appRoot"
}

$files = Get-ChildItem -Path $appRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $files -or $files.Count -eq 0) {
    throw "No Java files found under $appRoot"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
javac -encoding UTF-8 -d $OutputDir $files
java -cp $OutputDir app.execution.fix.ExecutionSafetySelfTest

Write-Host "Compile check passed: $($files.Count) Java files -> $OutputDir"
