# ReCall backend

한국어 종합 사용법(실행, LIVE 설정, 품질 평가, 평가 스크립트, 테스트)은 [USAGE.md](USAGE.md)를 보세요.

Java 21, Spring Boot **3.5.16**, JDBC, Flyway, PDFBox **3.0.8**. This is a single-instance, anonymous **local-development MVP**, not a production authentication system. No live AI requests happen unless explicitly enabled and a key is configured.

## Run and test

From the repository root:

```sh
cd backend
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# Alternatively:
./mvnw package
java -jar target/recall.jar --spring.profiles.active=dev
```

Java 21 is required; Maven and Docker are not. `mvnw.cmd` is provided for Windows. The Apache Maven 3.9.11 wrapper is checksum-pinned and adapted to keep the Maven distribution, dependency cache, and download staging beneath `backend/.cache/`. Run commands inside `backend/`. The dev profile creates a persistent H2 PostgreSQL-mode database in `backend/data/`; tests use an isolated in-memory H2 database and a localhost mock Claude server. Tests never use a real API key or charge a provider.

The default profile uses **PostgreSQL**, with Flyway migrations applied on startup. There is **no automatic H2 fallback**: H2 is used only by the explicitly selected `dev` or `test` profile. Set `DB_URL` to a **JDBC URL**, not a `postgres://` URL. Supply an existing empty database and a role allowed to create tables. The same migration is used for PostgreSQL and H2. For the same integration/API/migration suite against a **dedicated disposable PostgreSQL test database**:

```sh
DB_URL=jdbc:postgresql://localhost:5432/recall_test \
DB_USERNAME=recall \
DB_PASSWORD=your-test-password \
./mvnw -Ppgtest verify
```

The `pgtest` Maven profile activates Spring test profiles `test,pgtest`: the latter overrides the datasource using the required `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables and explicitly requires the PostgreSQL driver. The equivalent CI command `./mvnw test -Dspring.profiles.active=pgtest` is also supported by the test profile resolver. Neither option can silently substitute H2. The provider stays mocked, so this suite never makes live AI requests. Those tests add records; never point them at a real learner database. The full suite (including `QualityEvalTest` and the V2/V3 migrations) has been run this way against a local PostgreSQL 17; use a PostgreSQL CI service to keep it verified.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | unset | `dev` for persistent H2; unset for PostgreSQL |
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/recall` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `recall` | Database role |
| `DB_PASSWORD` | `recall` | Database password; replace outside local development |
| `LIVE_ENABLED` | `false` | Explicit opt-in to paid provider requests |
| `CLAUDE_API_KEY` | empty | Claude key; never returned or persisted |
| `CLAUDE_MODEL` | `claude-haiku-4-5-20251001` | Override with a model available to your account |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Base URL; `/v1/messages` appended |
| `PROVIDER_TIMEOUT_SECONDS` | `45` | Complete response deadline, clamped to 1–120 seconds |
| `PROVIDER_RETRY_DELAY_MS` | `500` | Retry base delay, clamped to 0–5000 ms; at most 3 total attempts |
| `COURSE_NAME` | `운영체제 (임시 개념 태그)` | Display name; all ten concept tags are provisional |
| `PROMPT_VERSION` | `v2` | Generation prompt: `v1` (original baseline) or `v2` (quality prompt); anything else fails startup |
| `STRUCTURED_OUTPUTS` | `true` | v2 only: send the JSON schema as `output_config.format`; `false` puts the schema in the prompt instead |
| `TARGET_USABLE_RATE` | `60` | Review summary target, percent of reviewed questions marked usable |
| `TARGET_TAG_RATE` | `80` | Review summary target, percent of reviewed questions with a correct tag |
| `JAVA_HOME` | detected Java | Optional Java 21 installation |
| `MAVEN_USER_HOME` | `.cache/maven-user` | Optional wrapper distribution cache override |

`liveEnabled` is true only when both `LIVE_ENABLED=true` and a nonblank API key are present. `.env` files are **not automatically loaded**; export variables in your shell. Do not commit credentials. Use the Vite development proxy for `/api`; this backend intentionally does not enable CORS.

