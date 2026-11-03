# Mutation results (P2)

From P2, `gtaw mutate --changed` runs PIT on changed classes (unit and slice tests only) and keeps mutants on the changed lines.

- Survived mutant on a line a scenario references → the oracle is too weak: propose the missing assertion as a gap.
- Survived mutant on code no scenario covers → a missing scenario: `spec-gap`.
- Equivalent mutants (no observable difference) are ignored; explain why in one sentence.
- Test strength = killed / covered; advisory until the repository's threshold is calibrated.
