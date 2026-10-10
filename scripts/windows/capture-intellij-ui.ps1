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
    [string]$IdeHome = $env:LATTICE_UI_IDE_HOME,
    [string]$JavaHome,
    [string]$Theme = 'ExperimentalDark',
    [string]$Output,
    [switch]$Review,
    [switch]$InputsOnly,
    [switch]$StyleOnly,
    [string[]]$GradleArgs = @()
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')

if (-not $DependenciesReady) {
    $dependencyParameters = @{}
    foreach ($key in $PSBoundParameters.Keys) { $dependencyParameters[$key] = $PSBoundParameters[$key] }
    if (-not $dependencyParameters.ContainsKey('IdeHome') -and $IdeHome) { $dependencyParameters.IdeHome = $IdeHome }
    Invoke-LatticeDependencies 'capture-intellij-ui.ps1' $dependencyParameters $Python
    return
}


function Get-Java21Home([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $null }
    $resolved = Resolve-Path -LiteralPath $Candidate -ErrorAction SilentlyContinue
    if (-not $resolved) { return $null }
    $jdkHome = $resolved.Path
    $javaName = if ($env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }
    $javacName = if ($env:OS -eq 'Windows_NT') { 'javac.exe' } else { 'javac' }
    $java = Join-Path $jdkHome "bin/$javaName"
    $javac = Join-Path $jdkHome "bin/$javacName"
    $release = Join-Path $jdkHome 'release'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
        -not (Test-Path -LiteralPath $javac -PathType Leaf) -or
        -not (Test-Path -LiteralPath $release -PathType Leaf)) { return $null }
    if ((Get-Content -LiteralPath $release -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.|\")') { return $null }
    return $jdkHome
}

if ($env:OS -ne 'Windows_NT') {
    throw 'Use this PowerShell entry point on Windows; Linux users can run scripts/linux/capture-intellij-ui.sh.'
}
if (-not [Environment]::UserInteractive) {
    throw 'Real IDE screenshots require an interactive Windows desktop session.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$selectedJavaHome = $null
if ($JavaHome) {
    $selectedJavaHome = Get-Java21Home $JavaHome
    if (-not $selectedJavaHome) { throw '-JavaHome must point to a full JDK 21 directory.' }
} else {
    $candidates = @($env:JAVA_HOME)
    if ($IdeHome) { $candidates += (Join-Path $IdeHome 'jbr') }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += (Split-Path (Split-Path $javaCommand.Source -Parent) -Parent) }
    $jdks = Join-Path $HOME '.jdks'
    if (Test-Path -LiteralPath $jdks -PathType Container) {
        $candidates += Get-ChildItem -LiteralPath $jdks -Directory | ForEach-Object { $_.FullName }
    }
    foreach ($candidate in $candidates) {
        $selectedJavaHome = Get-Java21Home $candidate
        if ($selectedJavaHome) { break }
    }
    if (-not $selectedJavaHome) {
        throw 'Java 21 was not found. Pass -JavaHome with a full JDK 21 directory or set JAVA_HOME.'
    }
}

$oldJavaHome = $env:JAVA_HOME
$oldGradleOpts = $env:GRADLE_OPTS
$oldJdkJavaOptions = $env:JDK_JAVA_OPTIONS
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $selectedJavaHome
    $proxyUrl = $env:HTTPS_PROXY
    if (-not $proxyUrl) { $proxyUrl = $env:HTTP_PROXY }
    $gradleJvmOptions = @()
    if ($oldGradleOpts) { $gradleJvmOptions += $oldGradleOpts }
    $uiJavaOptions = @(
        '-Dsun.java2d.uiScale=1.0',
        '-Dide.ui.scale=1.0',
        '-Djb.consents.confirmation.enabled=false',
        '-Dide.no.platform.update=true',
        '-Dide.show.tips.on.startup.default=false',
        '-Didea.trust.all.projects=true'
    )
    if ($proxyUrl) {
        $proxy = [Uri]$proxyUrl
        if (-not $proxy.IsAbsoluteUri -or -not $proxy.Host) { throw 'The configured HTTP(S)_PROXY value is not a valid URL.' }
        $gradleJvmOptions += @(
            "-Dhttps.proxyHost=$($proxy.Host)", "-Dhttps.proxyPort=$($proxy.Port)",
            "-Dhttp.proxyHost=$($proxy.Host)", "-Dhttp.proxyPort=$($proxy.Port)"
        )
        $uiJavaOptions += @(
            "-Dhttps.proxyHost=$($proxy.Host)", "-Dhttps.proxyPort=$($proxy.Port)",
            "-Dhttp.proxyHost=$($proxy.Host)", "-Dhttp.proxyPort=$($proxy.Port)",
            '-Dhttp.nonProxyHosts="localhost|127.*"'
        )
    }
    if ($env:LATTICE_UI_MAVEN_REPOSITORY) {
        $repository = $env:LATTICE_UI_MAVEN_REPOSITORY.Replace('"', '\"')
        $uiJavaOptions += ('-Dlattice.maven.repository="' + $repository + '"')
    }
    if ($gradleJvmOptions.Count -gt 0) { $env:GRADLE_OPTS = $gradleJvmOptions -join ' ' }
    $jdkOptions = @()
    if ($oldJdkJavaOptions) { $jdkOptions += $oldJdkJavaOptions }
    $jdkOptions += $uiJavaOptions
    $env:JDK_JAVA_OPTIONS = $jdkOptions -join ' '

    $gradleArguments = @('--no-daemon', 'uiScreenshotTest')
    if ($env:LATTICE_BUILD_IDE) { $gradleArguments += "-Plattice.ide.home=$env:LATTICE_BUILD_IDE" }
    if ($env:LATTICE_UI_MAVEN_REPOSITORY) { $gradleArguments += "-Plattice.ui.maven.repository=$env:LATTICE_UI_MAVEN_REPOSITORY" }
    if ($IdeHome) {
        $idePath = (Resolve-Path -LiteralPath $IdeHome).Path
        $gradleArguments += "-Plattice.ui.ide.home=$idePath"
    }
    if ($Theme) { $gradleArguments += "-Plattice.ui.theme=$Theme" }
    if ($Review) { $gradleArguments += '-Plattice.ui.review=true' }
    if ($InputsOnly -or $env:LATTICE_UI_INPUTS_ONLY -eq 'true') { $gradleArguments += '-Plattice.ui.inputsOnly=true' }
    if ($StyleOnly -or $env:LATTICE_UI_STYLE_ONLY -eq 'true') { $gradleArguments += '-Plattice.ui.styleOnly=true' }
    if ($Output) {
        $outputPath = [IO.Path]::GetFullPath($Output)
        $gradleArguments += "-Plattice.ui.output=$outputPath"
    }
    if ($env:LATTICE_UI_PLUGIN_VERSION) { $gradleArguments += "-PreleaseVersion=$env:LATTICE_UI_PLUGIN_VERSION" }
    if ($env:LATTICE_UI_RELEASE_NOTES_FILE) { $gradleArguments += "-PreleaseNotesFile=$env:LATTICE_UI_RELEASE_NOTES_FILE" }
    $gradleArguments += $GradleArgs

    Add-Type -AssemblyName System.Windows.Forms
    $desktop = [Windows.Forms.Screen]::PrimaryScreen.Bounds
    if ($desktop.Width -lt 1440 -or $desktop.Height -lt 800) {
        throw "Primary display is $($desktop.Width)x$($desktop.Height); real IDE capture requires at least 1440x800."
    }
    Write-Host "Using Java 21: $selectedJavaHome"
    Write-Host "Using Windows desktop: $($desktop.Width)x$($desktop.Height)"
    Write-Host 'Keep the test IDE visible and in the foreground during automation.'
    $scriptErrorPreference = $ErrorActionPreference
    try {
        # Java writes its harmless JDK_JAVA_OPTIONS notice to stderr. PowerShell 5
        # promotes native stderr to an error record when the preference is Stop.
        $ErrorActionPreference = 'Continue'
        Invoke-LatticeCommand (@($env:COMSPEC, '/d', '/c', '.\gradlew.bat') + $gradleArguments) $Python
        $gradleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $scriptErrorPreference
    }
    if ($gradleExitCode -ne 0) { throw "Gradle uiScreenshotTest failed with exit code $gradleExitCode." }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:GRADLE_OPTS = $oldGradleOpts
    $env:JDK_JAVA_OPTIONS = $oldJdkJavaOptions
    Pop-Location
}
