# MediaHandler — notes for Claude

Read this before changing anything. README.md is the user-facing documentation; this file is the
working map: how the pipeline really behaves, its constraints, and known issues.

## Finishing a task
When a requested change is done (built and tested), always ask: "Should I push this?" Pushing to
`main` triggers the release workflow, which builds the jar the deployed instance updates to via the
update button in the Settings UI. Don't push without a yes.

## Building
The project targets JDK 21 (`.sdkmanrc`). Build with `JAVA_HOME=~/.sdkman/candidates/java/21-tem ./mvnw ...` —
under the newer default JDK, Lombok's annotation processing breaks and the compile fails with
hundreds of misleading "cannot find symbol" errors.

## Debugging the deployed instance
- Logs: `curl -H 'Range: bytes=-100000' http://<host>:8080/actuator/logfile` (only the `logfile`
  actuator endpoint is exposed). Strip stack frames with `grep -vE '^\s+at '`.
- Processing history incl. per-step notes: `GET /api/records` (JSON; `processingNotes` is a JSON string).
- Config: `GET /api/config` (secrets masked). Source listing: `GET /api/source-files`.

## Pipeline, exactly as implemented

1. **Detection** — `FileMonitorService.scan()` (`@Scheduled`, every `media.poll-interval-ms`):
   walks the source folder (skipping ignored folders), keeps files with a configured video
   extension, and publishes a `FileReadyEvent` once a file's size is unchanged for
   `stability-threshold-seconds`. Sample files (`SampleFiles`: name contains "sample" and ≤
   `source.sample.max-mb`) are dropped at that point. Tracking state (`publishedFiles` etc.) is
   in memory only — after a restart every file present is published again.
2. **Queue** — `FileProcessingService.onFileReady()` only submits to `ProcessingQueue` (single
   worker thread "processing", deduplicated by source path). Every other entry point (RetryService,
   RecordsController retry/unskip/retry-failed, SourceFilesController rescan/rename) uses
   `FileProcessingService.submit(record)`; `execute()` is package-private and only runs on the worker.
   Controllers check `isQueued(path)` first and answer 409 instead of touching the record.
3. **Record** — on the worker, `processReadyFile()` continues the file's latest record unless it is
   SKIPPED (left alone) or MOVED (new record — except a copy-mode original: source still exists and
   is not newer than `processedAt`).
4. **Guards in `execute()`** — ignored folder / file gone / sample → SKIPPED with reason. Any
   unexpected RuntimeException is caught and the record gets LLM_FAILED or MOVE_FAILED (by stage),
   so a record never stays PENDING after an attempt.
5. **Title** — `TitleResolver.resolve()`; cheapest first, a TMDB miss moves to the next source:
   filename → LLM → TMDB → Wikipedia; filename title variants (exact match); for each release
   folder (≤ 2, generic names like `Sample`/`Subs` skipped): folder title variants (exact match),
   then folder → LLM → TMDB → Wikipedia; then `Folder: … | File: …` → LLM → TMDB → Wikipedia.
   - LLM results must be *complete* (`FilenameParserService.isComplete`); a show parse without S/E
     takes them from the filename (`fillEpisode`). LLM unreachable (`isUnavailable`) → stop,
     LLM_FAILED.
   - Local mode: `groundInInput` blanks year/season/episode not literally in the input. Output
     format is forced by `src/main/resources/llm/filename-metadata.gbnf`; `SYSTEM_PROMPT` holds
     the rules and few-shot examples.
   - LLM-derived names accept TMDB's first hit. `TitleVariants` guesses are only accepted if the
     hit's `name` or `originalName` equals the guess after `TitleVariants.normalize`.
   - TMDB answers are cached per attempt by type|name|year, so each search runs once.
   - Every step is a `ProcessingNote(step, detail, outcome)`; steps: `LLM`, `TMDB`, `WIKI`,
     `VARIANTS`, `TMDB_ERROR`, `MOVED`/`COPIED`, `SKIPPED`, `MOVE_FAILED`, `FOLDER_CLEANUP`,
     `FOLDER_DELETED`, `DELETE_SCHEDULED`, `COPY_KEPT`, `PATH_RECOVERY`, `ERROR`. Older records
     contain `TMDB_1`, `TMDB_2`, `LLM_FOLDER` and no outcome — the UI infers it (`noteOutcome`).
