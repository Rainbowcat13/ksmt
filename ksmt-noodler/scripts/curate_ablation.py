#!/usr/bin/env python3
"""Explicitly replace one host-interrupted ablation attempt, preserving its raw data.

Only an original wall time exceeding the sum of all budgets plus ten seconds is
eligible. The replacement uses the frozen runtime, unchanged solver settings and
budgets, and an exact singleton source list. The analyzer independently validates
the proposed registry before the derived comparison is accepted.
"""

import argparse
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from analyze_ablation import STAGES, VARIANTS, analyze, atomic_text, suite_variant_settings
from analyze_benchmark import AnalysisError, parse_properties, require
from run_ablation import MAIN, file_hash, suite_lock, verify_suite


def original_attempt(suite, configuration, stage, variant, sample):
    require(sample in configuration["selections"][stage], "Source is not in the requested stage")
    runs = list((suite / "runs" / stage / variant).glob("*/metadata.properties"))
    require(len(runs) == 1, "Expected exactly one original variant run")
    matches = []
    for path in (runs[0].parent / "records").glob("*.properties"):
        row = parse_properties(path.read_bytes())
        if row.get("sample") == sample:
            matches.append((path, row))
    require(len(matches) == 1, "Expected exactly one original attempt")
    record, row = matches[0]
    maximum = sum(configuration[key] for key in ("solve-ms", "startup-parse-ms", "validation-ms")) + 10000
    require(row.get("wall_ns", "").isdigit() and int(row["wall_ns"]) > maximum * 1_000_000,
            "Only an objectively flagged host wall-time outlier can be replaced")
    return record, row


def curate(args):
    suite = args.suite.resolve()
    with suite_lock(suite / ".suite.lock"):
        configuration = json.loads((suite / "suite.json").read_text(encoding="utf-8"))
        require(bool(args.reason.strip()), "An explicit replacement reason is required")
        original, row = original_attempt(suite, configuration, args.stage, args.variant, args.sample)
        registry_path = suite / "curations.json"
        previous = registry_path.read_text(encoding="utf-8") if registry_path.exists() else None
        registry = json.loads(previous) if previous else {"schema": 1, "replacements": []}
        require(registry.get("schema") == 1 and isinstance(registry.get("replacements"), list),
                "Unsupported curation registry")
        require(not any((item["stage"], item["variant"], item["sample"]) ==
                        (args.stage, args.variant, args.sample) for item in registry["replacements"]),
                "This attempt already has an explicit replacement")
        previous_status = analyze(suite)
        require(not previous_status["faults"] and not previous_status["disagreements"],
                "Resolve correctness or worker faults before timing curation")
        verify_suite(suite, configuration)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        destination = suite / "supplemental" / (stamp + "-" + args.variant)
        destination.mkdir(parents=True)
        selection = destination / "source-list.txt"
        selection.write_text(args.sample + "\n", encoding="utf-8", newline="")
        reason = {"stage": args.stage, "variant": args.variant, "sample": args.sample,
                  "original_run": str(original.parent.parent), "original_key": row["key"],
                  "original_record_sha256": file_hash(original), "reason": args.reason.strip()}
        atomic_text(destination / "reason.json", json.dumps(reason, indent=2) + "\n")
        command = [configuration["java"], "-Xss8m", "-cp", configuration["classpath"], MAIN, "run",
                   "--source", configuration["source"], "--source-list", str(selection),
                   "--output", str(destination / "runs"), "--solvers", "noodler-yices",
                   "--variant", args.variant]
        for key in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats"):
            command += ["--" + key, str(configuration[key])]
        for key, value in suite_variant_settings(configuration, args.variant).items():
            command += ["--" + key, value]
        print("Rerunning the flagged attempt; originals remain at " + str(original), flush=True)
        with (destination / "launcher.log").open("w", encoding="utf-8") as output:
            process = subprocess.Popen(command, cwd=configuration["repo"], stdout=output, stderr=subprocess.STDOUT)
            try:
                code = process.wait()
            except BaseException:
                if process.poll() is None:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                       capture_output=True, check=False)
                    else:
                        process.terminate()
                    process.wait(timeout=15)
                raise
            require(code == 0, "Supplemental worker launcher failed; inspect " + str(destination))
        metadata_files = list((destination / "runs").glob("*/metadata.properties"))
        require(len(metadata_files) == 1, "Supplemental launcher did not publish exactly one run")
        records = list((destination / "runs").glob("*/records/*.properties"))
        require(len(records) == 1, "Supplemental run did not publish exactly one attempt")
        replacement = records[0]
        require(replacement.parent.parent == metadata_files[0].parent,
                "Supplemental checkpoint is outside the recorded run")
        replacement_row = parse_properties(replacement.read_bytes())
        require(replacement_row.get("sample") == args.sample, "Supplemental source changed")
        entry = dict(reason, replacement_run=str(replacement.parent.parent),
                     replacement_key=replacement_row["key"], replacement_record_sha256=file_hash(replacement))
        registry["replacements"].append(entry)
        candidate = json.dumps(registry, indent=2) + "\n"
        atomic_text(destination / "proposed-curations.json", candidate)
        try:
            atomic_text(registry_path, candidate)
            status = analyze(suite)
            require(not status["faults"] and not status["disagreements"] and
                    status["wall_outliers"] == previous_status["wall_outliers"] - 1,
                    "Replacement audit did not clear exactly its timing flag; retain raw data for investigation")
            require(status.get("curated_rows", 0) == len(registry["replacements"]),
                    "Analyzer did not acknowledge the complete replacement registry")
        except BaseException:
            if previous is None:
                if registry_path.exists():
                    registry_path.unlink()
            else:
                atomic_text(registry_path, previous)
            analyze(suite)
            raise
        print("Explicit replacement accepted; provenance: " + str(registry_path), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--stage", choices=STAGES, required=True)
    parser.add_argument("--variant", choices=VARIANTS, required=True)
    parser.add_argument("--sample", required=True)
    parser.add_argument("--reason", required=True)
    args = parser.parse_args()
    try:
        curate(args)
    except (AnalysisError, OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Explicit curation stopped: " + str(error) + "\n")


if __name__ == "__main__":
    main()
