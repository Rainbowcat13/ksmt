#!/usr/bin/env python3
"""Audit one fresh benchmark run and export a white PNG/SVG table plus CSV/Markdown.

Use build/noodler-report-venv/Scripts/python.exe on Windows. The dedicated venv
contains matplotlib and Pillow; the audit itself uses only the standard library.
Bootstrap from the repository root (Python 3.12):
  python -m venv build/noodler-report-venv
  build/noodler-report-venv/Scripts/python.exe -m pip install -r ksmt-noodler/scripts/requirements-benchmark-table.txt
No solver is launched, and no input file is changed. Final exports require a
complete, consistent run without correctness, worker-failure, or wall-time flags.
--allow-partial explicitly permits a clearly marked preview instead.
--overwrite regenerates an existing prefix only after every new artifact is staged.
"""

import argparse
import csv
import json
import math
import re
import shutil
import tempfile
import textwrap
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from analyze_benchmark import AnalysisError, csv_rows, parse_properties, require, sha


LABELS = {
    "z3": "Z3", "cvc5": "cvc5", "noodler-z3": "Noodler + Z3",
    "noodler-cvc5": "Noodler + cvc5", "noodler-yices": "Noodler + Yices",
}
DECISIVE = {"SAT", "UNSAT"}
OUTCOMES = DECISIVE | {"UNKNOWN", "TIMEOUT", "EXCLUDED", "INVALID_WITNESS", "UNVERIFIED_WITNESS",
                       "UNSUPPORTED", "ERROR", "PARSE_ERROR", "CRASH", "STARTUP_PARSE_TIMEOUT"}
FAILURES = {"INVALID_WITNESS", "UNVERIFIED_WITNESS", "UNSUPPORTED", "ERROR", "PARSE_ERROR",
            "CRASH", "STARTUP_PARSE_TIMEOUT"}
TIME_COLUMNS = ("startup_ms", "parse_ms", "setup_ms", "solve_ms", "validation_ms", "wall_ms")
SETTINGS = ("max-automata-states", "max-automata-storage", "max-encoding-constraints",
            "max-preprocessing-units", "max-character-positions", "max-idle-rounds",
            "optional-preprocessing", "small-first-search")
OPTIONAL_SETTINGS = ("adaptive-length-projection",)
REQUIRED_COLUMNS = {"key", "sample_id", "sample", "source_sha256", "solver", "trial", "eligible",
                    "outcome", "raw_status", "validation_status", "log", "completed_utc"}


@dataclass
class TableData:
    run: Path
    metadata: dict
    manifest: dict
    rows: list
    solvers: list
    repeats: int
    excluded: set
    eligible: set
    unclassified: set
    completed: int
    expected: int
    missing: int
    warnings: list
    complete: bool
    input_hashes: dict
    input_source: str


def positive_int(value, label):
    require(str(value).isdigit() and int(value) > 0, "Invalid " + label)
    return int(value)


def milliseconds(row, name, required=False):
    text = row.get(name, "")
    require(bool(text) or not required, "Missing " + name + " for " + row.get("sample", "attempt"))
    if not text:
        return None
    try:
        value = float(text)
    except ValueError as error:
        raise AnalysisError("Invalid " + name + ": " + text) from error
    require(math.isfinite(value) and value >= 0, "Invalid " + name + ": " + text)
    return value


def checkpoint_row(record):
    row = dict(record)
    for column in TIME_COLUMNS:
        nanos = record.get(column.replace("_ms", "_ns"), "")
        if nanos:
            require(nanos.isdigit(), "Invalid checkpoint timing: " + column)
            row[column] = str(int(nanos) / 1_000_000)
        else:
            row[column] = ""
    return row


