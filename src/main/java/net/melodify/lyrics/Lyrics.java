package net.melodify.lyrics;

import java.util.List;

/**
 * Lyrics for one track. SYNCED lines carry real timestamps. UNSYNCED lines come from plain-text lyrics
 * and have estimated timestamps spread across the track (clearly flagged in the GUI).
 */
public record Lyrics(Kind kind, List<LyricLine> lines, String source) {
    public enum Kind { SYNCED, UNSYNCED, INSTRUMENTAL, NONE }

    public static final Lyrics NONE = new Lyrics(Kind.NONE, List.of(), "");
    public static final Lyrics INSTRUMENTAL = new Lyrics(Kind.INSTRUMENTAL, List.of(), "");

    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** Index of the last line whose time is <= positionMs, or -1 if the song hasn't reached the first line. */
    public int indexAt(long positionMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
