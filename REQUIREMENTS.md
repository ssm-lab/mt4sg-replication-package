# Requirements

## Operating system

The artifact has been tested on Windows 10 build 19045. The CARLA build and `run_experiments.ps1` are for Windows.

## Hardware

The measurements in `02-data` were taken on an AMD Ryzen 9 7950X (16 cores, 32 threads) with 64 GB RAM. CARLA, the scene extractor, and the analysis pipeline run on the same machine, so all timestamps share 1 clock.

## Software

| Software | Version |
|---|---|
| CARLA | 0.9.16 |
| Java | OpenJDK 21.0.4 (Temurin) |
| Eclipse | Eclipse Modeling Tools 2025-06, with VIATRA 2.9.1 and EMF 2.39 |
| mt4sg | [release v0.1.0](https://github.com/ryangowland1/mt4sg) |
| Carla2Ecore | [release v0.1.0](https://github.com/ryangowland1/carla2ecore) |
| Python (CARLA client) | 3.12.10, with pyzmq 27.1.0 |
| Python (analysis) | 3.10 or higher |

The [mt4sg quick start](https://github.com/ryangowland1/mt4sg/blob/v0.1.0/QUICKSTART.md) installs CARLA, Eclipse, and the CARLA client. The measurements ran on the Java in this table, and the scripts run on the Java 21 that comes with Eclipse.

## Python dependencies

The packages for the analysis are listed in `03-analysis/requirements.txt`.
Install them inside a virtual environment (see the README for setup instructions).

## Storage

`02-data` takes 33 MB. `run_experiments.ps1` and `run_profiles.ps1` write to `rerun/data`, so `02-data` stays as recorded.