def load_records(run, allow_partial):
    """Prefer immutable checkpoints; a complete CSV must agree with them."""
    record_files = sorted((run / "records").glob("*.properties")) if (run / "records").is_dir() else None
    hashes, warnings = {}, []
    records = []
    if record_files is not None:
        for path in record_files:
            blob = path.read_bytes()
            record = parse_properties(blob)
            require(record.get("key") == path.stem, "Checkpoint filename/key mismatch: " + str(path))
            records.append(checkpoint_row(record))
            hashes[str(path.relative_to(run))] = sha(blob)
    raw_path = run / "raw.csv"
    raw_rows, columns = [], []
    raw_valid = False
    if raw_path.is_file():
        raw_blob = raw_path.read_bytes()
        hashes["raw.csv"] = sha(raw_blob)
        try:
            columns, raw_rows = csv_rows(raw_blob, REQUIRED_COLUMNS, "raw.csv")
            raw_valid = True
        except AnalysisError:
            require(allow_partial and record_files is not None, "Raw CSV is incomplete or malformed")
            warnings.append("Raw CSV is being written; preview uses immutable checkpoints")
    elif record_files is None:
        raise AnalysisError("Run has neither immutable records nor raw.csv")
    else:
        warnings.append("Raw CSV is missing; preview uses immutable checkpoints")
    if record_files is None:
        # With no checkpoints, final completeness and a stable raw-file checksum are mandatory below.
        return raw_rows, hashes, warnings, "raw.csv"
    by_key = {row.get("key"): row for row in records}
    require(len(by_key) == len(records), "Duplicate checkpoint key")
    if raw_valid:
        raw_by_key = {row["key"]: row for row in raw_rows}
        require(len(raw_by_key) == len(raw_rows), "Duplicate raw CSV key")
        if by_key.keys() != raw_by_key.keys():
            warnings.append("Raw CSV/checkpoint sets differ; preview uses the checkpoint snapshot")
        for key in by_key.keys() & raw_by_key.keys():
            record, raw = by_key[key], raw_by_key[key]
            for column in columns:
                if column in TIME_COLUMNS:
                    expected, actual = milliseconds(record, column), milliseconds(raw, column)
                    require((expected is None and actual is None) or
                            (expected is not None and actual is not None and abs(expected - actual) <= 0.00051),
                            "Raw CSV/checkpoint timing mismatch: " + key + "/" + column)
                else:
                    require(raw[column] == record.get(column, ""), "Raw CSV/checkpoint mismatch: " + key + "/" + column)
    return records, hashes, warnings, "immutable records"


