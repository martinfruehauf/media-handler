<img src="src/main/resources/static/favicon.svg" width="64" alt="MediaHandler" />

# Media Handler

A Spring Boot service that watches a source folder for new media files, parses their filenames using an LLM, looks up canonical metadata on TMDB, then renames and moves (or copies) them into a clean folder structure.

---

## Deploy on Proxmox (LXC)

Run one of these on your Proxmox host — it creates a Debian 12 LXC container and starts the app as a systemd service.

**From ShitHub (local Forgejo, LAN only):**
```bash
bash <(curl -s http://shithub.lan/martin/media-handler/raw/branch/main/scripts/setup-lxc.sh) --source shithub
```

**From GitHub (public internet):**
```bash
bash <(curl -s https://raw.githubusercontent.com/martinfruehauf/media-handler/main/scripts/setup-lxc.sh)
```

> The GitHub repository may be private. If the curl fails, ask the owner to make it public, or use the ShitHub command instead.

The script asks only for container/network settings (ID, hostname, password, disk, RAM, CPU, bridge, IP). It does **not** ask for folder paths — those are configured after the container is running, once your NAS mounts are in place.

After setup, open `http://<container-ip>:8080` to complete the **first-run setup wizard**.

---

## First-run setup wizard

On the first visit after a fresh install the UI shows a full-screen setup overlay. It will not appear again once you have saved your configuration.

The wizard collects:

