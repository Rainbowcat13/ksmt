#!/usr/bin/env python3
r"""Derive a traceable comparison without changing benchmark inputs (Python 3.9+).

Example:
  python analyze_benchmark.py --primary RUN --supplemental RERUN \
    --replace-case QF_S/family/case.smt2 --replacement-reason "Host sleep" \
    --output NEW_ANALYSIS_DIRECTORY

The supplemental manifest must contain exactly the explicitly named cases. All
five backends and every configured trial are replaced together. No solver runs.
Use --self-test for synthetic, standard-library-only sanity checks.
"""

import argparse
import csv
import hashlib
import io
import itertools
import json
import math
import re
import statistics
import sys
import tempfile
import unittest
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


SOLVERS = ("z3", "cvc5", "noodler-z3", "noodler-cvc5", "noodler-yices")
DECISIVE = {"SAT", "UNSAT"}
OUTCOMES = (
    "SAT", "UNSAT", "UNKNOWN", "TIMEOUT", "INVALID_WITNESS",
    "UNVERIFIED_WITNESS", "UNSUPPORTED", "PARSE_ERROR", "ERROR", "CRASH",
    "STARTUP_PARSE_TIMEOUT", "EXCLUDED",
)
MANIFEST_COLUMNS = {"sample_id", "sample", "sha256", "bytes", "logic", "family", "expected_status"}
ROW_COLUMNS = {
    "key", "sample_id", "sample", "source_sha256", "family", "logic", "expected_status",
    "solver", "trial", "eligible", "outcome", "raw_status", "validation_status", "validator",
    "solve_ms", "validation_ms", "wall_ms", "witness_bindings", "witness_sha256",
    "preprocess_skipped", "preprocess_skip_reason", "details",
    "log", "completed_utc",
}
SAME_METADATA = (
    "schema", "runtime_sha256", "classpath", "solve-ms", "startup-parse-ms",
    "validation-ms", "repeats", "solvers", "java_version", "java_vendor", "os",
    "os_version", "architecture",
)
# Absent in historical schema-1 runs; if present, settings must match symmetrically.
# Source-list hashes may differ because a supplemental run deliberately selects a subset.
SAME_OPTIONAL_METADATA = (
    "variant", "hybrid-configuration-sha256", "max-automata-states", "max-automata-storage",
    "max-encoding-constraints", "max-preprocessing-units", "max-character-positions",
    "max-idle-rounds", "optional-preprocessing", "small-first-search", "adaptive-length-projection",
)
PROVENANCE_COLUMNS = (
    "analysis_origin", "analysis_source_run", "analysis_source_raw_sha256",
    "analysis_source_log", "replaced_primary_key", "replaced_primary_outcome",
    "replaced_primary_raw_status", "replaced_primary_wall_ms",
)


class AnalysisError(ValueError):
    """Invalid, incomplete, or incomparable input; no analysis should be published."""


def require(condition, message):
    if not condition:
        raise AnalysisError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest_text(text):
    return sha(text.encode("utf-8"))


def unescape_property(value):
    """Decode java.util.Properties.store escapes, including Windows paths."""
    def replace(match):
        token = match.group(1)
        if token.startswith("u"):
            require(bool(re.fullmatch(r"u[0-9a-fA-F]{4}", token)), "Invalid property Unicode escape")
            return chr(int(token[1:], 16))
        return {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(token, token)
    return re.sub(r"\\(u.{0,4}|.)", replace, value)


def parse_properties(data):
    result = {}
    continuation = ""
    for physical in data.decode("iso-8859-1").splitlines():
        line = continuation + physical.lstrip() if continuation else physical.lstrip()
        trailing = len(line) - len(line.rstrip("\\"))
        if trailing % 2:
            continuation = line[:-1]
            continue
        continuation = ""
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"((?:\\.|[^:=\s])*)\s*(?:[:=]\s*)?(.*)", line)
        require(match is not None, "Malformed metadata property")
        key, value = (unescape_property(part) for part in match.groups())
        require(key not in result, "Duplicate metadata property: " + key)
        result[key] = value
    require(not continuation, "Unfinished metadata property continuation")
    return result


def csv_rows(data, required, label):
    csv.field_size_limit(min(sys.maxsize, 2**31 - 1))  # C long is 32 bits on Windows.
    try:
        reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig"), newline=""), strict=True)
        columns = reader.fieldnames or []
        require(len(set(columns)) == len(columns), label + ": duplicate CSV headers")
        require(required <= set(columns), label + ": missing columns " + str(sorted(required - set(columns))))
        rows = list(reader)
    except (csv.Error, UnicodeError) as error:
        raise AnalysisError(label + ": " + str(error)) from error
    for number, row in enumerate(rows, 2):
        require(None not in row and None not in row.values(), f"{label}:{number}: incomplete/extra CSV cells")
    return columns, rows


def finite_ms(row, column, mandatory=False):
    value = row.get(column, "")
    if not value:
        require(not mandatory, f"{row['sample']} [{row['solver']}]: missing {column}")
        return None
    try:
        number = float(value)
    except ValueError as error:
        raise AnalysisError("Invalid " + column + ": " + value) from error
    require(math.isfinite(number) and number >= 0, "Invalid " + column + ": " + value)
    return number


