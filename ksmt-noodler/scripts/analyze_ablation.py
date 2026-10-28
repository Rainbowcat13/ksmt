#!/usr/bin/env python3
"""Audit and summarize the sequential Noodler/Yices ablation suite (stdlib only).

Partial reports explicitly retain missing attempts. SAT counts require independent
witness validation; UNSAT is a solver claim. Historical data select the diagnostic
subset and check disagreements, but never substitute for a fresh baseline.
"""

import argparse
import csv
import io
import json
import math
import re
import statistics
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from analyze_benchmark import AnalysisError, csv_rows, parse_properties, require, sha


BASE_SETTINGS = {
    "max-automata-states": "100000", "max-automata-storage": "1000000",
    "max-encoding-constraints": "1000000", "max-preprocessing-units": "1000000",
    "max-character-positions": "100000", "max-idle-rounds": "2",
    "optional-preprocessing": "false", "small-first-search": "false",
}
CAPS = {
    "states": "max-automata-states", "storage": "max-automata-storage",
    "encoding": "max-encoding-constraints", "preprocessing": "max-preprocessing-units",
    "positions": "max-character-positions",
}
VARIANTS = ["baseline"] + [f"{cap}-{scale}x" for cap in CAPS for scale in (2, 4)] + [
    "optional", "small-first", "combined",
]
STAGES = ("missed-sat", "all-eligible")
DECISIVE = {"SAT", "UNSAT"}
OUTCOMES = DECISIVE | {
    "UNKNOWN", "TIMEOUT", "INVALID_WITNESS", "UNVERIFIED_WITNESS", "UNSUPPORTED",
    "PARSE_ERROR", "ERROR", "CRASH", "STARTUP_PARSE_TIMEOUT", "EXCLUDED",
}
NORMAL_OUTCOMES = {"SAT", "UNSAT", "UNKNOWN", "TIMEOUT"}
CURATION_FIELDS = {
    "stage", "variant", "sample", "original_run", "original_key", "original_record_sha256",
    "replacement_run", "replacement_key", "replacement_record_sha256", "reason",
}


def variant_settings(variant):
    require(variant in VARIANTS, "Unknown variant: " + variant)
    settings = dict(BASE_SETTINGS)
    for cap, parameter in CAPS.items():
        for scale in (2, 4):
            if variant == f"{cap}-{scale}x":
                settings[parameter] = str(int(settings[parameter]) * scale)
    if variant in ("optional", "combined"):
        settings["optional-preprocessing"] = "true"
    if variant in ("small-first", "combined"):
        settings["small-first-search"] = "true"
    return settings


def suite_variant_settings(config, variant):
    """Keep historical frozen runtimes valid while auditing newly declared policies."""
    expected = variant_settings(variant)
    declared = config.get("variant_settings", {}).get(variant, expected)
    require(set(expected) <= set(declared) <= set(expected) | {"adaptive-length-projection"},
            "Unrecognized or incomplete suite settings: " + variant)
    require(all(declared[key] == value for key, value in expected.items()),
            "Suite settings changed the named preset: " + variant)
    require(declared.get("adaptive-length-projection", "true") in ("true", "false"),
            "Invalid adaptive-length-projection suite policy")
    return dict(declared)


def read_csv(path):
    data = path.read_bytes()
    return csv_rows(data, set(), str(path))[1]


def atomic_text(path, text):
    temporary = path.with_suffix(path.suffix + ".tmp-" + uuid.uuid4().hex)
    temporary.write_text(text, encoding="utf-8", newline="")
    temporary.replace(path)


def milliseconds(row, key):
    value = row.get(key, "")
    if not value:
        return None
    number = float(value)
    require(math.isfinite(number) and number >= 0, f"Invalid {key} in {row.get('sample')}")
    return number


def load_variant(suite, stage, variant, config, expected, common):
    parent = suite / "runs" / stage / variant
    runs = sorted(p.parent for p in parent.glob("*/metadata.properties"))
    require(len(runs) <= 1, "Multiple runs need explicit curation: " + str(parent))
    if not runs:
        return None, []
    run = runs[0].resolve()
    return run, load_run(run, stage, variant, config, expected, common)


