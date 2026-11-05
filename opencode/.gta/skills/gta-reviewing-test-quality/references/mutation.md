# Mutation results (P2)

`gtaw mutate --changed [--base <ref>]` runs PIT on changed classes (unit and slice tests only) and keeps mutants on the changed lines. It reports `mutants`, `covered`, `killed`, `strength` (killed / covered), the `survived` mutants as `Class:line method (Mutator)` and the `notCovered` lines. It is advisory: it never fails the command.

- Survived mutant on a line a scenario references → the oracle is too weak: propose the missing assertion as a gap.
- Survived mutant on code no scenario covers → a missing scenario: `spec-gap`.
- Equivalent mutants (no observable difference) are ignored; explain why in one sentence.
- Test strength = killed / covered; advisory until the repository's threshold is calibrated.
