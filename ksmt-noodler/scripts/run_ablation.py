#!/usr/bin/env python3
"""Prepare and run the complete resumable, sequential Yices ablation matrix.

prepare snapshots compiled runtime entries and checks all reference corpus hashes.
run executes all 14 variants on missed SAT cases, audits them, then executes all
14 on every eligible case. No historical result replaces a fresh measurement.
"""

import argparse
import csv
import json
import os
import platform
import shutil
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from analyze_ablation import STAGES, VARIANTS, analyze, atomic_text, read_csv, suite_variant_settings, variant_settings
from analyze_benchmark import AnalysisError, require, sha


MAIN = "io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt"


def file_hash(path):
    import hashlib
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_source(source, name):
    require(name and "\\" not in name and not Path(name).is_absolute() and ".." not in Path(name).parts,
            "Invalid source path: " + name)
    path = (source / name).resolve()
    require(path.is_relative_to(source), "Source escapes corpus: " + name)
    return path


def select_cases(reference, inventory):
    entries = {r["sample"]: r for r in inventory}
    require(len(entries) == len(inventory), "Duplicate inventory source")
    eligible, excluded, sat, hybrid_sat = set(), set(), set(), set()
    for row in reference:
        name = row["sample"]
        require(name in entries and row["source_sha256"] == entries[name]["sha256"], "Reference source mismatch")
        if row["eligible"] == "true":
            eligible.add(name)
        if row["eligible"] == "false":
            excluded.add(name)
        if row["outcome"] == "SAT":
            require(row["validation_status"] == "SAT", "Reference SAT is not validated")
            sat.add(name)
            if row["solver"] == "noodler-yices":
                hybrid_sat.add(name)
    require(not eligible & excluded, "Conflicting reference eligibility")
    require(eligible | excluded == set(entries), "Reference does not classify the complete inventory")
    missed = sat - hybrid_sat
    require(missed <= eligible, "Diagnostic selection contains an excluded case")
    return {"missed-sat": {n: entries[n]["sha256"] for n in sorted(missed)},
            "all-eligible": {n: entries[n]["sha256"] for n in sorted(eligible)}}, excluded


def snapshot_runtime(classpath, destination):
    destination.mkdir()
    manifest, snapshot_entries = [], []
    for index, value in enumerate(classpath.split(os.pathsep)):
        require(bool(value), "Empty classpath entry")
        original = Path(value).resolve()
        require(original.exists(), "Missing runtime entry: " + str(original))
        target = destination / f"{index:03d}-{original.name}"
        if original.is_dir():
            shutil.copytree(original, target)
            files = sorted(p for p in target.rglob("*") if p.is_file())
            digests = {str(p.relative_to(target)).replace("\\", "/"): file_hash(p) for p in files}
        else:
            shutil.copy2(original, target)
            digests = {".": file_hash(target)}
        manifest.append({"original": str(original), "snapshot": str(target), "files": digests})
        snapshot_entries.append(str(target))
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return os.pathsep.join(snapshot_entries)


