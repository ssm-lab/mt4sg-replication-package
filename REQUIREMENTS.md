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
| mt4sg | commit `b06b753` |
| Python (CARLA client) | 3.12.10, with pyzmq 27.1.0 |
| Python (analysis) | 3.10 or higher |

## Python dependencies

The packages for the analysis are listed in `03-analysis/requirements.txt`.
Install them inside a virtual environment (see the README for setup instructions).

## Storage

`02-data` takes 15 MB. `run_experiments.ps1` writes new runs to `02-data`.
