package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.modsbyfox.jane.core.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalJarAnalysisCacheTest {
    @TempDir Path gameDir;

    @Test
    void exactHashInvalidatesSameSizeAndMtimeReplacement() throws IOException {
        Path jar = jar("AAAA");
        FileTime originalTime = Files.getLastModifiedTime(jar);
        var cache = new PhysicalJarAnalysisCache<String>(4);
        AtomicInteger scans = new AtomicInteger();

        assertEquals("scan-1", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals("scan-1", analyze(cache, jar, "config-a", "rules-1", scans));
        Files.writeString(jar, "BBBB");
        Files.setLastModifiedTime(jar, originalTime);
        assertEquals(4, Files.size(jar));
        assertEquals(originalTime, Files.getLastModifiedTime(jar));

        assertEquals("scan-2", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals(2, scans.get());
    }

    @Test
    void cachedOldHashRejectsSameSizeAndMtimeReplacementAfterCallerHash() throws IOException {
        Path jar = jar("AAAA");
        FileTime originalTime = Files.getLastModifiedTime(jar);
        String originalHash = Hashing.sha512(jar);
        var cache = new PhysicalJarAnalysisCache<String>(4);
        AtomicInteger scans = new AtomicInteger();

        assertEquals("scan-1", cache.getOrAnalyze(gameDir, jar, originalHash, 4,
                originalTime, "config-a", "rules-1", canonical -> "scan-" + scans.incrementAndGet()));
        Files.writeString(jar, "BBBB");
        Files.setLastModifiedTime(jar, originalTime);
        assertEquals(4, Files.size(jar));
        assertEquals(originalTime, Files.getLastModifiedTime(jar));

        assertThrows(IOException.class, () -> cache.getOrAnalyze(gameDir, jar, originalHash, 4,
                originalTime, "config-a", "rules-1", canonical -> "scan-" + scans.incrementAndGet()));
        assertEquals(1, scans.get());
        assertEquals("scan-2", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals(2, scans.get());
    }

    @Test
    void configAndRuleIdentityInvalidateAnalysisAndLruIsBounded() throws IOException {
        Path jar = jar("AAAA");
        var cache = new PhysicalJarAnalysisCache<String>(2);
        AtomicInteger scans = new AtomicInteger();

        assertEquals("scan-1", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals("scan-2", analyze(cache, jar, "config-b", "rules-1", scans));
        assertEquals("scan-2", analyze(cache, jar, "config-b", "rules-1", scans));
        assertEquals("scan-3", analyze(cache, jar, "config-b", "rules-2", scans));
        assertEquals("scan-4", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals(4, scans.get());
    }

    @Test
    void changedBytesDuringAnalysisFailClosedWithoutCaching() throws IOException {
        Path jar = jar("AAAA");
        FileTime originalTime = Files.getLastModifiedTime(jar);
        String originalHash = Hashing.sha512(jar);
        var cache = new PhysicalJarAnalysisCache<String>(2);

        assertThrows(IOException.class, () -> cache.getOrAnalyze(gameDir, jar, originalHash, 4,
                originalTime, "config-a", "rules-1", canonical -> {
                    Files.writeString(canonical, "BBBB");
                    Files.setLastModifiedTime(canonical, originalTime);
                    return "stale";
                }));

        Files.writeString(jar, "AAAA");
        Files.setLastModifiedTime(jar, originalTime);
        AtomicInteger scans = new AtomicInteger();
        assertEquals("scan-1", analyze(cache, jar, "config-a", "rules-1", scans));
        assertEquals(1, scans.get());
    }

    @Test
    void rejectsUnverifiedOriginAndUnboundedCapacity() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new PhysicalJarAnalysisCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalJarAnalysisCache<>(513));
        Path outside = Files.writeString(gameDir.resolve("outside.jar"), "AAAA");
        Files.createDirectory(gameDir.resolve("mods"));
        var cache = new PhysicalJarAnalysisCache<String>(2);
        assertThrows(IOException.class, () -> cache.getOrAnalyze(gameDir, outside, Hashing.sha512(outside),
                Files.size(outside), Files.getLastModifiedTime(outside), "config-a", "rules-1", path -> "unsafe"));
    }

    private String analyze(PhysicalJarAnalysisCache<String> cache, Path jar,
                           String configIdentity, String ruleVersion, AtomicInteger scans) throws IOException {
        return cache.getOrAnalyze(gameDir, jar, Hashing.sha512(jar), Files.size(jar),
                Files.getLastModifiedTime(jar), configIdentity, ruleVersion,
                canonical -> "scan-" + scans.incrementAndGet());
    }

    private Path jar(String content) throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        return Files.writeString(mods.resolve("example.jar"), content);
    }
}
