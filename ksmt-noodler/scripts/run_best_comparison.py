#!/usr/bin/env python3
"""Wait for the full ablation, repeat its winner, and export a white table.

Selection is registered before launch: maximize solved cases, then validated SAT;
prefer smaller cap multipliers and fewer experimental policies on ties. All five
backends are rerun by default in fresh sequential worker JVMs. Historical rows
are never inserted into the new comparison.
"""

import argparse
import json
import os
import shutil
import subprocess
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from analyze_ablation import BASE_SETTINGS, CAPS, VARIANTS, analyze, atomic_text, read_csv, suite_variant_settings
from analyze_benchmark import AnalysisError, parse_properties, require
from run_ablation import MAIN, file_hash, suite_lock, verify_suite


ALL_SOLVERS = "z3,cvc5,noodler-z3,noodler-cvc5,noodler-yices"
SELECTION_POLICY = (
    "Maximize SAT+UNSAT on the completed all-eligible stage; then maximize validated SAT; "
    "then minimize the sum of the five cap/default ratios; then minimize enabled experimental "
    "policies; then use the predeclared VARIANTS order. Timings do not break ties."
)


def rank_variants(rows, configuration=None):
    groups = {variant: [r for r in rows if r.get("ablation_stage") == "all-eligible"
                        and r.get("ablation_variant") == variant] for variant in VARIANTS}
    baseline_cases = {r["sample"] for r in groups["baseline"]}
    require(bool(baseline_cases), "No full-set baseline cases")
    scores = []
    for variant, group in groups.items():
        require(len(group) == len(baseline_cases) and {r["sample"] for r in group} == baseline_cases,
                "Incomplete/duplicate full-set group: " + variant)
        require(all(r.get("validation_status") == "SAT" for r in group if r["outcome"] == "SAT"),
                "Unvalidated SAT in selection data")
        settings = suite_variant_settings(configuration or {}, variant)
        counts = Counter(r["outcome"] for r in group)
        cap_cost = sum(int(settings[key]) / int(BASE_SETTINGS[key]) for key in CAPS.values())
        policies = sum(settings[key] == "true" for key in ("optional-preprocessing", "small-first-search"))
        scores.append({"variant": variant, "cases": len(group), "sat": counts["SAT"], "unsat": counts["UNSAT"],
                       "solved": counts["SAT"] + counts["UNSAT"], "unknown": counts["UNKNOWN"],
                       "timeout": counts["TIMEOUT"], "cap_multiplier_sum": cap_cost,
                       "experimental_policies": policies, "settings": settings})
    return sorted(scores, key=lambda r: (-r["solved"], -r["sat"], r["cap_multiplier_sum"],
                                        r["experimental_policies"], VARIANTS.index(r["variant"])))


def clean_audit(status):
    require(not status["disagreements"] and not status["faults"] and not status["wall_outliers"],
            "Ablation audit has a correctness, worker, or timing flag; inspect its analysis before selection")


def verify_inventory(suite, configuration):
    source = Path(configuration["source"])
    expected = {r["sample"] for r in read_csv(suite / "reference-manifest.csv")}
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*")
              if p.is_file() and p.name.endswith(".smt2")}
    require(actual == expected, "Corpus file inventory changed; added or removed sources need a new experiment")


