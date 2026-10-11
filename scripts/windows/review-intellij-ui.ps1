[CmdletBinding()]
param(
    [string]$DownloadDir,
    [string]$BinariesDir,
    [string]$GradleUserHome,
    [string]$BuildIdeHome,
    [switch]$Cleanup,
    [switch]$NoCleanup,
    [string]$Python = 'python',
    [switch]$DependenciesReady,
    [Parameter(Position = 0)] [string[]]$Themes = @(),
    [string]$OutputRoot = $env:LATTICE_UI_REVIEW_OUTPUT,
    [string]$IdeHome = $env:LATTICE_UI_IDE_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenRepository,
    [string]$FixtureCache = $env:LATTICE_UI_FIXTURE_CACHE,
    [switch]$InputsOnly,
    [switch]$StyleOnly
)

$ErrorActionPreference = 'Stop'

if (-not $DependenciesReady) {
    . (Join-Path $PSScriptRoot 'windows-common.ps1')
    $dependencyParameters = @{}
    foreach ($key in $PSBoundParameters.Keys) { $dependencyParameters[$key] = $PSBoundParameters[$key] }
    if (-not $dependencyParameters.ContainsKey('IdeHome') -and $IdeHome) { $dependencyParameters.IdeHome = $IdeHome }
    Invoke-LatticeDependencies 'review-intellij-ui.ps1' $dependencyParameters $Python
    return
}

if ($env:OS -ne 'Windows_NT') {
    throw 'Use this PowerShell entry point on Windows; Linux users can run scripts/linux/review-intellij-ui.sh.'
}
if (-not [Environment]::UserInteractive) {
    throw 'Real IDE screenshots require an interactive Windows desktop session.'
}
Add-Type -AssemblyName System.Windows.Forms
$desktop = [Windows.Forms.Screen]::PrimaryScreen.Bounds
if ($desktop.Width -lt 1440 -or $desktop.Height -lt 800) {
    throw "Primary display is $($desktop.Width)x$($desktop.Height); real IDE capture requires at least 1440x800."
}
$defaultThemes = @('ExperimentalDark', 'ExperimentalLight', 'ExperimentalLightWithLightHeader',
                   'JetBrainsHighContrastTheme', 'Darcula', 'IntelliJ', 'JetBrainsLightTheme')
$supportedThemes = $defaultThemes + @('Islands Dark', 'Islands Light', 'Islands Darcula')
if ($Themes.Count -eq 0) { $Themes = $defaultThemes }
foreach ($theme in $Themes) {
    if ($theme -notin $supportedThemes) { throw "Unsupported theme: $theme" }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoRoot
$oldMavenRepository = $env:LATTICE_UI_MAVEN_REPOSITORY
try {
    if (-not $OutputRoot) { $OutputRoot = Join-Path $repoRoot 'build/ui-review' }
    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    $OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
    $mavenRepository = if ($MavenRepository) { [IO.Path]::GetFullPath($MavenRepository) } else { Join-Path $repoRoot 'build/ui-test-maven/repository' }
    $env:LATTICE_UI_MAVEN_REPOSITORY = $mavenRepository
    if ($FixtureCache) { $FixtureCache = (Resolve-Path -LiteralPath $FixtureCache).Path }

    $captureScript = Join-Path $PSScriptRoot 'capture-intellij-ui.ps1'
    foreach ($theme in $Themes) {
        $resultDirectory = Join-Path $OutputRoot $theme
        New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
        $log = Join-Path $OutputRoot "$theme.log"
        $captureParameters = @{
            Theme = $theme
            Output = $resultDirectory
            Review = $true
            DependenciesReady = $true
        }
        if ($IdeHome) { $captureParameters.IdeHome = $IdeHome }
        if ($JavaHome) { $captureParameters.JavaHome = $JavaHome }
        if ($InputsOnly) { $captureParameters.InputsOnly = $true }
        if ($StyleOnly) { $captureParameters.StyleOnly = $true }
        Write-Host "Capturing theme: $theme"
        & $captureScript @captureParameters 2>&1 | Tee-Object -FilePath $log
        if ($LASTEXITCODE -ne 0) { throw "Capture failed for theme '$theme' with exit code $LASTEXITCODE." }
        $testResult = Join-Path $repoRoot 'build/test-results/uiScreenshotTest/TEST-com.segfault03.ideadb.ui.IntellijUiScreenshotTest.xml'
        Copy-Item -LiteralPath $testResult -Destination (Join-Path $resultDirectory 'test-result.xml') -Force
    }

    $indexLines = [System.Collections.Generic.List[string]]::new()
    $indexLines.Add('# Real IntelliJ UI captures')
    $indexLines.Add('')
    $indexLines.Add('Every image is a full desktop capture of the running IDE.')
    $indexLines.Add('')
    foreach ($directory in Get-ChildItem -LiteralPath $OutputRoot -Directory | Sort-Object Name) {
        $indexLines.Add("## $($directory.Name)")
        $indexLines.Add('')
        foreach ($png in Get-ChildItem -LiteralPath $directory.FullName -Filter '*.png' -File | Sort-Object Name) {
            $indexLines.Add("- [$($png.BaseName)](<$($directory.Name)/$($png.Name)>)")
        }
        $indexLines.Add("- [Runtime evidence](<$($directory.Name)/runtime-evidence.txt>)")
        $indexLines.Add("- [JUnit result](<$($directory.Name)/test-result.xml>)")
        $indexLines.Add('')
    }
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllLines((Join-Path $OutputRoot 'index.md'), $indexLines.ToArray(), $encoding)
    Write-Host "Screenshots and live-runtime evidence: $OutputRoot"
} finally {
    $env:LATTICE_UI_MAVEN_REPOSITORY = $oldMavenRepository
    Pop-Location
}
