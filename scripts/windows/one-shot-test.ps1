# All remaining arguments are forwarded unchanged to the portable Python CLI.
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Python = 'python',
    [Parameter(ValueFromRemainingArguments = $true)] [string[]]$ScriptArgs
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
Invoke-LatticePython (Join-Path $PSScriptRoot '../one-shot-test.py') $ScriptArgs $Python
exit $LASTEXITCODE