def prepare(args):
    root, source, output = args.repo.resolve(), args.source.resolve(), args.output.resolve()
    require(not output.exists(), "Suite output already exists; use run --suite to resume")
    require(args.java.is_file(), "Java executable not found")
    reference, inventory = read_csv(args.reference), read_csv(args.manifest)
    selections, excluded = select_cases(reference, inventory)
    require(len(selections["missed-sat"]) == 114, "Expected the approved 114-case diagnostic set")
    require(len(selections["all-eligible"]) == 752 and len(inventory) == 3248,
            "Reference inventory changed; review the intended corpus")
    print(f"Checking all {len(inventory)} source hashes...", flush=True)
    for entry in inventory:
        path = safe_source(source, entry["sample"])
        require(file_hash(path) == entry["sha256"], "Source changed: " + entry["sample"])
    output.mkdir(parents=True)
    (output / "preparation.json").write_text(json.dumps({"state": "preparing", "created_utc":
        datetime.now(timezone.utc).isoformat()}) + "\n", encoding="utf-8")
    (output / "selections").mkdir()
    (output / "logs").mkdir()
    for stage, entries in selections.items():
        (output / "selections" / (stage + ".txt")).write_text("\n".join(entries) + "\n", encoding="utf-8")
    shutil.copy2(args.reference, output / "reference-outcomes.csv")
    shutil.copy2(args.manifest, output / "reference-manifest.csv")
    (output / "reference-exclusions.txt").write_text("\n".join(sorted(excluded)) + "\n", encoding="utf-8")
    classpath = snapshot_runtime(args.classpath_file.read_text(encoding="utf-8-sig").strip(), output / "runtime")
    java_version = subprocess.run([str(args.java), "-version"], capture_output=True, text=True, check=True)
    configuration = {
        "schema": 1, "created_utc": datetime.now(timezone.utc).isoformat(), "repo": str(root),
        "source": str(source), "java": str(args.java.resolve()), "classpath": classpath,
        "java_version": java_version.stdout + java_version.stderr, "platform": platform.platform(),
        "solve-ms": 10000, "startup-parse-ms": 30000, "validation-ms": 10000, "repeats": 1,
        "variants": VARIANTS, "variant_settings": {v: dict(variant_settings(v),
            **{"adaptive-length-projection": args.adaptive_length_projection}) for v in VARIANTS},
        "selections": selections, "inventory_count": len(inventory), "excluded_count": len(excluded),
        "reference": str(args.reference.resolve()), "reference_sha256": file_hash(args.reference),
        "reference_manifest_sha256": file_hash(args.manifest),
        "runtime_manifest_sha256": file_hash(output / "runtime" / "manifest.json"),
        "selection_sha256": {s: file_hash(output / "selections" / (s + ".txt")) for s in STAGES},
    }
    (output / "suite.json").write_text(json.dumps(configuration, indent=2) + "\n", encoding="utf-8")
    atomic_text(output / "preparation.json", json.dumps({"state": "ready"}) + "\n")
    analyze(output)
    print("Prepared suite: " + str(output), flush=True)


def verify_suite(suite, configuration):
    current_java = subprocess.run([configuration["java"], "-version"], capture_output=True, text=True, check=True)
    require(current_java.stdout + current_java.stderr == configuration["java_version"], "Java runtime version changed")
    require(file_hash(suite / "reference-outcomes.csv") == configuration["reference_sha256"], "Reference changed")
    require(file_hash(suite / "reference-manifest.csv") == configuration["reference_manifest_sha256"], "Inventory changed")
    runtime_manifest = suite / "runtime" / "manifest.json"
    require(file_hash(runtime_manifest) == configuration["runtime_manifest_sha256"], "Runtime manifest changed")
    for entry in json.loads(runtime_manifest.read_text(encoding="utf-8")):
        target = Path(entry["snapshot"])
        actual_names = ({str(p.relative_to(target)).replace("\\", "/") for p in target.rglob("*") if p.is_file()}
                        if target.is_dir() else {"."})
        require(actual_names == set(entry["files"]), "Runtime file set changed")
        for name, digest in entry["files"].items():
            require(file_hash(target if name == "." else target / name) == digest, "Runtime file changed: " + name)
    for stage in STAGES:
        require(file_hash(suite / "selections" / (stage + ".txt")) == configuration["selection_sha256"][stage],
                "Source selection changed")
    source = Path(configuration["source"])
    for entry in read_csv(suite / "reference-manifest.csv"):
        require(file_hash(safe_source(source, entry["sample"])) == entry["sha256"], "Corpus changed: " + entry["sample"])


@contextmanager
def suite_lock(path):
    with path.open("a+b") as stream:
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            if stream.read(1) == b"":
                stream.write(b"0")
                stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def checked_status(suite):
    status = analyze(suite)
    require(not status["disagreements"], "Decisive disagreement: stopped before further experiments; inspect analysis.md")
    require(not status["faults"], "Witness/worker failure: stopped; inspect analysis.md and logs")
    require(not status["wall_outliers"], "Host-interruption outlier: retained raw data; explicit curation is required")
    return status


def completed_count(parent):
    return sum(1 for _ in parent.glob("*/records/*.properties"))


