# Media Handler

A Spring Boot service that watches a source folder for new media files, parses their filenames using an LLM, looks up canonical metadata on TMDB, then renames and moves them into a clean folder structure.

---

## How it works

```
source-folder/
  Some.Movie.2005.1080p.BluRay.mkv
        │
        ▼
  [FileMonitorService]  — polls every 30 s, waits for file size to stabilise
        │
        ▼ FileReadyEvent
  [FileProcessingService]
        ├── FilenameParserService  — blocking LLM call → MediaMetadata
        ├── TmdbService           — REST call to TMDB search API → TmdbResult
        └── FileRenameService     — mkdir + Files.move
        │
        ▼
target-folder/
  Some Movie (2005).mkv                          ← movie
  Alien Earth (2025)/Season 01/
    Alien Earth (2025) - S01E01.mkv              ← show episode
```

Every processing attempt is persisted in an H2 database. Failed attempts are retried on a configurable schedule.

---

## Folder schema

| Type  | Target path |
|-------|-------------|
| Movie | `target-folder/Name (Year).ext` |
| Show  | `target-folder/Name (Year)/Season NN/Name (Year) - SxxExx.ext` |

---

## Configuration

Configuration works in two layers:

1. **application.yml / environment variables** — seed values used the first time the service starts and whenever a stored value is still a placeholder.
2. **Web UI (Settings tab)** — once you save a value through the UI it is written to the H2 database and takes precedence from that point on. Restarting the service does **not** overwrite values you have already saved through the UI.

In short: environment/YAML sets the defaults on first boot; the UI is the live source of truth after that.

### application.yml reference

| Key | Default | Description |
|-----|---------|-------------|
| `media.source-folder` | — | Folder to watch for new media files |
| `media.target-folder` | — | Root folder files are moved into |
| `media.file-extensions` | mkv mp4 avi m4v mov wmv | Extensions treated as media |
| `media.poll-interval-ms` | `30000` | How often the source folder is scanned (ms) |
| `media.stability-threshold-seconds` | `60` | Seconds a file size must be stable before processing |
| `media.tmdb.api-key` | — | TMDB API read-access token (Bearer) |
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
  target-folder: /path/to/your/library
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

Or in VS Code using the Spring Boot Dashboard, set the environment variable `SPRING_PROFILES_ACTIVE=development` in `.vscode/launch.json`.

### Configuring via the UI

Open `http://localhost:8080` and go to the **Settings** tab. Changes are saved to the database immediately when you click **Save Settings** and take effect for the next processing attempt — no restart needed.

**LLM provider options:**

| Provider | API Key | Base URL | Notes |
|----------|---------|----------|-------|
| OpenAI | `sk-...` | `https://api.openai.com` | Cloud, paid |
| Ollama (local) | `ollama` | `http://localhost:11434` | Free, runs locally |
| Anthropic | `sk-ant-...` | *(not used)* | Cloud, paid |

---

## Web UI

Open `http://localhost:8080` after starting the service.

- **Logs tab** — shows all files currently in the source folder with their status, plus the full processing history with pagination and filters.
- **Settings tab** — configure paths, TMDB API key, LLM provider/key/model, and date display format.

### Processing statuses

| Status | Meaning |
|--------|---------|
| `PENDING` | Queued or first attempt in progress |
| `LLM_FAILED` | LLM could not parse the filename |
| `TMDB_FAILED` | TMDB returned no results |
| `MOVE_FAILED` | File system move failed |
| `MOVED` | Successfully processed — terminal state |

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
- TMDB Search API
