# Negative controls

Independently written solutions with known provenance, one folder per control:
`<Hw>/<Hw>-Nxx/`. Each control is a **negative** against every student submission and every
synthetic copy (none of them could have been copied from it). Without these, false-positive
rates cannot be measured, because historical submissions have no verified provenance and are
treated as *unresolved*.

- 4 per homework (Hw1, Hw2, Hw8), each a deliberately different design; see `provenance.csv`.
- **AI-generated** at the instructor's direction ("generate benchmarks with AI"). The assumption that
  students did not use the same model is recorded as a limitation.
- Written only from the assignment descriptions and the instructor's starter code and testers. Each
  control includes the supplied tester, as a student submission would.
- Verified: HW1 and HW2 reproduce the instructor's expected output exactly; HW8 sorts correctly (independent check).
- **Control-vs-control pairs are excluded from labels**: all controls share one author.
- Split assignment (2 dev / 1 validation / 1 test per homework) is done by `LabelMain`.

No student data is in this folder, so it is committed.
