# AIAA: LLM Plagiarism Screening and Automated Assessment

Java 25 / Maven project. Modules:

| Package | Purpose | Status |
|---|---|---|
| `aiaa.llm` | Shared model adapter interface (`ModelAdapter`), `OpenAiAdapter`, `MockAdapter` | done |
| `aiaa.pdm` | Plagiarism Detection Module (PDM) | core done |
| `aiaa.synth` | Synthetic copy generator (labeled plagiarism test cases) | done (control flow needs an LLM) |
| CAM / OCAA | Code assessment and output-based grading | TODO |

## Prerequisites

- JDK 25
- Maven 3.9+ (or IntelliJ's bundled Maven)
- For real LLM runs: an OpenAI API key in `OPENAI_API_KEY` (optional `OPENAI_BASE_URL`). Never commit keys.

```
mvn test                     # unit + end-to-end tests (offline, uses MockAdapter)
mvn -q compile exec:java -Dexec.args="--help"
```

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
mvn -q compile exec:java -Dexec.args="--homework Hw1"
mvn -q compile exec:java -Dexec.args="--homework Hw1 --starter-mode keep"
# One cohort only
mvn -q compile exec:java -Dexec.args="--homework Hw1 --cohort F25"

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

**Amount copied** = fraction of the variant's substantive, non-starter lines (testers excluded) that came from the source. It is 1.0 for whole-program variants. A partial variant is derived from **both** its source (by the amount copied) and its host (the rest).

```bash
# Generate for every cohort of Hw1 (or add --cohort F25)
mvn -q compile exec:java -Dexec.mainClass=aiaa.synth.SynthMain -Dexec.args="--homework Hw1"
# Screen the generated dataset exactly like the real one
mvn -q compile exec:java -Dexec.args="--data-root synthetic-data --homework Hw1"
```

Add `--reference <dir>` to also derive "AI copying" variants from a verified AI reference solution.

### Not built yet (PDM)

- Negative controls (independently written solutions)
- Pair/student label files derived from the manifest
- Dev/validation/test split manifests with seeds
- Threshold sweep, threshold selection on validation data, and held-out metrics/charts
- AI reference generation (references must be checked by hand before use)
