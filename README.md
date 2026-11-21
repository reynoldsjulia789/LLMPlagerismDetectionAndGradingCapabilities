# AIAA: LLM Plagiarism Screening and Automated Assessment

Java 25 / Maven project. Modules:

| Package       | Purpose                                                                                                                                                                  | Status                           |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------|
| `aiaa.llm`    | Shared model adapter interface (`ModelAdapter`), `AnthropicAdapter` (Claude, used for this project), `GoogleAdapter` (Gemini), `GroqAdapter` (OpenAI-OSS), `MockAdapter` | done                             |
| `aiaa.pdm`    | Plagiarism Detection Module (PDM)                                                                                                                                        | core done                        |
| `aiaa.synth`  | Synthetic copy generator (labeled plagiarism test cases)                                                                                                                 | done (control flow needs an LLM) |
| `aiaa.data`   | Shared dataset layout, homework defaults, assignment materials                                                                                                           | done                             |
| `aiaa.labels` | Ground-truth labels and dev/validation/test split                                                                                                                        | done                             |
| `aiaa.eval`   | Threshold sweep, frozen selection, held-out detection metrics                                                                                                            | done                             |
| `aiaa.refs`   | AI reference solutions: generation and automatic verification                                                                                                            | done (generation needs an LLM)   |
| `aiaa.demo`   | One-command demonstration on a toy dataset                                                                                                                               | done                             |
| `aiaa.cam`    | Code Assessment Module (CAM)                                                                                                                                             | done                               |

## Prerequisites