def validate_row(row, metadata, manifest, solvers, repeats):
    require(REQUIRED_COLUMNS <= set(row), "Incomplete attempt record")
    name = row["sample"]
    require(name in manifest, "Attempt absent from manifest: " + name)
    entry = manifest[name]
    require(row["sample_id"] == entry["sample_id"] and row["source_sha256"] == entry["sha256"],
            "Attempt source/hash mismatch: " + name)
    for field in ("family", "logic", "expected_status"):
        require(row.get(field) == entry[field], "Attempt/manifest mismatch: " + name + "/" + field)
    require(row["solver"] in solvers, "Unexpected solver: " + row["solver"])
    trial = positive_int(row["trial"], "trial")
    require(trial <= repeats, "Unexpected trial: " + row["trial"])
    expected_key = sha((entry["sample_id"] + "\n" + row["solver"] + "\n" + row["trial"] + "\n" +
                        metadata["configuration_sha256"]).encode("utf-8"))
    require(row["key"] == expected_key, "Attempt/configuration hash mismatch: " + name)
    require(row["eligible"] in ("", "true", "false"), "Invalid eligibility: " + name)
    require(row["outcome"] in OUTCOMES, "Unknown outcome: " + row["outcome"])
    require(row["raw_status"] in ("SAT", "UNSAT", "UNKNOWN", "NOT_RUN", "NOT_RETURNED"), "Unknown raw status")
    require(bool(row["completed_utc"]), "Attempt lacks a completion timestamp")
    for column in TIME_COLUMNS:
        milliseconds(row, column, required=column == "wall_ms")
    if row["outcome"] in DECISIVE:
        require(row["eligible"] == "true" and row["raw_status"] == row["outcome"], "Decisive raw/outcome mismatch")
        require(milliseconds(row, "solve_ms", True) <= int(metadata["solve-ms"]) + 0.001,
                "Decisive result exceeded its solve budget")
    if row["outcome"] == "UNKNOWN":
        require(row["raw_status"] == "UNKNOWN", "UNKNOWN raw/outcome mismatch")
    if row["outcome"] in {"UNKNOWN", "TIMEOUT"}:
        require(row["eligible"] == "true", "Solver outcome lacks syntax classification")
    if row["outcome"] == "EXCLUDED":
        require(row["eligible"] == "false" and row["raw_status"] == "NOT_RUN" and
                row["validation_status"] == "NOT_APPLICABLE", "Syntax exclusion status mismatch")
    if row["outcome"] == "SAT":
        validator = "cvc5" if row["solver"] in ("z3", "noodler-z3") else "z3"
        require(row["validation_status"] == "SAT" and row.get("validator") == validator,
                "SAT lacks independent witness validation: " + name)
        require(milliseconds(row, "validation_ms", True) <= int(metadata["validation-ms"]) + 0.001,
                "SAT validation exceeded its budget")
        require(row.get("witness_bindings", "").isdigit() and
                bool(re.fullmatch("[0-9a-f]{64}", row.get("witness_sha256", ""))),
                "SAT lacks witness provenance")
    if row["outcome"] == "INVALID_WITNESS":
        require(row["raw_status"] == "SAT" and row["validation_status"] == "UNSAT", "Invalid witness status mismatch")
    if row["outcome"] == "UNVERIFIED_WITNESS":
        require(row["raw_status"] == "SAT" and row["validation_status"] not in DECISIVE,
                "Unverified witness status mismatch")
    if "variant" in metadata:
        require(row.get("variant") == metadata["variant"], "Attempt variant mismatch")
        require(row.get("hybrid_configuration_sha256") == metadata.get("hybrid-configuration-sha256"),
                "Attempt hybrid configuration mismatch")
        for field in SETTINGS + tuple(key for key in OPTIONAL_SETTINGS if key in metadata):
            require(row.get(field.replace("-", "_")) == metadata.get(field), "Attempt setting mismatch: " + field)