6. **Move/copy** — `FileRenameService`: `Movies/Name (Year)/Name (Year).ext`
   (`target.movies.own-folder`, default true; false → `Movies/Name (Year).ext`) or
   `Shows/Name (Year)/Season NN/Name (Year) - SxxEyy.ext`, name/year from TMDB, S/E from the LLM or
   filename. `/` and `\` in titles become `-`; everything else (incl. `:`) is kept — the library
   itself is inconsistent there ("Star Wars: Episode III…" vs "Dune - Part Two"). Target exists and
   `file.overwrite` off → SKIPPED. The library also has manually named folders (e.g. German
   "Adams Äpfel (2005)"); an English TMDB title creates a separate folder next to such a one.
7. **Cleanup** — `SourceFolderCleanup`: after a move (moved file's folder recursively, then empty
   parents up to the source root) and `sweepStaleFolders()` every `media.cleanup-interval-ms`
   (top-level source folders with a record inside, unchanged for `folder.cleanup.stale-hours`,
   nothing left to process). Deletes non-video files, samples and videos below
   `folder.cleanup.small-video-max-mb` except small episodes; removes empty folders. Never touches
   anything while an archive/partial download is present, never deletes a queued video or one with
   an open record (PENDING/*_FAILED), never enters ignored folders.
   Copy mode: `OriginalFileCleanupService` deletes only the original file after N hours.
8. **Startup** — `PendingRecordReconciler` closes PENDING records left from before the restart
   (superseded / file gone / ignored / sample → SKIPPED with reason); a PENDING record whose file
   still waits is left for the monitor, which continues it.

## Runtime model & constraints
- **Threads.** Scheduled jobs (scan, retry, copy cleanup, sweep) share Spring's single scheduler
  thread but only enqueue or do quick file-system work. All processing happens on the
  `ProcessingQueue` worker, one file at a time. Local CPU inference is ~15–25 s per LLM call (the
  first after a server start can take ~2 min), up to 4 calls per file; remote mode may block up
  to 4 min for Wake-on-LAN. The pipeline stop button stops scanning/retry, not files already queued.
- **LLM retries are disabled** (`DynamicChatClientProvider.NO_RETRY`); a failed call marks the file
  LLM_FAILED. Automatic retry of failed records (`RetryService`) is **off by default**.
- **Config** lives in the H2 `app_config` table; `AppConfigService.seed()` only inserts missing keys,
  so changing a default in `application.yml` does not affect existing installs. Every new setting
  needs: constant + seed in `AppConfigService`, property in `MediaProperties`, yml entry, input in
  `index.html`, load/save in `app.js`, README.
- **H2 schema** is `ddl-auto: update` — adding columns is fine, renames/removals are not migrated.
  **Do not add `MediaFileStatus` values without a migration:** Hibernate created a CHECK constraint
  with the enum values when the table was first made, and `update` never extends it (an old dev DB
  still rejects `SKIPPED`). Inserting a new value fails with "Value not permitted for column".
- **Tests** must not touch `./data` or `./logs` — `MediaHandlerApplicationTests` uses an in-memory DB.
- **Deployment**: LXC, systemd unit `mediahandler`, working dir `/opt/mediahandler`, user
  `mediahandler`, DB in `data/`, logs in `logs/`, llama-server output in `data/llama-server.log`.
  Local model: Qwen2.5-1.5B-Instruct Q4_K_M on CPU (small — expect naming mistakes; the real-model
  test `LocalLlmRealModelTest` can only run where a model is installed, e.g. in the LXC).
- **Ignored folders** (`source.ignored.folders`, default `usenet`) must never be scanned, renamed or
  deleted by any code path — check `IgnoredFolders.isIgnored` in anything that touches files.

## TMDB API (what we use and what's worth knowing)
- Auth: `Authorization: Bearer <read access token>`; base `https://api.themoviedb.org/3`.
- `GET /search/movie` — `query` (required), `year` (any release date of the film),
  `primary_release_year` (original release only — stricter than `year`, which we use),
  `language` (default en-US), `region`, `include_adult`, `page`. Results: `id`, `title`,
  `original_title`, `original_language`, `release_date`, `popularity`, `vote_count`, `vote_average`,
  `overview`, `genre_ids`, `poster_path`, …
- `GET /search/tv` — `query`, `first_air_date_year` (first air date only; what we use), `year`
  (first air date *or any episode's* air date), `language`, `include_adult`, `page`. Results:
  `id`, `name`, `original_name`, `original_language`, `origin_country`, `first_air_date`,
  `popularity`, …
- The first result is TMDB's highest-ranked match (per the user; the docs don't describe the
  ranking) — accepting it for LLM-derived names is intentional.
- `TmdbResult` carries `name` (en-US, used for renaming), `year`, `tmdbId`, `originalName`,
  `originalLanguage`.
- Not used yet but relevant: `/movie/{id}/alternative_titles` and `/movie/{id}/translations`
  (and `/tv/{id}/...`) list titles per language; `/find/{external_id}` looks up by IMDb id (an
  `.nfo` often contains one — a strong, exact signal).

## Future work / notes
- **Foreign-language titles.** Files are always named with the English TMDB title. Most films are
  American, but a German/Danish/French/... film may have no English title at all (TMDB then
  usually returns the original title in `title` — not verified here) or an English title nobody
  uses. Not solved yet. Requirement for now: such a file must never be deleted by accident — it
  typically ends up TMDB_FAILED and stays in the source; cleanup never deletes videos with an open
  record and never deletes videos ≥ the small-video limit. Keep it that way. Ideas for later: use
  `original_language`/`originalName` to decide, search with `language=de-DE` as a fallback, or
  use the IMDb id from an `.nfo`.
- **UI (a larger redesign is planned — collect ideas here):**
  - The detail panel now shows result → workflow timeline → details. Only the *last* attempt's
    notes are stored (`processingNotes` is overwritten per attempt); showing earlier attempts
    needs a notes history.
  - A failed record should offer a direct action: "enter the correct title" (and apply it to the
    other files of the same folder/season pack) instead of only Reprocess/Exclude.
  - The history table mixes all attempts and old orphans; grouping by file (latest record per
    source path) would be clearer than one row per record.
  - Show what is queued/processing right now (`ProcessingQueue.activePaths()`), and the LLM step
    currently running — a file can take a minute or more.
  - Settings are one long form; cleanup settings (enable, small-video limit, sweep age) belong in
    their own card with an explanation of what gets deleted.
  - The workflow timeline's connector line is barely visible; fine-tune when redesigning.

## Known issues (not fixed yet)
- Eagle-Eye-style leftovers: a folder whose files were removed outside MediaHandler *before* any
  record existed is never swept (by design — no record, no touch).
- A PENDING record with a legacy-encoded (invalid) path is left PENDING at startup.
- TMDB's first result is accepted for LLM-derived names without a plausibility check (intentional
  for now — see TMDB section).
