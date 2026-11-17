# Finite-domain bit-vector realization

`KNoodlerHybridSolver.configure { encodingTarget = NoodlerEncodingTarget.BV }` changes generated
character and DFA-state terms in exact fixed-length realization. The default `INT` target uses
mathematical integer terms. Backend selection is independent of this setting.

Characters use 18-bit unsigned values and explicit bounds `0 <= character <= 0x2FFFF`, preserving
the complete SMT-LIB character domain, including NUL and supplementary characters. The upper
quarter of the 18-bit domain is excluded. DFA identifiers use `max(1, ceil(log2(stateCount)))` bits.
Every state step is an exact disjunction of transitions from valid source IDs to valid target IDs;
unused bit patterns cannot be chosen as states. No generated arithmetic wraps. Both the full
transition encoding and the optional pruned encoding retain negative membership and repeated
variable sharing. Model reconstruction decodes unsigned values exactly and rejects values outside
the original domain before original-assertion validation.

The integer Boolean/length search, original mathematical integer expressions, and intermediate
arithmetic expressions remain integers. A character-position resource limit is an operational limit,
not an arithmetic bound or a semantic bound on strings. Exhaustion returns `UNKNOWN`. Failure at
one candidate length only excludes that exact Boolean/length candidate; bounded search failure
cannot establish global `UNSAT`. Unbounded arithmetic has no generally justified fixed width, so
this implementation deliberately retains the existing integer path for lengths and arithmetic.

Original formulas containing native bit-vectors are a separate capability. Selecting `BV` here
neither changes their widths nor turns original integers into bit-vectors.
