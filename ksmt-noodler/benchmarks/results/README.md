# Archived Noodler benchmark evidence

These are exact-byte copies of the final measurements made for the Noodler/Yices integration and optimization task. [archive-index.json](archive-index.json) records every copied file's original repository-relative location, byte size, and SHA256. Git line-ending conversion is disabled in this directory so archived source-manifest and configuration checksums stay valid across checkouts.

## Full comparison with default resource caps

Run `20261027T172635Z-0654d06e`, 752 supported cases, five backends, 3,760 completed attempts, one trial each. The assertion/check deadline was 10 seconds; startup/parsing and independent SAT validation had separate 30-second and 10-second deadlines.

- [Comparison table](full-defaults/comparison.png) and [aggregate CSV](full-defaults/comparison.csv).
- [Detailed summary](full-defaults/summary.md), [raw rows](full-defaults/raw.csv), [manifest](full-defaults/manifest.csv), and [metadata](full-defaults/metadata.properties).
- [Source selection audit](full-defaults/source-audit.json) and [frozen-runtime file hashes](full-defaults/runtime-manifest.json).

The current hybrid solved 510/752 cases; standalone Z3 solved 318 and cvc5 304. The Noodler wrappers solved 529 with Z3 and 489 with cvc5. All 754 SAT witnesses passed independent validation; no decisive disagreements or worker failures were recorded.

## Fourfold resource caps for Noodler + Yices

Run `20261027T223146Z-9babb927`, the same 752 cases and frozen runtime, only Noodler/Yices. Five public resource caps were multiplied by four; the 10-second assertion/check deadline, policies, and idle-round limit stayed unchanged.

- [Default-versus-fourfold table](fourfold-caps/comparison.png), [aggregate CSV](fourfold-caps/comparison.csv), and [paired case data](fourfold-caps/paired-cases.csv).
- [Comparison details](fourfold-caps/comparison.json), [effective settings](fourfold-caps/experiment.json), and [standalone aggregate](fourfold-caps/fourfold-result.csv).
- [Detailed summary](fourfold-caps/summary.md), [raw rows](fourfold-caps/raw.csv), [manifest](fourfold-caps/manifest.csv), and [metadata](fourfold-caps/metadata.properties).

Coverage rose from 510 to 512 cases without losing a previous result. Two UNKNOWN results became UNSAT, 114 became TIMEOUT, and 124 remained UNKNOWN. All 149 SAT witnesses passed Z3 validation; no decisive conflict with the five-backend control was found.

## Archive scope and reproduction

Native binaries, JVM outputs, worker logs, immutable per-attempt checkpoint files, generated prepared-corpus copies, and Python caches are deliberately excluded. Absolute paths in original metadata identify the measurement environment; they are provenance, not portable launch paths. The archived raw CSVs preserve the complete measured rows, including witness hashes, phase timings, resource settings, and failure reasons. `archive-index.json` can be checked independently with SHA256.

The strict table exporter also verifies the worker logs. Re-auditing or re-rendering the archived CSVs with that exporter therefore requires the original local logs; the archive does not contain dummy logs and does not weaken the audit. The final audited PNG tables are included directly. The local originals remain under the `build/reports` paths listed in the archive index.

For implementation details and fresh-run commands, see [the module README](../../README.md), [the full comparison](../../docs/combined-comparison.md), and [the resource-cap experiment](../../docs/resource-caps-4x.md). A fresh experiment builds a new runtime snapshot and records its own hashes; it does not overwrite or substitute these measured rows. The original third-party benchmark corpus is external to Git; the source lists and manifests identify the selected files.