def key_of(row):
    return row["sample"], row["solver"], int(row["trial"])


def case_trial(row):
    return row["sample"], int(row["trial"])


def is_decisive(row):
    return row["outcome"] == "UNSAT" or (
        row["outcome"] == "SAT" and row["validation_status"] == "SAT"
    )


@dataclass
class Run:
    directory: Path
    metadata: dict
    manifest: dict
    columns: list
    rows: list
    excluded: set
    hashes: dict


def load_run(directory):
    directory = directory.resolve()
    blobs = {name: (directory / name).read_bytes()
             for name in ("metadata.properties", "manifest.csv", "raw.csv")}
    metadata = parse_properties(blobs["metadata.properties"])
    for field in SAME_METADATA + ("manifest_sha256", "configuration_sha256"):
        require(bool(metadata.get(field)), f"{directory}: missing metadata {field}")
    require(metadata["schema"] == "1", "Only benchmark schema 1 is supported")
    require(tuple(metadata["solvers"].split(",")) == SOLVERS, "Expected all five backends in canonical order")
    for field in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats"):
        require(metadata[field].isdigit() and int(metadata[field]) > 0, "Invalid metadata " + field)
    for field in ("manifest_sha256", "configuration_sha256", "runtime_sha256"):
        require(bool(re.fullmatch("[0-9a-f]{64}", metadata[field])), "Invalid metadata digest " + field)
    require(sha(blobs["manifest.csv"]) == metadata["manifest_sha256"], "Manifest checksum does not match metadata")
    _, entries = csv_rows(blobs["manifest.csv"], MANIFEST_COLUMNS, "manifest.csv")
    require(bool(entries), "Empty manifest")
    manifest = {}
    for entry in entries:
        name = entry["sample"]
        require(name not in manifest, "Duplicate manifest source path: " + name)
        require(bool(name) and "\\" not in name and not Path(name).is_absolute()
                and ".." not in Path(name).parts, "Invalid relative source path: " + name)
        require(bool(re.fullmatch("[0-9a-f]{64}", entry["sha256"])), "Invalid source digest: " + name)
        require(entry["sample_id"] == digest_text(name + "\n" + entry["sha256"]), "Invalid sample ID: " + name)
        require(entry["bytes"].isdigit(), "Invalid source size: " + name)
        require(entry["expected_status"] in DECISIVE | {"UNKNOWN"}, "Invalid expected status: " + name)
        manifest[name] = entry
    columns, rows = csv_rows(blobs["raw.csv"], ROW_COLUMNS, "raw.csv")
    require(not set(columns) & set(PROVENANCE_COLUMNS), "Input must be raw data, not a previously derived CSV")
    seen = set()
    groups = defaultdict(list)
    repeats = int(metadata["repeats"])
    for row in rows:
        name = row["sample"]
        require(name in manifest, "Row absent from manifest: " + name)
        entry = manifest[name]
        for field in ("sample_id", "family", "logic", "expected_status"):
            require(row[field] == entry[field], f"{name}: row/manifest {field} mismatch")
        require(row["source_sha256"] == entry["sha256"], "Source hash mismatch: " + name)
        require(row["solver"] in SOLVERS, "Unknown solver: " + row["solver"])
        require(row["trial"].isdigit() and 1 <= int(row["trial"]) <= repeats, "Invalid trial: " + name)
        identity = key_of(row)
        require(identity not in seen, "Duplicate attempt: " + str(identity))
        seen.add(identity)
        expected_key = digest_text(f"{row['sample_id']}\n{row['solver']}\n{row['trial']}\n"
                                   + metadata["configuration_sha256"])
        require(row["key"] == expected_key, "Attempt key/configuration mismatch: " + str(identity))
        require(bool(row["completed_utc"]), "Missing completion timestamp: " + str(identity))
        require(row["eligible"] in ("", "true", "false"), "Invalid eligibility: " + str(identity))
        require(row["outcome"] in OUTCOMES, "Unrecognized outcome: " + row["outcome"])
        require(row["raw_status"] in ("SAT", "UNSAT", "UNKNOWN", "NOT_RUN", "NOT_RETURNED"),
                "Unrecognized raw status: " + str(identity))
        for field in ("startup_ms", "parse_ms", "setup_ms", "solve_ms", "validation_ms", "wall_ms"):
            finite_ms(row, field, mandatory=field == "wall_ms" or (field == "solve_ms" and is_decisive(row)))
        if row["outcome"] == "SAT":
            require(row["raw_status"] == "SAT" and row["validation_status"] == "SAT",
                    "SAT was not independently validated: " + str(identity))
            expected_validator = "cvc5" if row["solver"] in ("z3", "noodler-z3") else "z3"
            require(row["validator"] == expected_validator, "Incorrect independent validator: " + str(identity))
            require(finite_ms(row, "validation_ms", True) <= int(metadata["validation-ms"]) + 0.001,
                    "Validated SAT exceeds validation budget: " + str(identity))
            require(row["witness_bindings"].isdigit() and bool(re.fullmatch("[0-9a-f]{64}", row["witness_sha256"])),
                    "Validated SAT lacks witness provenance: " + str(identity))
        if row["outcome"] == "UNSAT":
            require(row["raw_status"] == "UNSAT", "UNSAT raw/outcome mismatch: " + str(identity))
        if row["outcome"] == "INVALID_WITNESS":
            require(row["raw_status"] == "SAT" and row["validation_status"] == "UNSAT",
                    "Invalid witness outcome lacks independent rejection: " + str(identity))
        if row["outcome"] == "UNVERIFIED_WITNESS":
            require(row["raw_status"] == "SAT" and row["validation_status"] not in DECISIVE,
                    "Inconsistent unverified witness status: " + str(identity))
        if is_decisive(row):
            require(row["eligible"] == "true", "Decisive result without confirmed syntax eligibility")
            # CSV times are rounded to 0.001 ms; tolerate only one rounding unit.
            require(finite_ms(row, "solve_ms", True) <= int(metadata["solve-ms"]) + 0.001,
                    "Decisive outcome exceeds solve budget: " + str(identity))
        log_path = (directory / row["log"]).resolve()
        require(bool(row["log"]) and directory in log_path.parents and log_path.is_file(),
                "Missing or escaping worker log: " + str(identity))
        groups[name].append(row)
    excluded = set()
    expected_group = [(solver, trial) for trial in range(1, repeats + 1) for solver in SOLVERS]
    for name in manifest:
        group = groups[name]
        require(bool(group), "Incomplete run: no attempt for " + name)
        exclusions = [row for row in group if row["eligible"] == "false"]
        actual = {(row["solver"], int(row["trial"])) for row in group}
        if exclusions:
            # The producer stops at eligible=false even if cleanup/transport then fails.
            exclusion_outcomes = {"EXCLUDED", "CRASH", "STARTUP_PARSE_TIMEOUT", "ERROR", "PARSE_ERROR", "UNSUPPORTED"}
            require(len(exclusions) == 1 and exclusions[0]["outcome"] in exclusion_outcomes,
                    "Inconsistent syntax exclusion: " + name)
            require(all(row["eligible"] != "true" for row in group), "Conflicting eligibility: " + name)
            stop = expected_group.index((exclusions[0]["solver"], int(exclusions[0]["trial"]))) + 1
            require(actual == set(expected_group[:stop]), "Incomplete/mismatched exclusion prefix: " + name)
            excluded.add(name)
        else:
            require(actual == set(expected_group), "Incomplete five-backend/trial group: " + name)
            require(all(row["outcome"] != "EXCLUDED" for row in group), "EXCLUDED without eligibility: " + name)
    return Run(directory, metadata, manifest, columns, rows, excluded,
               {name: sha(data) for name, data in blobs.items()})


