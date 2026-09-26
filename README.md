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
- Click any row to open the **detail panel**, which shows paths, error messages, timestamps, and a **Processing Steps** section listing every step the pipeline took (LLM parse, TMDB attempts, Wikipedia lookup, move/copy, scheduled deletion).

### Settings tab

| Card | Settings |
|------|----------|
| **Paths** | Source folder, target folders (movies / shows), overwrite existing files, copy mode, delete original after N hours, source folder cleanup |
| **TMDB** | Bearer token |
| **Title Resolution** | Wikipedia German→English translation (default: off) |
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
3. If not, it runs `wol <mac>` and polls the endpoint every 5 seconds for up to 2 minutes.
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