def load_benchmark(run, allow_partial=False):
    run = Path(run).resolve()
    meta_blob, manifest_blob = (run / "metadata.properties").read_bytes(), (run / "manifest.csv").read_bytes()
    metadata = parse_properties(meta_blob)
    require(metadata.get("schema") == "1", "Unsupported benchmark schema")
    require(sha(manifest_blob) == metadata.get("manifest_sha256"), "Manifest checksum mismatch")
    require(bool(re.fullmatch("[0-9a-f]{64}", metadata.get("configuration_sha256", ""))),
            "Invalid run configuration fingerprint")
    solvers = metadata.get("solvers", "").split(",")
    require(all(solver in LABELS for solver in solvers) and len(set(solvers)) == len(solvers), "Invalid solver list")
    repeats = positive_int(metadata.get("repeats", ""), "repeats")
    for field in ("solve-ms", "startup-parse-ms", "validation-ms"):
        positive_int(metadata.get(field, ""), field)
    _, entries = csv_rows(manifest_blob, {"sample", "sample_id", "sha256", "family", "logic", "expected_status"}, "manifest")
    manifest = {row["sample"]: row for row in entries}
    require(bool(manifest) and len(manifest) == len(entries), "Empty or duplicate source manifest")
    for name, entry in manifest.items():
        require(bool(re.fullmatch("[0-9a-f]{64}", entry["sha256"])), "Invalid source hash")
        require(entry["sample_id"] == sha((name + "\n" + entry["sha256"]).encode("utf-8")), "Invalid source identity")
    if "variant" in metadata:
        require(all(field in metadata for field in SETTINGS), "Incomplete hybrid settings metadata")
        fields = SETTINGS + tuple(key for key in OPTIONAL_SETTINGS if key in metadata)
        for field in OPTIONAL_SETTINGS:
            require(field not in metadata or metadata[field] in ("true", "false"), "Invalid " + field)
        settings_hash = sha("\n".join(field + "=" + metadata[field] for field in sorted(fields)).encode("utf-8"))
        require(metadata.get("hybrid-configuration-sha256") == settings_hash, "Hybrid settings fingerprint mismatch")
    selection_blob = None
    if metadata.get("source-list"):
        selection_blob = (run / "source-list.txt").read_bytes()
        require(sha(selection_blob) == metadata.get("source-list-sha256"), "Source-list snapshot checksum mismatch")
        selected = selection_blob.decode("utf-8-sig").splitlines()
        selected = [name for name in selected if name]
        require(len(selected) == len(set(selected)) and set(selected) == set(manifest), "Source-list/manifest mismatch")
        require(metadata.get("source-list-count") == str(len(selected)), "Source-list count mismatch")
    records, hashes, warnings, input_source = load_records(run, allow_partial)
    hashes.update({"metadata.properties": sha(meta_blob), "manifest.csv": sha(manifest_blob)})
    if selection_blob is not None:
        hashes["source-list.txt"] = sha(selection_blob)
    groups, seen = defaultdict(list), set()
    for row in records:
        validate_row(row, metadata, manifest, solvers, repeats)
        identity = row["sample"], row["solver"], int(row["trial"])
        require(identity not in seen, "Duplicate source/solver/trial attempt")
        seen.add(identity)
        log = (run / row["log"]).resolve()
        require(run in log.parents and log.is_file(), "Missing or escaping worker log")
        groups[row["sample"]].append(row)
    order = [(solver, trial) for trial in range(1, repeats + 1) for solver in solvers]
    excluded, eligible = set(), set()
    missing = 0
    for name in manifest:
        group = groups[name]
        excluded_rows = [row for row in group if row["eligible"] == "false"]
        actual = {(row["solver"], int(row["trial"])) for row in group}
        if excluded_rows:
            require(len(excluded_rows) == 1 and all(row["eligible"] != "true" for row in group), "Conflicting eligibility")
            terminal = excluded_rows[0]
            stop = order.index((terminal["solver"], int(terminal["trial"]))) + 1
            require(actual == set(order[:stop]), "Incomplete/mismatched syntax-exclusion prefix")
            require(terminal["outcome"] == "EXCLUDED" or terminal["outcome"] in FAILURES, "Bad exclusion outcome")
            excluded.add(name)
        else:
            require(all(row["outcome"] != "EXCLUDED" for row in group), "EXCLUDED without syntax classification")
            missing += len(set(order) - actual)
            if any(row["eligible"] == "true" for row in group):
                eligible.add(name)
    unclassified = set(manifest) - excluded - eligible
    if unclassified:
        warnings.append(f"{len(unclassified)} source(s) still lack syntax classification")
    failure_count = sum(row["outcome"] in FAILURES for row in records)
    if failure_count:
        warnings.append(f"{failure_count} invalid/unverified witness, unsupported, worker, or parser failures")
    conflicts = [name for name, group in groups.items() if len({row["outcome"] for row in group if row["outcome"] in DECISIVE}) > 1]
    if conflicts:
        warnings.append(f"{len(conflicts)} source(s) have contradictory decisive answers")
    label_conflicts = [row for row in records if row["outcome"] in DECISIVE and row["expected_status"] in DECISIVE
                       and row["outcome"] != row["expected_status"]]
    if label_conflicts:
        warnings.append(f"{len(label_conflicts)} decisive answers conflict with known labels")
    max_wall = sum(int(metadata[field]) for field in ("solve-ms", "startup-parse-ms", "validation-ms")) + 10000
    outliers = sum(milliseconds(row, "wall_ms", True) > max_wall for row in records)
    if outliers:
        warnings.append(f"{outliers} host wall-time outlier(s) above {max_wall} ms")
    completed = sum(row["sample"] not in excluded for row in records)
    expected = (len(manifest) - len(excluded)) * len(order)
    require(completed + missing == expected, "Attempt completeness accounting mismatch")
    complete = missing == 0 and not warnings
    if not complete and not allow_partial:
        raise AnalysisError(f"Final table refused: {missing} missing attempts; " + "; ".join(warnings))
    # Check snapshots before rendering. New checkpoints are harmless only for an explicit preview.
    for name, checksum in hashes.items():
        if sha((run / name).read_bytes()) != checksum:
            require(allow_partial and name == "raw.csv", "Input changed while auditing: " + name)
            warnings.append("Raw CSV advanced after the checkpoint snapshot")
            complete = False
    return TableData(run, metadata, manifest, records, solvers, repeats, excluded, eligible, unclassified,
                     completed, expected, missing, warnings, complete, hashes, input_source)


