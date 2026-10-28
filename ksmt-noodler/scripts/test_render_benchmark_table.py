"""Synthetic-only renderer tests; never read or launch repository solver workloads."""

import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from analyze_benchmark import AnalysisError, sha
from render_benchmark_table import (
    LABELS, SETTINGS, OutputRecoveryError, load_benchmark, publish_outputs, render, summary_rows, table_contents,
)


def properties(path, values):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    path.write_text("\n".join(key + "=" + escape(value) for key, value in values.items()) + "\n", encoding="ascii")


def fixture(root, solvers=tuple(LABELS), repeats=1):
    run = root / "synthetic-run"
    run.mkdir()
    (run / "records").mkdir()
    (run / "logs").mkdir()
    manifest, records = [], []
    configuration = sha(b"synthetic configuration")
    for name, expected in (("QF_S/synthetic/sat.smt2", "SAT"), ("QF_S/synthetic/unsat.smt2", "UNSAT"),
                           ("QF_S/synthetic/excluded.smt2", "UNKNOWN")):
        checksum = sha(name.encode())
        entry = dict(sample=name, sample_id=sha((name + "\n" + checksum).encode()), sha256=checksum,
                     family="QF_S/synthetic", logic="QF_S", expected_status=expected, bytes="1")
        manifest.append(entry)
        excluded = "excluded" in name
        for trial in range(1, repeats + 1):
            for solver in solvers:
                key = sha((entry["sample_id"] + "\n" + solver + "\n" + str(trial) + "\n" + configuration).encode())
                outcome = "EXCLUDED" if excluded else expected
                row = {field: entry[field] for field in ("sample", "sample_id", "family", "logic", "expected_status")}
                row.update(key=key, source_sha256=checksum, solver=solver, trial=str(trial),
                           eligible="false" if excluded else "true", outcome=outcome,
                           raw_status="NOT_RUN" if excluded else outcome,
                           validation_status="SAT" if outcome == "SAT" else "NOT_APPLICABLE",
                           validator=("cvc5" if solver in ("z3", "noodler-z3") else "z3") if outcome == "SAT" else "",
                           completed_utc="2026-01-01T00:00:00Z", log="logs/" + key + ".log",
                           witness_bindings="1" if outcome == "SAT" else "",
                           witness_sha256=sha(b"witness") if outcome == "SAT" else "",
                           solve_ns="1000000" if not excluded else "", wall_ns="20000000",
                           validation_ns="1000000" if outcome == "SAT" else "",
                           details="unsupported expression" if excluded else "")
                properties(run / "records" / (key + ".properties"), row)
                (run / row["log"]).write_text("synthetic worker trace", encoding="utf-8")
                records.append(row)
                if excluded:
                    break
            if excluded:
                break
    with (run / "manifest.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(manifest[0]))
        writer.writeheader()
        writer.writerows(manifest)
    metadata = {"schema": "1", "configuration_sha256": configuration,
                "manifest_sha256": sha((run / "manifest.csv").read_bytes()), "solvers": ",".join(solvers),
                "repeats": str(repeats), "solve-ms": "10000", "startup-parse-ms": "30000", "validation-ms": "10000"}
    properties(run / "metadata.properties", metadata)
    write_raw(run, records)
    return run, records, metadata


def write_raw(run, records):
    rows = []
    for record in records:
        row = {key: value for key, value in record.items() if not key.endswith("_ns")}
        for field in ("startup", "parse", "setup", "solve", "validation", "wall"):
            nanos = record.get(field + "_ns", "")
            row[field + "_ms"] = f"{int(nanos) / 1_000_000:.3f}" if nanos else ""
        rows.append(row)
    columns = sorted(set().union(*(row.keys() for row in rows)))
    with (run / "raw.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


class RendererTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.run, self.records, self.metadata = fixture(self.root)

    def update(self, record, **changes):
        record.update(changes)
        properties(self.run / "records" / (record["key"] + ".properties"), record)
        write_raw(self.run, self.records)

    def test_complete_groups_and_outcomes(self):
        data = load_benchmark(self.run)
        self.assertTrue(data.complete)
        self.assertEqual((10, 10, 0), (data.completed, data.expected, data.missing))
        self.assertEqual(1, len(data.excluded))
        for row in summary_rows(data):
            self.assertEqual((1, 1, 2, 2), (row["sat"], row["unsat"], row["solved"], row["eligible_trial_slots"]))
        self.assertNotIn("Other", table_contents(data, summary_rows(data))[0])

    def test_non_sat_checkpoints_may_omit_inapplicable_validator(self):
        # Kotlin checkpoints omit absent optional fields; their CSV columns remain empty.
        for row in self.records:
            if row["outcome"] != "SAT":
                row.pop("validator")
                properties(self.run / "records" / (row["key"] + ".properties"), row)
        write_raw(self.run, self.records)
        data = load_benchmark(self.run)
        self.assertTrue(data.complete)
        self.assertEqual(10, data.completed)

    def test_sat_checkpoint_still_requires_independent_validator(self):
        self.records[0].pop("validator")
        properties(self.run / "records" / (self.records[0]["key"] + ".properties"), self.records[0])
        write_raw(self.run, self.records)
        for preview in (False, True):
            with self.assertRaisesRegex(AnalysisError, "independent witness validation"):
                load_benchmark(self.run, allow_partial=preview)

    def test_unknown_requires_syntax_classification(self):
        self.update(self.records[0], eligible="", outcome="UNKNOWN", raw_status="UNKNOWN",
                    validation_status="NOT_APPLICABLE")
        with self.assertRaisesRegex(AnalysisError, "syntax classification"):
            load_benchmark(self.run)

    def test_incomplete_run_requires_explicit_preview(self):
        removed = self.records.pop(1)
        (self.run / "records" / (removed["key"] + ".properties")).unlink()
        write_raw(self.run, self.records)
        with self.assertRaisesRegex(AnalysisError, "Final table refused"):
            load_benchmark(self.run)
        data = load_benchmark(self.run, allow_partial=True)
        self.assertFalse(data.complete)
        self.assertEqual(1, data.missing)

    def test_unvalidated_sat_is_never_counted_even_in_preview(self):
        self.update(self.records[0], validation_status="UNKNOWN")
        for preview in (False, True):
            with self.assertRaisesRegex(AnalysisError, "independent witness validation"):
                load_benchmark(self.run, allow_partial=preview)

    def test_contradiction_and_failure_are_flagged_not_final(self):
        self.update(self.records[1], outcome="UNSAT", raw_status="UNSAT", validation_status="NOT_APPLICABLE")
        with self.assertRaisesRegex(AnalysisError, "contradictory decisive"):
            load_benchmark(self.run)
        self.assertFalse(load_benchmark(self.run, allow_partial=True).complete)

    def test_crash_is_other_and_blocks_final(self):
        self.update(self.records[1], outcome="CRASH", raw_status="NOT_RETURNED", validation_status="NOT_APPLICABLE")
        with self.assertRaisesRegex(AnalysisError, "worker"):
            load_benchmark(self.run)
        data = load_benchmark(self.run, allow_partial=True)
        self.assertIn("Other", table_contents(data, summary_rows(data))[0])
        self.assertEqual(1, sum(row["other"] for row in summary_rows(data)))

    def test_wall_time_outlier_blocks_final(self):
        self.update(self.records[0], wall_ns="70000000000")
        with self.assertRaisesRegex(AnalysisError, "wall-time outlier"):
            load_benchmark(self.run)

    def test_manifest_hash_and_raw_checkpoint_consistency(self):
        path = self.run / "manifest.csv"
        original = path.read_bytes()
        path.write_bytes(original + b"\n")
        with self.assertRaisesRegex(AnalysisError, "Manifest checksum"):
            load_benchmark(self.run)
        path.write_bytes(original)
        raw = self.run / "raw.csv"
        raw.write_text(raw.read_text(encoding="utf-8").replace("20.000", "21.000"), encoding="utf-8")
        with self.assertRaisesRegex(AnalysisError, "timing mismatch"):
            load_benchmark(self.run, allow_partial=True)

    def test_raw_csv_only_complete_run(self):
        # A portable closed run may omit checkpoint copies while retaining consistent raw/log/manifest files.
        for path in (self.run / "records").iterdir():
            path.unlink()
        (self.run / "records").rmdir()
        data = load_benchmark(self.run)
        self.assertTrue(data.complete)
        self.assertEqual("raw.csv", data.input_source)

    def test_one_solver_multiple_trials_uses_attempt_denominator(self):
        location = self.root / "single"
        location.mkdir()
        run, _, _ = fixture(location, solvers=("noodler-yices",), repeats=2)
        data = load_benchmark(run)
        summary = summary_rows(data)
        self.assertEqual((4, 4), (summary[0]["solved"], summary[0]["eligible_trial_slots"]))
        self.assertIn("Solved / eligible trials", table_contents(data, summary)[0])

    def test_variant_settings_fingerprint(self):
        settings = dict(zip(SETTINGS, ("100000", "1000000", "1000000", "1000000", "100000", "2", "true", "true")))
        settings_hash = sha("\n".join(key + "=" + settings[key] for key in sorted(settings)).encode())
        self.metadata.update(settings)
        self.metadata.update(variant="combined", **{"hybrid-configuration-sha256": settings_hash})
        for row in self.records:
            row.update(variant="combined", hybrid_configuration_sha256=settings_hash)
            row.update({key.replace("-", "_"): value for key, value in settings.items()})
            properties(self.run / "records" / (row["key"] + ".properties"), row)
        write_raw(self.run, self.records)
        properties(self.run / "metadata.properties", self.metadata)
        data = load_benchmark(self.run)
        self.assertIn("combined", table_contents(data, summary_rows(data))[1][-1][0])
        self.metadata["hybrid-configuration-sha256"] = "0" * 64
        properties(self.run / "metadata.properties", self.metadata)
        with self.assertRaisesRegex(AnalysisError, "settings fingerprint"):
            load_benchmark(self.run)

    def test_source_selection_hash_and_membership(self):
        source_list = "\n".join(sorted({row["sample"] for row in self.records})) + "\n"
        (self.run / "source-list.txt").write_bytes(source_list.encode())
        self.metadata.update(**{"source-list": "synthetic-selection.txt", "source-list-sha256": sha(source_list.encode()),
                                "source-list-count": "3"})
        properties(self.run / "metadata.properties", self.metadata)
        self.assertTrue(load_benchmark(self.run).complete)
        (self.run / "source-list.txt").write_text(source_list + "extra.smt2\n", encoding="utf-8")
        with self.assertRaisesRegex(AnalysisError, "Source-list"):
            load_benchmark(self.run)

    def test_adaptive_projection_is_included_in_settings_audit(self):
        settings = dict(zip(SETTINGS, ("100000", "1000000", "1000000", "1000000", "100000", "2", "false", "false")))
        settings["adaptive-length-projection"] = "true"
        fingerprint = sha("\n".join(key + "=" + settings[key] for key in sorted(settings)).encode())
        self.metadata.update(settings)
        self.metadata.update(variant="baseline", **{"hybrid-configuration-sha256": fingerprint})
        properties(self.run / "metadata.properties", self.metadata)
        for row in self.records:
            row.update(variant="baseline", hybrid_configuration_sha256=fingerprint)
            row.update({key.replace("-", "_"): value for key, value in settings.items()})
            properties(self.run / "records" / (row["key"] + ".properties"), row)
        write_raw(self.run, self.records)
        self.assertTrue(load_benchmark(self.run).complete)
        self.update(self.records[0], adaptive_length_projection="false")
        with self.assertRaisesRegex(AnalysisError, "setting mismatch: adaptive-length-projection"):
            load_benchmark(self.run)

    def test_publish_rolls_back_existing_outputs_on_replace_failure(self):
        stage = self.root / "stage"
        stage.mkdir()
        targets = {suffix: self.root / ("old." + suffix) for suffix in ("png", "svg", "csv", "md")}
        for suffix, target in targets.items():
            target.write_text("original " + suffix)
            (stage / ("table." + suffix)).write_text("new " + suffix)
        original_rename = Path.rename

        def rename(path, target):
            if path == stage / "table.csv":
                raise OSError("synthetic replacement failure")
            return original_rename(path, target)

        with patch.object(Path, "rename", rename):
            with self.assertRaisesRegex(OSError, "synthetic"):
                publish_outputs(stage, targets, overwrite=True)
        for suffix, target in targets.items():
            self.assertEqual("original " + suffix, target.read_text())

    def test_render_preserves_backups_when_rollback_unlink_or_rename_fails(self):
        original_rename, original_unlink = Path.rename, Path.unlink

        def synthetic_images(data, summaries, png, svg, selection_note):
            png.write_text("new png")
            svg.write_text("new svg")

        for failure in ("unlink", "rename"):
            with self.subTest(failure=failure):
                directory = self.root / ("recovery-" + failure)
                directory.mkdir()
                prefix = directory / "table"
                targets = {suffix: directory / ("table." + suffix) for suffix in ("png", "svg", "csv", "md")}
                for suffix, target in targets.items():
                    target.write_text("original " + suffix)

                def rename(path, target):
                    if path.name == "table.csv" and path.parent != directory:
                        raise OSError("synthetic publication failure")
                    if failure == "rename" and path.name == "previous.png":
                        raise OSError("synthetic recovery rename failure")
                    return original_rename(path, target)

                def unlink(path, *args, **kwargs):
                    if failure == "unlink" and path == targets["png"]:
                        raise OSError("synthetic recovery unlink failure")
                    return original_unlink(path, *args, **kwargs)

                with patch("render_benchmark_table.render_images", synthetic_images), \
                        patch.object(Path, "rename", rename), patch.object(Path, "unlink", unlink):
                    with self.assertRaises(OutputRecoveryError) as raised:
                        render(self.run, prefix, overwrite=True)
                recovery = raised.exception.stage
                self.assertIn(str(recovery), str(raised.exception))
                self.assertTrue(recovery.is_dir())
                self.assertEqual("original png", (recovery / "previous.png").read_text())
                for suffix in ("svg", "csv", "md"):
                    self.assertEqual("original " + suffix, targets[suffix].read_text())

    def test_interruption_between_backup_rename_and_bookkeeping_preserves_original(self):
        directory = self.root / "interruption"
        directory.mkdir()
        prefix = directory / "table"
        original = directory / "table.png"
        original.write_text("original png")
        original_rename = Path.rename

        def rename(path, target):
            result = original_rename(path, target)
            if path == original:
                raise KeyboardInterrupt("synthetic interruption after moving original")
            return result

        def synthetic_images(data, summaries, png, svg, selection_note):
            png.write_text("new png")
            svg.write_text("new svg")

        with patch("render_benchmark_table.render_images", synthetic_images), patch.object(Path, "rename", rename):
            with self.assertRaises(OutputRecoveryError) as raised:
                render(self.run, prefix, overwrite=True)
        self.assertIsInstance(raised.exception.__cause__, KeyboardInterrupt)
        recovery = raised.exception.stage
        self.assertIn(str(recovery), str(raised.exception))
        self.assertEqual("original png", (recovery / "previous.png").read_text())

    def test_partial_image_and_markdown_cannot_look_complete(self):
        removed = self.records.pop(1)
        (self.run / "records" / (removed["key"] + ".properties")).unlink()
        write_raw(self.run, self.records)
        data, outputs = render(self.run, self.root / "partial" / "table", allow_partial=True)
        self.assertFalse(data.complete)
        self.assertIn("PARTIAL / FLAGGED PREVIEW", outputs["md"].read_text(encoding="utf-8"))
        with outputs["csv"].open(encoding="utf-8", newline="") as stream:
            self.assertEqual({"False"}, {row["complete"] for row in csv.DictReader(stream)})

    def test_export_white_png_svg_csv_and_refuse_overwrite(self):
        from PIL import Image
        prefix = self.root / "export" / "table"
        before = sha((self.run / "raw.csv").read_bytes())
        data, outputs = render(self.run, prefix, selection_note="Synthetic fixture; not benchmark measurements.")
        self.assertTrue(data.complete)
        self.assertTrue(all(path.is_file() for path in outputs.values()))
        with Image.open(outputs["png"]) as image:
            self.assertGreaterEqual(image.width, 3000)
            self.assertEqual((255, 255, 255), image.convert("RGB").getpixel((0, 0)))
        self.assertIn("<svg", outputs["svg"].read_text(encoding="utf-8"))
        self.assertIn("**COMPLETE**", outputs["md"].read_text(encoding="utf-8"))
        with outputs["csv"].open(encoding="utf-8", newline="") as stream:
            self.assertEqual(5, len(list(csv.DictReader(stream))))
        self.assertEqual(before, sha((self.run / "raw.csv").read_bytes()))
        with self.assertRaisesRegex(AnalysisError, "already exists"):
            render(self.run, prefix)
        original_outputs = {suffix: path.read_bytes() for suffix, path in outputs.items()}
        with patch("render_benchmark_table.render_images", side_effect=OSError("synthetic staging failure")):
            with self.assertRaisesRegex(OSError, "staging failure"):
                render(self.run, prefix, overwrite=True)
        self.assertEqual(original_outputs, {suffix: path.read_bytes() for suffix, path in outputs.items()})
        render(self.run, prefix, overwrite=True, selection_note="Regenerated synthetic fixture.")
        self.assertIn("Regenerated synthetic fixture.", outputs["md"].read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