def curate(primary, supplemental, replacements):
    require(bool(replacements) and len(replacements) == len(set(replacements)), "Replacement paths must be explicit and unique")
    for field in SAME_METADATA:
        require(primary.metadata[field] == supplemental.metadata[field], "Incomparable runs: metadata differs: " + field)
    for field in SAME_OPTIONAL_METADATA:
        require(primary.metadata.get(field) == supplemental.metadata.get(field),
                "Incomparable runs: metadata differs: " + field)
    require(primary.columns == supplemental.columns, "Raw CSV schemas differ")
    require(set(supplemental.manifest) == set(replacements), "Supplemental manifest must equal the explicit replacement cases")
    for name in replacements:
        require(name in primary.manifest, "Replacement absent from primary manifest: " + name)
        require(primary.manifest[name] == supplemental.manifest[name], "Replacement source hash/manifest mismatch: " + name)
        require(name not in primary.excluded and name not in supplemental.excluded, "Cannot replace a syntax exclusion: " + name)
    require(all(row["eligible"] == "true" for row in supplemental.rows), "Supplemental groups require confirmed eligibility")
    max_wall = sum(int(primary.metadata[key]) for key in ("solve-ms", "startup-parse-ms", "validation-ms")) + 10000
    require(all(finite_ms(row, "wall_ms", True) <= max_wall for row in supplemental.rows),
            "Supplemental run still contains an environmental wall-time outlier")
    replacement_rows = {key_of(row): row for row in supplemental.rows}
    result = []
    for original in primary.rows:
        replaced = original["sample"] in replacements
        source = supplemental if replaced else primary
        row = dict(replacement_rows[key_of(original)] if replaced else original)
        row.update({
            "analysis_origin": "supplemental" if replaced else "primary",
            "analysis_source_run": str(source.directory),
            "analysis_source_raw_sha256": source.hashes["raw.csv"],
            "analysis_source_log": str((source.directory / row["log"]).resolve()),
            "replaced_primary_key": original["key"] if replaced else "",
            "replaced_primary_outcome": original["outcome"] if replaced else "",
            "replaced_primary_raw_status": original["raw_status"] if replaced else "",
            "replaced_primary_wall_ms": original["wall_ms"] if replaced else "",
        })
        result.append(row)
    return sorted(result, key=lambda row: (row["sample"], int(row["trial"]), SOLVERS.index(row["solver"])))


def cell(value):
    return str(value).replace("|", "\\|").replace("\r", "").replace("\n", "<br>")


def table(headers, rows):
    output = ["| " + " | ".join(map(cell, headers)) + " |", "|" + "---|" * len(headers)]
    output.extend("| " + " | ".join(map(cell, row)) + " |" for row in rows)
    return "\n".join(output) + "\n\n"


