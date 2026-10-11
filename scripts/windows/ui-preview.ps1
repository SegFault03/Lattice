# All remaining arguments are forwarded unchanged to the portable Python CLI.
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Python = 'python',
    [Parameter(ValueFromRemainingArguments = $true)] [string[]]$ScriptArgs
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
Invoke-LatticePython (Join-Path $PSScriptRoot '../ui-preview.py') $ScriptArgs $Python
exit $LASTEXITCODE
