package net.melodify.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses LRC ("[mm:ss.xx] text") synchronized lyrics. Understands multiple stamps per line and [offset:]. */
public final class LrcParser {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern WORD_STAMP = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    public static final String BREAK = "♪"; // musical note, shown for instrumental gaps

    private LrcParser() {}

    public static List<LyricLine> parse(String lrc) {
        if (lrc == null || lrc.isBlank()) return List.of();
        long offset = 0;
        Matcher om = OFFSET.matcher(lrc);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (NumberFormatException ignored) {
                // keep 0
            }
        }

        List<LyricLine> out = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            String line = raw.trim();
            Matcher m = STAMP.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(toMs(m));
                end = m.end();
            }
            if (times.isEmpty()) continue; // metadata tag such as [ar:...] or a stray line
            String text = WORD_STAMP.matcher(line.substring(end)).replaceAll("").trim();
            if (text.isEmpty()) text = BREAK;
            for (long t : times) out.add(new LyricLine(Math.max(0L, t - offset), text));
        }
        out.sort(Comparator.comparingLong(LyricLine::timeMs));

        // Collapse runs of break markers and reject files that contain nothing but breaks.
        List<LyricLine> cleaned = new ArrayList<>();
        boolean anyText = false;
        for (LyricLine l : out) {
            boolean isBreak = l.text().equals(BREAK);
            if (!isBreak) anyText = true;
            if (isBreak && !cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).text().equals(BREAK)) continue;
            cleaned.add(l);
        }
        return anyText ? List.copyOf(cleaned) : List.of();
    }

    private static long toMs(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        long ms = 0;
        String f = m.group(3);
        if (f != null) {
            long v = Long.parseLong(f);
            ms = switch (f.length()) {
                case 1 -> v * 100;
                case 2 -> v * 10;
                default -> v;
            };
        }
        return (min * 60 + sec) * 1000 + ms;
    }
}