def number(value):
    return "n/a" if value is None else f"{value:.3f}"


def median(values):
    return statistics.median(values) if values else None


def describe_attempt(row):
    return (row["sample"], row["solver"], row["trial"], row["raw_status"],
            row["outcome"], row["validation_status"], row["analysis_source_log"])


def matched_statistics(rows, left, right):
    left_rows = {case_trial(row): row for row in rows if row["solver"] == left}
    right_rows = {case_trial(row): row for row in rows if row["solver"] == right}
    pairs = []
    for key in left_rows.keys() & right_rows.keys():
        one, two = left_rows[key], right_rows[key]
        if is_decisive(one) and is_decisive(two) and one["outcome"] == two["outcome"]:
            pairs.append((finite_ms(one, "solve_ms", True), finite_ms(two, "solve_ms", True)))
    ratios = [left_ms / right_ms for left_ms, right_ms in pairs if right_ms > 0]
    return (left, right, len(pairs), number(sum(one for one, _ in pairs)),
            number(sum(two for _, two in pairs)), number(median(ratios)),
            len(pairs) - len(ratios), number(median([one - two for one, two in pairs])),
            sum(one < two for one, two in pairs), sum(one == two for one, two in pairs))


def build_analysis(primary, supplemental, rows, replacements, reason):
    text = ["# Curated Noodler benchmark comparison\n\n"]
    text.append(f"Primary: `{primary.directory}`. Supplemental: `{supplemental.directory}`.\n\n")
    text.append(f"Explicit replacement reason: {cell(reason)}. Each selected source has all five backends "
                "and every configured trial replaced together. Original files remain unchanged. "
                "`comparison.csv` retains source-run, raw-file checksum, log, and replaced-attempt provenance; "
                "`provenance.json` records all input checksums.\n\n")
    text.append(table(["Replacement source", "Source SHA256"],
                      [(name, primary.manifest[name]["sha256"]) for name in sorted(replacements)]))
    text.append(table(["Solver", "Trial", "Source", "Original outcome", "Original wall ms", "Rerun outcome", "Rerun wall ms"],
                      [(row["solver"], row["trial"], row["sample"], row["replaced_primary_outcome"],
                        row["replaced_primary_wall_ms"], row["outcome"], row["wall_ms"])
                       for row in rows if row["analysis_origin"] == "supplemental"]))
    metadata = primary.metadata
    text.append(f"Matched metadata: runtime SHA256 `{metadata['runtime_sha256']}`, identical classpath, Java/OS, "
                f"solver order, {metadata['repeats']} trial(s), assertion/check {metadata['solve-ms']} ms, "
                f"startup/parse {metadata['startup-parse-ms']} ms, validation {metadata['validation-ms']} ms. "
                "Configuration hashes may differ because the supplemental source selection differs.\n\n")
    text.append("SAT means independently validated complete original Bool/Int/String assignments. UNSAT is a solver "
                "claim without a proof certificate. UNKNOWN, timeouts, and unverified witnesses are inconclusive. "
                "The validator uses a different solver on the same Z3-parsed KSMT assertions; parsing is not independently checked. "
                "Counts and pairwise comparisons use source/trial units; cross-trial correctness is audited by source. "
                "One trial measures this run and cannot establish statistical performance stability.\n\n")

    groups = defaultdict(list)
    for row in rows:
        groups[row["sample"]].append(row)
    eligible = {name for name, group in groups.items() if any(row["eligible"] == "true" for row in group)}
    unclassified = set(primary.manifest) - primary.excluded - eligible
    text.append("## Coverage and outcomes\n\n")
    text.append(f"Manifest: **{len(primary.manifest)}** sources; syntax eligible: **{len(eligible)}**; "
                f"excluded: **{len(primary.excluded)}**; eligibility unclassified after all attempts: "
                f"**{len(unclassified)}**. All required backend/trial groups are complete.\n\n")
    families = sorted({entry["family"] for entry in primary.manifest.values()})
    coverage = []
    for family in families:
        names = {name for name, entry in primary.manifest.items() if entry["family"] == family}
        coverage.append((family, len(names), len(names & eligible), len(names & primary.excluded),
                         len(names & unclassified), sum(len(groups[name]) for name in names)))
    text.append(table(["Family", "Manifest", "Eligible", "Excluded", "Unclassified", "Recorded attempts"], coverage))
    present_outcomes = [outcome for outcome in OUTCOMES if any(row["outcome"] == outcome for row in rows)]
    text.append(table(["Solver", "Attempts"] + present_outcomes,
                      [(solver, sum(row["solver"] == solver for row in rows)) +
                       tuple(sum(row["solver"] == solver and row["outcome"] == outcome for row in rows)
                             for outcome in present_outcomes) for solver in SOLVERS]))
    text.append("Exclusion discovery stops a source's backend sequence; exclusion counts are not solver failures. "
                "Any failed parse attempt preceding a later exclusion remains in the derived CSV and totals.\n\n")
    family_solver = []
    for family in families:
        for solver in SOLVERS:
            subset = [row for row in rows if row["family"] == family and row["solver"] == solver
                      and row["outcome"] != "EXCLUDED"]
            family_solver.append((family, solver, len(subset), sum(row["outcome"] == "SAT" for row in subset),
                                  sum(row["outcome"] == "UNSAT" for row in subset),
                                  sum(not is_decisive(row) for row in subset)))
    text.append(table(["Family", "Solver", "Non-exclusion attempts", "Validated SAT", "UNSAT", "Inconclusive/failure"],
                      family_solver))

    text.append("## Correctness, known labels, and witness validation\n\n")
    disagreements = {name: group for name, group in groups.items()
                     if len({row["outcome"] for row in group if is_decisive(row)}) > 1}
    label_conflicts = [row for row in rows if is_decisive(row) and row["expected_status"] in DECISIVE
                      and row["outcome"] != row["expected_status"]]
    invalid = [row for row in rows if row["outcome"] == "INVALID_WITNESS"]
    text.append(f"Sources with decisive disagreements across any backend/trial: **{len(disagreements)}**. "
                f"Known-label conflicting attempts: **{len(label_conflicts)}**. "
                f"Invalid-witness attempts: **{len(invalid)}**.\n\n")
    label_audit = []
    for solver in SOLVERS:
        subset = [row for row in rows if row["solver"] == solver and row["outcome"] != "EXCLUDED"]
        known = [row for row in subset if row["expected_status"] in DECISIVE]
        label_audit.append((solver, len(known), sum(is_decisive(row) and row["outcome"] == row["expected_status"] for row in known),
                            sum(is_decisive(row) and row["outcome"] != row["expected_status"] for row in known),
                            sum(not is_decisive(row) for row in known), len(subset) - len(known)))
    text.append(table(["Solver", "Known-label attempts", "Agreements", "Conflicts", "Inconclusive", "Unknown labels"], label_audit))
    validation = Counter((row["solver"], row["raw_status"], row["validation_status"], row["outcome"])
                         for row in rows if row["raw_status"] == "SAT" or row["outcome"] in ("INVALID_WITNESS", "UNVERIFIED_WITNESS"))
    text.append(table(["Solver", "Raw status", "Validation", "Outcome", "Count"],
                      [key + (count,) for key, count in sorted(validation.items())]))
    issues = {row["key"]: row for group in disagreements.values() for row in group if is_decisive(row)}
    issues.update({row["key"]: row for row in label_conflicts + invalid})
    if issues:
        text.append(table(["Source", "Solver", "Trial", "Raw", "Outcome", "Validation", "Log"],
                          [describe_attempt(row) for row in issues.values()]))
        text.append("Correctness issues above require investigation before drawing solver rankings.\n\n")

    text.append("## Matched assertion/check timings\n\n")
    text.append("Only source/trial pairs with the same decisive outcome enter these comparisons. "
                "Timings exclude startup, parsing, setup, and the separately timed harness validation phase. "
                "Internal solver work inside check, including hybrid witness checks and legacy model detachment, remains included. "
                "Totals use identical case sets within each row. "
                "The median is over per-case left/right ratios; values below 1 favor the left solver. "
                "Zero right-hand times (CSV rounding) are omitted only from ratios and counted explicitly; "
                "median left-minus-right deltas retain all pairs. No timeout is treated as solved.\n\n")
    text.append(table(["Left", "Right", "Pairs", "Left total ms", "Right total ms", "Median L/R",
                       "Zero denominators", "Median L−R ms", "Left faster", "Ties"],
                      [matched_statistics(rows, left, right) for left, right in itertools.combinations(SOLVERS, 2)]))
    phase_rows = []
    for solver in SOLVERS:
        subset = [row for row in rows if row["solver"] == solver and row["outcome"] != "EXCLUDED"]
        for field in ("startup_ms", "parse_ms", "setup_ms", "solve_ms", "validation_ms", "wall_ms"):
            values = [value for row in subset if (value := finite_ms(row, field)) is not None]
            phase_rows.append((solver, field, len(values), len(subset) - len(values), number(median(values)),
                               number(max(values) if values else None)))
    text.append(table(["Solver", "Phase", "Recorded", "Missing", "Median ms", "Max ms"], phase_rows))

    text.append("## Wrapper gains, losses, and hybrid coverage\n\n")
    solved = {solver: {case_trial(row) for row in rows if row["solver"] == solver and is_decisive(row)} for solver in SOLVERS}
    comparisons = [("noodler-z3 versus z3", solved["noodler-z3"], solved["z3"]),
                   ("noodler-cvc5 versus cvc5", solved["noodler-cvc5"], solved["cvc5"]),
                   ("hybrid versus native union", solved["noodler-yices"], solved["z3"] | solved["cvc5"]),
                   ("hybrid versus all four others", solved["noodler-yices"], set().union(*(solved[solver] for solver in SOLVERS[:-1])))]
    text.append("Gain/unique means the left side decided a source/trial where the right side had no decisive result; "
                "loss is the reverse. A native union is the union of separately measured solver coverage, "
                "not a timed portfolio. Shared coverage may include contradictions listed in the correctness audit.\n\n")
    text.append(table(["Comparison", "Left solved", "Right solved", "Shared", "Gains/unique", "Losses"],
                      [(label, len(left), len(right), len(left & right), len(left - right), len(right - left))
                       for label, left, right in comparisons]))
    differences = [(label, direction, name, trial) for label, left, right in comparisons
                   for direction, keys in (("gain/unique", left - right), ("loss", right - left))
                   for name, trial in sorted(keys)]
    if differences:
        text.append(table(["Comparison", "Direction", "Source", "Trial"], differences))

    text.append("## Unknown, timeout, failure, and preprocessing reasons\n\n")
    reasons = Counter((row["solver"], row["outcome"], row["details"] or "(not supplied)") for row in rows
                      if not is_decisive(row) and row["outcome"] != "EXCLUDED")
    text.append(table(["Solver", "Outcome", "Reason", "Count"], [key + (count,) for key, count in sorted(reasons.items())]))
    skips = Counter((row["solver"], row["preprocess_skip_reason"] or "(not supplied)") for row in rows
                    if row["preprocess_skipped"] == "true")
    text.append(table(["Solver", "Preprocessing skipped reason", "Count"], [key + (count,) for key, count in sorted(skips.items())]))
    exclusions = Counter((row["family"], row["details"] or "(not supplied)") for row in rows if row["eligible"] == "false")
    text.append(table(["Family", "Syntax exclusion detail (may include a later worker failure)", "Sources"],
                      [key + (count,) for key, count in sorted(exclusions.items())]))
    max_wall = sum(int(metadata[key]) for key in ("solve-ms", "startup-parse-ms", "validation-ms")) + 10000
    outliers = [row for row in rows if finite_ms(row, "wall_ms", True) > max_wall]
    text.append(f"Remaining wall-time outliers beyond all phase budgets plus 10 seconds of cleanup/scheduling allowance "
                f"({max_wall} ms): **{len(outliers)}**. This is an environmental diagnostic, not a solver correctness test. "
                "No additional cases were silently omitted.\n\n")
    if outliers:
        text.append(table(["Source", "Solver", "Trial", "Wall ms", "Outcome", "Log"],
                          [(row["sample"], row["solver"], row["trial"], row["wall_ms"], row["outcome"],
                            row["analysis_source_log"]) for row in outliers]))
    return "".join(text)