def load_run(run, stage, variant, config, expected, common):
    """Validate a primary or explicitly selected supplemental run identically."""
    metadata = parse_properties((run / "metadata.properties").read_bytes())
    require(metadata.get("schema") == "1", "Unsupported run schema")
    settings = suite_variant_settings(config, variant)
    require(metadata.get("adaptive-length-projection") == settings.get("adaptive-length-projection"),
            "Run length-projection policy differs from its suite snapshot")
    for key, value in settings.items():
        require(metadata.get(key) == value, f"{variant}: unexpected {key}: {metadata.get(key)}")
    require(metadata.get("variant") == variant, "Variant metadata mismatch")
    settings_hash = sha("\n".join(f"{k}={v}" for k, v in sorted(settings.items())).encode())
    require(metadata.get("hybrid-configuration-sha256") == settings_hash, "Hybrid configuration digest mismatch")
    selection_blob = (run / "source-list.txt").read_bytes()
    require(sha(selection_blob) == metadata.get("source-list-sha256"), "Source-list snapshot digest mismatch")
    selected = selection_blob.decode("utf-8-sig").splitlines()
    require(len(selected) == len(set(selected)) and set(selected) == set(expected), "Source-list snapshot mismatch")
    require(metadata.get("source-list-count") == str(len(expected)), "Source-list count mismatch")
    for key in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats"):
        require(metadata.get(key) == str(config[key]), f"Mismatched {key} in {run}")
    require(metadata.get("solvers") == "noodler-yices", "Ablation expects one hybrid backend")
    require(metadata.get("classpath") == config["classpath"], "Runtime classpath changed")
    if "source" in config:
        require(metadata.get("source") == config["source"], "Source root changed")
    for key in ("runtime_sha256", "java_version", "java_vendor", "os", "os_version", "architecture"):
        require(bool(metadata.get(key)), f"Missing {key}")
        if key in common:
            require(common[key] == metadata[key], f"Incomparable {key} in {run}")
        else:
            common[key] = metadata[key]
    manifest_blob = (run / "manifest.csv").read_bytes()
    require(sha(manifest_blob) == metadata.get("manifest_sha256"), "Manifest checksum mismatch")
    manifest_rows = csv_rows(manifest_blob, {"sample", "sha256", "sample_id"}, "manifest")[1]
    manifest = {row["sample"]: row for row in manifest_rows}
    require(len(manifest) == len(manifest_rows), "Duplicate manifest source")
    require(set(manifest) == set(expected), f"{stage}/{variant}: wrong sample set")
    for name, entry in manifest.items():
        require(entry["sha256"] == expected[name], "Source hash mismatch: " + name)
        require(entry["sample_id"] == sha((name + "\n" + expected[name]).encode()), "Bad sample ID")
    # Immutable per-attempt checkpoints are authoritative even during a CSV append.
    rows, seen = [], set()
    for record in sorted((run / "records").glob("*.properties")):
        record_blob = record.read_bytes()
        row = parse_properties(record_blob)
        name = row.get("sample")
        require(name in manifest, "Attempt outside selected corpus")
        require(name not in seen, "Duplicate source attempt")
        seen.add(name)
        entry = manifest[name]
        require(row.get("solver") == "noodler-yices" and row.get("trial") == "1", "Unexpected attempt")
        require(row.get("sample_id") == entry["sample_id"] and row.get("source_sha256") == expected[name],
                "Attempt source mismatch")
        key = sha((entry["sample_id"] + "\nnoodler-yices\n1\n" + metadata["configuration_sha256"]).encode())
        require(row.get("key") == key and record.stem == key, "Attempt/configuration mismatch")
        require(row.get("variant") == variant and row.get("hybrid_configuration_sha256") == settings_hash,
                "Attempt variant/configuration mismatch")
        for setting, value in settings.items():
            require(row.get(setting.replace("-", "_")) == value, "Attempt effective setting mismatch")
        require(row.get("outcome") in OUTCOMES, "Unrecognized outcome")
        require(row.get("eligible") == "true", f"Eligibility changed for {name}")
        if row["outcome"] == "SAT":
            require(row.get("raw_status") == "SAT" and row.get("validation_status") == "SAT"
                    and row.get("validator") == "z3", "SAT without independent validation")
            require(bool(re.fullmatch("[0-9a-f]{64}", row.get("witness_sha256", "")))
                    and row.get("witness_bindings", "").isdigit(), "SAT witness metadata missing")
            require(row.get("validation_ns", "").isdigit()
                    and int(row["validation_ns"]) <= config["validation-ms"] * 1_000_000,
                    "SAT validation exceeds its budget or has missing timing")
        if row["outcome"] in DECISIVE:
            require(row.get("raw_status") == row["outcome"], "Decisive/raw status mismatch")
            require(row.get("solve_ns", "").isdigit()
                    and int(row["solve_ns"]) <= config["solve-ms"] * 1_000_000,
                    "Decisive solve exceeds its budget or has missing timing")
        for field in ("wall", "solve", "validation"):
            nanos = row.get(field + "_ns", "")
            row[field + "_ms"] = str(int(nanos) / 1_000_000) if nanos else ""
            milliseconds(row, field + "_ms")
        row.update(ablation_stage=stage, ablation_variant=variant, ablation_run=str(run),
                   ablation_record_sha256=sha(record_blob),
                   ablation_log=str(run / row.get("log", "")))
        rows.append(row)
    return rows


