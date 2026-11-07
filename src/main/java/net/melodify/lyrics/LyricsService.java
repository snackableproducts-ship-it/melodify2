package net.melodify.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.melodify.spotify.PlaybackState;
import net.melodify.util.Http;
import net.melodify.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetches lyrics from LRCLIB (https://lrclib.net) - a free, open, community-maintained lyrics database
 * with a public API that needs no key and explicitly offers synchronized (LRC) lyrics.
 *
 * Wrong-song protection: a candidate is only accepted when the artist matches AND the duration is
 * within 3 seconds AND the title matches (a differently-spelled title - e.g. a translated or romanized
 * one - is only accepted if artist matches and the duration is within half a second).
 *
 * Results are cached in memory (LRU) and positive results on disk, so each song is fetched once.
 * Call {@link #fetch} from a background thread only.
 */
public final class LyricsService {
    private static final Logger LOG = LoggerFactory.getLogger("Melodify/Lyrics");
    private static final String BASE = "https://lrclib.net/api/";
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Melodify/1.0 (Minecraft Fabric mod)",
            "Accept", "application/json");
    private static final double MAX_DURATION_DIFF_SEC = 3.0;
    private static final double STRICT_DURATION_DIFF_SEC = 0.5;
    private static final int CACHE_VERSION = 1;

    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^]]*]");
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "\\s+-\\s+.*(remaster|live|version|mono|stereo|edit|mix|deluxe|soundtrack|from|\\d{4}).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}+");

    private final Path cacheDir;
    private final Map<String, Lyrics> memory = Collections.synchronizedMap(new LinkedHashMap<String, Lyrics>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
            return size() > 200;
        }
    });

    public LyricsService(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Returns lyrics for the track (possibly Lyrics.NONE). Throws IOException on network/server errors. */
    public Lyrics fetch(PlaybackState t) throws IOException {
        String key = t.key();
        Lyrics hit = memory.get(key);
        if (hit != null) return hit;
        Lyrics disk = readDisk(key);
        if (disk != null) {
            memory.put(key, disk);
            return disk;
        }
        Lyrics result = lookup(t);
        memory.put(key, result);
        // "not found" is deliberately not written to disk, so lyrics added to LRCLIB later get picked up.
        if (result.kind() != Lyrics.Kind.NONE) writeDisk(key, result);
        return result;
    }

    // ------------------------------------------------------------------ lookup

    private static final class Candidate {
        final JsonObject o;
        final boolean exact;
        double score;

        Candidate(JsonObject o, boolean exact) {
            this.o = o;
            this.exact = exact;
        }

        boolean hasSynced() { return !Json.str(o, "syncedLyrics").isBlank(); }

        boolean hasPlain() { return !Json.str(o, "plainLyrics").isBlank(); }

        boolean hasAnything() { return hasSynced() || hasPlain() || Json.bool(o, "instrumental"); }
    }

    private Lyrics lookup(PlaybackState t) throws IOException {
        double trackSec = t.durationMs() / 1000.0;
        List<Candidate> cands = new ArrayList<>();

        // 1) Exact metadata lookup (LRCLIB matches artist/title/album/duration itself).
        JsonObject exact = getExact(t.title(), t.artist(), t.album(), trackSec);
        if (exact == null && !t.album().isBlank()) exact = getExact(t.title(), t.artist(), "", trackSec);
        if (exact == null && !t.firstArtist().isBlank() && !t.firstArtist().equals(t.artist())) {
            exact = getExact(t.title(), t.firstArtist(), "", trackSec);
        }
        if (exact != null && durationDiff(exact, trackSec) <= MAX_DURATION_DIFF_SEC) {
            Candidate c = new Candidate(exact, true);
            if (c.hasAnything()) cands.add(c);
        }

        // 2) Fuzzy search, filtered strictly, only if the exact lookup gave nothing usable.
        if (cands.isEmpty()) {
            String q = (cleanTitle(t.title()) + " " + t.firstArtist()).trim();
            JsonArray results = search(q);
            int n = 0;
            for (JsonElement e : results) {
                if (++n > 20 || !e.isJsonObject()) continue;
                Candidate c = new Candidate(e.getAsJsonObject(), false);
                if (c.hasAnything() && accept(c.o, t, trackSec)) cands.add(c);
            }
        }

        for (Candidate c : cands) c.score = score(c, t, trackSec);
        cands.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());

        for (Candidate c : cands) {
            if (!c.hasSynced()) continue;
            List<LyricLine> lines = LrcParser.parse(Json.str(c.o, "syncedLyrics"));
            if (!lines.isEmpty()) return new Lyrics(Lyrics.Kind.SYNCED, lines, "LRCLIB");
        }
        for (Candidate c : cands) {
            if (!c.hasPlain()) continue;
            Lyrics u = unsynced(Json.str(c.o, "plainLyrics"), t.durationMs());
            if (u != null) return u;
        }
        for (Candidate c : cands) {
            if (Json.bool(c.o, "instrumental")) return Lyrics.INSTRUMENTAL;
        }
        return Lyrics.NONE;
    }

    private JsonObject getExact(String title, String artist, String album, double durSec) throws IOException {
        StringBuilder u = new StringBuilder(BASE).append("get?track_name=").append(enc(title))
                .append("&artist_name=").append(enc(artist));
        if (!album.isBlank()) u.append("&album_name=").append(enc(album));
        if (durSec >= 1.0) u.append("&duration=").append(Math.round(durSec));
        Http.Response r = Http.get(u.toString(), HEADERS);
        if (r.status() == 200) return JsonParser.parseString(r.body()).getAsJsonObject();
        if (r.status() == 404 || r.status() == 400) return null;
        throw new IOException("LRCLIB HTTP " + r.status());
    }

    private JsonArray search(String q) throws IOException {
        Http.Response r = Http.get(BASE + "search?q=" + enc(q), HEADERS);
        if (r.status() == 200) {
            JsonElement e = JsonParser.parseString(r.body());
            return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        }
        if (r.status() == 404 || r.status() == 400) return new JsonArray();
        throw new IOException("LRCLIB HTTP " + r.status());
    }

    // ------------------------------------------------------------------ matching

    static boolean accept(JsonObject o, PlaybackState t, double trackSec) {
        String candArtist = norm(Json.str(o, "artistName"));
        boolean artistOk = overlaps(candArtist, norm(t.firstArtist())) || overlaps(candArtist, norm(t.artist()));
        if (!artistOk) return false;
        double diff = durationDiff(o, trackSec);
        if (diff > MAX_DURATION_DIFF_SEC) return false;
        String candTitle = normTitle(firstNonEmpty(Json.str(o, "trackName"), Json.str(o, "name")));
        boolean titleOk = titleMatch(candTitle, normTitle(t.title()));
        return titleOk || (diff >= 0 && diff <= STRICT_DURATION_DIFF_SEC);
    }

    private static double score(Candidate c, PlaybackState t, double trackSec) {
        double s = c.exact ? 100 : 0;
        String candTitle = normTitle(firstNonEmpty(Json.str(c.o, "trackName"), Json.str(c.o, "name")));
        if (titleMatch(candTitle, normTitle(t.title()))) s += 20;
        if (!t.album().isBlank() && norm(Json.str(c.o, "albumName")).equals(norm(t.album()))) s += 5;
        double diff = durationDiff(c.o, trackSec);
        if (diff >= 0) s -= diff * 2;
        return s;
    }

    /** Absolute duration difference in seconds, or 0 when either side's duration is unknown. */
    private static double durationDiff(JsonObject o, double trackSec) {
        double d = Json.dbl(o, "duration");
        if (d <= 0 || trackSec <= 0) return 0.0;
        return Math.abs(d - trackSec);
    }

    static boolean titleMatch(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int lo = Math.min(a.length(), b.length());
        int hi = Math.max(a.length(), b.length());
        return lo >= 4 && (a.contains(b) || b.contains(a)) && (double) lo / hi >= 0.7;
    }

    private static boolean overlaps(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    /** Lowercase, accent-insensitive, letters+digits only (keeps every script, so CJK etc. still compare). */
    static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        d = MARKS.matcher(d).replaceAll("");
        d = Normalizer.normalize(d, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(d).replaceAll("");
    }

    static String normTitle(String title) {
        return norm(cleanTitle(title));
    }

    /** Strips "(feat. X)", "[Live]", " - 2011 Remaster" and similar decorations. */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String s = BRACKETS.matcher(title).replaceAll(" ");
        s = VERSION_SUFFIX.matcher(s).replaceAll("");
        return s.trim().replaceAll("\\s{2,}", " ");
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ unsynced fallback

    /** Spreads plain lyrics evenly over the track. Timing is an estimate and flagged as UNSYNCED. */
    private static Lyrics unsynced(String plain, long durationMs) {
        List<String> lines = new ArrayList<>();
        for (String l : plain.split("\\R")) {
            String s = l.trim();
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.isEmpty()) return null;
        long total = durationMs > 0 ? durationMs : 180_000L;
        long start = Math.min(8_000L, (long) (total * 0.06));
        long end = (long) (total * 0.94);
        List<LyricLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(new LyricLine(start + (end - start) * i / lines.size(), lines.get(i)));
        }
        return new Lyrics(Lyrics.Kind.UNSYNCED, List.copyOf(out), "LRCLIB");
    }

    // ------------------------------------------------------------------ disk cache

    private Path cacheFile(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            return cacheDir.resolve(HexFormat.of().formatHex(h) + ".json");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Lyrics readDisk(String key) {
        try {
            Path f = cacheFile(key);
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (Json.lng(o, "v") != CACHE_VERSION) return null;
            Lyrics.Kind kind = Lyrics.Kind.valueOf(Json.str(o, "kind"));
            if (kind == Lyrics.Kind.INSTRUMENTAL) return Lyrics.INSTRUMENTAL;
            List<LyricLine> lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonArray p = e.getAsJsonArray();
                lines.add(new LyricLine(p.get(0).getAsLong(), p.get(1).getAsString()));
            }
            return lines.isEmpty() ? null : new Lyrics(kind, List.copyOf(lines), Json.str(o, "source"));
        } catch (Exception e) {
            return null; // corrupt cache entry: just refetch
        }
    }

    private void writeDisk(String key, Lyrics l) {
        try {
            Files.createDirectories(cacheDir);
            JsonObject o = new JsonObject();
            o.addProperty("v", CACHE_VERSION);
            o.addProperty("kind", l.kind().name());
            o.addProperty("source", l.source());
            JsonArray arr = new JsonArray();
            for (LyricLine line : l.lines()) {
                JsonArray p = new JsonArray();
                p.add(line.timeMs());
                p.add(line.text());
                arr.add(p);
            }
            o.add("lines", arr);
            Files.writeString(cacheFile(key), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Could not write lyrics cache: {}", e.toString());
        }
    }
}