def summary_rows(data):
    result = []
    denominator = (len(data.manifest) - len(data.excluded)) * data.repeats
    for solver in data.solvers:
        rows = [row for row in data.rows if row["solver"] == solver and row["sample"] not in data.excluded]
        counts = Counter(row["outcome"] for row in rows)
        result.append({
            "solver": solver, "label": LABELS[solver],
            "hybrid_variant": data.metadata.get("variant", "baseline") if solver == "noodler-yices" else "",
            "sat": counts["SAT"], "unsat": counts["UNSAT"], "solved": counts["SAT"] + counts["UNSAT"],
            "eligible_trial_slots": denominator, "unknown": counts["UNKNOWN"], "timeout": counts["TIMEOUT"],
            "other": sum(count for outcome, count in counts.items() if outcome not in DECISIVE | {"UNKNOWN", "TIMEOUT"}),
            "completed_attempts": len(rows), "expected_attempts": denominator,
            "complete": data.complete, "source_run": str(data.run),
        })
    return result


def table_contents(data, summaries):
    denominator_label = "eligible" if not data.unclassified else "selected*"
    if data.repeats > 1:
        denominator_label += " trials"
    headers = ["Solver", "SAT", "UNSAT", "Solved / " + denominator_label, "UNKNOWN", "Timeout"]
    other = any(row["other"] for row in summaries)
    if other:
        headers.append("Other")
    cells = []
    for row in summaries:
        label = row["label"]
        if row["hybrid_variant"]:
            label += " (" + row["hybrid_variant"] + ")"
        values = [label, str(row["sat"]), str(row["unsat"]),
                  f"{row['solved']} / {row['eligible_trial_slots']}", str(row["unknown"]), str(row["timeout"])]
        if other:
            values.append(str(row["other"]))
        cells.append(values)
    return headers, cells


def captions(data, selection_note):
    trials = f"{data.repeats} trial" + ("s" if data.repeats != 1 else "") + " per solver"
    lines = [f"{len(data.manifest):,} source files; {len(data.eligible):,} confirmed eligible; "
             f"{len(data.excluded):,} unsupported-syntax exclusions. {trials}.",
             f"Assertion/check {int(data.metadata['solve-ms']) / 1000:g} s; "
             f"startup/parse {int(data.metadata['startup-parse-ms']) / 1000:g} s; "
             f"independent SAT validation {int(data.metadata['validation-ms']) / 1000:g} s.",
             "SAT assignments were checked by another solver; UNSAT results are solver claims without proof certificates.",
             f"Run: {data.run.name}. Fresh measurements only; no historical rows substituted."]
    if data.unclassified:
        lines.append(f"*{len(data.unclassified)} source(s) still lack syntax classification; denominator includes them.")
    if selection_note:
        lines.append(selection_note.strip())
    if data.warnings:
        lines.append("PREVIEW warnings: " + "; ".join(data.warnings))
    return [piece for line in lines for piece in textwrap.wrap(line, width=122)]