## HTTP contract

All JSON errors have `{ "code": "...", "message": "..." }`; common statuses: 400 invalid input, 401 invalid capability, 404 absent/not-owned resource, 409 version/idempotency conflict, 413 oversized PDF, 503 disabled live generation/full queue.

Call `POST /api/learners` first: **201 `{token}`**. Store this opaque bearer capability locally and send `X-Learner-Token: <token>` on every other `/api` request except public `GET /api/config`. Only a SHA-256 token hash is stored. Losing the token loses access; there is no recovery, user account, or identity verification. Anyone holding it has full access to that learner's data. Avoid internet exposure; production requires real authentication, abuse controls, retention policy, TLS, and resource isolation.

| Endpoint | Result / request |
|---|---|
| `GET /actuator/health` | Public `{status:"UP"}` |
| `GET /api/config` | `{liveEnabled,courseName,timezone:"Asia/Seoul",promptVersion}` |
| `GET /api/tags` | `[{id,name}]` |
| `GET /api/dashboard` | `{dueCount,questionCount,attemptCount,accuracy,concepts:[{tagId,tagName,attemptCount,correctCount,accuracy,unresolvedWrongCount}]}` |
| `POST /api/documents` | Multipart field `file`; **201** `{id,name,pageCount,createdAt}` |
| `GET /api/documents` | Array of document metadata |
| `POST /api/jobs` | `{mode:"LIVE",documentId}` or `{mode:"DEMO"}`; **202** full job |
| `GET /api/jobs` | Array of full jobs, newest first |
| `GET /api/jobs/{id}` | Full job |
| `GET /api/questions?view=manage\|quiz\|due\|mistakes&tagId=...` | Array of questions; view defaults to `manage` |
| `GET /api/questions/{id}?view=quiz\|manage` | One question; default `manage` |
| `PUT /api/questions/{id}` | `{text,options,correctIndex,tagId,difficulty,explanation,version}` → updated question |
| `DELETE /api/questions/{id}` | **204**, soft-deleted and excluded from current questions/stats/review |
| `PUT /api/questions/{id}/review` | `{usable,tagCorrect,issueType,memo,version}` → updated question (manage view); replaces any earlier review |
| `DELETE /api/questions/{id}/review` | **204**, removes the review |
| `GET /api/reviews/summary?jobId=...&promptVersion=...` | Review totals for the learner's current questions; both filters optional |
| `GET /api/reviews/export.csv` | UTF-8 CSV (with BOM) of every current question and its review |
| `POST /api/attempts` | `{questionId,selectedIndex,idempotencyKey,version}` → **200** `{id,questionId,selectedIndex,correct,correctIndex,explanation,box,dueDate}` |

Question fields: `{id,text,options,tagId,difficulty,sourceStartPage,sourceEndPage,documentName,mode,version,box,dueDate,latestCorrect}`. `manage` and `mistakes` additionally include `correctIndex` and `explanation`; **quiz/due never include either**. `manage` also includes `jobId`, `promptVersion` (null for DEMO) and `review` (`{usable,tagCorrect,issueType,memo,updatedAt}` or null). Indices must be JSON integers 0–3, options exactly four unique nonblank strings, and difficulty `EASY|MEDIUM|HARD`. Text limit 4000 chars, each option 1000, explanation 6000. `idempotencyKey` must be a canonical lowercase UUID string. All IDs are UUIDs except concept tags:

`os-basics`, `process`, `thread`, `scheduling`, `synchronization`, `deadlock`, `memory`, `virtual-memory`, `filesystem`, `io`.

Job fields: `{id,documentId,mode,status,totalChunks,completedChunks,failedChunks,questionCount,error,createdAt,promptVersion,chunks}`. `promptVersion` is fixed when a LIVE job is created and is null for DEMO. Status is `queued|running|succeeded|partial|failed`. Each chunk has `{index,status,error,attempts,durationMs,inputTokens,outputTokens,parseAttempts,parseSuccess}`. Chunk indices are zero-based; PDF page numbers are one-based. Durations include retry delays; tokens accumulate across successful and schema-invalid provider responses. HTTP failures without usage metadata report zero tokens. Parsing success is separate from atomic storage success. A queued job may already be running/completed by the time the creation response is returned.

