"""Synthetic selection regressions; no benchmark files, JVMs, or solvers are used."""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from analyze_ablation import VARIANTS, variant_settings
from analyze_benchmark import AnalysisError
from run_best_comparison import ALL_SOLVERS, clean_audit, rank_variants, verify_inventory, verify_repeat


def fixture():
    return [
        {"ablation_stage": "all-eligible", "ablation_variant": variant,
         "sample": sample, "outcome": "UNKNOWN", "validation_status": "NOT_APPLICABLE"}
        for variant in VARIANTS for sample in ("family/a.smt2", "family/b.smt2")
    ]


def outcomes(rows, variant, values):
    group = [row for row in rows if row["ablation_variant"] == variant]
    for row, outcome in zip(group, values):
        row["outcome"] = outcome
        row["validation_status"] = "SAT" if outcome == "SAT" else "NOT_APPLICABLE"


class BestComparisonSelectionTest(unittest.TestCase):
    def test_solved_count_precedes_sat_count(self):
        rows = fixture()
        outcomes(rows, "baseline", ["UNSAT", "UNSAT"])
        outcomes(rows, "combined", ["SAT", "UNKNOWN"])
        self.assertEqual("baseline", rank_variants(rows)[0]["variant"])

    def test_validated_sat_breaks_equal_solved_tie(self):
        rows = fixture()
        outcomes(rows, "baseline", ["UNSAT", "UNSAT"])
        outcomes(rows, "optional", ["SAT", "UNSAT"])
        self.assertEqual("optional", rank_variants(rows)[0]["variant"])

    def test_lower_cap_cost_breaks_equal_outcome_tie(self):
        rows = fixture()
        outcomes(rows, "states-4x", ["SAT", "SAT"])
        outcomes(rows, "states-2x", ["SAT", "SAT"])
        self.assertEqual("states-2x", rank_variants(rows)[0]["variant"])

    def test_cap_cost_precedes_policy_count(self):
        rows = fixture()
        outcomes(rows, "states-2x", ["SAT", "SAT"])
        outcomes(rows, "combined", ["SAT", "SAT"])
        self.assertEqual("combined", rank_variants(rows)[0]["variant"])

    def test_fewer_policies_precede_declared_order(self):
        rows = fixture()
        outcomes(rows, "optional", ["SAT", "SAT"])
        outcomes(rows, "combined", ["SAT", "SAT"])
        self.assertEqual("optional", rank_variants(rows)[0]["variant"])
        outcomes(rows, "baseline", ["SAT", "SAT"])
        self.assertEqual("baseline", rank_variants(rows)[0]["variant"])

    def test_declared_order_breaks_final_tie_without_using_timings(self):
        rows = fixture()
        outcomes(rows, "states-2x", ["SAT", "SAT"])
        outcomes(rows, "storage-2x", ["SAT", "SAT"])
        for row in rows:
            row["solve_ms"] = "9000" if row["ablation_variant"] == "states-2x" else "1"
        self.assertEqual("states-2x", rank_variants(rows)[0]["variant"])

    def test_diagnostic_results_do_not_select_winner(self):
        rows = fixture()
        outcomes(rows, "baseline", ["SAT", "UNKNOWN"])
        rows.extend({"ablation_stage": "missed-sat", "ablation_variant": "combined",
                     "sample": f"diagnostic/{index}.smt2", "outcome": "SAT",
                     "validation_status": "SAT"} for index in range(20))
        ranking = rank_variants(rows)
        self.assertEqual("baseline", ranking[0]["variant"])
        self.assertTrue(all(item["cases"] == 2 for item in ranking))

    def test_missing_variant_and_missing_case_are_rejected(self):
        rows = fixture()
        with self.assertRaises(AnalysisError):
            rank_variants([row for row in rows if row["ablation_variant"] != "combined"])
        with self.assertRaises(AnalysisError):
            rank_variants(rows[:-1])

    def test_duplicate_and_substituted_case_are_rejected(self):
        rows = fixture()
        rows[-1]["sample"] = rows[-2]["sample"]
        with self.assertRaises(AnalysisError):
            rank_variants(rows)
        rows[-1]["sample"] = "different-family/b.smt2"
        with self.assertRaises(AnalysisError):
            rank_variants(rows)

    def test_unvalidated_sat_is_rejected(self):
        rows = fixture()
        rows[0]["outcome"] = "SAT"
        with self.assertRaises(AnalysisError):
            rank_variants(rows)

    def test_clean_audit_rejects_each_flag(self):
        clean = {"disagreements": [], "faults": 0, "wall_outliers": 0}
        clean_audit(clean)
        for key, value in (("disagreements", ["family/a.smt2"]), ("faults", 1), ("wall_outliers", 1)):
            with self.subTest(flag=key), self.assertRaises(AnalysisError):
                clean_audit(dict(clean, **{key: value}))


class BestComparisonProvenanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="best-comparison-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.suite = root / "ablation"
        self.destination = root / "comparison"
        self.source = root / "sources"
        self.run = self.destination / "runs" / "run"
        for directory in (self.suite, self.source / "family", self.run / "records"):
            directory.mkdir(parents=True)
        self.inventory = [
            {"sample": "family/a.smt2", "sha256": "a" * 64},
            {"sample": "family/b.smt2", "sha256": "b" * 64},
        ]
        for row in self.inventory:
            (self.source / row["sample"]).write_text("(check-sat)\n", encoding="utf-8")
        self.configuration = {
            "source": str(self.source), "classpath": "frozen-test-runtime",
            "solve-ms": 10000, "startup-parse-ms": 30000, "validation-ms": 10000, "repeats": 1,
        }
        self.runtime = {"runtime_sha256": "f" * 64, "java_version": "test-jvm"}
        (self.suite / "status.json").write_text(json.dumps({"runtime": self.runtime}), encoding="utf-8")
        self.write_csv(self.suite / "reference-manifest.csv", self.inventory)
        self.write_csv(self.run / "manifest.csv", self.inventory)
        self.write_csv(self.destination / "ablation-comparison.csv", [
            {"sample": "family/a.smt2", "outcome": "SAT"},
            {"sample": "family/b.smt2", "outcome": "UNKNOWN"},
        ])
        self.metadata = {
            "variant": "baseline", "solvers": ALL_SOLVERS, "source-list": "", "prefix": "",
            "offset": "0", "limit": str(2**31 - 1), **self.configuration,
            **variant_settings("baseline"), **self.runtime,
        }
        self.write_properties(self.run / "metadata.properties", self.metadata)

    @staticmethod
    def write_csv(path, rows):
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def write_properties(path, values):
        def escape(value):
            return str(value).replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=")
        path.write_text("".join(f"{escape(key)}={escape(value)}\n" for key, value in values.items()),
                        encoding="utf-8")

    def verify(self, finished=False):
        verify_repeat(self.run, self.suite, self.configuration, "baseline", ALL_SOLVERS, finished=finished)

    def test_matching_repeat_provenance_is_accepted(self):
        self.verify()
        self.write_properties(self.run / "records" / "a.properties",
                              {"sample": "family/a.smt2", "outcome": "SAT"})
        self.verify(finished=True)

    def test_resume_rejects_each_wrong_effective_setting(self):
        mismatches = {
            "variant": "combined", "solvers": "noodler-yices", "source-list": "subset.txt",
            "max-automata-storage": "2000000", "solve-ms": "20000", "classpath": "other-runtime",
            "optional-preprocessing": "true", "runtime_sha256": "0" * 64,
        }
        for key, value in mismatches.items():
            with self.subTest(setting=key):
                self.write_properties(self.run / "metadata.properties", dict(self.metadata, **{key: value}))
                with self.assertRaises(AnalysisError):
                    self.verify()

    def test_repeat_rejects_missing_or_changed_manifest_sources(self):
        manifests = [self.inventory[:1],
                     [dict(self.inventory[0], sha256="c" * 64), self.inventory[1]],
                     [self.inventory[0], dict(self.inventory[1], sample="other/b.smt2")]]
        for manifest in manifests:
            with self.subTest(manifest=manifest):
                self.write_csv(self.run / "manifest.csv", manifest)
                with self.assertRaises(AnalysisError):
                    self.verify()

    def test_finished_repeat_rejects_cross_ablation_decisive_conflict(self):
        self.write_properties(self.run / "records" / "a.properties",
                              {"sample": "family/a.smt2", "outcome": "UNSAT"})
        with self.assertRaises(AnalysisError):
            self.verify(finished=True)

    def test_inventory_rejects_added_sources(self):
        verify_inventory(self.suite, self.configuration)
        (self.source / "notes.txt").write_text("not a benchmark", encoding="utf-8")
        verify_inventory(self.suite, self.configuration)
        (self.source / "extra.smt2").write_text("(check-sat)", encoding="utf-8")
        with self.assertRaises(AnalysisError):
            verify_inventory(self.suite, self.configuration)

    def test_inventory_rejects_removed_sources(self):
        (self.source / "family" / "b.smt2").unlink()
        with self.assertRaises(AnalysisError):
            verify_inventory(self.suite, self.configuration)


if __name__ == "__main__":
    unittest.main()