def analyze(primary_path, supplemental_path, replacements, reason, output):
    require(bool(reason.strip()), "An explicit replacement reason is required")
    primary, supplemental = load_run(primary_path), load_run(supplemental_path)
    require(primary.directory != supplemental.directory, "Primary and supplemental must be separate runs")
    output = output.resolve()
    require(not output.exists(), "Output must be a new directory: " + str(output))
    for directory in (primary.directory, supplemental.directory):
        require(directory not in output.parents and output not in directory.parents,
                "Output must be separate from both raw run directories")
    rows = curate(primary, supplemental, replacements)
    report = build_analysis(primary, supplemental, rows, replacements, reason)
    # Detect an append or modification during analysis; never publish a moving snapshot.
    for run in (primary, supplemental):
        for name, checksum in run.hashes.items():
            require(sha((run.directory / name).read_bytes()) == checksum, "Input changed during analysis: " + str(run.directory / name))
    provenance = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "script_sha256": sha(Path(__file__).read_bytes()),
        "primary": {"directory": str(primary.directory), "files_sha256": primary.hashes,
                    "configuration_sha256": primary.metadata["configuration_sha256"]},
        "supplemental": {"directory": str(supplemental.directory), "files_sha256": supplemental.hashes,
                         "configuration_sha256": supplemental.metadata["configuration_sha256"]},
        "verified_common_metadata": {field: primary.metadata[field] for field in SAME_METADATA},
        "replacement_cases": sorted(replacements), "replacement_reason": reason,
        "replacement_rows": len(supplemental.rows), "derived_rows": len(rows),
        "source_policy": "Original datasets preserved; complete five-backend/trial groups substituted only for named cases",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    # Publish the directory only after every artifact has been written successfully.
    with tempfile.TemporaryDirectory(prefix="." + output.name + "-staging-", dir=output.parent) as staging_name:
        staging = Path(staging_name)
        with (staging / "comparison.csv").open("x", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=primary.columns + list(PROVENANCE_COLUMNS))
            writer.writeheader()
            writer.writerows(rows)
        (staging / "analysis.md").write_text(report, encoding="utf-8")
        provenance["derived_files_sha256"] = {name: sha((staging / name).read_bytes())
                                              for name in ("comparison.csv", "analysis.md")}
        # Java Properties may contain UTF-16 surrogate escapes; JSON escapes preserve them losslessly.
        (staging / "provenance.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        require(not output.exists(), "Output was created concurrently: " + str(output))
        staging.rename(output)
    return rows


def self_test():
    """Tests synthetic files only; never discovers repository benchmark data."""
    from unittest.mock import patch
    class AnalysisTests(unittest.TestCase):
        def setUp(self):
            self.temp = tempfile.TemporaryDirectory()
            self.addCleanup(self.temp.cleanup)
            self.root = Path(self.temp.name)
            self.primary = self.fixture("primary", ["QF_S/test/a.smt2", "QF_S/test/b.smt2", "QF_S/test/excluded.smt2"])
            self.supplemental = self.fixture("supplemental", ["QF_S/test/a.smt2"])

        def fixture(self, name, cases):
            directory = self.root / name
            directory.mkdir()
            (directory / "logs").mkdir()
            manifest = []
            rows = []
            config = digest_text(name)
            for case in cases:
                checksum = digest_text("(assert true)")
                entry = dict(sample=case, sample_id=digest_text(case + "\n" + checksum), sha256=checksum,
                             bytes="13", family="QF_S/test", logic="QF_S", expected_status="SAT")
                manifest.append(entry)
                for solver in SOLVERS:
                    row = {column: "" for column in ROW_COLUMNS}
                    row.update({field: entry[field] for field in ("sample", "sample_id", "family", "logic", "expected_status")})
                    row.update(source_sha256=checksum, solver=solver, trial="1", eligible="true", outcome="SAT",
                               raw_status="SAT", validation_status="SAT", validator="cvc5" if solver in ("z3", "noodler-z3") else "z3",
                               solve_ms="1.000", validation_ms="1.000", wall_ms="20.000", witness_bindings="0",
                               witness_sha256=digest_text(""), completed_utc="2026-01-01T00:00:00Z")
                    row["key"] = digest_text(entry["sample_id"] + "\n" + solver + "\n1\n" + config)
                    row["log"] = "logs/" + row["key"] + ".log"
                    (directory / row["log"]).write_text("synthetic fixture", encoding="utf-8")
                    if "excluded" in case:
                        row.update(eligible="false", outcome="EXCLUDED", raw_status="NOT_RUN",
                                   validation_status="NOT_APPLICABLE", solve_ms="", details="unsupported syntax")
                    rows.append(row)
                    if "excluded" in case:
                        break
            for filename, values, columns in (("manifest.csv", manifest, sorted(MANIFEST_COLUMNS)), ("raw.csv", rows, sorted(ROW_COLUMNS))):
                with (directory / filename).open("w", encoding="utf-8", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=columns)
                    writer.writeheader()
                    writer.writerows(values)
            metadata = dict(schema="1", runtime_sha256="1" * 64, classpath="synthetic", repeats="1",
                            solvers=",".join(SOLVERS), java_version="17", java_vendor="test", os="test",
                            os_version="1", architecture="test", configuration_sha256=config,
                            manifest_sha256=sha((directory / "manifest.csv").read_bytes()))
            metadata.update({"solve-ms": "10000", "startup-parse-ms": "30000", "validation-ms": "10000"})
            (directory / "metadata.properties").write_text("\n".join(key + "=" + value for key, value in metadata.items()), encoding="ascii")
            return directory

        def test_complete_replacement_and_provenance(self):
            before = sha((self.primary / "raw.csv").read_bytes())
            rows = analyze(self.primary, self.supplemental, ["QF_S/test/a.smt2"], "synthetic sleep", self.root / "derived")
            self.assertEqual(11, len(rows))
            self.assertEqual(5, sum(row["analysis_origin"] == "supplemental" for row in rows))
            self.assertEqual(before, sha((self.primary / "raw.csv").read_bytes()))
            report = (self.root / "derived/analysis.md").read_text(encoding="utf-8")
            self.assertIn("all four others", report)
            self.assertIn("**0**", report)
            with self.assertRaises(AnalysisError):
                analyze(self.primary, self.supplemental, ["QF_S/test/a.smt2"], "test", self.root / "derived")

        def test_missing_backend_is_rejected(self):
            path = self.supplemental / "raw.csv"
            lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
            path.write_text("".join(lines[:-1]), encoding="utf-8")
            with self.assertRaisesRegex(AnalysisError, "Incomplete five-backend"):
                load_run(self.supplemental)

        def rewrite_row(self, directory, change):
            path = directory / "raw.csv"
            columns, rows = csv_rows(path.read_bytes(), ROW_COLUMNS, "synthetic")
            change(rows)
            with path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=columns)
                writer.writeheader()
                writer.writerows(rows)

        def test_unvalidated_sat_is_rejected(self):
            self.rewrite_row(self.supplemental, lambda rows: rows[0].update(validation_status="TIMEOUT"))
            with self.assertRaisesRegex(AnalysisError, "not independently validated"):
                load_run(self.supplemental)

        def test_duplicate_attempt_is_rejected(self):
            self.rewrite_row(self.supplemental, lambda rows: rows.append(dict(rows[0])))
            with self.assertRaisesRegex(AnalysisError, "Duplicate attempt"):
                load_run(self.supplemental)

        def test_exclusion_followed_by_worker_failure_is_complete(self):
            self.rewrite_row(self.primary, lambda rows: rows[-1].update(outcome="CRASH", details="cleanup failure"))
            run = load_run(self.primary)
            self.assertIn("QF_S/test/excluded.smt2", run.excluded)
            self.assertEqual("CRASH", run.rows[-1]["outcome"])

        def test_unicode_properties_provenance(self):
            for directory in (self.primary, self.supplemental):
                path = directory / "metadata.properties"
                path.write_text(path.read_text(encoding="ascii").replace("classpath=synthetic",
                                "classpath=C\\:\\\\work\\\\\\ud83d\\ude00\\\\classes"), encoding="ascii")
            output = self.root / "unicode-derived"
            analyze(self.primary, self.supplemental, ["QF_S/test/a.smt2"], "synthetic sleep", output)
            provenance = json.loads((output / "provenance.json").read_text(encoding="utf-8"))
            self.assertEqual("C:\\work\\\U0001f600\\classes", provenance["verified_common_metadata"]["classpath"])

        def test_output_write_failure_leaves_no_published_directory(self):
            write_text = Path.write_text
            def fail_provenance(path, *args, **kwargs):
                if path.name == "provenance.json":
                    raise OSError("synthetic disk failure")
                return write_text(path, *args, **kwargs)
            output = self.root / "failed-output"
            with patch.object(Path, "write_text", fail_provenance):
                with self.assertRaisesRegex(OSError, "synthetic disk failure"):
                    analyze(self.primary, self.supplemental, ["QF_S/test/a.smt2"], "test", output)
            self.assertFalse(output.exists())
            self.assertEqual([], list(self.root.glob(".failed-output-staging-*")))

        def test_changed_manifest_is_rejected(self):
            path = self.supplemental / "manifest.csv"
            path.write_bytes(path.read_bytes() + b"\n")
            with self.assertRaisesRegex(AnalysisError, "Manifest checksum"):
                load_run(self.supplemental)

        def test_supplemental_environmental_outlier_is_rejected(self):
            self.rewrite_row(self.supplemental, lambda rows: rows[0].update(wall_ms="999999.000"))
            with self.assertRaisesRegex(AnalysisError, "environmental wall-time"):
                curate(load_run(self.primary), load_run(self.supplemental), ["QF_S/test/a.smt2"])

        def test_cross_trial_correctness_and_coverage_gain(self):
            one, two = load_run(self.primary), load_run(self.supplemental)
            rows = curate(one, two, ["QF_S/test/a.smt2"])
            original = next(row for row in rows if row["sample"] == "QF_S/test/a.smt2")
            contradiction = dict(original, trial="2", outcome="UNSAT", raw_status="UNSAT",
                                 validation_status="NOT_APPLICABLE", key="synthetic second trial")
            rows.append(contradiction)
            report = build_analysis(one, two, rows, ["QF_S/test/a.smt2"], "test")
            self.assertIn("disagreements across any backend/trial: **1**", report)
            self.assertIn("Known-label conflicting attempts: **1**", report)

        def test_budget_fingerprint_source_and_selection_mismatch(self):
            one, two = load_run(self.primary), load_run(self.supplemental)
            for field in ("solve-ms", "runtime_sha256", "classpath"):
                original = two.metadata[field]
                two.metadata[field] = "different"
                with self.assertRaisesRegex(AnalysisError, "metadata differs"):
                    curate(one, two, ["QF_S/test/a.smt2"])
                two.metadata[field] = original
            for field in ("optional-preprocessing", "adaptive-length-projection"):
                two.metadata[field] = "true"
                with self.assertRaisesRegex(AnalysisError, "metadata differs"):
                    curate(one, two, ["QF_S/test/a.smt2"])
                del two.metadata[field]
            with self.assertRaisesRegex(AnalysisError, "must equal"):
                curate(one, two, ["QF_S/test/b.smt2"])
            two.manifest["QF_S/test/a.smt2"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(AnalysisError, "source hash"):
                curate(one, two, ["QF_S/test/a.smt2"])

        def test_ratio_zero_and_unknown_not_decisive(self):
            rows = load_run(self.supplemental).rows
            rows[1]["solve_ms"] = "0.000"
            stats = matched_statistics(rows, "z3", "cvc5")
            self.assertEqual(1, stats[2])
            self.assertEqual("n/a", stats[5])
            self.assertEqual(1, stats[6])
            rows[1]["outcome"] = "UNKNOWN"
            self.assertEqual(0, matched_statistics(rows, "z3", "cvc5")[2])

        def test_properties_windows_and_unicode(self):
            values = parse_properties(b"source=C\\:\\\\Users\\\\name\ncolon\\:key=a\\tb\\nline\nunicode=\\u03bb\n")
            self.assertEqual("C:\\Users\\name", values["source"])
            self.assertEqual("a\tb\nline", values["colon:key"])
            self.assertEqual("\u03bb", values["unicode"])

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(AnalysisTests)
    return unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--primary", type=Path, help="Completed primary run directory")
    parser.add_argument("--supplemental", type=Path, help="Completed rerun containing only the named cases")
    parser.add_argument("--replace-case", action="append", default=[], help="Exact manifest source path; repeat for multiple cases")
    parser.add_argument("--replacement-reason", help="Explicit environmental reason for replacing complete groups")
    parser.add_argument("--output", type=Path, help="New directory separate from the raw runs")
    parser.add_argument("--self-test", action="store_true", help="Run synthetic standard-library sanity checks only")
    args = parser.parse_args()
    if args.self_test:
        return 0 if self_test() else 1
    if not all((args.primary, args.supplemental, args.replace_case, args.replacement_reason, args.output)):
        parser.error("--primary, --supplemental, --replace-case, --replacement-reason, and --output are required")
    try:
        rows = analyze(args.primary, args.supplemental, args.replace_case, args.replacement_reason, args.output)
    except (AnalysisError, OSError, UnicodeError) as error:
        print("Analysis refused: " + str(error), file=sys.stderr)
        return 2
    print(f"Derived {len(rows)} rows; analysis: {args.output.resolve() / 'analysis.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