## Semantics and limits

- PDF: actual parse validation, ≤10 MiB, 1–80 pages; reject encryption, malformed files, and documents with no extracted text (scans require OCR elsewhere). Blank pages in otherwise text PDFs are retained but cannot be cited. An additional **1,000,000 extracted-character limit** limits expansion. Only extracted pages/chunks are stored, **never original PDF bytes**. Multipart uploads at the supported file limit remain in memory.
- Chunks are ≤12,000 characters including page markers. Long pages are split while retaining their true page number. A generated source range must stay within the chunk and every cited page must have nonblank extracted text.
- Source-range validation verifies page membership, **not semantic citation correctness**; learners should compare generated claims with the document.
- DEMO rejects nonnull `documentId`. It always generates the same three clearly labelled `[샘플]` questions, with null document/provenance, without any provider request.
- LIVE prompts explicitly treat PDF text as untrusted data. Claude receives JSON Schema instructions. Responses undergo strict schema validation (including duplicate keys/trailing data, exact fields, integer indices, allowed tags/difficulties, option uniqueness, and page provenance).
- Two generation workers, queue capacity eight; at most two concurrent provider requests. Job/chunk metadata commits before enqueue. Overload returns **503** and leaves an inspectable failed job (`OVERLOADED`); the error message includes its ID.
- At most three total provider attempts for timeouts, network errors, HTTP 408/429/5xx, or invalid schemas. Other HTTP failures are terminal. Responses capped at 1 MiB with a complete-body timeout; redirects disabled. Logs/job errors never include keys, full PDF content, or provider response bodies.
- Chunk question writes and success counters are atomic/idempotent. Maximum three questions per chunk and thirty per job. Remaining chunks after reaching thirty are marked succeeded with zero attempts/questions (quota skip), so all chunks reach a terminal state.
- On **single-instance** startup, queued/running chunks become failed (`INTERRUPTED`), and jobs become failed/partial according to existing successes. Completed questions survive; no automatic paid resumption occurs. Do not run multiple application instances against the same database.
- Every answer, including an initial wrong answer, enters box 1, due tomorrow. Later correct answers advance to a maximum box 5; intervals for boxes 1–5 are 1/3/7/14/30 days. Wrong answers reset to box 1. Dates use an injectable `Clock` and `Asia/Seoul`. Due includes today and overdue; unseen box 0 is never due.
- Learner-row database locks serialize attempts/edits/deletions. Repeating the same idempotency key and payload returns the original result without another promotion; different payload returns 409. Separate keys represent separate answers. Stale question versions return 409.
- **Editing increments version and permanently removes that question's attempts/idempotency records and review progress** (box 0, no due date, no latest result). Old quiz versions cannot be answered. Deletion makes a question inaccessible, including replay of old submissions, and excludes its historical attempts from current dashboard counts.
- Mistakes means **latest** answer wrong, not “ever wrong.” Accuracy is 0–100, rounded to two decimals, and zero for no attempts. Deleted questions and reset histories do not contribute.

## Prompt versions (v1 baseline, v2 quality prompt)

The prompt is chosen by `PROMPT_VERSION` and recorded on each LIVE job and on every question it creates, so results from the two versions can be compared.

- **v1** is the original behavior, unchanged: six-line system prompt, schema inside the prompt, at least one question per chunk, and one invalid question fails the whole chunk. Use it to measure the baseline.
- **v2** (default) loads its system prompt from `src/main/resources/prompts/question-gen-v2.txt`. It keeps the untrusted-data and provenance rules and adds question-quality criteria, difficulty definitions and examples.
  - The JSON schema goes in `output_config.format` (structured outputs). The same schema is sent for every chunk. Constraints that API does not accept (string lengths, exactly four options, page membership, the per-request limit) are stated in the prompt and enforced by the server-side parser.
  - A chunk may yield **zero questions** (cover page, table of contents); that is a successful chunk, not a retry.
  - A question that fails validation is dropped and the valid ones are kept. Only an answer whose questions are all invalid is retried.
  - Questions already accepted in the same job are passed to later chunks as data, and a question whose normalized text repeats an earlier one is dropped.
  - Thinking blocks beside the text block are ignored when reading the answer. Running a model that thinks by default is otherwise untested (`max_tokens` stays 4096 and `stop_reason` must be `end_turn`).
