#!/usr/bin/env python3
"""Summarizes the simulation data into the results in 04-results.

Averages every scene of the runs of 1 scenario, external vehicle count, and
strategy, starting warmup_s seconds into each run. Processing time runs from a
scene's arrival until all triggered transformations have completed.

Typical usage example:

  python analyze.py
  python analyze.py --data ../02-data --results ../04-results
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

import pandas as pd
from matplotlib import pyplot as plt
from matplotlib import ticker

ROOT = Path(__file__).resolve().parent.parent
STAGES = ["parse_ms", "update_ms", "query_ms", "transformation_ms"]
STAGE_LABELS = ["Parsing", "Model update", "Query", "Transformation"]
STRATEGIES = ["incremental", "batch"]
STRATEGY_STYLE = {"incremental": ("#2a78d6", "o"), "batch": ("#eb6834", "s")}
STAGE_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
INK = "#0b0b0b"
MUTED = "#52514e"
GRID = "#e4e3dc"


def load(data):
    """Returns every measured scene as 1 DataFrame, tagged with its run.

    Raises:
        ValueError: A run's measurements don't cover every scene it sent.
    """
    frames = []
    for path in sorted((data / "measurements").glob("*.csv")):
        scenario, external, seed, strategy = path.stem.split("-")
        meta_path = data / "scenes" / f"{path.stem}.jsonl.meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        frame = pd.read_csv(path)
        if len(frame) != meta["scenes"]:
            raise ValueError(
                f"{path.name}: {len(frame)} of {meta['scenes']} scenes measured"
            )
        frame["scenario"] = scenario.upper()
        frame["external"] = int(external)
        frame["seed"] = int(seed)
        frame["strategy"] = strategy
        frames.append(frame)
    return pd.concat(frames, ignore_index=True)


def summarize(scenes, warmup_scenes):
    """Returns 1 row per scenario, external vehicle count, and strategy."""
    keys = ["scenario", "external", "strategy"]
    steady = scenes[scenes["tick"] > warmup_scenes]
    grouped = steady.groupby(keys)
    summary = grouped.agg(
        runs=("seed", "nunique"),
        scenes=("tick", "size"),
        vertices=("vertices", "mean"),
        vehicles=("vehicles", "mean"),
        edges=("edges", "mean"),
        distance_edges=("distance", "mean"),
        spatial_edges=("spatial", "mean"),
        lane_edges=("lane", "mean"),
        rss_longitudinal_edges=("rss_longitudinal", "mean"),
        rss_lateral_edges=("rss_lateral", "mean"),
        processing_ms=("total_ms", "mean"),
    )
    for stage in STAGES:
        summary[stage] = grouped[stage].mean()
    summary = summary.reset_index()
    # Labels each configuration by scenario, external vehicles, and edges, such
    # as A-40V-1950E, with the edges of the incremental strategy.
    edges = summary[summary["strategy"] == "incremental"].set_index(
        ["scenario", "external"]
    )["edges"]
    summary["configuration"] = [
        f"{scenario}-{external}V-{edges[(scenario, external)]:.0f}E"
        for scenario, external in zip(summary["scenario"], summary["external"])
    ]
    return summary


def scene_digest(path):
    """Returns a digest of a scene file's messages, without send timestamps."""
    digest = hashlib.sha256()
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            message = json.loads(line)
            message.pop("timestamp", None)
            digest.update(json.dumps(message, sort_keys=True).encode())
    return digest.hexdigest()


def check_scenes(data):
    """Returns, per seed, whether every strategy received the same scenes."""
    rows = []
    paths = sorted((data / "scenes").glob("*-incremental.jsonl.gz"))
    for incremental in paths:
        name = incremental.name.removesuffix("-incremental.jsonl.gz")
        batch = incremental.with_name(f"{name}-batch.jsonl.gz")
        same = batch.exists() and scene_digest(incremental) == scene_digest(
            batch
        )
        rows.append({"run": name, "same_scenes": same})
    return pd.DataFrame(rows)


