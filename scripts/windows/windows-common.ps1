function Invoke-LatticePython([string]$ScriptPath, [string[]]$Arguments, [string]$PythonCommand) {
    # The Python supervisor exits if this PowerShell dies; its OS jobs own tools.
    & $PythonCommand (Join-Path $PSScriptRoot '../processes.py') --parent-pid $PID --script $ScriptPath -- @Arguments
    $global:LASTEXITCODE = $LASTEXITCODE
}

function Invoke-LatticeCommand([string[]]$CommandArguments, [string]$PythonCommand) {
    & $PythonCommand (Join-Path $PSScriptRoot '../processes.py') --parent-pid $PID --command -- @CommandArguments
    $global:LASTEXITCODE = $LASTEXITCODE
}

function Invoke-LatticeDependencies([string]$ScriptName, [hashtable]$Parameters, [string]$PythonCommand) {
    $configuration = [IO.Path]::GetTempFileName()
    try {
        $values = @{}
        foreach ($key in $Parameters.Keys) {
            $value = $Parameters[$key]
            if ($value -is [Management.Automation.SwitchParameter]) { $value = $value.IsPresent }
            $values[$key] = $value
        }
        $encoding = [Text.UTF8Encoding]::new($false)
        $json = @{ script = $ScriptName; parameters = $values } | ConvertTo-Json -Depth 5
        [IO.File]::WriteAllText($configuration, $json, $encoding)
        Invoke-LatticePython (Join-Path $PSScriptRoot 'windows-script-dependencies.py') @($configuration) $PythonCommand
        if ($LASTEXITCODE -ne 0) { throw "Dependency preparation or $ScriptName failed (exit $LASTEXITCODE)." }
    } finally {
        Remove-Item -LiteralPath $configuration -Force -ErrorAction SilentlyContinue
    }
}