def render_images(data, summaries, png_path, svg_path, selection_note):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    headers, cells = table_contents(data, summaries)
    notes = captions(data, selection_note)
    table_height = 0.44 * (len(cells) + 1)
    height = 1.35 + table_height + 0.21 * len(notes) + 0.35
    with plt.rc_context({"font.family": "DejaVu Sans", "svg.fonttype": "path", "figure.facecolor": "white",
                         "axes.facecolor": "white", "savefig.facecolor": "white", "text.color": "#17202b"}):
        fig = plt.figure(figsize=(13.2, height), facecolor="white")
        fig.text(0.045, 1 - 0.43 / height, "String solver benchmark", fontsize=23, weight="bold", va="center")
        status = "COMPLETE" if data.complete else "PARTIAL / FLAGGED PREVIEW"
        fig.text(0.045, 1 - 0.9 / height, f"{status}  ·  {data.completed:,} / {data.expected:,} required solver attempts",
                 fontsize=11.5, weight="bold", color="#304961" if data.complete else "#a04415", va="center")
        table_bottom = (0.35 + 0.21 * len(notes)) / height
        axes = fig.add_axes([0.045, table_bottom, 0.91, table_height / height])
        axes.set_axis_off()
        first_width = 0.38
        widths = [first_width] + [(1 - first_width) / (len(headers) - 1)] * (len(headers) - 1)
        table = axes.table(cellText=cells, colLabels=headers, cellLoc="center", colLoc="center",
                           colWidths=widths, bbox=[0, 0, 1, 1])
        table.auto_set_font_size(False)
        table.set_fontsize(11.4)
        for (row, column), cell in table.get_celld().items():
            cell.set_facecolor("white")
            cell.set_edgecolor("#d9dfe5")
            cell.set_linewidth(0.6)
            cell.visible_edges = "B" if row else "BT"
            cell.PAD = 0.045
            if row == 0:
                cell.get_text().set_weight("bold")
                cell.get_text().set_fontsize(10.3)
                cell.set_linewidth(1.0)
            if column == 0:
                cell.get_text().set_ha("left")
            if row and data.solvers[row - 1] == "noodler-yices":
                cell.get_text().set_weight("bold")
        for index, note in enumerate(notes):
            y = (0.2 + 0.21 * (len(notes) - index - 1)) / height
            fig.text(0.045, y, note, fontsize=8.6, color="#56616d", va="bottom")
        fig.savefig(png_path, dpi=300, facecolor="white", transparent=False)
        fig.savefig(svg_path, facecolor="white", transparent=False)
        plt.close(fig)


class OutputRecoveryError(AnalysisError):
    """Recovery was incomplete; the staging directory must remain on disk."""

    def __init__(self, stage, publication_error, recovery_errors):
        self.stage = stage
        super().__init__(f"Output publication failed ({publication_error}); recovery was incomplete. "
                         f"Recovery files were preserved at {stage}. " + "; ".join(recovery_errors))


def publish_outputs(stage, targets, overwrite):
    """Publish staged files, restoring previous outputs if any replacement fails."""
    existing = {suffix: target for suffix, target in targets.items() if target.exists()}
    require(overwrite or not existing, "Output appeared concurrently; use --overwrite to regenerate")
    require(all(path.is_file() and not path.is_symlink() for path in existing.values()),
            "Existing output must be a regular file")
    backups, installed = {}, []
    try:
        for suffix, target in existing.items():
            backup = stage / ("previous." + suffix)
            target.rename(backup)
            backups[suffix] = backup
        for suffix, target in targets.items():
            require(not target.exists(), "Output appeared concurrently: " + str(target))
            (stage / ("table." + suffix)).rename(target)
            installed.append(target)
    except (OSError, AnalysisError) as publication_error:
        recovery_errors = []
        for target in reversed(installed):
            try:
                target.unlink()
            except OSError as error:
                recovery_errors.append(f"Could not remove new output {target}: {error}")
        for suffix, backup in backups.items():
            target = targets[suffix]
            try:
                require(not target.exists(), "Recovery target is occupied: " + str(target))
                backup.rename(target)
            except (OSError, AnalysisError) as error:
                recovery_errors.append(f"Could not restore {backup} to {target}: {error}")
        if recovery_errors:
            raise OutputRecoveryError(stage, publication_error, recovery_errors) from publication_error
        raise


