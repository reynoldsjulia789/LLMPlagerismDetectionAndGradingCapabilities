# AIAA: LLM Plagiarism Screening and Automated Assessment

Java 25 / Maven project. Modules:

| Package | Purpose | Status |
|---|---|---|
| `aiaa.llm` | Shared model adapter interface (`ModelAdapter`), `OpenAiAdapter`, `MockAdapter` | done |
| `aiaa.pdm` | Plagiarism Detection Module (PDM) | core done |
| `aiaa.synth` | Synthetic copy generator (labeled plagiarism test cases) | done (control flow needs an LLM) |
| `aiaa.data` | Shared dataset layout, homework defaults, assignment materials | done |
| `aiaa.labels` | Ground-truth labels and dev/validation/test split | done |
| `aiaa.eval` | Threshold sweep, frozen selection, held-out detection metrics | done |
| `aiaa.refs` | AI reference solutions: generation and automatic verification | done (generation needs an LLM) |
| `aiaa.demo` | One-command demonstration on a toy dataset | done |
| CAM / OCAA | Code assessment and output-based grading | partner's branch |

## Prerequisites

- JDK 25. Maven is **not** needed: `./mvnw` (`mvnw.cmd` on Windows) downloads Maven 3.9.9 on first use.
- For real LLM runs: an OpenAI API key in `OPENAI_API_KEY` (optional `OPENAI_BASE_URL`). Never commit keys.

```bash
./mvnw test                                   # all unit + end-to-end tests (offline, MockAdapter)
./mvnw -q compile exec:java -Dexec.args="--help"                       # PDM (default main class)
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.demo.DemoMain        # the demonstration, see below
```

Every tool has `--help`. Run a tool other than the PDM with `-Dexec.mainClass=aiaa.<package>.<Name>Main`.

## Demonstration (one command)

```bash
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.demo.DemoMain    # add -Dexec.args="--mock-llm" to exercise the LLM path offline
```

This runs the complete detection workflow on `demo/`: copies → labels and split → PDM with and without starter exclusion → threshold selection → one held-out evaluation. It regenerates everything in `demo-output/`. `demo/` is a made-up toy homework (an int `Stack`) with 12 synthetic "submissions" and 4 negative controls. It contains no course material or student data. **Demo results are not research results**, and the full research evaluation runs on the real data as described below.

## Dataset layout (`aiaa.data`)

All modules read the same layout. The real data lives under `HomeworkSubmissions-test data/` (gitignored); generated synthetic datasets use the same layout under `synthetic-data/`, so either can be passed as `--data-root`.

```
<root>/
  HomeworkDescriptions/            hw1_description_startupcode.zip, hw2_description_startupcode.zip, hw8_description.docx
  HomeworkSubmissions/
    Hw1/
      F25Hw1/                      one cohort (term) of one homework
        <submission folder>/       one student's .java files
      Sp26Hw1/
    Hw2/ ...   Hw8/ ...
```

Built-in defaults per homework (`HomeworkSpec`; `--starter` / `--exclude-file` override them):

| Homework | Starter code | Excluded from similarity |
|---|---|---|
| Hw1 | `hw1_description_startupcode.zip` | `MyLinkedListTester.java` |
| Hw2 | `hw2_description_startupcode.zip` | `Tester.java` |
| Hw8 | none | nothing (the HW8 `Tester` is student-written) |

## Plagiarism Detection Module (PDM)

For one homework at a time, the PDM:

