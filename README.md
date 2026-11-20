# AIAA: LLM Plagiarism Screening and Automated Assessment

Java 25 / Maven project. Modules:

| Package | Purpose | Status |
|---|---|---|
| `aiaa.llm` | Shared model adapter interface (`ModelAdapter`), `OpenAiAdapter`, `MockAdapter` | done |
| `aiaa.pdm` | Plagiarism Detection Module (PDM) | core done |
| CAM / OCAA | Code assessment and output-based grading | TODO |

## Prerequisites

- JDK 25
- Maven 3.9+ (or IntelliJ's bundled Maven)
- For real LLM runs: an OpenAI API key in `OPENAI_API_KEY` (optional `OPENAI_BASE_URL`). Never commit keys.

```
mvn test                     # unit + end-to-end tests (offline, uses MockAdapter)
mvn -q compile exec:java -Dexec.args="--help"
```

## Plagiarism Detection Module (PDM)

For one assignment at a time, the PDM:

1. **Loads and anonymizes.** Each subfolder of `--submissions` is one submission and gets an anonymous ID (`F25Hw1-S01`, ...). The folder-to-ID mapping is written only to `--id-map` (default `private/`, which is gitignored). Files are shown under their declared class name, so a name-prefixed file such as `Name_CDoublyLinkedList.java` appears as `CDoublyLinkedList.java`. E-mail addresses and `@author` / `Name:` / `Student ID:` comment values are redacted. This is best-effort: free-form names in comments are not detected.
2. **Excludes instructor material** (`--starter-mode strip`, the default). Files named with `--exclude-file` (supplied testers) are dropped. Lines matching the starter code are removed: whitespace-normalized, multiset-matched, and only lines containing letters or digits. Each kept line retains its original line number. `--starter-mode keep` gives the without-exclusion comparison.
3. **Runs the token baseline** (non-LLM) on every pair and every reference. Tokens are normalized (identifiers become `ID`, literals become `STR`/`NUM`, comments and imports are dropped), then 5-gram **Jaccard** (primary) and **containment** (more sensitive to partial copying) are computed, both on a 0–100 scale.
4. **Runs the LLM judge** (`--adapter openai|mock`) on every peer pair `S_ij` and on every submission against each AI reference `S_i_AI`. The model returns JSON with an overall `similarity` plus `structure`/`syntax`/`tokens`/`logic` sub-scores, cited evidence regions (`File.java:start-end`), and a rationale. Prompts that are too large are skipped with status `TOO_LARGE`, never truncated.
5. **Computes student scores** per method: `S_i = α·max_j S_ij + (1−α)·S_i_AI`, where `S_i_AI` is the maximum over the reference bank and α defaults to 0.5. The peer and AI components are also reported separately, because thresholds are chosen for each one independently. The combined score is blank when no reference is supplied.

Similarity scores are **screening evidence for instructor review**, not findings of cheating.
Similarity to an AI reference only detects copying from *that known reference*.

### Example commands

```bash
D="HomeworkSubmissions-test data"
# Token baseline only (free), with and without starter exclusion
mvn -q compile exec:java -Dexec.args="--assignment F25Hw1 \
  --submissions '$D/HomeworksSubmissions/F25Hw1' \
  --starter '$D/HomeworkDescriptions/hw1_description_startupcode.zip' \
  --exclude-file MyLinkedListTester.java"
#   add --starter-mode keep for the comparison run

# OpenAI: smoke test on 5 pairs first, then the full run with --resume
export OPENAI_API_KEY=...
... --adapter openai --model <exact-model-id> --max-llm-pairs 5 \
    --reference refs/F25Hw1/gpt --price-in <usd/Mtok> --price-out <usd/Mtok> \
    --pricing-source "<pricing page URL, date>"
```

HW2: starter `hw2_description_startupcode.zip`, `--exclude-file Tester.java`. HW8 has no starter code.

### Outputs (`pdm-output/<assignment>/<mode>/`, gitignored)

| File | Contents |
|---|---|
| `comparisons.csv` | One row per comparison × method: IDs, similarity, containment, sub-scores, evidence, status, model and prompt version, tokens, latency, retries, cost |
| `submission_scores.csv` | Per submission × method: max peer score and partner, AI score and reference, α, combined `S_i`, ok/failed counts |
| `inventory.csv` | Per submission/reference: files included/excluded, redactions, starter lines removed, eligibility and reason |
| `run_manifest.json` | Full configuration, model ID and returned version, prompt version hashes, call/token/retry/cost totals, timestamps |
| `llm_responses.jsonl` | Raw model response for every call (used by `--resume`) |

Prompts live in `src/main/resources/prompts/`. Each prompt version is the template name plus a hash of its text, so any edit to a frozen prompt shows up in the outputs.

### Not built yet (PDM)

- Synthetic copy variants with provenance labels, and negative controls
- Dev/validation/test split manifests with seeds
- Threshold sweep, threshold selection on validation data, and held-out metrics/charts
- AI reference generation (references must be checked by hand before use)
