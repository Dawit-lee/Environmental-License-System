package com.envpermit.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Result lines of the {@code measure} task: stdout and build/measurements/results.txt (docs/measurements/). */
public final class Measure {

    private static final Path OUT = Path.of("build/measurements/results.txt");

    private Measure() {
    }

    public static synchronized void log(String format, Object... args) {
        String line = format.formatted(args);
        System.out.println("[MEASURE] " + line);
        try {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "중앙값 12.3ms (최소 11.0 ~ 최대 14.2, 5회)" */
    public static String millis(long[] nanos) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return "중앙값 %.1fms (최소 %.1f ~ 최대 %.1f, %d회)".formatted(s[s.length / 2] / 1e6, s[0] / 1e6,
                s[s.length - 1] / 1e6, s.length);
    }

    public static String pct(int part, int total) {
        return "%d/%d (%.1f%%)".formatted(part, total, total == 0 ? 0.0 : 100.0 * part / total);
    }
}
