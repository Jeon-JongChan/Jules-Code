#requires -Version 5.1
<#
.SYNOPSIS
    Jules Code 테스트 실행 스크립트 (.build/test/)
.DESCRIPTION
    포터블 빌드 도구 환경을 활성화하고 단위 테스트(JUnit) 또는 UI 캡처 스크립트를 실행합니다.
    예:
      .\.build\test\run_tests.ps1 -Type unit
      .\.build\test\run_tests.ps1 -Type capture
.PARAMETER Type
    실행할 테스트 종류: 'unit' (기본값) 또는 'capture'
#>
[CmdletBinding()]
param(
    [ValidateSet('unit', 'capture')][string]$Type = 'unit',
    [string]$CaptureScript = 'capture_clean.py'
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $PSCommandPath
if (-not $ScriptDir) { $ScriptDir = $PSScriptRoot }
$ProjectRoot = (Get-Item (Join-Path $ScriptDir '..\..')).FullName

if ($Type -eq 'unit') {
    Write-Host "[Test-Runner] Gradle 단위 테스트(testDebugUnitTest) 실행 중..." -ForegroundColor Cyan
    Set-Location $ProjectRoot
    $KotlinScript = Join-Path $ProjectRoot '.build\kotlin.ps1'
    powershell -ExecutionPolicy Bypass -Command "& '$KotlinScript' -Action env; .\gradlew.bat testDebugUnitTest"
} else {
    Write-Host "[Test-Runner] UI 캡처 스크립트 ($CaptureScript) 실행 중..." -ForegroundColor Cyan
    $TargetScript = Join-Path $ScriptDir $CaptureScript
    if (-not (Test-Path $TargetScript)) {
        throw "테스트 스크립트를 찾을 수 없습니다: $TargetScript"
    }
    python $TargetScript
}