def render(run, output_prefix, allow_partial=False, selection_note="", overwrite=False):
    data = load_benchmark(run, allow_partial)
    prefix = Path(output_prefix).resolve()
    require(data.run not in prefix.parents, "Export outside the raw benchmark run directory")
    targets = {suffix: prefix.with_name(prefix.name + "." + suffix) for suffix in ("png", "svg", "csv", "md")}
    require(overwrite or not any(path.exists() for path in targets.values()),
            "Output prefix already exists; choose a new prefix or use --overwrite")
    prefix.parent.mkdir(parents=True, exist_ok=True)
    summaries = summary_rows(data)
    headers, cells = table_contents(data, summaries)
    notes = captions(data, selection_note)
    stage = Path(tempfile.mkdtemp(prefix=".benchmark-table-", dir=prefix.parent))
    preserve_stage = False
    try:
        render_images(data, summaries, stage / "table.png", stage / "table.svg", selection_note)
        with (stage / "table.csv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(summaries[0]))
            writer.writeheader()
            writer.writerows(summaries)
        status = "COMPLETE" if data.complete else "PARTIAL / FLAGGED PREVIEW"
        markdown = ["# String solver benchmark", "", f"**{status}** — {data.completed}/{data.expected} required attempts.", "",
                    "| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
        markdown += ["| " + " | ".join(row) + " |" for row in cells]
        markdown += [""] + notes + ["", "## Provenance", "", f"Source: `{data.run}`; input: {data.input_source}.",
                                      "", "Effective hybrid settings:", "", "```json",
                                      json.dumps({key: data.metadata[key] for key in
                                                  ("variant",) + SETTINGS + OPTIONAL_SETTINGS
                                                  if key in data.metadata}, indent=2),
                                      "```", "", "Input SHA256 checksums (including immutable checkpoints):", "", "```json",
                                      json.dumps(data.input_hashes, indent=2, ensure_ascii=True), "```", "",
                                      "Generated UTC: " + datetime.now(timezone.utc).isoformat(), ""]
        (stage / "table.md").write_text("\n".join(markdown), encoding="utf-8")
        publish_outputs(stage, targets, overwrite)
    except BaseException as error:
        # A signal can interrupt between a backup rename and the bookkeeping update.
        # Inspect the filesystem before cleanup, including for KeyboardInterrupt/SystemExit.
        preserve_stage = isinstance(error, OutputRecoveryError) or any(stage.glob("previous.*"))
        if preserve_stage and not isinstance(error, OutputRecoveryError):
            raise OutputRecoveryError(stage, error, ["Publication stopped before recovery completed"]) from error
        raise
    finally:
        if not preserve_stage:
            shutil.rmtree(stage)
    return data, targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output-prefix", type=Path, required=True)
    parser.add_argument("--allow-partial", action="store_true", help="Allow a clearly marked partial/flagged preview")
    parser.add_argument("--selection-note", default="", help="Caption describing measured variant selection")
    parser.add_argument("--overwrite", action="store_true", help="Regenerate existing outputs after staging all files")
    args = parser.parse_args()
    try:
        data, targets = render(args.run, args.output_prefix, args.allow_partial, args.selection_note, args.overwrite)
    except (AnalysisError, OSError, ValueError, ImportError) as error:
        parser.exit(2, "Table export refused: " + str(error) + "\n")
    print(("Complete table" if data.complete else "Partial/flagged preview") + ": " + str(targets["png"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
