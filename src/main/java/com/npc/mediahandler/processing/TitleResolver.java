package com.npc.mediahandler.processing;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.llm.FilenameParserService;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.media.TitleVariants;
import com.npc.mediahandler.tmdb.TmdbResult;
import com.npc.mediahandler.tmdb.TmdbService;
import com.npc.mediahandler.wiki.WikipediaTitleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the TMDB entry for a file. Cheapest sources first, and a TMDB miss moves on to the next
 * source instead of failing the file:
 * <ol>
 *   <li>LLM parse of the filename → TMDB → Wikipedia (German → English title) → TMDB</li>
 *   <li>title words cut from the filename ({@link TitleVariants}) → TMDB, exact title match only</li>
 *   <li>for each release folder above the file (generic ones like {@code Sample/} skipped):
 *       its title words → TMDB (exact match), then LLM parse of the folder name → TMDB → Wikipedia</li>
 *   <li>LLM parse of {@code Folder: … | File: …} combined → TMDB → Wikipedia</li>
 * </ol>
 * Season and episode missing from a folder parse are taken from the filename. Every step is
 * recorded as a {@link ProcessingNote} in the order it ran.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TitleResolver {

    /** Folders that say nothing about the title — look at the folder above instead. */
    private static final Set<String> GENERIC_FOLDERS = Set.of(
            "sample", "samples", "sub", "subs", "subtitles", "proof", "proofs", "extras", "extra",
            "featurettes", "bonus", "cd1", "cd2", "cd3", "disc1", "disc2", "disk1", "disk2");

    /** At most this many release folders above the file are tried. */
    private static final int MAX_FOLDERS = 2;

    private final FilenameParserService llm;
    private final TmdbService tmdbService;
    private final WikipediaTitleService wikiService;
    private final AppConfigService configService;
    private final MediaProperties properties;

    public record Resolution(MediaMetadata metadata, TmdbResult tmdb, MediaFileStatus failStatus, String error) {
        static Resolution found(MediaMetadata metadata, TmdbResult tmdb) {
            return new Resolution(metadata, tmdb, null, null);
        }

        static Resolution failed(MediaFileStatus status, String error) {
            return new Resolution(null, null, status, error);
        }

        public boolean isFound() {
            return tmdb != null;
        }
    }

    public Resolution resolve(String filename, Path source, List<ProcessingNote> notes) {
        return new Attempt(filename, source, notes).run();
    }

    /** State of one resolution: what was learned so far and which TMDB searches already ran. */
    private class Attempt {
        private final String filename;
        private final Path source;
        private final List<ProcessingNote> notes;
        private final TitleVariants.Parsed fileParsed;
        /** TMDB answers by type|name|year, so the same search never runs twice (null = no match). */
        private final Map<String, TmdbResult> searched = new HashMap<>();
        private final List<String> triedNames = new ArrayList<>();
        /** First complete LLM result — supplies type, season and episode. */
        private MediaMetadata known;
        private MediaMetadata lastLlmResult;
        private boolean llmUnavailable;

        Attempt(String filename, Path source, List<ProcessingNote> notes) {
            this.filename = filename;
            this.source = source;
            this.notes = notes;
            this.fileParsed = TitleVariants.parse(FilenameUtils.getBaseName(filename));
        }

        Resolution run() {
            Optional<Resolution> found = fromLlm("filename", filename, filename);
            if (llmUnavailable) return llmUnavailableFailure();
            if (found.isEmpty()) found = fromVariants("filename", fileParsed);

            List<String> folders = releaseFolders();
            for (String folder : folders) {
                if (found.isPresent()) break;
                found = fromVariants("folder \"" + folder + "\"", TitleVariants.parse(folder));
                if (found.isEmpty()) found = fromLlm("folder", folder, folder);
                if (llmUnavailable) return llmUnavailableFailure();
            }
            if (found.isEmpty() && !folders.isEmpty()) {
                found = fromLlm("folder + filename", folders.get(0) + " | " + filename,
                        "Folder: " + folders.get(0) + " | File: " + filename);
                if (llmUnavailable) return llmUnavailableFailure();
            }
            return found.orElseGet(this::failure);
        }

        /** LLM parse of {@code input}, then TMDB and Wikipedia with the parsed name. */
        private Optional<Resolution> fromLlm(String label, String shown, String input) {
            MediaMetadata m = llm.parse(input);
            if (m == null) m = new MediaMetadata(null, null, null, null, null, "no response");
            lastLlmResult = m;
            if (FilenameParserService.isUnavailable(m)) {
                llmUnavailable = true;
                notes.add(ProcessingNote.fail("LLM", "%s \"%s\" → %s".formatted(label, shown, m.error())));
                return Optional.empty();
            }
            if (m.isError()) {
                notes.add(ProcessingNote.fail("LLM", "%s \"%s\" → %s".formatted(label, shown, m.error())));
                return Optional.empty();
            }
            m = fillEpisode(m);
            boolean complete = FilenameParserService.isComplete(m);
            notes.add(new ProcessingNote("LLM", "%s \"%s\" → %s%s".formatted(
                    label, shown, describe(m), complete ? "" : " — incomplete"),
                    complete ? ProcessingNote.OK : ProcessingNote.FAIL));
            if (!complete) return Optional.empty();
            if (known == null) known = m;

            TmdbResult result = search(m);
            if (result != null) return Optional.of(Resolution.found(m, result));

            if (!wikiService.isEnabled()) return Optional.empty();
            Optional<String> english = wikiService.findEnglishTitle(m.name());
            if (english.isEmpty()) {
                notes.add(ProcessingNote.fail("WIKI", "\"%s\" → no English article".formatted(m.name())));
                return Optional.empty();
            }
            notes.add(ProcessingNote.ok("WIKI", "\"%s\" → English title \"%s\"".formatted(m.name(), english.get())));
            MediaMetadata translated = new MediaMetadata(m.type(), english.get(), m.year(), m.season(), m.episode(), null);
            result = search(translated);
            return result != null ? Optional.of(Resolution.found(translated, result)) : Optional.empty();
        }

        /** Title words cut from a raw name; a TMDB hit only counts if its title matches exactly. */
        private Optional<Resolution> fromVariants(String label, TitleVariants.Parsed parsed) {
            String type;
            String season = null;
            String episode = null;
            if (parsed.isShow() || fileParsed.isShow()) {
                type = "show";
                TitleVariants.Parsed marker = parsed.isShow() ? parsed : fileParsed;
                season = marker.season();
                episode = marker.episode();
            } else if (known != null) {
                type = known.type();
                season = known.season();
                episode = known.episode();
            } else {
                type = "movie";
            }
            if ("show".equals(type) && (season == null || episode == null)) return Optional.empty();
            String year = parsed.year() != null ? parsed.year() : known != null ? known.year() : "";

            List<String> tried = new ArrayList<>();
            for (String candidate : TitleVariants.candidates(parsed)) {
                MediaMetadata m = new MediaMetadata(type, candidate, "show".equals(type) ? "" : year, season, episode, null);
                boolean seen = searched.containsKey(key(m));
                TmdbResult result = cachedSearch(m);
                if (!seen) {
                    tried.add("\"" + candidate + "\"");
                    triedNames.add(candidate);
                }
                if (result != null && matchesExactly(candidate, result)) {
                    notes.add(ProcessingNote.ok("VARIANTS", "from %s: %s \"%s\" → \"%s\" (%s), TMDB id %s".formatted(
                            label, type, candidate, result.name(), result.year(), result.tmdbId())));
                    return Optional.of(Resolution.found(m, result));
                }
            }
            if (!tried.isEmpty()) {
                notes.add(ProcessingNote.fail("VARIANTS", "from %s: %s %s → no exact TMDB title match".formatted(
                        label, type, String.join(", ", tried))));
            }
            return Optional.empty();
        }

        /** A show parse without season/episode (e.g. a season-pack folder) takes them from the filename. */
        private MediaMetadata fillEpisode(MediaMetadata m) {
            if (!m.isShow() || (StringUtils.isNotBlank(m.season()) && StringUtils.isNotBlank(m.episode()))) return m;
            String season = null;
            String episode = null;
            if (known != null && known.isShow()) {
                season = known.season();
                episode = known.episode();
            } else if (fileParsed.isShow()) {
                season = fileParsed.season();
                episode = fileParsed.episode();
            }
            if (season == null) return m;
            return new MediaMetadata(m.type(), m.name(), m.year(), season, episode, null);
        }

        private TmdbResult search(MediaMetadata m) {
            triedNames.add(m.name());
            TmdbResult result = cachedSearch(m);
            String what = "%s \"%s\"%s".formatted(m.type(), m.name(),
                    StringUtils.isNotBlank(m.year()) ? " (" + m.year() + ")" : "");
            if (result == null) {
                notes.add(ProcessingNote.fail("TMDB", what + " → no match"));
            } else {
                notes.add(ProcessingNote.ok("TMDB", "%s → \"%s\" (%s), id %s".formatted(
                        what, result.name(), result.year(), result.tmdbId())));
            }
            return result;
        }

        private TmdbResult cachedSearch(MediaMetadata m) {
            String key = key(m);
            if (!searched.containsKey(key)) searched.put(key, searchQuietly(m));
            return searched.get(key);
        }

        private TmdbResult searchQuietly(MediaMetadata m) {
            try {
                return m.isMovie()
                        ? tmdbService.searchMovie(m.name(), m.year())
                        : tmdbService.searchShow(m.name(), m.year());
            } catch (RuntimeException e) {
                log.warn("TMDB request failed for '{}': {}", m.name(), e.getMessage());
                notes.add(ProcessingNote.fail("TMDB_ERROR", String.valueOf(e.getMessage())));
                return null;
            }
        }

        /** Parent folders of the file inside the source root, nearest first, generic names skipped. */
        private List<String> releaseFolders() {
            String rootValue = configService.getOrDefault(AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
            if (StringUtils.isBlank(rootValue)) return List.of();
            Path root = Paths.get(rootValue).toAbsolutePath().normalize();
            List<String> result = new ArrayList<>();
            for (Path dir = source.toAbsolutePath().normalize().getParent();
                    dir != null && !dir.equals(root) && dir.startsWith(root) && result.size() < MAX_FOLDERS;
                    dir = dir.getParent()) {
                String name = dir.getFileName().toString();
                if (!GENERIC_FOLDERS.contains(name.toLowerCase(Locale.ROOT)) && !result.contains(name)) {
                    result.add(name);
                }
            }
            return result;
        }

        private Resolution llmUnavailableFailure() {
            return Resolution.failed(MediaFileStatus.LLM_FAILED, "LLM: " + lastLlmResult.error());
        }

        private Resolution failure() {
            if (known != null || !triedNames.isEmpty()) {
                return Resolution.failed(MediaFileStatus.TMDB_FAILED,
                        "TMDB: no match for " + String.join(", ", triedNames.stream().distinct().map(n -> "'" + n + "'").toList()));
            }
            if (lastLlmResult != null && lastLlmResult.isShow() && !lastLlmResult.isError()) {
                return Resolution.failed(MediaFileStatus.LLM_FAILED,
                        "LLM: TV show is missing season or episode — cannot rename without S/E");
            }
            String reason = lastLlmResult != null && lastLlmResult.isError()
                    ? lastLlmResult.error() : "could not extract a title";
            return Resolution.failed(MediaFileStatus.LLM_FAILED, "LLM: " + reason);
        }
    }

    static boolean matchesExactly(String candidate, TmdbResult result) {
        String wanted = TitleVariants.normalize(candidate);
        return wanted.equals(TitleVariants.normalize(result.name()))
                || wanted.equals(TitleVariants.normalize(result.originalName()));
    }

    private static String key(MediaMetadata m) {
        return m.type() + "|" + TitleVariants.normalize(m.name()) + "|" + StringUtils.defaultString(m.year());
    }

    private static String describe(MediaMetadata m) {
        StringBuilder s = new StringBuilder().append(m.type()).append(" \"").append(m.name()).append('"');
        if (StringUtils.isNotBlank(m.year())) s.append(" (").append(m.year()).append(')');
        if (m.isShow()) s.append(' ').append(StringUtils.defaultString(m.season(), "S?"))
                .append(StringUtils.defaultString(m.episode(), "E?"));
        return s.toString();
    }
}