| Field | Required | Description |
|-------|----------|-------------|
| Source Folder | ✓ | Path the service watches for new media (e.g. `/mnt/nas/downloads`) |
| Target — Movies | ✓ | Root folder for processed movies (e.g. `/mnt/nas/movies`) |
| Target — Shows | ✓ | Root folder for processed shows (e.g. `/mnt/nas/shows`) |
| TMDB API Key | ✓ | Bearer token from [themoviedb.org](https://www.themoviedb.org/settings/api) |
| LLM Mode | ✓ | **Local** (default) uses the llama-server + model installed in the container by `install.sh`. **Remote** asks for Provider / Key / URL / Model of an OpenAI-compatible (e.g. Ollama) or Anthropic endpoint |

All values can be changed at any time in the **Settings** tab.

---

## How it works

```
source-folder/
  Futurama.S10.GERMAN.DL.1080p.mkv
        │
        ▼
  [FileMonitorService]  — polls every 30 s, waits for file size to stabilise
        │
        ▼ FileReadyEvent
  [ProcessingQueue]     — one worker; scan, retry and UI actions all queue here
        │
        ▼
  [FileProcessingService]
        ├── TitleResolver         — see "How a name is found"
        │     ├── FilenameParserService  — LLM call → MediaMetadata
        │     ├── TmdbService            — TMDB search
        │     ├── WikipediaTitleService  — German → English title (on by default)
        │     └── TitleVariants          — title words from file/folder names, exact match only
        │           ├── search de.wikipedia.org for German title
        │           ├── follow interlanguage link → English title
        │           └── retry TMDB with English title
        └── FileRenameService     — mkdir + Files.move  (or Files.copy)
              └── OriginalFileCleanupService — deletes originals on schedule
        │
        ▼
target-folder-movies/
  Some Movie (2005).mkv

target-folder-shows/
  Futurama (1999)/Season 10/
    Futurama (1999) - S10E01.mkv
```

Every processing attempt is persisted in an H2 database. Each step is recorded as a **processing note** visible in the detail panel. Failed records can be retried automatically on a schedule, but this is **off by default** (`media.retry.enabled`); otherwise use **↻ Reprocess** in the UI.

### Which files are processed

A file in the source folder is treated as a movie or episode when all of these hold:

1. Its extension is in `media.file-extensions`.
2. It is not inside an **Ignored Folder**.
3. Its size has not changed for `media.stability-threshold-seconds`.
4. It is **not a sample**: a file whose name contains `sample` (any case) and that is no larger than the **Sample file limit** (default 200 MB) is skipped and logged. The size check means a real movie with "sample" in its title is still processed. It only runs once the size is stable, so a movie that is still downloading is never mistaken for a sample.

A file whose latest record is `SKIPPED` is not picked up again after a restart. Use **↻ Re-include** to process it.

### How a name is found

Cheapest sources first. A miss moves on to the next source instead of failing the file, and every step appears in the **Workflow** of the detail panel with ✓ or ✗:

1. **LLM, filename.** The filename is sent to the LLM. A usable result is a movie with a name, or a show with name, season and episode. In local mode, a year, season or episode that doesn't literally appear in the input is dropped (small models invent them), and the response format is fixed by a grammar.
2. **TMDB.** Movie or TV search with that name (and year). The first result is TMDB's best match and is used.
3. **Wikipedia.** If TMDB found nothing and **Title Resolution** is on (the default), the name is searched on de.wikipedia.org, and TMDB is searched again with the English article title.
4. **Title variants.** The words before the episode marker, year or first technical tag of the filename (`jajunge-south.park.s23e02…` → `jajunge south park`), then the same with one or two words dropped at the front or end (`south park`, `jajunge south`, …). These are guesses, so a TMDB hit only counts if its title matches the variant **exactly** (ignoring case, accents and punctuation).
5. **Folder names.** The folders above the file (up to two; generic ones like `Sample`, `Subs`, `Proof`, `CD1` are skipped), nearest first: first their title variants (exact match only), then an LLM parse of the folder name → TMDB → Wikipedia. A season/episode missing from a folder name (e.g. a season pack folder) is taken from the filename.
6. **Folder + filename** combined in one LLM request → TMDB → Wikipedia.

If nothing matches: `TMDB_FAILED` when a title was extracted, `LLM_FAILED` otherwise. If the LLM can't be reached at all, the file fails right away with `LLM_FAILED` instead of trying the other sources.

Names are always the English TMDB titles (`language=en-US`). For a film without an English title, TMDB usually returns its original title (see the foreign-language note in `CLAUDE.md`).

---

## Folder schema

| Type  | Target path |
|-------|-------------|
| Movie | `target-folder-movies/Name (Year).ext` |
| Show  | `target-folder-shows/Name (Year)/Season NN/Name (Year) - SxxExx.ext` |

---

## Configuration

Configuration works in two layers:

1. **application.yml / environment variables** — seed values used the first time the service starts and whenever a stored value is still a placeholder. Source/target folders and TMDB key have no default in `application.yml` — they must be entered in the setup wizard or Settings tab.
2. **Web UI (setup wizard / Settings tab)** — values are written to the H2 database and take precedence. Restarting the service does **not** overwrite values you have already saved through the UI.

### application.yml reference

| Key | Default | Description |
|-----|---------|-------------|
| `media.source-folder` | *(none — set in wizard)* | Folder to watch for new media files |
| `media.target-folder-movies` | *(none — set in wizard)* | Root folder movies are moved/copied into |
| `media.target-folder-shows` | *(none — set in wizard)* | Root folder shows are moved/copied into |
| `media.ignored-folders` | `usenet` | Folders inside the source folder (relative, or absolute paths) that are never scanned, processed, renamed or deleted. Seeds the **Ignored Folders** setting |
| `media.sample-max-mb` | `200` | Files named `*sample*` up to this size (MB) are skipped as release samples. Seeds the **Sample file limit** setting |
| `media.cleanup-small-video-max-mb` | `200` | Folder cleanup deletes video files below this size (MB), except episodes. Seeds the cleanup small-video setting |
| `media.cleanup-stale-hours` | `6` | The periodic sweep only cleans folders unchanged for this long. Seeds the sweep setting |
| `media.cleanup-interval-ms` | `1800000` | How often copy-mode originals are deleted and leftover folders are swept (ms) |
| `media.file-extensions` | mkv mp4 avi m4v mov wmv | Extensions treated as media |
| `media.poll-interval-ms` | `30000` | How often the source folder is scanned (ms) |
| `media.stability-threshold-seconds` | `60` | Seconds a file size must be stable before processing |
| `media.tmdb.api-key` | *(none — set in wizard)* | TMDB API read-access token (Bearer) |
| `media.tmdb.base-url` | `https://api.themoviedb.org/3` | TMDB base URL |
| `media.retry.enabled` | `false` | Enable automatic retry of failed records |
| `media.retry.interval-ms` | `300000` | How often failed records are retried (ms) |
| `media.retry.max-attempts` | `5` | Maximum total attempts per record |
| `media.llm.mode` | `local` | `local` = llama-server in this container, `remote` = the `spring.ai.openai.*` / Anthropic endpoint below. Existing installs upgrading from a version without this setting stay on `remote` |
| `media.llm.local.server-binary` | `/opt/llama.cpp/llama-server` | llama.cpp server binary |
| `media.llm.local.model-path` | `/opt/mediahandler/models/qwen2.5-1.5b-instruct-q4_k_m.gguf` | GGUF model file |
| `media.llm.local.model-name` | `qwen2.5-1.5b-instruct` | Model alias sent in requests |
| `media.llm.local.threads` | `4` | Inference threads — match the container's cores |
| `media.llm.local.port` | `8081` | llama-server port (bound to 127.0.0.1) |
| `media.llm.local.ctx-size` | `2048` | Context size in tokens |
| `media.llm.local.idle-timeout-seconds` | `600` | Stop llama-server after this idle time; `0` = keep running |
| `media.llm.local.startup-timeout-seconds` | `120` | Max wait for the model to load |
| `media.llm.local.extra-args` | *(empty)* | Extra llama-server arguments |
| `spring.ai.openai.api-key` | `ollama` | LLM API key (use `ollama` for local Ollama) |
| `spring.ai.openai.base-url` | `http://192.168.178.81:11434` | LLM base URL |
| `spring.ai.openai.chat.options.model` | `qwen2.5:14b` | LLM model name |

### Local secrets (development profile)

Create `src/main/resources/application-development.yml` (git-ignored) with your real keys:

```yaml
media:
  source-folder: /path/to/your/downloads
  target-folder-movies: /path/to/your/movies
  target-folder-shows: /path/to/your/shows
  tmdb:
    api-key: YOUR_TMDB_BEARER_TOKEN

spring:
  ai:
    openai:
      base-url: http://localhost:11434
      api-key: ollama
      chat:
        options:
          model: qwen2.5:14b
```

Then start with the development profile active:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

Or in VS Code using the Spring Boot Dashboard, set `SPRING_PROFILES_ACTIVE=development` in `.vscode/launch.json`.

### Configuring via the UI

Open `http://localhost:8080` and go to the **Settings** tab. Changes take effect for the next processing attempt — no restart needed.

**LLM provider options:**

| Provider | API Key | Base URL | Notes |
|----------|---------|----------|-------|
| OpenAI | `sk-...` | `https://api.openai.com` | Cloud, paid |
| Ollama (local) | `ollama` | `http://localhost:11434` | Free, runs locally |
| Anthropic | `sk-ant-...` | *(not used)* | Cloud, paid |

---

## Web UI

Open `http://localhost:8080` after starting the service.

### Logs tab

- **Pipeline controls** (optional) — a **Running / Stopped** status pill and **▶ / ■** play/stop button appear at the top of this tab when enabled. Enable them via the **Developer Tools** section in Settings.
- Source folder grid showing all media files currently present with their status. Each file has:
  - **↻ Reprocess** — re-queue a failed file immediately (also available globally via the **↻ Reprocess** button in the section header)
  - **↻ Re-include** — re-queue a skipped/excluded file for processing
  - **✕ Exclude** — mark a failed file as skipped so it won't be retried
  - **✏ Rename** — rename the file in place and re-queue it
- Processing history table with status filters, configurable page size (1, 2, 5, 10, 20, 50, 100, 1000, All), and pagination.
- Click any row to open the **detail panel**: the result first (target path or error), then the **Workflow** of the last attempt: every step in the order it ran (LLM parses, TMDB searches, Wikipedia, title variants, move/copy, cleanup), each marked ✓ succeeded, ✗ failed or • info. Paths and timestamps follow below.

### Settings tab

| Card | Settings |
|------|----------|
| **Paths** | Source folder, target folders (movies / shows), ignored folders inside the source folder (comma-separated, default `usenet`), sample file limit in MB (default 200), overwrite existing files, copy mode, delete original after N hours, source folder cleanup, its small-video limit in MB (default 200) and the leftover-folder sweep age in hours (default 6) |
| **TMDB** | Bearer token |
| **Title Resolution** | Wikipedia German→English translation (default: on) |
| **LLM Provider** | Mode (Local / Remote). Local: binary, model path, model name, threads, port, idle timeout. Remote: provider, API key, base URL, model |
| **Wake on LAN** | Enable/disable WOL, MAC address, optional shutdown command (remote mode only) |
| **Display** | Date format |
| **Developer Tools** | Checkbox to show pipeline controls (Running/Stopped + Play/Stop) in the Logs tab; **Update** button to pull the latest release JAR and restart the service |

> **Update source:** The **Update from ShitHub** button pulls from `http://shithub.lan/martin/media-handler` (local Forgejo, LAN only). The **Update from GitHub** button is only enabled after a reachability check — the repository may be private, in which case ask the owner to make it public.

---

## Local LLM (default)

Filename cleanup runs on a small instruct model (Qwen2.5-1.5B-Instruct, Q4_K_M GGUF) served by llama.cpp's `llama-server` on the CPU, inside the same LXC.

- **Started on demand.** MediaHandler launches `llama-server` (127.0.0.1 only) when a file needs parsing and stops it after `idle-timeout-seconds` without requests. Loading takes a second or two, and the ~1.8 GB of RAM is only used while files are being processed. Set the timeout to `0` to keep it loaded. If something already answers on the configured port (for example a llama-server you run yourself), MediaHandler uses it and never stops it.
- **Output format is enforced** with a GBNF grammar (`src/main/resources/llm/filename-metadata.gbnf`), so the model can only produce the `type:/name:/year:/season:/episode:` (or `error:`) lines the parser expects.
- **Invented values are dropped.** Small models like to add a plausible year or `S01E01` that isn't in the filename. In local mode, year and season/episode are kept only if they appear in the input (`2019`, `S03E07`, `3x07`). Otherwise the file goes through the usual folder-name fallback or fails with "missing season or episode" instead of being renamed wrongly.
- Server output of the current run: `data/llama-server.log`.

### LXC setup

New containers: `setup-lxc.sh` now defaults to **4 cores, 3072 MB RAM, 8 GB disk**, and `install.sh` installs everything:

1. `libgomp1` and `libssl3`, plus the prebuilt llama.cpp CPU build (`llama-<tag>-bin-ubuntu-x64.tar.gz`) unpacked to `/opt/llama.cpp/`. Pin a version with `--llama-cpp-tag b11201`.
2. The model downloaded to `/opt/mediahandler/models/` (override with `--model-url`).
3. `MEDIA_LLM_LOCAL_THREADS=$(nproc)` in `/etc/mediahandler.env`.
4. `KillMode=control-group` in `mediahandler.service`, so stopping the service also stops llama-server. There's no separate unit for llama-server.

Use `--skip-local-llm` to install without it (MediaHandler then starts in remote mode).

Existing containers:

```bash
# on the Proxmox host
pct set <ctid> --cores 4 --memory 3072
pct resize <ctid> rootfs +4G

# inside the container
curl -fsSL -o /tmp/install.sh https://raw.githubusercontent.com/martinfruehauf/media-handler/main/scripts/install.sh
bash /tmp/install.sh --local-llm-only
```

Then set **Settings → LLM Provider → Mode** to **Local**, set **Threads** to the core count, and save. Upgraded installs keep using the remote LLM until you switch.

Integration test against a real model (skipped by default):

```bash
./mvnw test -Dtest=LocalLlmRealModelTest -Dllama.server=/opt/llama.cpp/llama-server -Dllama.model=/opt/mediahandler/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
```

---

## Wake on LAN

Only used in **remote** LLM mode. The service can automatically wake the LLM machine when a file needs to be processed, and shut it down again after it has been idle for a while.

**Enabled by default.** Configure in the **Wake on LAN** settings card.

| Setting | Default | Description |
|---------|---------|-------------|
| `llm.wol.enabled` | `true` | Send a WOL magic packet before LLM requests when the machine is unreachable |
| `llm.wol.mac` | `b4:a9:fc:cd:58:88` | MAC address of the LLM machine |
| `llm.wol.shutdown-cmd` | *(auto-derived)* | Shell command to shut down the LLM machine after idle. Leave blank to auto-build `ssh -o StrictHostKeyChecking=no <llm-host> sudo shutdown -h now` from the configured LLM base URL |

**How it works:**

1. A file arrives in the source folder and is queued for processing.
2. Before the LLM call the service checks if the LLM endpoint is reachable.
3. If not, it sends a magic packet (UDP broadcast from Java, no `wol` binary needed) and polls the endpoint every 5 seconds for up to 4 minutes. Processing waits during that time.
4. Once reachable, the LLM call proceeds normally.
5. After the last LLM call, a 5-minute idle timer starts. When it fires (and no new requests have come in), the shutdown command is executed via SSH.

**Health indicator states (LLM dot in the header):**

| Colour | Meaning |
|--------|---------|
| Green | LLM reachable, WOL not involved |
| Yellow | Machine waking up, or currently running after being woken via WOL |
| Red | WOL timed out or LLM unreachable |

Hover over the indicator for a detailed status message.

---

## Processing statuses

| Status | Meaning |
|--------|---------|
| `PENDING` | Queued or in progress. A PENDING record left over from before a restart is closed at startup (`SKIPPED` with the reason) unless its file is still waiting, in which case processing continues with it |
| `LLM_FAILED` | LLM could not parse the filename, or could not be reached (no automatic back-off — the retry scheduler picks it up) |
| `TMDB_FAILED` | TMDB returned no results (after optional Wikipedia retry) |
| `MOVE_FAILED` | File system move/copy failed |
| `MOVED` | Successfully processed — file is at target path |
| `SKIPPED` | Target file already exists and overwrite is disabled, file is a sample, file no longer exists, renamed in the UI, or manually excluded — use **↻ Re-include** to re-queue |

---

## Copy mode & deferred deletion

When **Copy instead of moving** is enabled, the original file is kept in the source folder after the target copy is created. Optionally set **Delete original after N hours** to have the cleanup scheduler remove the original automatically. The scheduled deletion time is shown in the detail panel and survives service restarts.

---

## Source folder cleanup

When a file is **moved** (not copied), the service can automatically clean up the subfolder it came from. This is enabled by default and can be toggled via **Delete source folder after move** in the Paths settings card.

The cleanup runs immediately after a successful move, and again periodically for folders that were left behind (see below). It covers the folder **and all of its subfolders** (e.g. `Sample/`, `Subs/`, `Proof/`):

1. If an archive or partial download (`.rar`, `.zip`, `.par2`, `.r00`, `.part`, `.crdownload`, `.!qb`, `.tmp`, …) is anywhere in there, the download may still be running, so nothing is touched.
2. All non-video files (`.nfo`, `.jpg`, `.srt`, `.sfv`, etc.) are deleted.
3. Video files below the **Cleanup: delete video files below (MB)** setting (default 200 MB) are deleted, and so are sample files (see [Which files are processed](#which-files-are-processed)). There's one exception: a small file with an episode marker (`S01E03`, `1x03`) that isn't a sample is kept, because it's an unprocessed episode of a season pack. Each deletion is logged with its size as a `FOLDER_CLEANUP` step.
4. Larger video files are kept, because they're still waiting to be processed. So is any video file that is queued or has an open (`PENDING` or failed) record.
5. Every folder that is now empty is removed, deepest first. Empty parent folders are removed too, up to the source root (e.g. an outer `Release - by uploader/` wrapper). Each one is recorded as `FOLDER_DELETED`.

The source root itself is never removed, and ignored folders are never entered or deleted.

**Periodic sweep.** Every 30 minutes (`media.cleanup-interval-ms`) each top-level folder of the source root is checked. It is cleaned with the rules above, and removed once empty, only if all of these hold:

- MediaHandler has a record for at least one file in it (it never touches a download it hasn't seen);
- nothing in it has changed for **Cleanup: sweep leftover folders unchanged for (hours)** (default 6);
- no video file in it is still waiting: every video in it would be deleted by the rules above;
- no archive or partial download is present.

This cleans up folders left by older versions, by a cleanup that was skipped while an archive was still there, or by files removed outside MediaHandler.

---

## Wikipedia title translation

Controlled by the **Title Resolution** setting (default: on). When a TMDB lookup fails, the service:

1. Searches `de.wikipedia.org` for the parsed title.
2. Follows the interlanguage link to the English Wikipedia article title.
3. Retries TMDB with the English title.

All steps appear in the **Workflow** of the detail panel.

---

## Server logs

Besides the journal (`journalctl -u mediahandler`), the service writes a rolling log file to `./logs/mediahandler.log` (10 MB per file, 7 days, 100 MB total). It can be read over HTTP without logging into the container, through Spring Boot Actuator:

```bash
# whole current file
curl http://<container-ip>:8080/actuator/logfile
# last ~100 KB only
curl -H 'Range: bytes=-100000' http://<container-ip>:8080/actuator/logfile
```

---

## Runtime behaviour & limits

- **One file at a time, in a queue.** The folder scan, automatic retry and all UI actions (**↻ Reprocess**, **Re-include**, **Rename**) put files into one queue that a single worker processes in order. A file that is already queued or being processed is not queued again (the UI says so), so it can never be processed twice at once. The scheduled jobs themselves only queue work, so a slow LLM call no longer delays scanning or cleanup. Parsing with the local LLM takes roughly 15–25 s per call (the first call after a model start longer), up to 4 calls per file.
- **Tracking is in memory.** After a restart every file still in the source folder is picked up again. Open records are continued, not duplicated. Copy-mode originals are recognised and not processed again.
- **Settings are stored in the database.** Values from `application.yml` are only used for settings that don't exist yet, so changing a default there does not change an existing install.
- **Files that still need attention are never deleted.** No cleanup removes a video file that is queued or has a `PENDING`/`*_FAILED` record (e.g. a film TMDB couldn't match).

---

## Database

Processing history is stored in an H2 file database at `./data/mediahandler` (git-ignored).

The H2 console is available at `http://localhost:8080/h2-console` while the app is running (JDBC URL: `jdbc:h2:file:./data/mediahandler`).

---

## Running

**Prerequisites:** Java 21, Maven, and either llama.cpp's `llama-server` + a GGUF model (local mode) or a running Ollama instance / OpenAI-compatible endpoint / Anthropic API key (remote mode).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

---

## Tech stack

- Spring Boot 4 · Spring AI · Spring Data JPA
- H2 (file-based, persistent)
- Lombok · Apache Commons Lang3 / IO / Collections4
- TMDB Search API · Wikipedia API (no key required)
