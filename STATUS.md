# Artifact Status

We apply for the following ACM badges for the paper
**_Real-time Safety Analysis of Driving Scenes by Incremental Model Transformations over Scene Graphs_**:

## Artifact Evaluated: Functional

- **Documented**: `README.md` explains the artifact's purpose, folder structure, and step-by-step reproduction instructions. `REQUIREMENTS.md` specifies the hardware and software environment. `analyze.py` includes module and function level docstrings, and `run_experiments.ps1` includes PowerShell help.
- **Consistent**: The artifact matches the paper's experiments. `01-configurations/` holds the scenario files and the settings of the paper's experiment setup. `analyze.py` produces the paper's tables and figures in `04-results/` from `02-data/`.
- **Complete**: The scenario files, the configuration, the run script, the analysis script, and the measurements and scene updates of all 120 simulation runs are included.
- **Exercisable**: The analysis runs with a single command from `03-analysis/`. The simulation runs start with a single command from `01-configurations/`.
- **Verification and validation**: `run_experiments.ps1` checks that the analysis pipeline measured every scene update sent, and replaces an invalid run with a run on the next random seed. `analyze.py` checks that both strategies received the same scenes for each seed and writes the result to `04-results/statistics/scene_check.csv`.
- **Reproducible**: The random seed of a run is the only source of randomness, so running a seed again sends the same scene updates. `analyze.py` reproduces the statistics, tables, and plots in `04-results/` exactly from `02-data/`.

## Artifact Evaluated: Reusable

- **Well-structured**: A numbered folder convention (`01-configurations`, `02-data`, `03-analysis`, `04-results`) separates inputs, data, scripts, and outputs.
- **Carefully documented**: `README.md` documents every configuration key and every measurement column.
- **Standard formats**: Configurations and scene updates use JSON, measurements and statistics use CSV, and figures use PDF and PNG. Dependencies are managed via `requirements.txt` and a virtual environment.
- **Reusable**: `run_experiments.ps1` runs any CARLA Scenario Editor scenario on a straight road, with the external vehicle counts, number of runs, and strategies set in `evaluation.json`. `analyze.py` reads any data directory in the layout of `02-data`.

## Artifact Available

- Archived on Zenodo: `TODO`.
- Released under the MIT License.