def verify_repeat(run, suite, configuration, winner, solvers, finished=False):
    metadata = parse_properties((run / "metadata.properties").read_bytes())
    expected = {"variant": winner, "solvers": solvers, "source": configuration["source"],
                "source-list": "", "prefix": "", "offset": "0", "limit": str(2**31 - 1),
                "classpath": configuration["classpath"], **suite_variant_settings(configuration, winner)}
    expected.update({key: str(configuration[key]) for key in
                     ("solve-ms", "startup-parse-ms", "validation-ms", "repeats")})
    expected.update(json.loads((suite / "status.json").read_text(encoding="utf-8"))["runtime"])
    for key, value in expected.items():
        require(metadata.get(key) == value, f"Repeat run does not match selected job: {key}")
    reference = read_csv(suite / "reference-manifest.csv")
    observed = read_csv(run / "manifest.csv")
    require(len(observed) == len(reference) and {r["sample"]: r for r in observed} ==
            {r["sample"]: r for r in reference}, "Repeat manifest differs from the full reference inventory")
    if finished:
        known = {}
        for row in read_csv(run.parent.parent / "ablation-comparison.csv"):
            if row["outcome"] in ("SAT", "UNSAT"):
                known.setdefault(row["sample"], set()).add(row["outcome"])
        for record in (run / "records").glob("*.properties"):
            row = parse_properties(record.read_bytes())
            if row.get("outcome") in ("SAT", "UNSAT"):
                require(not known.get(row["sample"], set()) - {row["outcome"]},
                        "Fresh decisive answer conflicts with the ablation: " + row["sample"])


def wait_for_ablation(suite, destination):
    last_variant = None
    while True:
        status = json.loads((suite / "status.json").read_text(encoding="utf-8"))
        clean_audit(status)
        active = json.loads((suite / "active.json").read_text(encoding="utf-8"))
        if status["complete"] and active.get("running") is False:
            break
        variant = (active.get("stage"), active.get("variant"))
        if variant != last_variant:
            print(f"Waiting for completed ablation: {variant}; {status['recorded']}/{status['expected']}", flush=True)
            last_variant = variant
        atomic_text(destination / "status.json", json.dumps({"state": "waiting-for-ablation",
                    "controller_pid": os.getpid(),
                    "ablation_recorded": status["recorded"], "ablation_expected": status["expected"],
                    "active_ablation_variant": active.get("variant"), "updated_utc": datetime.now(timezone.utc).isoformat()},
                    indent=2) + "\n")
        time.sleep(10)
    status = analyze(suite)
    clean_audit(status)
    require(status["complete"], "Ablation became incomplete during final audit")


def select_winner(suite, destination, configuration):
    result = destination / "selection.json"
    if result.exists():
        selection = json.loads(result.read_text(encoding="utf-8"))
        require(selection["selection_policy"] == SELECTION_POLICY, "Selection rule changed")
        require(file_hash(destination / "ablation-comparison.csv") == selection["comparison_sha256"],
                "Selection evidence changed")
        return selection["winner"]
    wait_for_ablation(suite, destination)
    verify_suite(suite, configuration)
    verify_inventory(suite, configuration)
    evidence = destination / "ablation-comparison.csv"
    shutil.copy2(suite / "comparison.csv", evidence)
    ranking = rank_variants(read_csv(evidence), configuration)
    winner = ranking[0]["variant"]
    selection = {"selected_utc": datetime.now(timezone.utc).isoformat(), "selection_policy": SELECTION_POLICY,
                 "winner": winner, "ranking": ranking, "comparison_sha256": file_hash(evidence),
                 "ablation_suite": str(suite), "runtime": json.loads((suite / "status.json").read_text())["runtime"]}
    atomic_text(result, json.dumps(selection, indent=2) + "\n")
    print(f"Selected {winner}: {ranking[0]['solved']} solved, including {ranking[0]['sat']} validated SAT", flush=True)
    return winner


