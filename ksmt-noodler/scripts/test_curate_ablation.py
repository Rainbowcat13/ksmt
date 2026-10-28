"""Synthetic recovery-helper tests. All Java/runtime calls are replaced with mocks."""

import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from analyze_benchmark import AnalysisError
from curate_ablation import curate, original_attempt


def properties(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(key + "=" + str(value) for key, value in values.items()) + "\n", encoding="ascii")


class CurationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.suite = Path(temporary.name)
        self.sample = "QF_S/synthetic/interrupted.smt2"
        self.configuration = {
            "selections": {"missed-sat": [self.sample], "all-eligible": [self.sample]},
            "solve-ms": 10000, "startup-parse-ms": 30000, "validation-ms": 10000, "repeats": 1,
            "java": "synthetic-java-must-never-run", "classpath": "synthetic-frozen-runtime",
            "source": "synthetic-corpus", "repo": str(self.suite),
        }
        (self.suite / "suite.json").write_text(json.dumps(self.configuration), encoding="utf-8")
        self.run = self.suite / "runs" / "missed-sat" / "baseline" / "original-run"
        properties(self.run / "metadata.properties", {"schema": 1})
        self.record = self.run / "records" / "original-key.properties"
        self.row = {"sample": self.sample, "key": "original-key", "wall_ns": "60000000001"}
        properties(self.record, self.row)
        self.original_bytes = self.record.read_bytes()
        self.args = argparse.Namespace(suite=self.suite, stage="missed-sat", variant="baseline",
                                       sample=self.sample, reason="Synthetic host interruption")

    def attempt(self, sample=None):
        return original_attempt(self.suite, self.configuration, "missed-sat", "baseline", sample or self.sample)

    def worker(self, command, **kwargs):
        output = Path(command[command.index("--output") + 1])
        run = output / "replacement-run"
        properties(run / "metadata.properties", {"schema": 1})
        properties(run / "records" / "replacement-key.properties",
                   {"sample": self.sample, "key": "replacement-key", "wall_ns": "1000000"})
        return subprocess.CompletedProcess(command, 0)

    def invoke(self, worker=None, audit=None):
        def start(command, **kwargs):
            result = (worker or self.worker)(command, **kwargs)
            return MagicMock(wait=MagicMock(return_value=result.returncode))

        with patch("curate_ablation.verify_suite") as verify, \
                patch("curate_ablation.subprocess.Popen", side_effect=start) as launch, \
                patch("curate_ablation.subprocess.run", side_effect=AssertionError("No real runtime launch allowed")), \
                patch("curate_ablation.analyze", side_effect=audit) as analyzer:
            if audit is None:
                analyzer.side_effect = [
                    {"faults": 0, "disagreements": [], "wall_outliers": 1, "curated_rows": 0},
                    {"faults": 0, "disagreements": [], "wall_outliers": 0, "curated_rows": 1},
                ]
            curate(self.args)
            return verify, launch, analyzer

    def test_original_requires_source_in_requested_stage(self):
        with self.assertRaisesRegex(AnalysisError, "Source is not"):
            self.attempt("QF_S/synthetic/not-selected.smt2")

    def test_original_requires_exactly_one_run(self):
        metadata = self.run / "metadata.properties"
        saved = metadata.read_bytes()
        metadata.unlink()
        with self.assertRaisesRegex(AnalysisError, "exactly one original variant run"):
            self.attempt()
        metadata.write_bytes(saved)
        properties(self.run.parent / "second-run" / "metadata.properties", {"schema": 1})
        with self.assertRaisesRegex(AnalysisError, "exactly one original variant run"):
            self.attempt()

    def test_original_requires_exactly_one_matching_attempt(self):
        self.record.unlink()
        with self.assertRaisesRegex(AnalysisError, "exactly one original attempt"):
            self.attempt()
        properties(self.record, self.row)
        properties(self.record.with_name("duplicate.properties"), self.row)
        with self.assertRaisesRegex(AnalysisError, "exactly one original attempt"):
            self.attempt()

    def test_original_wall_guard_is_strict_and_numeric(self):
        for value in ("", "-1", "NaN", "59999999999", "60000000000"):
            with self.subTest(value=value):
                properties(self.record, dict(self.row, wall_ns=value))
                with self.assertRaisesRegex(AnalysisError, "objectively flagged"):
                    self.attempt()
        properties(self.record, self.row)
        record, row = self.attempt()
        self.assertEqual(self.record, record)
        self.assertEqual(self.row, row)
        self.assertEqual(self.original_bytes, self.record.read_bytes())

    def test_reason_and_duplicate_registry_prevent_launch(self):
        self.args.reason = "  "
        with patch("curate_ablation.subprocess.Popen") as launch:
            with self.assertRaisesRegex(AnalysisError, "explicit replacement reason"):
                curate(self.args)
            launch.assert_not_called()
        self.args.reason = "Synthetic reason"
        registry = {"schema": 1, "replacements": [
            {"stage": "missed-sat", "variant": "baseline", "sample": self.sample},
        ]}
        path = self.suite / "curations.json"
        original = json.dumps(registry)
        path.write_text(original, encoding="utf-8")
        with patch("curate_ablation.subprocess.Popen") as launch:
            with self.assertRaisesRegex(AnalysisError, "already has"):
                curate(self.args)
            launch.assert_not_called()
        self.assertEqual(original, path.read_text(encoding="utf-8"))

    def test_launch_uses_frozen_settings_and_singleton_source(self):
        verify, launch, analyzer = self.invoke()
        verify.assert_called_once_with(self.suite, self.configuration)
        command = launch.call_args.args[0]
        self.assertEqual(self.configuration["java"], command[0])
        self.assertEqual(self.configuration["classpath"], command[command.index("-cp") + 1])
        for key in ("solve-ms", "startup-parse-ms", "validation-ms", "repeats", "source"):
            self.assertEqual(str(self.configuration[key]), command[command.index("--" + key) + 1])
        self.assertEqual("baseline", command[command.index("--variant") + 1])
        self.assertEqual("noodler-yices", command[command.index("--solvers") + 1])
        selection = Path(command[command.index("--source-list") + 1])
        self.assertEqual(self.sample + "\n", selection.read_text(encoding="utf-8"))
        self.assertEqual(self.original_bytes, self.record.read_bytes())
        registry = json.loads((self.suite / "curations.json").read_text(encoding="utf-8"))
        self.assertEqual(1, len(registry["replacements"]))
        self.assertEqual("original-key", registry["replacements"][0]["original_key"])
        self.assertEqual(2, analyzer.call_count)
        analyzer.assert_called_with(self.suite)

    def test_replacement_source_change_is_rejected_without_registry(self):
        def changed_source(command, **kwargs):
            result = self.worker(command, **kwargs)
            output = Path(command[command.index("--output") + 1])
            properties(output / "replacement-run" / "records" / "replacement-key.properties",
                       {"sample": "QF_S/synthetic/other.smt2", "key": "replacement-key"})
            return result

        with self.assertRaisesRegex(AnalysisError, "Supplemental source changed"):
            self.invoke(worker=changed_source)
        self.assertFalse((self.suite / "curations.json").exists())
        self.assertEqual(self.original_bytes, self.record.read_bytes())

    def test_replacement_requires_exactly_one_run_even_with_only_one_record(self):
        def extra_empty_run(command, **kwargs):
            result = self.worker(command, **kwargs)
            output = Path(command[command.index("--output") + 1])
            properties(output / "extra-run" / "metadata.properties", {"schema": 1})
            return result

        with self.assertRaisesRegex(AnalysisError, "exactly one.*run"):
            self.invoke(worker=extra_empty_run)
        self.assertFalse((self.suite / "curations.json").exists())

    def test_failed_audit_restores_registry_and_keeps_raw_proposal(self):
        path = self.suite / "curations.json"
        previous = '{"schema": 1, "replacements": []}\n'
        path.write_text(previous, encoding="utf-8")
        calls = []

        def audit(suite):
            calls.append(path.read_text(encoding="utf-8"))
            return {"faults": int(len(calls) == 2), "disagreements": [], "wall_outliers": 1, "curated_rows": 0}

        with self.assertRaisesRegex(AnalysisError, "did not clear exactly its timing flag"):
            self.invoke(audit=audit)
        self.assertEqual(previous, path.read_text(encoding="utf-8"))
        self.assertEqual(3, len(calls))
        self.assertEqual(previous, calls[-1])
        self.assertEqual(self.original_bytes, self.record.read_bytes())
        self.assertEqual(1, len(list((self.suite / "supplemental").glob("*/proposed-curations.json"))))
        self.assertEqual(1, len(list((self.suite / "supplemental").glob("*/runs/*/records/*.properties"))))


if __name__ == "__main__":
    unittest.main()