- JDK 25. Maven is **not** needed: `./mvnw` (`mvnw.cmd` on Windows) downloads Maven 3.9.9 on first use.
- For real LLM runs: an Anthropic API key in `ANTHROPIC_API_KEY`. See [Model access](#model-access-claude) below. Never commit keys.

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

## Model access (Claude)

> **Deviation from the specification.** The spec requires OpenAI GPT (or a three-family comparison through AWS Bedrock). No working, paid OpenAI key was available to the group, so with both group members' agreement this project uses **Anthropic Claude** through the Anthropic API, pending the instructor's approval. Every run records the exact provider and model, and `--adapter openai` still works unchanged if a paid OpenAI key becomes available. The Word report must state this deviation.

### 1. Store the key (once per computer)

The tools read the key **only** from the `ANTHROPIC_API_KEY` environment variable. It is never stored in a file in the repository, so it cannot be committed by accident.

**Windows** (keeps the key out of shell history):
1. Press Start, type **Edit environment variables for your account**, and open it.
2. Under *User variables*, click **New...**. Use the name `ANTHROPIC_API_KEY` and paste your key (it starts with `sk-ant-`) as the value.
3. **Close and reopen VS Code and any terminals.** Programs only see variables set after they started.

**macOS / Linux:** add `export ANTHROPIC_API_KEY=...` to your shell profile (`~/.zshrc` or `~/.bashrc`), then open a new terminal.

Check it without revealing it (PowerShell): `if ($env:ANTHROPIC_API_KEY) { "set" } else { "not set" }`

### 2. Choose the model and effort

Pass the model with `--model`. It is recorded in every output.

| Model | `--model` | Input / output, $ per 1M tokens |
|---|---|---|
| Claude Opus 5.5 (recommended default) | `claude-opus-5-5` | $4 / $20 |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 |

Prices are from https://platform.claude.com/docs/en/about-claude/pricing. Check them on the day of the run and pass `--pricing-source "<that URL>, <date>"`. Pick one model for all PDM runs; changing models after thresholds are frozen invalidates them.

- **Thinking:** Claude reasons before answering. That reasoning is billed as output tokens and counts toward `--max-output-tokens` (default 16000).
- **Effort:** `--effort low|medium|high|xhigh|max` controls how much it reasons. Without the flag, the model's default applies (`medium` for Opus 5.5). Effort is recorded in the run manifest.
- **Dry-run caution:** the `--dry-run` estimates assume 400 output tokens per call, which is too low with thinking. After the smoke test, read the average output tokens per call from `run_manifest.json` and pass it as `--assumed-output-tokens`.
- **Not supported:** current Claude models reject `--temperature`, so the adapter reports it as an unsupported setting instead of sending it. Results are therefore not deterministic, which is why the spec's stability repeats matter.
- **Refusals** are recorded as failed comparisons. The adapter deliberately does **not** fall back to a different model, so every result belongs to exactly one model.

### Additional Model Access (Google Gemini & Groq)

**Substitution Disclosure:** 
For the three-family comparison in the Code Assessment Module (CAM), 
paid OpenAI and AWS Bedrock accounts were unavailable due to budget constraints.
With group agreement, CAM compares **Anthropic Claude** (`AnthropicAdapter`), 
**Google Gemini** (`GoogleAdapter` via Google AI Studio Free Tier), and 
**OpenAI GPT-OSS** (`GroqAdapter` via Groq Cloud Free Tier). 
Every run records the exact provider, requested model ID, returned model version, token counts, latency, and pricing.

Store API keys as environment variables
- `ANTHROPIC_API_KEY`
- `GEMINI_API_KEY`
- `GROQ_API_KEY`

In CAM, to change LLM model for Google, Anthropic, or Groq, change the corresponding private static final String at the 
top of `RunCAM`

### 3. Smoke test (a few cents)

PowerShell. Note the quotes around `-Dexec.args`:
```powershell
./mvnw.cmd -q compile exec:java "-Dexec.args=--homework Hw1 --adapter anthropic --model claude-opus-5-5 --max-llm-pairs 5 --out pdm-output/smoke/Hw1 --price-in 4 --price-out 20 --pricing-source 'https://platform.claude.com/docs/en/about-claude/pricing, <date>'"
```
Success looks like `LLM: 5 calls, 0 failures`. Then check the `LLM` rows in `comparisons.csv`, and `llm.model_returned` and the token totals in `run_manifest.json`.

### 4. Sharing access with a group member (one project only)

**Never send the key itself** in chat, e-mail or a commit. Give your partner **their own key inside a project workspace with a spend cap**. You stay in control, and nothing else in your account is exposed. The steps below are in the Claude Console (platform.claude.com) and require you to be an organization admin.

1. **Create a workspace for the project:** Settings → [Workspaces](https://platform.claude.com/settings/workspaces) → **Create workspace** (for example `aiaa-project`).
2. **Cap its spending:** Settings → [Spend limits](https://platform.claude.com/settings/spend-limits) → under **Workspace limits**, choose the workspace → **Set limit** next to **Monthly spend limit**. Optionally add an e-mail notification. Organization-wide limits still apply on top.
3. **Invite your partner** on the [Members](https://platform.claude.com/settings/members) page with the least-privileged organization role, **User**. Organization users and developers see no workspace until an admin adds them.
4. **Add them to the project workspace only**, with the role **Workspace Developer**. That role can create and manage API keys and use the API in that workspace, but not change workspace settings, members or limits.
5. Your partner signs in, creates **their own** API key in `aiaa-project`, and stores it as `ANTHROPIC_API_KEY` on their computer as in step 1. Each of you should also create your own project key in that workspace, so all project usage is counted against its spend limit.
6. **Revoking access:** remove the member from the workspace on the Members page (choose the workspace in the **Workspace** dropdown first; with **All** selected, the button removes them from the whole organization), or archive their key. At the end of the project, **Archive workspace** deactivates the workspace and every key in it. This cannot be undone.

Usage is billed to your organization's credits. The workspace spend limit is what keeps the project within the amount you agree on.

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
4. **Runs the LLM judge** (`--adapter anthropic|openai|mock`) on every peer pair `S_ij` and on every submission against each AI reference `S_i_AI`. The model returns JSON with an overall `similarity` plus `structure`/`syntax`/`tokens`/`logic` sub-scores, cited evidence regions (`File.java:start-end`), and a rationale. Prompts that are too large are skipped with status `TOO_LARGE`, never truncated.
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

# Claude: smoke test on 5 pairs first, then the full run with --resume (key: see Model access below)
... --homework Hw1 --adapter anthropic --model claude-opus-5-5 --max-llm-pairs 5 \
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
| `CONTROL_FLOW` | LLM rewrite (loops, inverted ifs, guard clauses, switch); retried with compiler errors fed back. Skipped without `--adapter anthropic` (or `openai`) |
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
./mvnw -q compile exec:java -Dexec.mainClass=aiaa.refs.RefMain -Dexec.args="--homework Hw1 --adapter anthropic --model claude-opus-5-5"
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

## Code Assessment Module (`aiaa.cam`)

The Code Assessment Module evaluates functional correctness and code quality across `Hw1`, `Hw2`, and `Hw8` in two stages:

1. **Output Comparison Based Assessment (`JUnitRunner`).** Copies each student's `.java` files (excluding student-written `Tester` files) and the corresponding instructor JUnit 5 test suite + reflection adapter (`Hw1ListAdapter`, `Hw2ListAdapter`, `ListAdapter`) into an isolated temporary directory, compiles with `javac`, and executes JUnit 5 with preemptive timeouts (`ThreadMode.SEPARATE_THREAD` + 15s process kill) to prevent infinite loops from hanging the batch. The JUnit pass percentage (`0.00–100.00`) serves as the ground-truth baseline grade (`junit_baseline_grade`).
2. **LLM Code Assessment (`LlmGrader`).** Sends the complete, untruncated student source code and assignment rubric to each configured `ModelAdapter` using a strict JSON schema (`src/main/resources/prompts/cam_grading_schema.json`). Each required method is scored individually (`0–100`) alongside qualitative feedback and an overall code grade.
3. **Statistical Evaluation (`CAMStatistics`).** Computes Mean Absolute Error (MAE), Root Mean Squared Error (RMSE), Mean Signed Error, within-±5% and within-±10% accuracy (per assignment and pooled), and aggregates API latency, token counts, retries, and USD cost into chart-ready CSV tables.

### Prerequisites & JUnit Standalone JAR

`JUnitRunner` requires `lib/junit-platform-console-standalone.jar` (JUnit Platform 1.11.4) to compile and execute student submissions in isolated child JVMs. Maven copies this automatically during `process-resources`:

```powershell
mvn process-resources
```

### Example Commands

```powershell
# 1. Offline dry run (default): runs real JUnit 5 tests on all submissions with simulated LLM responses
./mvnw.cmd -q compile exec:java "-Dexec.mainClass=aiaa.cam.RunCAM"

# 2. Live 1-submission smoke test (verifies API keys, JSON schema parsing, and CSV formatting)
./mvnw.cmd -q compile exec:java "-Dexec.mainClass=aiaa.cam.RunCAM" "-Dexec.args=--live --limit 1"

# 3. Full live evaluation across all submissions and configured models
./mvnw.cmd -q compile exec:java "-Dexec.mainClass=aiaa.cam.RunCAM" "-Dexec.args=--live"
```

### To Run in IntelliJ:
1. Open the root project folder
2. Place student submissions in a folder titled HomeworkSubmissions inside src/mainresources, ensure the submissions are organized by homework (Hw1, Hw2, Hw8), then by quarter
3. Locate the ... menu next to the play and debug buttons at the top of the IDE
4. Under configuration, click edit
5. Name the configuration and add args and environment vars in the correct locations, then click run.
    - arg options: 
        - `--live` runs with live connections to LLMs - uses tokens
        - `--limit <int>` limits the number of submissions evaluated to the specified int
6. Run results will be located in the folder assessment_results

### Outputs (`assessment_results/`, gitignored)

| File                          | Contents |
|-------------------------------|---|
| `grade_comparison.csv`        | Per submission: `submission_id`, `assignment`, `cohort`, `junit_baseline_grade`, `<provider>_grade` for each model, signed error `error_<provider>`, `run_id`, and `status` (`SUCCESS` or `COMPILE_OR_TEST_ERROR`) |
| `method_grades.csv`           | Per submission × model × method: individual method score (`0–100`) and qualitative feedback |
| `experiment_log.csv`          | Per API call: provider, requested/returned model version, prompt/rubric versions, sampling parameters, input/output tokens, retries, latency (ms), estimated USD cost, and timestamp |
| `grading_metrics_summary.csv` | MAE, RMSE, Mean Signed Error, and ±5% / ±10% accuracy per assignment (`Hw1`, `Hw2`, `Hw8`) and `POOLED` |
| `efficiency_summary.csv`      | Mean and standard deviation of wall-clock latency (ms), total tokens, API calls, retries, and USD cost per model |
| `scatter_plot_chart_data.csv` | Baseline JUnit grade, `equality_line` (`y = x`), and predicted LLM grades formatted for native Word/Excel scatter plots |

### Troubleshooting (CAM)

- **`FileNotFoundException: assessment_results\grade_comparison.csv (The process cannot access the file...)`:** Close any open CSV files from `assessment_results\` in Excel before re-running `RunCAM`.
- **`COMPILE_OR_TEST_ERROR` on all submissions:** Ensure `lib/junit-platform-console-standalone.jar` exists by running `mvn process-resources`. (Individual `COMPILE_OR_TEST_ERROR` rows indicate genuine syntax/import errors in a student's submission.)
- **Environment variables not recognized in IDE:** Close and reopen your IDE/terminal after adding `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, or `GROQ_API_KEY` in Windows Environment Variables.