def wall_limit(config):
    return sum(config[key] for key in ("solve-ms", "startup-parse-ms", "validation-ms")) + 10000


def apply_curations(suite, config, rows, common):
    """Replace only individually identified host-wall outliers, after every entry validates."""
    path = suite / "curations.json"
    if not path.exists():
        return rows, []
    blob = path.read_bytes()
    document = json.loads(blob)
    require(isinstance(document, dict) and set(document) == {"schema", "replacements"}, "Invalid curation schema")
    require(type(document["schema"]) is int and document["schema"] == 1 and
            isinstance(document["replacements"], list), "Invalid curation schema")
    originals = {(row["ablation_stage"], row["ablation_variant"], row["sample"]): row for row in rows}
    validated, seen, replacements_seen = [], set(), set()
    for entry in document["replacements"]:
        require(isinstance(entry, dict) and set(entry) == CURATION_FIELDS and
                all(isinstance(value, str) and value.strip() for value in entry.values()), "Invalid curation entry")
        stage, variant, name = entry["stage"], entry["variant"], entry["sample"]
        require(stage in STAGES and variant in VARIANTS, "Unknown curation stage/variant")
        require(name in config["selections"][stage], "Curation source outside stage")
        identity = stage, variant, name
        require(identity not in seen, "Duplicate curation source/stage/variant")
        seen.add(identity)
        require(identity in originals, "Curation original attempt is missing")
        original = originals[identity]
        for field in ("original_run", "replacement_run"):
            require(Path(entry[field]).is_absolute(), "Curation run paths must be absolute")
        original_run, replacement_run = (Path(entry[field]).resolve() for field in ("original_run", "replacement_run"))
        require(original_run == Path(original["ablation_run"]).resolve(), "Curation original run/stage mismatch")
        require(replacement_run != original_run and (suite / "runs").resolve() not in replacement_run.parents,
                "Supplemental run must be separate from primary runs")
        for prefix in ("original", "replacement"):
            for suffix in ("key", "record_sha256"):
                require(bool(re.fullmatch("[0-9a-f]{64}", entry[prefix + "_" + suffix])), "Invalid curation digest")
        require(entry["original_key"] == original["key"] and
                entry["original_record_sha256"] == original["ablation_record_sha256"], "Curation original record mismatch")
        require(original["outcome"] in NORMAL_OUTCOMES, "Curation cannot conceal an original worker/validation failure")
        require((milliseconds(original, "wall_ms") or 0) > wall_limit(config),
                "Only measured host-wall outliers may be replaced")
        replacement_identity = replacement_run, entry["replacement_key"]
        require(replacement_identity not in replacements_seen, "Supplemental attempt reused by multiple curations")
        replacements_seen.add(replacement_identity)
        replacement_rows = load_run(replacement_run, stage, variant, config,
                                    {name: config["selections"][stage][name]}, dict(common))
        require(len(replacement_rows) == 1, "Supplemental run must contain exactly one completed attempt")
        replacement = replacement_rows[0]
        require(entry["replacement_key"] == replacement["key"] and
                entry["replacement_record_sha256"] == replacement["ablation_record_sha256"],
                "Curation replacement record mismatch")
        require(replacement["outcome"] in NORMAL_OUTCOMES,
                "Supplemental attempt has a worker/validation failure")
        require(replacement.get("raw_status") in {"SAT", "UNSAT", "UNKNOWN", "NOT_RETURNED"},
                "Invalid supplemental raw status")
        if replacement["outcome"] == "UNKNOWN":
            require(replacement["raw_status"] == "UNKNOWN", "Supplemental UNKNOWN/raw status mismatch")
        replacement_wall = milliseconds(replacement, "wall_ms")
        require(replacement_wall is not None and replacement_wall <= wall_limit(config),
                "Supplemental attempt is missing wall timing or is another host-wall outlier")
        require(bool(replacement.get("completed_utc")), "Supplemental attempt lacks completion timestamp")
        log = Path(replacement["ablation_log"]).resolve()
        require(replacement_run in log.parents and log.is_file(), "Missing or escaping supplemental worker log")
        metadata_blob = (replacement_run / "metadata.properties").read_bytes()
        manifest_blob = (replacement_run / "manifest.csv").read_bytes()
        selection_blob = (replacement_run / "source-list.txt").read_bytes()
        validated.append({**entry, "curations_sha256": sha(blob), "original": dict(original),
                          "replacement": dict(replacement), "replacement_metadata_sha256": sha(metadata_blob),
                          "replacement_manifest_sha256": sha(manifest_blob),
                          "replacement_source_list_sha256": sha(selection_blob)})
    # Do not publish any substitution until all entries and referenced records pass.
    replacements = {}
    for curation in validated:
        original, replacement = curation["original"], dict(curation["replacement"])
        replacement.update(curation_reason=curation["reason"], curation_manifest_sha256=sha(blob))
        replacement.update({"curation_original_" + key: value for key, value in original.items()})
        replacements[curation["stage"], curation["variant"], curation["sample"]] = replacement
    require(path.read_bytes() == blob, "Curation manifest changed during audit")
    return [replacements.get((row["ablation_stage"], row["ablation_variant"], row["sample"]), row) for row in rows], validated


