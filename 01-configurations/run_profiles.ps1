<#
.SYNOPSIS
    Profiles every recorded run of 1 scenario with Java Flight Recorder.

.DESCRIPTION
    For each run of -Scenario with a measurement file in -Data, starts the Evaluation class
    of the ca.mcmaster.mt4sg.evaluation plug-in with Flight Recorder on, then replays the
    run's recorded scene updates to it with profile_replay.py, at scene_rate_hz. CARLA isn't
    needed. After the last run, component_samples.py counts the samples of each component
    and writes them to -Out, by default in rerun\data, so 02-data stays as recorded.

    The recordings go to %TEMP%\mt4sg-profile, about 2 MB per run, which this script empties
    first. The mt4sg release, Carla2Ecore, and the evaluation plug-in must be built, as for
    run_experiments.ps1.

.EXAMPLE
    .\run_profiles.ps1
#>
param(
    [string]$Mt4sg = (Join-Path $PSScriptRoot "..\..\mt4sg"),
    [string]$Carla2Ecore = (Join-Path $PSScriptRoot "..\..\carla2ecore"),
    [string]$EclipsePlugins = (Join-Path $PSScriptRoot "..\..\eclipse\plugins"),
    [string]$Python = "",
    [string]$Scenario = "a",
    [string]$Config = (Join-Path $PSScriptRoot "evaluation.json"),
    [string]$Data = (Join-Path $PSScriptRoot "..\02-data"),
    [string]$Out = (Join-Path $PSScriptRoot "..\rerun\data\profile\component_samples.csv"),
    [string]$Endpoint = "tcp://127.0.0.1:5556",
    [string]$JavaOptions = "-Xmx8g"
)

$ErrorActionPreference = "Stop"
$Mt4sg = (Resolve-Path $Mt4sg).Path
$Carla2Ecore = (Resolve-Path $Carla2Ecore).Path
$EclipsePlugins = (Resolve-Path $EclipsePlugins).Path
# The analysis pipeline runs on the Java that comes with Eclipse.
$jre = Get-ChildItem $EclipsePlugins -Directory -Filter "org.eclipse.justj.openjdk.hotspot.jre.full.*" |
    Select-Object -First 1
if ($jre) { $env:PATH = (Join-Path $jre.FullName "jre\bin") + ";$env:PATH" }
if (-not $Python) { $Python = Join-Path $Mt4sg "carla\.venv\Scripts\python.exe" }
$Data = (Resolve-Path $Data).Path
$period = 1 / (Get-Content $Config -Raw | ConvertFrom-Json).scene_rate_hz
$recordings = Join-Path $env:TEMP "mt4sg-profile"
$log = Join-Path $recordings "run.log"
Remove-Item -Recurse -Force $recordings -ErrorAction SilentlyContinue
New-Item -ItemType Directory $recordings | Out-Null

# java reads the classpath from an argfile, where a backslash is an escape character.
$jars = Get-ChildItem $EclipsePlugins -Filter "*.jar" | Where-Object {
    $_.Name -notmatch '\.source_' -and
    $_.Name -match '^(org\.eclipse\.(emf|core|equinox|osgi|text|jface|viatra|xtext|xtend|collections)|org\.apache\.log4j|com\.google\.(guava|inject|gson)|javax\.inject|jakarta\.inject)' }
$bins = @(Join-Path $PSScriptRoot "ca.mcmaster.mt4sg.evaluation\bin") +
    ("models", "queries", "visualization", "core" |
        ForEach-Object { Join-Path $Mt4sg "plugins\ca.mcmaster.mt4sg.$_\bin" }) +
    @(Join-Path $Carla2Ecore "bin")
$libs = Get-ChildItem (Join-Path $Carla2Ecore "lib") -Filter "*.jar"
$argfile = Join-Path $recordings "profile.args"
"-cp `"$((@($jars.FullName) + @($libs.FullName) + $bins) -join ';' -replace '\\', '/')`"" |
    Set-Content -Encoding ascii $argfile

$runs = Get-ChildItem (Join-Path $Data "measurements") -Filter "$Scenario-*.csv"
foreach ($run in $runs) {
    $name = $run.BaseName
    $strategy = $name -replace '^[^-]+-\d+-\d+-', ''
    $recording = Join-Path $recordings $name
    $pipeline = Start-Process java -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -ArgumentList @(
            "@`"$argfile`"", $JavaOptions, "-XX:FlightRecorderOptions:stackdepth=2048",
            "`"-XX:StartFlightRecording:filename=$recording.jfr,settings=profile,method-profiling=max`"",
            "ca.mcmaster.mt4sg.evaluation.ProfileMain", $strategy, $Endpoint, "`"$recording.csv`"")
    # Carla2Ecore logs to standard error.
    while (-not (Select-String -Path "$log.err" -Pattern "Subscribed to" -Quiet -ErrorAction SilentlyContinue)) {
        if ($pipeline.HasExited) { throw "The pipeline exited before subscribing, see $log.err" }
        Start-Sleep -Milliseconds 200
    }
    & $Python (Join-Path $PSScriptRoot "profile_replay.py") `
        (Join-Path $Data "scenes\$name.jsonl.gz") ($Endpoint -replace "127\.0\.0\.1", "*") $period
    $pipeline.WaitForExit()
    Write-Host "[profile] $name done"
}

& $Python (Join-Path $PSScriptRoot "component_samples.py") $recordings $Out