def write_table(path, header, rows, columns):
    """Writes a booktabs tabular with the given header lines and rows."""
    lines = [
        rf"\begin{{tabular}}{{@{{}}{columns}@{{}}}}",
        r"\toprule",
        *header,
        r"\midrule",
        *(" & ".join(row) + r" \\" for row in rows),
        r"\bottomrule",
        r"\end{tabular}",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_rq1_table(summary, path):
    """Writes the average processing time of both strategies and their ratio."""
    wide = summary.pivot_table(
        index=["scenario", "external", "configuration"],
        columns="strategy",
        values="processing_ms",
    )
    rows = [
        [
            configuration,
            *(f"{row[strategy]:.1f}" for strategy in STRATEGIES),
            f"{row['batch'] / row['incremental']:.1f}",
        ]
        for (_, _, configuration), row in wide.iterrows()
    ]
    write_table(
        path,
        [r"Configuration & Incremental & Batch & Ratio \\"],
        rows,
        "lrrr",
    )


def write_rq2_table(summary, path):
    """Writes the average time per stage of both strategies."""
    rows = []
    for _, row in summary.sort_values(
        ["scenario", "external", "strategy"], ascending=[True, True, False]
    ).iterrows():
        rows.append(
            [
                row["configuration"],
                row["strategy"],
                *(f"{row[stage]:.2f}" for stage in STAGES),
            ]
        )
    write_table(
        path,
        [r"Configuration & Strategy & " + " & ".join(STAGE_LABELS) + r" \\"],
        rows,
        "llrrrr",
    )


def style(axis):
    """Applies the shared look of the plots to axis."""
    axis.spines[["top", "right"]].set_visible(False)
    axis.spines[["left", "bottom"]].set_color(MUTED)
    axis.tick_params(colors=MUTED, labelcolor=INK, labelsize=7)
    axis.grid(axis="y", color=GRID, linewidth=0.5)
    axis.set_axisbelow(True)


def plot_scaling(summary, path, period_ms):
    """Plots average processing time against edge count."""
    figure, axes = plt.subplots(1, 2, figsize=(7.0, 2.6), sharey=True)
    for axis, scenario in zip(axes, ["A", "B"]):
        style(axis)
        for strategy in STRATEGIES:
            color, marker = STRATEGY_STYLE[strategy]
            rows = summary[
                (summary["scenario"] == scenario)
                & (summary["strategy"] == strategy)
            ]
            rows = rows.sort_values("edges")
            axis.plot(
                rows["edges"],
                rows["processing_ms"],
                color=color,
                linewidth=1.5,
                marker=marker,
                markersize=5,
                markeredgecolor="white",
                label=strategy.capitalize(),
            )
        axis.set_xscale("log")
        axis.set_yscale("log")
        for scale in (axis.xaxis, axis.yaxis):
            scale.set_major_locator(ticker.LogLocator(subs=(1, 2, 5)))
            scale.set_major_formatter(ticker.StrMethodFormatter("{x:g}"))
            scale.set_minor_formatter(ticker.NullFormatter())
        axis.axhline(period_ms, color=MUTED, linewidth=0.8)
        axis.annotate(
            f"{period_ms:.0f} ms scene period",
            (0.02, period_ms),
            xycoords=("axes fraction", "data"),
            xytext=(0, -3),
            textcoords="offset points",
            va="top",
            fontsize=7,
            color=MUTED,
        )
        axis.set_title(f"Scenario {scenario}", fontsize=8, color=INK)
        axis.set_xlabel("Edges in the scene graph", fontsize=7, color=INK)
    axes[0].set_ylabel(
        "Average processing time per scene (ms)", fontsize=7, color=INK
    )
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(
        handles, labels, fontsize=7, frameon=False, loc="lower center", ncol=2
    )
    figure.tight_layout(rect=(0, 0.1, 1, 1))
    figure.savefig(path, bbox_inches="tight")
    figure.savefig(path.with_suffix(".png"), dpi=200, bbox_inches="tight")
    plt.close(figure)


def plot_stages(summary, path):
    """Plots average stage times as stacked bars, 1 panel per strategy."""
    figure, axes = plt.subplots(1, 2, figsize=(7.0, 2.6), sharey=True)
    for axis, strategy in zip(axes, STRATEGIES):
        style(axis)
        rows = summary[summary["strategy"] == strategy].sort_values(
            ["scenario", "external"]
        )
        labels = rows["configuration"].tolist()
        bottom = [0.0] * len(rows)
        for stage, label, color in zip(STAGES, STAGE_LABELS, STAGE_COLORS):
            values = rows[stage].tolist()
            axis.bar(
                labels,
                values,
                bottom=bottom,
                color=color,
                width=0.6,
                edgecolor="white",
                linewidth=0.8,
                label=label,
            )
            bottom = [b + v for b, v in zip(bottom, values)]
        axis.set_title(strategy.capitalize(), fontsize=8, color=INK)
        axis.set_xlabel("Configuration", fontsize=7, color=INK)
        axis.tick_params(axis="x", labelrotation=90)
    axes[0].set_ylabel("Average time per scene (ms)", fontsize=7, color=INK)
    axes[0].legend(fontsize=7, frameon=False, loc="upper left")
    figure.tight_layout()
    figure.savefig(path, bbox_inches="tight")
    figure.savefig(path.with_suffix(".png"), dpi=200, bbox_inches="tight")
    plt.close(figure)


def parse_args():
    """Returns the data, results, and configuration paths."""
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--data", type=Path, default=ROOT / "02-data")
    parser.add_argument("--results", type=Path, default=ROOT / "04-results")
    parser.add_argument(
        "--config",
        type=Path,
        default=ROOT / "01-configurations" / "evaluation.json",
    )
    return parser.parse_args()


def main():
    """Writes the statistics, tables, and plots to the results directory."""
    args = parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    period_ms = 1000.0 / config["scene_rate_hz"]
    warmup_scenes = round(config["warmup_s"] * config["scene_rate_hz"])
    plots = args.results / "plots"
    statistics = args.results / "statistics"
    tables = args.results / "tables"
    for folder in (plots, statistics, tables):
        folder.mkdir(parents=True, exist_ok=True)
    summary = summarize(load(args.data), warmup_scenes)
    summary.to_csv(statistics / "summary.csv", index=False, float_format="%.4f")
    check = check_scenes(args.data)
    check.to_csv(statistics / "scene_check.csv", index=False)
    write_rq1_table(summary, tables / "rq1_processing_time.tex")
    write_rq2_table(summary, tables / "rq2_stages.tex")
    plot_scaling(summary, plots / "rq3_scaling.pdf", period_ms)
    plot_stages(summary, plots / "rq2_stages.pdf")
    with pd.option_context("display.width", 250, "display.max_columns", 40):
        print(summary.round(2).to_string(index=False))
    print(
        f"Seeds with the same scenes for both strategies: "
        f"{int(check['same_scenes'].sum())} of {len(check)}"
    )


if __name__ == "__main__":
    main()