1. **Loads and anonymizes.** By default all cohorts of the homework are **pooled**: every pair is compared, including across terms (a Spring student copying a Fall submission), and each pair is tagged `WITHIN` or `CROSS` cohort. `--cohort F25` restricts a run to one cohort. Each submission gets an anonymous ID (`F25Hw1-S01`, `Sp26Hw1-S03`, ...); folders already named that way (generated datasets) keep their names. The mapping from anonymous ID to cohort and folder is written only to `--id-map` (default `private/`, which is gitignored). Files are shown under their declared class name, so a name-prefixed file such as `Name_CDoublyLinkedList.java` appears as `CDoublyLinkedList.java`. E-mail addresses and `@author` / `Name:` / `Student ID:` comment values are redacted. This is best-effort: free-form names in comments are not detected.
2. **Excludes instructor material** (`--starter-mode strip`, the default). Excluded files (supplied testers) are dropped. Lines matching the starter code are removed: matched on their code tokens (so spacing, trailing comments and brace placement don't matter), multiset-matched, and only lines containing letters or digits. Each kept line retains its original line number. `--starter-mode keep` gives the without-exclusion comparison.
3. **Runs the token baseline** (non-LLM) on every pair and every reference. Tokens are normalized (identifiers become `ID`, literals become `STR`/`NUM`, comments and imports are dropped), then 5-gram **Jaccard** (primary) and **containment** (more sensitive to partial copying) are computed, both on a 0–100 scale.
4. **Runs the LLM judge** (`--adapter openai|mock`) on every peer pair `S_ij` and on every submission against each AI reference `S_i_AI`. The model returns JSON with an overall `similarity` plus `structure`/`syntax`/`tokens`/`logic` sub-scores, cited evidence regions (`File.java:start-end`), and a rationale. Prompts that are too large are skipped with status `TOO_LARGE`, never truncated.
5. **Computes student scores** per method: `S_i = α·max_j S_ij + (1−α)·S_i_AI`, where `max_j` runs over all partners (both cohorts), `S_i_AI` is the maximum over the reference bank and α defaults to 0.5. The within-cohort and cross-cohort peer maxima are also reported. The peer and AI components are also reported separately, because thresholds are chosen for each one independently. The combined score is blank when no reference is supplied.

Similarity scores are **screening evidence for instructor review**, not findings of cheating.
Similarity to an AI reference only detects copying from *that known reference*.

### Example commands

```bash
# Token baseline only (free): Hw1, both cohorts pooled, with and without starter exclusion
./mvnw -q compile exec:java -Dexec.args="--homework Hw1"
./mvnw -q compile exec:java -Dexec.args="--homework Hw1 --starter-mode keep"
# One cohort only
./mvnw -q compile exec:java -Dexec.args="--homework Hw1 --cohort F25"

# OpenAI: smoke test on 5 pairs first, then the full run with --resume
export OPENAI_API_KEY=...
... --homework Hw1 --adapter openai --model <exact-model-id> --max-llm-pairs 5 \
    --reference refs/Hw1/gpt --price-in <usd/Mtok> --price-out <usd/Mtok> \
    --pricing-source "<pricing page URL, date>"
```

Explicit folders still work: `--assignment <label> --submissions <dir>` (repeat `--submissions` to pool several cohorts, each labeled by its folder name).

**Cost note:** pooling cohorts roughly doubles the pairs (Hw1: 741 pooled vs 363 within-cohort). A pooled synthetic dataset is much larger (Hw1 with 64 copies per cohort is 151 submissions, 11,325 pairs), so plan LLM runs on it carefully.

### Outputs (`pdm-output/<assignment>/<mode>/`, gitignored)

| File | Contents |
|---|---|
| `comparisons.csv` | One row per comparison × method: IDs, cohorts and `cohort_relation` (`WITHIN`/`CROSS`/`REFERENCE`), similarity, containment, sub-scores, evidence, status, model and prompt version, tokens, latency, retries, cost |
| `submission_scores.csv` | Per submission × method: cohort, max peer score and partner, within- and cross-cohort maxima, AI score and reference, α, combined `S_i`, ok/failed counts |
| `inventory.csv` | Per submission/reference: files included/excluded, redactions, starter lines removed, eligibility and reason |
| `run_manifest.json` | Full configuration, model ID and returned version, prompt version hashes, call/token/retry/cost totals, timestamps |
| `llm_responses.jsonl` | Raw model response for every call (used by `--resume`) |

Prompts live in `src/main/resources/prompts/`. Each prompt version is the template name plus a hash of its text, so any edit to a frozen prompt shows up in the outputs.

## Synthetic copy generator (`aiaa.synth`)

Builds an anonymized, labeled dataset for each cohort of a homework, written in the shared layout under `synthetic-data/` (gitignored, since it contains student code). The starter code is copied to `synthetic-data/HomeworkDescriptions/`, so the result can be screened like the real data. Each cohort folder `synthetic-data/HomeworkSubmissions/<Hw>/<cohort>/` contains:

- `<cohort>-Sxx/`: the originals, identity-scrubbed, with display file names. The IDs match the PDM's anonymous IDs.
- `<cohort>-Vxx/`: synthetic copies. Each one compiles; failed attempts are recorded but not written.
- `manifest.csv`: provenance for every variant, including source, host, transformation, details, target and achieved amount copied, methods copied, seed, compile status, token Jaccard against the source and host, and LLM model and prompt version.
- `generation_log.json`: configuration, seed, originals that don't compile, and counts by type and status.
- and, outside the dataset, `private/synth_id_map_<cohort>.csv`: the mapping from anonymous IDs to original folders.

Copies are made within a cohort (source and partial-copy host come from the same term).

`--sources` (default 8) compiling submissions are picked with `--seed`, and each one yields 8 variants:

| Transformation | How |
|---|---|
| `EXACT` | identical copy |
| `RENAME` | consistent renaming of fields, locals and parameters across files (falls back to locals only if the result doesn't compile); method and type names are never renamed |
| `REFORMAT` | new indent, brace style and operator spacing; the code-token sequence is verified unchanged |
| `COMMENT_EDIT` | deletes, rewords and adds comments; code is untouched |
| `REORDER` | shuffles the order of methods, constructors and nested types; fields stay in place |
| `CONTROL_FLOW` | LLM rewrite (loops, inverted ifs, guard clauses, switch); retried with compiler errors fed back. Skipped without `--adapter openai` |
| `STACKED` | rename + reorder + comment edits + reformat |
| `PARTIAL` | replaces some of another student's (the host's) methods with the source's versions; target 25/50/75%, rotating across sources |

**Amount copied** = fraction of the variant's substantive, non-starter lines (testers excluded) that came from the source. It is 1.0 for whole-program variants. A partial variant is derived from **both** its source (by the amount copied) and its host (the rest). Hosts are chosen from submissions that are neither a source nor already a host, so copy families stay small (at most 9 submissions).

```bash
# Generate for every cohort of Hw1 (or add --cohort F25)
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.synth.SynthMain -Dexec.args="--homework Hw1"
# Screen the generated dataset exactly like the real one
./mvnw -q compile exec:java -Dexec.args="--data-root synthetic-data --homework Hw1"
```

Add `--reference <dir>` to also derive "AI copying" variants from a verified AI reference solution.

## Negative controls (`negative-controls/`, committed)

Four independently written solutions per homework, `negative-controls/<Hw>/<Hw>-Nxx/`, with `provenance.csv`. They are AI-generated at the instructor's direction, written only from the assignment descriptions and starter code, and verified: HW1 and HW2 reproduce the instructor's expected output exactly, and HW8 sorts correctly. A control is a known **negative** against every submission, which makes false-positive rates measurable. The PDM adds them automatically as cohort `Controls` (`--no-controls` to skip). See `negative-controls/README.md` for limitations.

## Labels and splits (`aiaa.labels`)

```bash
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.labels.LabelMain     # defaults: synthetic-data, negative-controls, seed 42
```

Writes `synthetic-data/labels/` (gitignored, with the data):

| File | Contents |
|---|---|
| `<Hw>/pair_labels.csv` | Every peer pair (and every submission × AI reference): `pair_key` (joins with the PDM's `comparisons.csv`), kinds, cohorts, split, `label`, `relation`, transformation, amount copied, label source |
| `<Hw>/submission_labels.csv` | Every submission: kind, label, transformation, source, host, amount copied, family, split |
| `split_manifest.csv` | Submission → family → split |
| `split_log.json` | Seed, target and achieved split sizes per homework and kind, pair-label counts per split, label definitions |

**Pair labels.** `COPY`: one side was derived from the other (relation `SOURCE`, or `HOST` for a partial copy's host), or both from a common submission (`SIBLING`). `NEGATIVE`: exactly one side is a negative control. `EXCLUDED`: both are controls (same author). `UNRESOLVED`: no verified provenance, e.g. original vs original; never treat these as negatives.

**Submission labels.** `COPY` (peer-copy variant), `AI_COPY` (variant of an AI reference), `NEGATIVE` (control), `UNRESOLVED` (original).

**Split** (50/25/25 dev/validation/test, seeded). A *family* never straddles splits. A family is a copy with its source and host, plus a student's submissions to the same homework in both terms. Grouping a student *across* homeworks (`--group-students across-homeworks`) is infeasible on this data: nearly every student did every homework, which chains everything into two families. Controls are split 2/1/1 per homework. Pairs whose sides fall in different splits are `CROSS_SPLIT` and are left out of metrics.

## Thresholds and detection metrics (`aiaa.eval`)

Two phases, which enforce the spec's "freeze thresholds before held-out evaluation":

```bash
# 1. sweep + select on VALIDATION, freeze (pass one --run per PDM output: homework x starter mode x model)
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.eval.EvalMain -Dexec.args="select \
    --run pdm-output/Hw1/strip --run pdm-output/Hw1/keep --run pdm-output/Hw2/strip ..."
# 2. apply the frozen thresholds ONCE to TEST
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.eval.EvalMain -Dexec.args="test --run ... (same runs)"
```

- **Configurations:** a threshold is selected per homework × starter mode × method/model/prompt × comparison type (`PEER`, `AI_REFERENCE`) × level (`PAIR`, `STUDENT`).
- **Sweep:** 0–100 in steps of 5, then steps of 0.5 within ±5 of the best coarse value. A score is flagged when it is ≥ the threshold.
- **Rule:** maximum validation recall subject to validation false-positive rate ≤ 5% (`--max-fpr`). Ties go to the lower FPR, then to the higher threshold. The status is reported when no threshold qualifies (`NO_THRESHOLD_MEETS_FPR`, `NO_USEFUL_THRESHOLD`, `NO_NEGATIVES`, …).
- **Truth:** positives are `COPY` (or `AI_COPY`); negatives are `NEGATIVE` (controls) only. `UNRESOLVED` pairs never count as false positives; their flag rate is reported separately as instructor review workload. Failed LLM comparisons count as missed detections.
- **Student level:** a submission's score is its best partner score over **all** splits (as in deployment), excluding control-vs-control pairs. Its threshold is selected separately with the same rule.
- **Freeze:** `thresholds.json` stores the thresholds and the SHA-256 of every input. `test` refuses if an input changed, refuses a second evaluation unless given `--allow-reevaluation` (logged in `evaluation_log.jsonl`), and `select` is refused once a test evaluation exists.
- **Outputs:** `sweep.csv` (DEV + VALIDATION curves), `selection_summary.csv`, `validation_errors.csv`, `test_results.csv` (confusion counts, precision/recall/F1/FPR/FNR, 95% Wilson intervals, family-cluster bootstrap intervals, cluster counts), `test_breakdown.csv` (recall by transformation, relation, amount copied, cohort relation), `test_errors.csv` (false positives and negatives with starter share, code size and a first-pass hint), `detection_results.csv` (the spec's detection results table), `sweep_test_postfreeze.csv` (threshold sensitivity after the freeze), and `starter_mode_comparison.csv`.
- **Uncertainty caveat:** the independent units for the false-positive rate are the controls. Validation and test have **1 control per homework**, so FPR intervals are wide, and `test_results.csv` reports the cluster counts so this stays visible.

**Do not run `test` on the real data until the dataset and prompts are final.** Adding the LLM control-flow copies changes the labels and splits, and the held-out set may be evaluated only once.

## AI reference solutions (`aiaa.refs`)

```bash
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.refs.RefMain -Dexec.args="--dry-run --price-in <usd/Mtok> --price-out <usd/Mtok>"
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.refs.RefMain -Dexec.args="--homework Hw1 --adapter openai --model <exact-id>"
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.refs.RefMain -Dexec.args="--verify negative-controls/Hw1/Hw1-N01 --homework Hw1"
```

The generator sends the assignment description, starter code and supplied tester to the model, then **verifies** the result:
- It compiles the result together with the instructor's unchanged testers.
- It runs the program in a separate JVM with a timeout. **This executes model-written code on your machine.**
- HW1 and HW2 must reproduce the description's CORRECT OUTPUT exactly.
- HW8 must print `true`/`true` and pass an independent check of both sorts against `Arrays.sort`.

A failed attempt is retried with the compiler errors or the first output difference fed back. Verified results go to `references/<Hw>/<model>/` with `provenance.json`. Unverified ones go to `<model>.unverified/`. `references/` is gitignored because these are solutions to assignments the instructor wants to reuse. `human_reviewed` stays `false` until a person has read the code, as the spec requires independent checking. Pass a verified folder to the PDM and the generator with `--reference`.

## Cost estimates before spending (`--dry-run`)

`PdmMain`, `SynthMain` and `RefMain` take `--dry-run`. It builds every prompt the real run would send, then prints the number of calls, **estimated** tokens (characters ÷ 3.5) and, with `--price-in/--price-out`, the cost. The PDM also extrapolates to the spec's 100-submission benchmark (4,950 pairs). No model is called and no key is needed. Example (token counts only; the cost depends on the model's price):

| Run | Peer calls | Est. input tokens |
|---|---|---|
| Real data, one homework (both terms + controls) | 861–946 | 3.0M–5.6M |
| Synthetic dataset, one homework | 10,585–12,090 | 42M–71M |
| 100-submission benchmark, one homework | 4,950 | 16M–33M |
| Control-flow copies, one cohort | 8 | ≈38k |
| AI reference, one homework | 1 | ≈3k–6k |

### Not built yet (PDM)

- Running anything that needs the API key: OpenAI smoke test, AI references, control-flow copies, LLM PDM runs, 3× stability repeats
- Detection charts and tables in the Word report (the CSVs above are their data)
