"""Synthetic audit/selection regressions; no solver calls or external packages."""

import csv
import io
import json
import tempfile
import unittest
from pathlib import Path

from analyze_ablation import NORMAL_OUTCOMES, OUTCOMES, VARIANTS, analyze, suite_variant_settings, variant_settings
from analyze_benchmark import AnalysisError, parse_properties, sha
from run_ablation import select_cases, snapshot_runtime


def properties(path, values):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=").replace(":", "\\:")
    path.write_text("\n".join(escape(k) + "=" + escape(v) for k, v in values.items()) + "\n", encoding="ascii")


def table(path, rows):
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    path.write_text(stream.getvalue(), encoding="utf-8")


class AblationAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.digest = sha(b"case")
        self.name = "QF_S/family/example.smt2"
        self.sample_id = sha((self.name + "\n" + self.digest).encode())
        self.config = {"variants": VARIANTS, "classpath": "snapshot-runtime", "solve-ms": 10000,
                       "startup-parse-ms": 30000, "validation-ms": 10000, "repeats": 1,
                       "inventory_count": 2, "excluded_count": 1,
                       "selections": {stage: {self.name: self.digest} for stage in ("missed-sat", "all-eligible")}}
        (self.root / "suite.json").write_text(json.dumps(self.config), encoding="utf-8")
        table(self.root / "reference-outcomes.csv", [{"sample": self.name, "outcome": "SAT", "validation_status": "SAT"}])

    def make_run(self, variant="baseline", outcome="UNKNOWN", stage="missed-sat", overrides=None, directory=None):
        directory = directory or self.root / "runs" / stage / variant / "run-1"
        directory.mkdir(parents=True)
        (directory / "records").mkdir()
        (directory / "logs").mkdir()
        (directory / "logs" / "example.log").write_text("synthetic worker log", encoding="utf-8")
        (directory / "source-list.txt").write_text(self.name + "\n", encoding="utf-8")
        table(directory / "manifest.csv", [{"sample": self.name, "sha256": self.digest, "sample_id": self.sample_id}])
        settings = suite_variant_settings(self.config, variant)
        metadata = dict(settings, variant=variant, solvers="noodler-yices", classpath="snapshot-runtime",
                        runtime_sha256="runtime", java_version="17", java_vendor="vendor", os="Windows", os_version="11",
                        architecture="amd64", configuration_sha256="config", schema="1",
                        manifest_sha256=sha((directory / "manifest.csv").read_bytes()))
        metadata.update({k: self.config[k] for k in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats")})
        settings_hash = sha("\n".join(f"{k}={v}" for k, v in sorted(settings.items())).encode())
        metadata.update({"hybrid-configuration-sha256": settings_hash, "source-list-count": "1",
                         "source-list-sha256": sha((directory / "source-list.txt").read_bytes())})
        metadata.update(overrides or {})
        properties(directory / "metadata.properties", metadata)
        key = sha((self.sample_id + "\nnoodler-yices\n1\nconfig").encode())
        row = {"key": key, "sample": self.name, "sample_id": self.sample_id, "source_sha256": self.digest,
               "solver": "noodler-yices", "trial": "1", "eligible": "true", "outcome": outcome,
               "raw_status": outcome, "validation_status": "SAT" if outcome == "SAT" else "NOT_APPLICABLE",
               "validator": "z3", "solve_ns": "1000000", "wall_ns": "5000000", "log": "logs/example.log",
               "completed_utc": "2026-09-20T00:00:00Z"}
        row.update(variant=variant, hybrid_configuration_sha256=settings_hash)
        row.update(witness_sha256=sha(b"witness"), witness_bindings="1", validation_ns="1000000")
        row.update({k.replace("-", "_"): v for k, v in settings.items()})
        record = directory / "records" / (key + ".properties")
        properties(record, row)
        return directory, record, row

    def test_new_suite_projection_policy_is_fingerprinted_and_audited(self):
        self.config["variant_settings"] = {variant: dict(variant_settings(variant),
            **{"adaptive-length-projection": "false"}) for variant in VARIANTS}
        (self.root / "suite.json").write_text(json.dumps(self.config), encoding="utf-8")
        _, record, row = self.make_run(outcome="SAT")
        self.assertEqual(1, analyze(self.root)["recorded"])
        row["adaptive_length_projection"] = "true"
        properties(record, row)
        with self.assertRaisesRegex(AnalysisError, "effective setting mismatch"):
            analyze(self.root)

    def test_historical_suite_does_not_silently_accept_a_new_policy(self):
        self.make_run(overrides={"adaptive-length-projection": "true"})
        with self.assertRaisesRegex(AnalysisError, "policy differs"):
            analyze(self.root)

    def make_curation(self, original_outcome="UNKNOWN", replacement_outcome="UNKNOWN"):
        original_run, original_record, original = self.make_run(outcome=original_outcome)
        original["wall_ns"] = "900000000000"
        properties(original_record, original)
        replacement_run, replacement_record, replacement = self.make_run(
            outcome=replacement_outcome, directory=self.root / "supplemental" / "run-1")
        entry = dict(stage="missed-sat", variant="baseline", sample=self.name,
                     original_run=str(original_run), original_key=original["key"],
                     original_record_sha256=sha(original_record.read_bytes()), replacement_run=str(replacement_run),
                     replacement_key=replacement["key"], replacement_record_sha256=sha(replacement_record.read_bytes()),
                     reason="Confirmed host suspension during this attempt; singleton rerun under identical settings.")
        self.write_curations([entry])
        return entry, (original_run, original_record, original), (replacement_run, replacement_record, replacement)

    def write_curations(self, entries):
        (self.root / "curations.json").write_text(json.dumps({"schema": 1, "replacements": entries}), encoding="utf-8")

    def test_curation_preserves_original_progress_and_exposes_provenance(self):
        entry, (_, original_record, _), (_, replacement_record, _) = self.make_curation(replacement_outcome="SAT")
        original_bytes, replacement_bytes = original_record.read_bytes(), replacement_record.read_bytes()
        status = analyze(self.root)
        self.assertEqual((1, 28, 1, 0, 1), (status["recorded"], status["expected"], status["curated_rows"],
                                         status["wall_outliers"], status["original_wall_outliers"]))
        self.assertFalse(status["complete"])
        self.assertEqual(original_bytes, original_record.read_bytes())
        self.assertEqual(replacement_bytes, replacement_record.read_bytes())
        self.assertEqual("UNKNOWN", status["curations"][0]["original"]["outcome"])
        self.assertEqual("SAT", status["curations"][0]["replacement"]["outcome"])
        self.assertEqual(entry["original_record_sha256"], status["curations"][0]["original_record_sha256"])
        with (self.root / "comparison.csv").open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(len(rows), 1)
        self.assertEqual(("SAT", "UNKNOWN"), (rows[0]["outcome"], rows[0]["curation_original_outcome"]))
        self.assertIn(entry["reason"], (self.root / "analysis.md").read_text(encoding="utf-8"))

    def test_curation_does_not_hide_original_decisive_disagreement(self):
        self.make_curation(original_outcome="UNSAT", replacement_outcome="SAT")
        status = analyze(self.root)
        self.assertEqual(status["disagreements"], [self.name])

    def test_curation_leaves_subsequently_completed_rows_unchanged(self):
        self.make_curation()
        self.assertEqual(analyze(self.root)["recorded"], 1)
        _, record, _ = self.make_run("optional", "SAT")
        original_bytes = record.read_bytes()
        status = analyze(self.root)
        self.assertEqual((2, 1, 0), (status["recorded"], status["curated_rows"], status["wall_outliers"]))
        self.assertEqual(original_bytes, record.read_bytes())
        with (self.root / "comparison.csv").open(encoding="utf-8", newline="") as stream:
            future = next(row for row in csv.DictReader(stream) if row["variant"] == "optional")
        self.assertEqual("SAT", future["outcome"])
        self.assertEqual("", future["curation_original_outcome"])

    def test_curation_cannot_replace_ordinary_inconclusive_result(self):
        entry, (_, original_record, original), _ = self.make_curation()
        original["wall_ns"] = "60000000000"  # Equality with the guard is not an outlier.
        properties(original_record, original)
        entry["original_record_sha256"] = sha(original_record.read_bytes())
        self.write_curations([entry])
        with self.assertRaisesRegex(AnalysisError, "Only measured host-wall outliers"):
            analyze(self.root)

    def test_curation_rejects_duplicate_or_wrong_original_identity(self):
        entry, _, _ = self.make_curation()
        mutations = ({"stage": "all-eligible"}, {"variant": "combined"}, {"sample": "not-selected.smt2"},
                     {"original_run": str(self.root / "other")}, {"original_key": "0" * 64},
                     {"original_record_sha256": "0" * 64}, {"replacement_key": "0" * 64},
                     {"replacement_record_sha256": "0" * 64}, {"original_run": "relative/path"})
        for changes in mutations:
            with self.subTest(changes=changes):
                self.write_curations([{**entry, **changes}])
                with self.assertRaises(AnalysisError):
                    analyze(self.root)
        self.write_curations([entry, entry])
        with self.assertRaisesRegex(AnalysisError, "Duplicate curation"):
            analyze(self.root)

    def test_curation_rejects_supplemental_configuration_changes(self):
        _, _, (replacement_run, _, _) = self.make_curation()
        metadata_path = replacement_run / "metadata.properties"
        original = metadata_path.read_bytes()
        metadata = parse_properties(original)
        for field, value in (("runtime_sha256", "different"), ("classpath", "different"),
                             ("solve-ms", "20000"), ("repeats", "2"), ("variant", "combined"),
                             ("max-automata-storage", "4000000"), ("source-list-sha256", "0" * 64),
                             ("source-list-count", "2"), ("java_version", "21")):
            with self.subTest(field=field):
                properties(metadata_path, {**metadata, field: value})
                with self.assertRaises(AnalysisError):
                    analyze(self.root)
        metadata_path.write_bytes(original)
        self.assertEqual(analyze(self.root)["curated_rows"], 1)

    def test_curation_rejects_wrong_source_and_non_singleton_selection(self):
        _, _, (replacement_run, _, _) = self.make_curation()
        metadata_path = replacement_run / "metadata.properties"
        metadata = parse_properties(metadata_path.read_bytes())
        selection = replacement_run / "source-list.txt"
        for contents in ("wrong.smt2\n", self.name + "\nextra.smt2\n", self.name + "\n" + self.name + "\n"):
            with self.subTest(contents=contents):
                selection.write_text(contents, encoding="utf-8")
                properties(metadata_path, {**metadata, "source-list-sha256": sha(selection.read_bytes())})
                with self.assertRaisesRegex(AnalysisError, "Source-list snapshot mismatch"):
                    analyze(self.root)

    def test_curation_rejects_changed_source_root_and_source_content(self):
        _, (original_run, _, _), (replacement_run, _, _) = self.make_curation()
        self.config["source"] = "frozen-source-root"
        (self.root / "suite.json").write_text(json.dumps(self.config), encoding="utf-8")
        for run in (original_run, replacement_run):
            path = run / "metadata.properties"
            properties(path, {**parse_properties(path.read_bytes()), "source": "frozen-source-root"})
        path = replacement_run / "metadata.properties"
        metadata = parse_properties(path.read_bytes())
        properties(path, {**metadata, "source": "changed-source-root"})
        with self.assertRaisesRegex(AnalysisError, "Source root changed"):
            analyze(self.root)
        manifest = replacement_run / "manifest.csv"
        table(manifest, [{"sample": self.name, "sha256": sha(b"different source"), "sample_id": self.sample_id}])
        properties(path, {**metadata, "manifest_sha256": sha(manifest.read_bytes())})
        with self.assertRaisesRegex(AnalysisError, "Source hash mismatch"):
            analyze(self.root)

    def test_curation_rejects_repeated_outlier_missing_log_and_missing_completion(self):
        entry, _, (replacement_run, replacement_record, replacement) = self.make_curation()
        for changes in ({"wall_ns": "900000000000"}, {"wall_ns": ""}, {"completed_utc": ""},
                        {"log": "logs/missing.log"}, {"log": "../outside.log"}, {"outcome": "CRASH"},
                        {"raw_status": "SAT"}):
            with self.subTest(changes=changes):
                properties(replacement_record, {**replacement, **changes})
                entry["replacement_record_sha256"] = sha(replacement_record.read_bytes())
                self.write_curations([entry])
                with self.assertRaises(AnalysisError):
                    analyze(self.root)
        properties(replacement_record, replacement)
        entry["replacement_record_sha256"] = sha(replacement_record.read_bytes())
        self.write_curations([entry])
        self.assertEqual(analyze(self.root)["curated_rows"], 1)

    def test_curation_rejects_reusing_primary_measurement(self):
        entry, _, _ = self.make_curation()
        other_run, other_record, other = self.make_run(stage="all-eligible")
        entry.update(replacement_run=str(other_run), replacement_key=other["key"],
                     replacement_record_sha256=sha(other_record.read_bytes()))
        self.write_curations([entry])
        with self.assertRaisesRegex(AnalysisError, "separate from primary"):
            analyze(self.root)

    def test_curation_validates_all_entries_before_writing_any_report(self):
        entry, _, _ = self.make_curation()
        analyze(self.root)
        artifacts = [self.root / name for name in ("comparison.csv", "analysis.md", "status.json")]
        before = [path.read_bytes() for path in artifacts]
        self.write_curations([entry, {**entry, "sample": "unavailable.smt2"}])
        with self.assertRaises(AnalysisError):
            analyze(self.root)
        self.assertEqual(before, [path.read_bytes() for path in artifacts])

    def test_curation_strict_schema(self):
        entry, _, _ = self.make_curation()
        documents = ({"schema": 2, "replacements": [entry]}, {"schema": True, "replacements": [entry]},
                     {"schema": 1, "replacements": [{**entry, "reason": " "}]},
                     {"schema": 1, "replacements": [{**entry, "extra": "field"}]},
                     {"schema": 1, "replacements": [entry], "extra": "field"})
        for document in documents:
            with self.subTest(document=document):
                (self.root / "curations.json").write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaises(AnalysisError):
                    analyze(self.root)

    def test_curation_cannot_hide_original_fault_with_long_wall_time(self):
        entry, (_, original_record, original), _ = self.make_curation()
        for outcome in OUTCOMES - NORMAL_OUTCOMES:
            with self.subTest(outcome=outcome):
                properties(original_record, {**original, "outcome": outcome})
                entry["original_record_sha256"] = sha(original_record.read_bytes())
                self.write_curations([entry])
                with self.assertRaisesRegex(AnalysisError, "original worker/validation failure"):
                    analyze(self.root)

    def test_every_abnormal_outcome_is_a_fault(self):
        _, record, row = self.make_run()
        for outcome in OUTCOMES - NORMAL_OUTCOMES:
            with self.subTest(outcome=outcome):
                properties(record, {**row, "outcome": outcome})
                self.assertEqual(analyze(self.root)["faults"], 1)

    def test_partial_report_retains_missing_attempts(self):
        self.make_run()
        status = analyze(self.root)
        self.assertFalse(status["complete"])
        self.assertEqual((status["recorded"], status["expected"]), (1, 28))
        self.assertIn("Partial", (self.root / "analysis.md").read_text(encoding="utf-8"))

    def test_complete_matrix_is_accepted(self):
        for stage in ("missed-sat", "all-eligible"):
            for variant in VARIANTS:
                self.make_run(variant, "SAT", stage)
        self.assertTrue(analyze(self.root)["complete"])

    def test_cap_mismatch_is_rejected(self):
        self.make_run("states-2x", overrides={"max-automata-states": "400000"})
        with self.assertRaisesRegex(AnalysisError, "max-automata-states"):
            analyze(self.root)

    def test_runtime_mismatch_is_rejected(self):
        self.make_run()
        self.make_run("optional", overrides={"runtime_sha256": "different-runtime"})
        with self.assertRaisesRegex(AnalysisError, "runtime_sha256"):
            analyze(self.root)

    def test_repeated_stage_outcomes_are_kept_separate(self):
        self.make_run("baseline", "UNKNOWN", "missed-sat")
        self.make_run("baseline", "SAT", "all-eligible")
        self.assertEqual(analyze(self.root)["recorded"], 2)
        text = (self.root / "analysis.md").read_text(encoding="utf-8")
        self.assertIn("| baseline | 1 | 0 | 0 | 1 | 1 |", text)

    def test_sat_requires_validation(self):
        _, record, row = self.make_run(outcome="SAT")
        row["validation_status"] = "UNKNOWN"
        properties(record, row)
        with self.assertRaisesRegex(AnalysisError, "independent validation"):
            analyze(self.root)

    def test_global_disagreement_is_reported(self):
        self.make_run(outcome="UNSAT")
        self.assertEqual(analyze(self.root)["disagreements"], [self.name])

    def test_over_budget_decisive_result_is_rejected(self):
        _, record, row = self.make_run(outcome="SAT")
        row["solve_ns"] = "10000000001"
        properties(record, row)
        with self.assertRaisesRegex(AnalysisError, "exceeds its budget"):
            analyze(self.root)

    def test_host_sleep_is_flagged_and_preserved(self):
        _, record, row = self.make_run()
        row["wall_ns"] = "900000000000"
        properties(record, row)
        original = record.read_bytes()
        self.assertEqual(analyze(self.root)["wall_outliers"], 1)
        self.assertEqual(record.read_bytes(), original)

    def test_duplicate_attempt_is_rejected(self):
        _, record, _ = self.make_run()
        record.with_name("duplicate.properties").write_bytes(record.read_bytes())
        with self.assertRaises(AnalysisError):
            analyze(self.root)

    def test_snapshot_does_not_change_with_original(self):
        classes = self.root / "classes"
        classes.mkdir()
        (classes / "Solver.class").write_bytes(b"original")
        copied = Path(snapshot_runtime(str(classes), self.root / "snapshot"))
        (classes / "Solver.class").write_bytes(b"modified")
        self.assertEqual((copied / "Solver.class").read_bytes(), b"original")


class SelectionTest(unittest.TestCase):
    def test_only_validated_sat_misses_are_selected(self):
        inventory = [{"sample": n, "sha256": n + "-hash"} for n in ("miss", "solved", "excluded")]
        reference = [
            {"sample": "miss", "source_sha256": "miss-hash", "eligible": "true", "solver": "z3",
             "outcome": "SAT", "validation_status": "SAT"},
            {"sample": "miss", "source_sha256": "miss-hash", "eligible": "true", "solver": "noodler-yices",
             "outcome": "UNKNOWN", "validation_status": "NOT_APPLICABLE"},
            {"sample": "solved", "source_sha256": "solved-hash", "eligible": "true", "solver": "noodler-yices",
             "outcome": "SAT", "validation_status": "SAT"},
            {"sample": "excluded", "source_sha256": "excluded-hash", "eligible": "false", "solver": "z3",
             "outcome": "EXCLUDED", "validation_status": "NOT_APPLICABLE"},
        ]
        selections, excluded = select_cases(reference, inventory)
        self.assertEqual(set(selections["missed-sat"]), {"miss"})
        self.assertEqual(set(selections["all-eligible"]), {"miss", "solved"})
        self.assertEqual(excluded, {"excluded"})
        reference[0]["validation_status"] = "UNKNOWN"
        with self.assertRaises(AnalysisError):
            select_cases(reference, inventory)


if __name__ == "__main__":
    unittest.main()
