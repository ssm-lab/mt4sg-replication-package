#!/usr/bin/env python3
"""Counts the Java Flight Recorder samples of each component for every profiled run.

Reads each <run>.jfr in a folder with the measurement file <run>.csv beside it,
and writes 1 row per run and component. A sample counts when it falls in the
timed processing of a scene after the warmup. It goes to the innermost frame
that belongs to a known component, except that EMF code the query engine calls
goes to that VIATRA component. Needs jfr of a JDK on the PATH, which
run_profiles.ps1 sets up.

Typical usage example:

  python component_samples.py %TEMP%\\mt4sg-profile ..\\02-data\\profile\\component_samples.csv
"""

import collections
import csv
import datetime
import json
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# The first scene after the 2 s warmup at 10 Hz.
FIRST_COUNTED_TICK = 21
COMPONENTS = [
    ("org.eclipse.viatra.query.runtime.base.", "VIATRA Base"),
    ("org.eclipse.viatra.query.runtime.emf.", "VIATRA Base"),
    ("org.eclipse.viatra.query.runtime.", "Rete network"),
    ("ca.mcmaster.mt4sg.queries.", "Rete network"),
    ("org.eclipse.viatra.transformation.runtime.emf.modelmanipulation.", "EMF"),
    ("org.eclipse.viatra.transformation.", "EVM"),
    ("ca.mcmaster.mt4sg.core.runner.Transformations", "Rule actions"),
    ("ca.mcmaster.mt4sg.evaluation.BatchTransformations", "Rule actions"),
    ("ca.mcmaster.mt4sg.evaluation.JavaGraphTraversal", "Java graph traversal"),
    ("org.eclipse.emf.", "EMF"),
]
# Work of the evaluation itself, outside the timed stages of a scene.
UNTIMED = (
    "Evaluation$Strategy.fullSnapshot",
    "Evaluation$Pipeline.counts",
    "Evaluation$Pipeline.dispose",
)


def seconds(stamp):
    """Returns a Flight Recorder timestamp as epoch seconds."""
    return datetime.datetime.fromisoformat(
        re.sub(r"(\.\d{6})\d+", r"\1", stamp)).timestamp()


def label_of(name):
    return next((label for prefix, label in COMPONENTS if name.startswith(prefix)), None)


def component(names):
    """Returns the component of a stack trace, innermost frame first."""
    known = [label for label in map(label_of, names) if label]
    if not known:
        return "Other"
    # Like the query engine's EMF calls, the EMF reads and RSS helper calls of the
    # Java graph traversal count for it.
    if "Java graph traversal" in known:
        return "Java graph traversal"
    if known[0] == "EMF":
        # EMF code that the query engine runs for itself, such as comparing Rete
        # recipes or attaching its index adapters to the model, counts for that
        # VIATRA component.
        caller = next((label for label in known if label != "EMF"), "EMF")
        if caller in ("Rete network", "VIATRA Base"):
            return caller
    return known[0]


def recording(jfr):
    return subprocess.run(
        ["jfr", "print", "--json", "--stack-depth", "2048", "--events",
         "jdk.ExecutionSample", str(jfr)],
        capture_output=True, check=True).stdout


def counts_of(jfr, raw):
    """Counts the samples of each component in 1 run's recording."""
    with open(jfr.with_suffix(".csv"), newline="") as f:
        rows = list(csv.DictReader(f))
    warmup_end = next(float(r["received_ms"]) / 1000 for r in rows
                      if int(r["tick"]) == FIRST_COUNTED_TICK)
    counts = collections.Counter()
    for event in json.loads(raw)["recording"]["events"]:
        if seconds(event["values"]["startTime"]) < warmup_end:
            continue
        names = [(f["method"]["type"]["name"] + "." + f["method"]["name"]).replace("/", ".")
                 for f in event["values"]["stackTrace"]["frames"]]
        if not any("Evaluation$Strategy.process" in n for n in names):
            continue
        if any(any(u in n for u in UNTIMED) for n in names) or any(
                n.endswith("CommandStack.flush") or "CommandStackImpl.flush" in n
                for n in names):
            continue
        counts[component(names)] += 1
    return counts


def main():
    folder, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    jfrs = sorted(folder.glob("*.jfr"))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", newline="") as out, ThreadPoolExecutor(8) as pool:
        writer = csv.writer(out)
        writer.writerow(["scenario", "external", "strategy", "component", "samples"])
        for jfr, raw in zip(jfrs, pool.map(recording, jfrs)):
            scenario, external, _, strategy = jfr.stem.split("-", 3)
            counts = counts_of(jfr, raw)
            for label, n in sorted(counts.items()):
                writer.writerow([scenario.upper(), int(external), strategy, label, n])
            total = sum(counts.values())
            print(f"{jfr.stem}: {total} samples, " + ", ".join(
                f"{k} {v / total:.0%}" for k, v in counts.most_common()), flush=True)


if __name__ == "__main__":
    main()
