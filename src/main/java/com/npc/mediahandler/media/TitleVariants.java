package com.npc.mediahandler.media;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives title candidates from a raw release name without the LLM: the words before the first
 * episode marker, year or technical tag, then the same with words dropped at the front (release
 * group prefix like {@code jajunge-south.park.s23e02}) or the end. Candidates are only safe to use
 * with an exact TMDB title match — they are guesses.
 */
public final class TitleVariants {

    /** Parsed pieces of one raw name. {@code season}/{@code episode} are set only for an episode marker. */
    public record Parsed(List<String> titleWords, String year, String season, String episode) {
        public boolean isShow() {
            return season != null && episode != null;
        }
    }

    private static final Pattern EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?!\\d)|(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])");
    private static final Pattern SEASON_ONLY = Pattern.compile("(?i)s\\d{1,2}");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern RESOLUTION = Pattern.compile("(?i)\\d{3,4}[pi]|4k|8k");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s._\\-\\[\\]()+]+");

    /** Tokens that are never part of a title and mark where the technical part begins. */
    private static final Set<String> TECH_TAGS = Set.of(
            "german", "ger", "deutsch", "english", "eng", "multi", "dual", "dl", "ml", "dubbed", "subbed",
            "uhd", "hdr", "hdr10", "dv", "sdr", "bluray", "bdrip", "brrip", "bd", "web", "webrip", "webdl",
            "hdtv", "dvdrip", "dvd", "remux", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid",
            "aac", "ac3", "ac3d", "eac3", "dts", "dtshd", "truehd", "atmos", "dd", "ddp", "dd5", "ddp5",
            "proper", "repack", "internal", "readnfo");

    private static final int MAX_DROPPED_WORDS = 2;

    private TitleVariants() {}

    public static Parsed parse(String rawName) {
        String season = null;
        String episode = null;
        Matcher ep = EPISODE.matcher(rawName);
        if (ep.find()) {
            String s = ep.group(1) != null ? ep.group(1) : ep.group(3);
            String e = ep.group(2) != null ? ep.group(2) : ep.group(4);
            season  = "S%02d".formatted(Integer.parseInt(s));
            episode = "E%02d".formatted(Integer.parseInt(e));
        }

        String[] tokens = SEPARATORS.split(rawName.strip());
        List<String> words = new ArrayList<>();
        String year = null;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            boolean isYear = YEAR.matcher(token).matches();
            // A year as the first word is a title ("1917", "2012")
            if (isYear && !words.isEmpty()) {
                year = token;
                break;
            }
            if (EPISODE.matcher(token).matches() || SEASON_ONLY.matcher(token).matches()
                    || RESOLUTION.matcher(token).matches() || TECH_TAGS.contains(lower)) {
                break;
            }
            // "s23e02" inside a token that the separators didn't split
            if (i > 0 && EPISODE.matcher(token).find()) break;
            words.add(lower);
        }
        return new Parsed(List.copyOf(words), year, season, episode);
    }

    /**
     * Title candidates, most complete first: all words, then with 1..{@value MAX_DROPPED_WORDS}
     * words dropped (front before end, because release group prefixes are more common than
     * suffixes). A single word is only offered when the title has at most two words.
     */
    public static List<String> candidates(Parsed parsed) {
        List<String> words = parsed.titleWords();
        int n = words.size();
        Set<String> result = new LinkedHashSet<>();
        for (int dropped = 0; dropped <= MAX_DROPPED_WORDS; dropped++) {
            for (int front = dropped; front >= 0; front--) {
                int end = dropped - front;
                int length = n - front - end;
                if (length < 1 || (length == 1 && n > 2)) continue;
                result.add(String.join(" ", words.subList(front, n - end)));
            }
        }
        return List.copyOf(result);
    }

    /** Lowercase, accents and punctuation removed, single spaces — for exact title comparison. */
    public static String normalize(String title) {
        if (title == null) return "";
        String decomposed = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }
}
