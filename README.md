# Data Package
**For the article *Real-time Safety Analysis of Driving Scenes by Incremental Model Transformations over Scene Graphs.***

---
## About

Real-time safety analysis of autonomous vehicles requires low-latency processing of continuously evolving driving environments. This is challenging because even small changes in the physical scene can alter multiple spatial and semantic relationships that must be updated and re-evaluated before safety conditions can be assessed.
In this work, we present a model-driven approach based on incrementally maintained scene graphs. Driving scenes are represented as typed scene graph models that are updated in place as new observations arrive. Incremental model transformations semantically enrich the scene graph and subsequently evaluate safety conditions, materializing detected violations as explicit model elements.
We implement the approach using EMF and \viatra{} and demonstrate its utility with Responsibility-Sensitive Safety (RSS) conditions.
Our work demonstrates how model-driven engineering can bring structure, efficiency, and extensibility to real-time safety analysis in dynamic driving environments.

## Content description
- `01-configurations` - Contains the CARLA configuration artifacts for the experiments.
- `02-data` - Contains simulation data obtained from CARLA using the configuration artifacts.
- `03-scripts` - Contains Python analysis scripts to obtain the results in the 04-results folder.
- `04-results`- Contains the data, plots and visualization that are used in the publication.

## Setup