def run_repeat(suite, destination, configuration, winner, solvers):
    parent = destination / "runs"
    runs = list(parent.glob("*/metadata.properties"))
    require(len(runs) <= 1, "Multiple fresh runs require explicit curation")
    command = [configuration["java"], "-Xss8m", "-cp", configuration["classpath"], MAIN, "run"]
    if runs:
        verify_repeat(runs[0].parent, suite, configuration, winner, solvers)
        command += ["--resume", str(runs[0].parent)]
    else:
        command += ["--source", configuration["source"], "--output", str(parent), "--variant", winner,
                    "--solvers", solvers]
        for key in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats"):
            command += ["--" + key, str(configuration[key])]
        for key, value in suite_variant_settings(configuration, winner).items():
            command += ["--" + key, value]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    log = destination / f"benchmark-{stamp}.log"
    print("Starting fresh full comparison: " + str(log), flush=True)
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=configuration["repo"], stdout=output, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                records = sum(1 for _ in parent.glob("*/records/*.properties"))
                atomic_text(destination / "status.json", json.dumps({"state": "running-fresh-comparison",
                            "winner": winner, "solvers": solvers, "recorded": records,
                            "parent_pid": process.pid, "log": str(log),
                            "updated_utc": datetime.now(timezone.utc).isoformat()}, indent=2) + "\n")
                print(f"Fresh comparison ({winner}): {records} committed records, including exclusions", flush=True)
                for _ in range(12):
                    if process.poll() is not None:
                        break
                    time.sleep(5)
        except BaseException:
            if process.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False)
                else:
                    process.terminate()
                process.wait(timeout=15)
            raise
    require(process.returncode == 0, f"Fresh comparison failed; inspect {log}")
    completed = list(parent.glob("*/metadata.properties"))
    require(len(completed) == 1, "Fresh comparison did not publish one run")
    verify_repeat(completed[0].parent, suite, configuration, winner, solvers, finished=True)
    return completed[0].parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ablation-suite", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--solvers", default=ALL_SOLVERS, choices=(ALL_SOLVERS, "noodler-yices"))
    parser.add_argument("--render-python", required=True, type=Path)
    args = parser.parse_args()
    suite, destination = args.ablation_suite.resolve(), args.output.resolve()
    if destination.is_relative_to(suite) or suite.is_relative_to(destination):
        parser.error("Repeat output must be a separate directory outside the ablation suite and its ancestors")
    if destination.exists() and any(destination.iterdir()) and not (destination / "job.json").is_file():
        parser.error("Existing nonempty output is not a repeat job; choose a new output directory")
    destination.mkdir(parents=True, exist_ok=True)
    try:
        with suite_lock(destination / ".repeat.lock"):
            configuration = json.loads((suite / "suite.json").read_text(encoding="utf-8"))
            job = {"ablation_suite": str(suite), "ablation_suite_sha256": file_hash(suite / "suite.json"),
                   "selection_policy": SELECTION_POLICY, "solvers": args.solvers,
                   "render_python": str(args.render_python.resolve())}
            if (destination / "job.json").exists():
                require(json.loads((destination / "job.json").read_text(encoding="utf-8")) == job,
                        "Existing repeat job uses another configuration")
            else:
                atomic_text(destination / "job.json", json.dumps(job, indent=2) + "\n")
            winner = select_winner(suite, destination, configuration)
            verify_suite(suite, configuration)
            verify_inventory(suite, configuration)
            run = run_repeat(suite, destination, configuration, winner, args.solvers)
            renderer = Path(__file__).with_name("render_benchmark_table.py").resolve()
            require(args.render_python.is_file(), "Plotting Python executable is missing")
            subprocess.run([str(args.render_python), str(renderer), "--run", str(run),
                            "--output-prefix", str(destination / "benchmark-table"), "--overwrite",
                            "--selection-note", "Variant selected by solved coverage among 14 full-corpus "
                            "configurations; one repeat, not a speed ranking."], check=True,
                           cwd=configuration["repo"])
            atomic_text(destination / "status.json", json.dumps({"state": "complete", "winner": winner,
                        "run": str(run), "table_png": str(destination / "benchmark-table.png"),
                        "completed_utc": datetime.now(timezone.utc).isoformat()}, indent=2) + "\n")
            print("Saved white-background table: " + str(destination / "benchmark-table.png"), flush=True)
    except (AnalysisError, OSError, ValueError, subprocess.CalledProcessError) as error:
        atomic_text(destination / "status.json", json.dumps({"state": "stopped", "reason": str(error)}, indent=2) + "\n")
        parser.exit(1, f"Best-configuration repeat stopped: {error}\n")


if __name__ == "__main__":
    main()