def run(args):
    suite = args.suite.resolve()
    require((suite / "suite.json").is_file(),
            "Suite preparation is incomplete; preserve this directory and prepare a new output directory")
    configuration = json.loads((suite / "suite.json").read_text(encoding="utf-8"))
    with suite_lock(suite / ".suite.lock"):
        print("Verifying source inventory and frozen runtime...", flush=True)
        verify_suite(suite, configuration)
        checked_status(suite)
        for stage in STAGES:
            for variant in VARIANTS:
                parent = suite / "runs" / stage / variant
                expected = len(configuration["selections"][stage])
                if completed_count(parent) == expected:
                    continue
                runs = list(parent.glob("*/metadata.properties"))
                require(len(runs) <= 1, "Multiple runs in " + str(parent))
                command = [configuration["java"], "-Xss8m", "-cp", configuration["classpath"], MAIN, "run"]
                if runs:
                    command += ["--resume", str(runs[0].parent)]
                else:
                    command += ["--source", configuration["source"], "--source-list",
                                str(suite / "selections" / (stage + ".txt")), "--output", str(parent),
                                "--solvers", "noodler-yices", "--variant", variant]
                    for key in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats"):
                        command += ["--" + key, str(configuration[key])]
                    for key, value in suite_variant_settings(configuration, variant).items():
                        command += ["--" + key, value]
                stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
                log = suite / "logs" / f"{stage}-{variant}-{stamp}.log"
                print(f"Starting {stage}/{variant}: {completed_count(parent)}/{expected}; log={log}", flush=True)
                atomic_text(suite / "active.json", json.dumps({"stage": stage, "variant": variant,
                            "started_utc": stamp, "launcher_pid": os.getpid(), "log": str(log)}, indent=2) + "\n")
                with log.open("w", encoding="utf-8") as stream:
                    process = subprocess.Popen(command, cwd=configuration["repo"], stdout=stream,
                                               stderr=subprocess.STDOUT)
                    last_report = time.monotonic()
                    try:
                        while process.poll() is None:
                            time.sleep(5)
                            if time.monotonic() - last_report >= 60:
                                status = checked_status(suite)
                                print(f"Progress {stage}/{variant}: {completed_count(parent)}/{expected}; "
                                      f"suite {status['recorded']}/{status['expected']}", flush=True)
                                last_report = time.monotonic()
                    except BaseException:
                        if process.poll() is None:
                            if os.name == "nt":
                                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                               capture_output=True, check=False)
                            else:
                                process.terminate()
                            process.wait(timeout=15)
                        raise
                require(process.returncode == 0, f"Benchmark launcher failed ({process.returncode}); see {log}")
                status = checked_status(suite)
                require(completed_count(parent) == expected, "Benchmark returned with missing attempts")
                print(f"Completed {stage}/{variant}; suite {status['recorded']}/{status['expected']}", flush=True)
            print("Stage complete: " + stage, flush=True)
            if args.until_stage == stage:
                break
        status = checked_status(suite)
        atomic_text(suite / "active.json", json.dumps({"running": False, "complete": status["complete"]}, indent=2) + "\n")
        print("Report: " + str(suite / "analysis.md"), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("prepare")
    setup.add_argument("--repo", type=Path, default=Path.cwd())
    setup.add_argument("--source", type=Path, required=True)
    setup.add_argument("--reference", type=Path, required=True)
    setup.add_argument("--manifest", type=Path, required=True)
    setup.add_argument("--classpath-file", type=Path, required=True)
    setup.add_argument("--java", type=Path, required=True)
    setup.add_argument("--output", type=Path, required=True)
    setup.add_argument("--adaptive-length-projection", choices=("true", "false"), default="true")
    launch = commands.add_parser("run")
    launch.add_argument("--suite", type=Path, required=True)
    launch.add_argument("--until-stage", choices=STAGES)
    args = parser.parse_args()
    try:
        (prepare if args.command == "prepare" else run)(args)
    except (AnalysisError, OSError, ValueError) as error:
        parser.exit(1, f"Ablation stopped: {error}\n")


if __name__ == "__main__":
    main()
