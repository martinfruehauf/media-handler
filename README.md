so do# Media Handler

A Spring Boot service that watches a source folder for new media files, parses their filenames using a local LLM, looks up canonical metadata on TMDB, then renames and moves them into a clean folder structure.

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

Every processing attempt is persisted in an H2 database. Failed attempts are retried automatically on a configurable schedule.

---

## Folder schema

| Type  | Target path |
|-------|-------------|
| Movie | `target-folder/Name (Year).ext` |
| Show  | `target-folder/Name (Year)/Season NN/Name (Year) - SxxExx.ext` |

---

## Configuration

All settings live under the `media.*` namespace.

| Key | Default | Description |
|-----|---------|-------------|
| `media.source-folder` | — | Folder to watch for new media files |
| `media.target-folder` | — | Root folder files are moved into |
| `media.file-extensions` | mkv mp4 avi m4v mov wmv | Extensions to consider as media |
| `media.poll-interval-ms` | `30000` | How often the source folder is scanned |
| `media.stability-threshold-seconds` | `60` | Seconds a file's size must be stable before processing |
| `media.tmdb.api-key` | — | TMDB API read-access token (Bearer) |
| `media.tmdb.base-url` | `https://api.themoviedb.org/3` | TMDB base URL |
| `media.retry.interval-ms` | `300000` | How often failed records are retried (5 min) |
| `media.retry.max-attempts` | `5` | Total attempts before giving up |

### Local secrets

Copy your TMDB bearer token into `src/main/resources/application-development.yml` (git-ignored):

```yaml
media:
  tmdb:
    api-key: YOUR_TMDB_BEARER_TOKEN
```

Then activate the profile when running:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

---

## Database

Processing history is stored in an H2 file database at `./data/mediahandler` (also git-ignored).

The H2 console is available at <http://localhost:8080/h2-console> while the app is running.

| Status | Meaning |
|--------|---------|
| `PENDING` | First attempt in progress |
| `LLM_FAILED` | LLM could not parse the filename — will be retried |
| `TMDB_FAILED` | TMDB returned no results — will be retried |
| `MOVE_FAILED` | File system move failed — will be retried |
| `MOVED` | Successfully processed — terminal state |

---

## Running

**Prerequisites:** Java 21, Maven, a running Ollama instance (or any OpenAI-compatible endpoint).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

### LLM

The service uses an OpenAI-compatible API. By default it points to a local Ollama instance running `qwen2.5:14b`. Change `spring.ai.openai.*` in `application.yml` to use a different model or provider.

---

## Tech stack

- Spring Boot 4 · Spring AI · Spring Data JPA
- H2 (file-based, persistent)
- Lombok · Apache Commons Lang3 / IO / Collections4
- TMDB Search API
