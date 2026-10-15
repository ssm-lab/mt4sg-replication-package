<#
.SYNOPSIS
    Runs every scenario, external vehicle count, seed, and strategy once, live in CARLA.

.DESCRIPTION
    For each run, starts mt4sg's Evaluation class subscribed to the scene extractor, then
    plays the scenario file in CARLA with scenario_evaluation.py. Writes
    to -OutDir:

      scenes\<scenario>-<external>-<seed>-<strategy>.jsonl.gz   the scenes as sent
      scenes\<...>.jsonl.meta.json                              the run's summary
      measurements\<scenario>-<external>-<seed>-<strategy>.csv  1 row per scene

    The seed initializes the random number generator of scenario_evaluation.py, which
    places the external vehicles. Seeds count up from 1. An invalid seed keeps only its
    summary, and the next seed runs instead, until each scenario and count has
    runs_per_count valid seeds. Runs that already have a measurement file are skipped,
    so the script resumes where it stopped.

    CARLA must be running, and mt4sg must be built, with each plug-in's classes in its bin
    folder.

.EXAMPLE
    .\run_experiments.ps1 -Mt4sg ..\..\mt4sg -EclipsePlugins C:\eclipse\plugins
#>
param(
    [Parameter(Mandatory = $true)][string]$Mt4sg,
    [Parameter(Mandatory = $true)][string]$EclipsePlugins,
    [string]$Python = "",
    [string]$Config = (Join-Path $PSScriptRoot "evaluation.json"),
    [string]$OutDir = (Join-Path $PSScriptRoot "..\02-data"),
    [string]$Endpoint = "tcp://127.0.0.1:5556",
    [string]$JavaOptions = "-Xmx8g"
)

$ErrorActionPreference = "Stop"
$Mt4sg = (Resolve-Path $Mt4sg).Path
if (-not $Python) { $Python = Join-Path $Mt4sg "carla\.venv\Scripts\python.exe" }
$Config = (Resolve-Path $Config).Path
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
$settings = Get-Content $Config -Raw | ConvertFrom-Json
$script = Join-Path $Mt4sg "carla\src\scenario_evaluation.py"
$scenes = Join-Path $OutDir "scenes"
$measurements = Join-Path $OutDir "measurements"
$log = Join-Path $env:TEMP "mt4sg-evaluation.log"
New-Item -ItemType Directory -Force $scenes, $measurements | Out-Null

# java reads the classpath from an argfile, where a backslash is an escape character.
$jars = Get-ChildItem $EclipsePlugins -Filter "*.jar" | Where-Object {
    $_.Name -notmatch '\.source_' -and
    $_.Name -match '^(org\.eclipse\.(emf|core|equinox|osgi|text|jface|viatra|xtext|xtend|collections)|org\.apache\.log4j|com\.google\.(guava|inject|gson)|javax\.inject|jakarta\.inject)' }
$bins = "models", "queries", "carla", "visualization", "core" |
    ForEach-Object { Join-Path $Mt4sg "plugins\ca.mcmaster.mt4sg.$_\bin" }
$libs = Get-ChildItem (Join-Path $Mt4sg "plugins\ca.mcmaster.mt4sg.carla\lib") -Filter "*.jar"
$argfile = Join-Path $env:TEMP "mt4sg-evaluation.args"
"-cp `"$((@($jars.FullName) + @($libs.FullName) + $bins) -join ';' -replace '\\', '/')`"" |
    Set-Content -Encoding ascii $argfile

# Returns $true for a valid run, $false for an invalid one.
function Invoke-LiveRun($Name, $Scenario, $External, $Seed, $Strategy) {
    $csv = Join-Path $measurements "$Name-$Strategy.csv"
    $out = Join-Path $scenes "$Name-$Strategy.jsonl.gz"
    $meta = Join-Path $scenes "$Name-$Strategy.jsonl.meta.json"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Remove-Item $log, "$log.err", $meta -ErrorAction SilentlyContinue
        $pipeline = Start-Process java -PassThru -NoNewWindow `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err" -ArgumentList @(
                "@`"$argfile`"", $JavaOptions, "ca.mcmaster.mt4sg.core.runner.Evaluation",
                $Strategy, $Endpoint, "`"$csv.partial`"")
        while (-not (Select-String -Path $log -Pattern "SUB connected" -Quiet -ErrorAction SilentlyContinue)) {
            if ($pipeline.HasExited) { throw "The pipeline exited before subscribing, see $log.err" }
            Start-Sleep -Milliseconds 200
        }
        & $Python $script --config $Config --scenario $Scenario --external $External `
            --seed $Seed --out $out --publish ($Endpoint -replace "127\.0\.0\.1", "*") |
            Where-Object { $_ -notmatch '^INFO:' } | Write-Host
        $status = $LASTEXITCODE
        $pipeline.WaitForExit()
        if (-not (Test-Path $meta)) { throw "The scenario failed: $Name $Strategy" }
        if ($status -ne 0) {
            Remove-Item "$csv.partial", $out -ErrorAction SilentlyContinue
            return $false
        }
        $sent = (Get-Content $meta | ConvertFrom-Json).scenes
        $processed = (Get-Content "$csv.partial").Count - 1
        if ($processed -eq $sent) {
            Move-Item -Force "$csv.partial" $csv
            return $true
        }
        Write-Host "[runs] $Name $Strategy processed $processed of $sent scenes, attempt $attempt"
    }
    throw "$Name $Strategy lost scenes in 3 attempts"
}

# Scenarios are named keys, each pointing at a scenario file.
foreach ($scenario in $settings.scenarios.PSObject.Properties.Name) {
    foreach ($external in $settings.external_vehicles) {
        $valid = 0
        for ($seed = 1; $valid -lt $settings.runs_per_count -and $seed -le 3 * $settings.runs_per_count; $seed++) {
            $name = "$scenario-$external-$seed"
            $ok = $true
            foreach ($strategy in $settings.strategies) {
                $meta = Join-Path $scenes "$name-$strategy.jsonl.meta.json"
                if (Test-Path (Join-Path $measurements "$name-$strategy.csv")) { continue }
                if ((Test-Path $meta) -and -not (Get-Content $meta | ConvertFrom-Json).valid) {
                    $ok = $false
                    break
                }
                if (-not (Invoke-LiveRun $name $scenario $external $seed $strategy)) {
                    if ($strategy -ne $settings.strategies[0]) {
                        throw "$name is valid for $($settings.strategies[0]) but not $strategy"
                    }
                    $ok = $false
                    break
                }
                Write-Host "[runs] $name $strategy done"
            }
            if ($ok) { $valid++ }
        }
        Write-Host "[runs] scenario $scenario, $external external vehicles: $valid valid seeds"
    }
}