def render_report(suite, config, rows, common, curations=()):
    groups = defaultdict(list)
    by_source = defaultdict(list)
    for row in rows:
        groups[row["ablation_stage"], row["ablation_variant"]].append(row)
        if row["outcome"] in DECISIVE:
            by_source[row["sample"]].append(row)
    for curation in curations:
        original = curation["original"]
        if original["outcome"] in DECISIVE:
            by_source[original["sample"]].append(original)
    reference = read_csv(suite / "reference-outcomes.csv")
    reference_by_source = defaultdict(set)
    for row in reference:
        if row["outcome"] == "UNSAT" or (row["outcome"] == "SAT" and row["validation_status"] == "SAT"):
            reference_by_source[row["sample"]].add(row["outcome"])
    disagreements = []
    for name, source_rows in by_source.items():
        outcomes = {r["outcome"] for r in source_rows} | reference_by_source[name]
        if len(outcomes) > 1:
            disagreements.append(name)
    faults = [r for r in rows if r["outcome"] not in NORMAL_OUTCOMES]
    # A generous sum-of-phase-budgets guard detects suspend/hibernation contamination.
    max_wall = wall_limit(config)
    outliers = [r for r in rows if (milliseconds(r, "wall_ms") or 0) > max_wall]
    expected_total = sum(len(config["selections"][s]) * len(VARIANTS) for s in STAGES)
    complete = len(rows) == expected_total
    lines = ["# Noodler/Yices ablation comparison", "",
             f"Updated {datetime.now(timezone.utc).isoformat()}. **{'Complete' if complete else 'Partial'}: "
             f"{len(rows)}/{expected_total} attempts recorded.**", "",
             "Fresh sequential JVMs, one trial per case/variant, 10 s assertion/check, 30 s startup/parse, "
             "10 s independent Z3 witness validation. Baseline is rerun with the same binaries as every variant. "
             "Both-policy combined uses the original caps. Caps are varied individually; no cap multipliers are bundled.", "",
             f"Corpus inventory: {config['inventory_count']} files; {len(config['selections']['all-eligible'])} "
             f"eligible; {config['excluded_count']} unsupported-syntax exclusions carried from the source-hashed "
             "reference inventory. Every selected source is checked against that inventory. The 114-case diagnostic "
             "selection uses historical SAT outcomes and must not be presented as representative coverage.", "",
             f"Decisive disagreements (including historical cross-solver answers): **{len(disagreements)}**. "
             f"Witness, worker, parser, or unexpected eligibility failures: **{len(faults)}**. "
             f"Wall-time contamination flags: **{len(outliers)}**.", "",
             f"Explicit host-wall curations: **{len(curations)}**. Each substitutes one verified singleton rerun "
             "for one measured wall-time outlier without increasing completed-attempt counts. Original raw records "
             "remain unchanged and their decisive answers remain in disagreement checks.", "",
             "SAT counts require validated witnesses. UNSAT answers have no proof certificate. Missing attempts "
             "are not failures or zero runtimes. Timing medians below use only paired cases solved with the same "
             "answer. One fixed-order trial cannot establish timing stability.", ""]
    for stage in STAGES:
        size = len(config["selections"][stage])
        baseline = {r["sample"]: r for r in groups[stage, "baseline"]}
        lines += [f"## {stage} ({size} cases)", "",
                  "| Variant | Done | SAT | UNSAT | UNKNOWN | Timeout | Other | Gained/lost vs baseline* | Paired Δ solve ms* |",
                  "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for variant in VARIANTS:
            group = groups[stage, variant]
            counts = Counter(r["outcome"] for r in group)
            current = {r["sample"]: r for r in group}
            both = set(current) & set(baseline)
            gained = sum(current[n]["outcome"] in DECISIVE and baseline[n]["outcome"] not in DECISIVE for n in both)
            lost = sum(current[n]["outcome"] not in DECISIVE and baseline[n]["outcome"] in DECISIVE for n in both)
            deltas = [milliseconds(current[n], "solve_ms") - milliseconds(baseline[n], "solve_ms") for n in both
                      if current[n]["outcome"] in DECISIVE and current[n]["outcome"] == baseline[n]["outcome"]
                      and milliseconds(current[n], "solve_ms") is not None
                      and milliseconds(baseline[n], "solve_ms") is not None]
            delta = f"{statistics.median(deltas):+.3f} (n={len(deltas)})" if deltas else "—"
            other = sum(v for k, v in counts.items() if k not in {"SAT", "UNSAT", "UNKNOWN", "TIMEOUT"})
            change = f"+{gained}/−{lost} (n={len(both)})" if both else "—"
            lines.append(f"| {variant} | {len(group)}/{size} | {counts['SAT']} | {counts['UNSAT']} | "
                         f"{counts['UNKNOWN']} | {counts['TIMEOUT']} | {other} | {change} | {delta} |")
        lines += ["", "*Only the intersection of completed cases is paired while a stage is partial.", "",
                  "| Variant | UNKNOWN reasons (counts) |", "|---|---|"]
        for variant in VARIANTS:
            reasons = Counter(r.get("details", "") for r in groups[stage, variant] if r["outcome"] == "UNKNOWN")
            if reasons:
                description = "; ".join(f"{key.replace('|', '/')} ({count})" for key, count in reasons.most_common())
                lines.append(f"| {variant} | {description} |")
        lines.append("")
    lines += ["## Repeated diagnostic cases across stages", "",
              "The full-set stage measures the diagnostic cases again with the same worker settings. "
              "This table keeps the stages separate and exposes outcome variability rather than pooling duplicates.", "",
              "| Variant | Paired completed cases | SAT both | SAT diagnostic only | SAT full-set only | Any outcome changes |",
              "|---|---:|---:|---:|---:|---:|"]
    for variant in VARIANTS:
        diagnostic = {r["sample"]: r for r in groups["missed-sat", variant]}
        full = {r["sample"]: r for r in groups["all-eligible", variant]}
        paired = set(diagnostic) & set(full)
        both_sat = sum(diagnostic[n]["outcome"] == full[n]["outcome"] == "SAT" for n in paired)
        diagnostic_sat = sum(diagnostic[n]["outcome"] == "SAT" and full[n]["outcome"] != "SAT" for n in paired)
        full_sat = sum(full[n]["outcome"] == "SAT" and diagnostic[n]["outcome"] != "SAT" for n in paired)
        changed = sum(full[n]["outcome"] != diagnostic[n]["outcome"] for n in paired)
        lines.append(f"| {variant} | {len(paired)} | {both_sat} | {diagnostic_sat} | {full_sat} | {changed} |")
    lines.append("")
    if disagreements:
        lines += ["## Decisive disagreements requiring investigation", ""] + ["- " + n for n in sorted(disagreements)]
    if outliers:
        lines += ["", "## Suspected host interruptions (retained, not silently replaced)", ""]
        lines += [f"- {r['ablation_stage']}/{r['ablation_variant']}: {r['sample']} ({r['wall_ms']} ms)" for r in outliers]
    if curations:
        lines += ["", "## Explicit host-wall curation provenance", "",
                  "`curations.json` pins both immutable record hashes. `status.json` retains the complete original "
                  "and replacement records; `comparison.csv` includes original fields with the `curation_original_` prefix.", ""]
        for curation in curations:
            original, replacement = curation["original"], curation["replacement"]
            lines += [f"- {curation['stage']}/{curation['variant']}: `{curation['sample']}`. "
                      f"Original {original['outcome']} ({original['wall_ms']} ms); "
                      f"replacement {replacement['outcome']} ({replacement['wall_ms']} ms). "
                      f"Reason: {curation['reason']}",
                      f"  Original: `{curation['original_run']}/records/{curation['original_key']}.properties` "
                      f"(SHA256 `{curation['original_record_sha256']}`).",
                      f"  Replacement: `{curation['replacement_run']}/records/{curation['replacement_key']}.properties` "
                      f"(SHA256 `{curation['replacement_record_sha256']}`)."]
    lines += ["", "## Reproduction and artifacts", "",
              "`suite.json` records selections, reference hashes, JVM, classpath snapshot, and budgets. "
              "`runtime/manifest.json` hashes the copied runtime. `runs/<stage>/<variant>/<run-id>/` retains "
              "metadata, manifests, immutable per-attempt records, worker logs, raw CSVs, and individual summaries. "
              "`comparison.csv` is derived from immutable records and links back to each run and log.", "",
              "Resume with `python ksmt-noodler/scripts/run_ablation.py run --suite <this-directory>`. "
              "Refresh this report with `python ksmt-noodler/scripts/analyze_ablation.py --suite <this-directory>`.", ""]
    return "\n".join(lines), {"complete": complete, "recorded": len(rows), "expected": expected_total,
                              "disagreements": disagreements, "faults": len(faults), "wall_outliers": len(outliers),
                              "original_wall_outliers": len(outliers) + len(curations),
                              "curated_rows": len(curations), "curations": list(curations), "runtime": common}


def analyze(suite):
    suite = Path(suite).resolve()
    config = json.loads((suite / "suite.json").read_text(encoding="utf-8"))
    require(config["variants"] == VARIANTS, "Variant matrix changed")
    common, rows = {}, []
    for stage in STAGES:
        for variant in VARIANTS:
            _, group = load_variant(suite, stage, variant, config, config["selections"][stage], common)
            rows.extend(group)
    rows, curations = apply_curations(suite, config, rows, common)
    report, status = render_report(suite, config, rows, common, curations)
    columns = sorted({key for row in rows for key in row})
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=columns, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    atomic_text(suite / "comparison.csv", output.getvalue())
    atomic_text(suite / "analysis.md", report)
    atomic_text(suite / "status.json", json.dumps(status, indent=2) + "\n")
    return status


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        print(json.dumps(analyze(arguments.suite), indent=2))
    except (AnalysisError, OSError, ValueError) as error:
        parser.exit(1, f"Ablation audit failed: {error}\n")