- `STRUCTURED_OUTPUTS=false` keeps the v2 prompt but puts the schema back in the prompt, for a model or gateway that rejects `output_config`.

The v2 request shape is covered by mock-server tests only. **It has not been sent to the real API**, and no quality difference between v1 and v2 has been measured.

## Question reviews

`PUT /api/questions/{id}/review` stores one review per question: `usable` and `tagCorrect` (required booleans), optional `issueType` (`WRONG_ANSWER`, `AMBIGUOUS`, `OFF_TOPIC`, `TOO_EASY`, `DUPLICATE`, `OTHER`; only allowed when `usable` is false), optional `memo` (≤2000 chars) and the question `version` (stale → 409). Saving again overwrites. Editing a question deletes its review, because the judgement was about the old content. Deleted questions leave the totals.

`GET /api/reviews/summary` returns `{questionCount,reviewedCount,usableCount,usableRate,tagCorrectCount,tagCorrectRate,issues,byTag,byDifficulty,targetUsableRate,targetTagCorrectRate,usableTargetMet,tagTargetMet}`. Rates are percentages of **reviewed** questions; the two `...TargetMet` values are null until something is reviewed. All review endpoints are limited to the calling learner. CSV cells that start with `=`, `+`, `-` or `@` are prefixed with `'` so spreadsheets do not run them as formulas.

## Evaluation script

`scripts/live_eval.py` (Python 3, standard library only) runs generation through the HTTP API and writes files for human review. It never reads the API key; the backend holds it.

```sh
python scripts/live_eval.py --dry-run --out eval-out                 # no server: sample data, checks the output format
python scripts/live_eval.py --demo --out eval-out                    # real server, DEMO job, no provider request
python scripts/live_eval.py --pdf lecture1.pdf lecture2.pdf --out eval-out   # LIVE: paid provider requests
python -m unittest discover -s scripts -p "test_*.py"               # format tests
```

Per run it writes `<name>.<promptVersion>.md` (question, four options, marked answer, tag, difficulty, explanation, source pages), a matching `.csv` with empty `usable(Y/N)`, `tag_correct(Y/N)` and `memo` columns, and `summary.md`. The summary lists, per run: chunks succeeded/failed, failure reasons, question count, duration, input/output tokens, retries, parse success rate and prompt version, plus checks that need no reviewer (answer-position distribution, how often the correct option is the longest, near-duplicate pairs). Runs accumulate in `runs.json`, so the same PDF under v1 and v2 appears side by side. If the server reports LIVE disabled, the script prints the variables to set and exits with status 2.

To compare prompts: start the backend with `PROMPT_VERSION=v1`, run the PDFs, restart with `PROMPT_VERSION=v2`, run the same PDFs into the same `--out` folder. Output folders hold text derived from lecture material; `eval-out/` is git-ignored.

## Verification coverage

Integration tests use real Spring MVC/JDBC/Flyway with H2 PostgreSQL mode and mock HTTP only. They cover learner isolation, capability hashing, public health/config, demo separation, strict input/schema checks, malformed/encrypted/scanned/oversized PDFs, provenance, retries/partial failure, metrics, bounded upstream responses/timeouts, quota/idempotent chunk writes, restart recovery, queue overload, concurrent attempts, edit/delete reset behavior, overdue inclusion, and Seoul midnight. `QualityEvalTest` runs the application with `PROMPT_VERSION=v2` and covers the structured-output request shape, the schema-in-prompt fallback, zero-question chunks, dropping invalid questions, cross-chunk de-duplication, thinking blocks, and review save/overwrite/validation/isolation/summary/CSV. The original suite is pinned to v1 and unchanged. Unit tests cover every Leitner transition/cap and long-page chunk splitting. Provider timing/token assertions use synthetic mock responses; no measured live latency, accuracy, or cost is claimed.
