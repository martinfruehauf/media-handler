<img src="src/main/resources/static/favicon.svg" width="64" alt="MediaHandler" />

# Media Handler

A Spring Boot service that watches a source folder for new media files, parses their filenames using an LLM, looks up canonical metadata on TMDB, then renames and moves (or copies) them into a clean folder structure.

---

## Deploy on Proxmox (LXC)

Run this on your Proxmox host — it creates a Debian 12 LXC container and starts the app as a systemd service:

```bash
bash <(curl -s https://raw.githubusercontent.com/martinfruehauf/media-handler/main/scripts/setup-lxc.sh)
```

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
| LLM Provider / Key / URL / Model | — | Optional at setup; can be configured later in Settings |

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
  [FileProcessingService]
        ├── FilenameParserService  — LLM call → MediaMetadata
        │     └── folder-name fallback if filename alone is ambiguous
        ├── TmdbService           — REST call to TMDB (first attempt)
        │     └── [optional] WikipediaTitleService
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

Every processing attempt is persisted in an H2 database. Each step is recorded as a **processing note** visible in the detail panel. Failed attempts are retried on a configurable schedule.

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
| `media.file-extensions` | mkv mp4 avi m4v mov wmv | Extensions treated as media |
| `media.poll-interval-ms` | `30000` | How often the source folder is scanned (ms) |
| `media.stability-threshold-seconds` | `60` | Seconds a file size must be stable before processing |
| `media.tmdb.api-key` | *(none — set in wizard)* | TMDB API read-access token (Bearer) |
| `media.tmdb.base-url` | `https://api.themoviedb.org/3` | TMDB base URL |
| `media.retry.enabled` | `false` | Enable automatic retry of failed records |
| `media.retry.interval-ms` | `300000` | How often failed records are retried (ms) |
| `media.retry.max-attempts` | `5` | Maximum total attempts per record |
| `spring.ai.openai.api-key` | `ollama` | LLM API key (use `ollama` for local Ollama) |
| `spring.ai.openai.base-url` | `http://localhost:11434` | LLM base URL |
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
- Click any row to open the **detail panel**, which shows paths, error messages, timestamps, and a **Processing Steps** section listing every step the pipeline took (LLM parse, TMDB attempts, Wikipedia lookup, move/copy, scheduled deletion).

### Settings tab

| Card | Settings |
|------|----------|
| **Paths** | Source folder, target folders (movies / shows), overwrite existing files, copy mode, delete original after N hours, source folder cleanup |
| **TMDB** | Bearer token |
| **Title Resolution** | Wikipedia German→English translation (default: off) |
| **LLM Provider** | Provider, API key, base URL, model |
| **Display** | Date format |
| **Developer Tools** | Checkbox to show pipeline controls (Running/Stopped + Play/Stop) in the Logs tab; **Update** button to pull the latest release JAR and restart the service |

---

## Processing statuses

| Status | Meaning |
|--------|---------|
| `PENDING` | Queued or first attempt in progress |
| `LLM_FAILED` | LLM could not parse the filename |
| `TMDB_FAILED` | TMDB returned no results (after optional Wikipedia retry) |
| `MOVE_FAILED` | File system move/copy failed |
| `MOVED` | Successfully processed — file is at target path |
| `SKIPPED` | Target file already exists and overwrite is disabled, or manually excluded — use **↻ Re-include** to re-queue |

---

## Copy mode & deferred deletion

When **Copy instead of moving** is enabled, the original file is kept in the source folder after the target copy is created. Optionally set **Delete original after N hours** to have the cleanup scheduler remove the original automatically. The scheduled deletion time is shown in the detail panel and survives service restarts.

---

## Source folder cleanup

When a file is **moved** (not copied), the service can automatically clean up the subfolder it came from. This is enabled by default and can be toggled via **Delete source folder after move** in the Paths settings card.

The cleanup runs immediately after a successful move:

1. All non-video files (`.nfo`, `.jpg`, `.srt`, `.sfv`, etc.) are deleted silently.
2. Video files smaller than **50 MB** are treated as sample clips and deleted. Each one is logged with its size as a `FOLDER_CLEANUP` step in the processing history.
3. If the folder is now empty it is removed and recorded as `FOLDER_DELETED` in the processing history.

The source root itself is never touched — only immediate subfolders the processed file came from.

---

## Wikipedia title translation

Enabled per-file via the **Title Resolution** setting (default: off). When a TMDB lookup fails, the service:

1. Searches `de.wikipedia.org` for the parsed title.
2. Follows the interlanguage link to the English Wikipedia article title.
3. Retries TMDB with the English title.

All steps appear in the **Processing Steps** section of the detail panel.

---

## Database

Processing history is stored in an H2 file database at `./data/mediahandler` (git-ignored).

The H2 console is available at `http://localhost:8080/h2-console` while the app is running (JDBC URL: `jdbc:h2:file:./data/mediahandler`).

---

## Running

**Prerequisites:** Java 21, Maven, a running Ollama instance (or any OpenAI-compatible endpoint or Anthropic API key).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

---

## Tech stack

- Spring Boot 4 · Spring AI · Spring Data JPA
- H2 (file-based, persistent)
- Lombok · Apache Commons Lang3 / IO / Collections4
- TMDB Search API · Wikipedia API (no key required)
