package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded cache for expensive analysis of a top-level physical mod JAR.
 *
 * <p>The caller must calculate {@code exactSha512} from the complete JAR bytes on
 * every request and verify that its size and modification time stayed stable
 * while hashing. Size and modification time alone must never be used as a cache
 * identity. A cache hit skips only the expensive analysis and independently
 * rechecks the exact hash before returning, closing the gap after the caller's
 * hash was calculated.
 * Values supplied by the analyzer must be immutable.
 */
public final class PhysicalJarAnalysisCache<T> {
    private static final int MAX_ENTRIES = 512;
    private static final Pattern SHA512 = Pattern.compile("[0-9a-f]{128}");

    @FunctionalInterface
    public interface Analyzer<T> {
        T analyze(Path canonicalJar) throws IOException;
    }

    private record Key(Path canonicalJar, String sha512, String configIdentity, String ruleVersion) { }

    private final int maxEntries;
    private final Map<Key, T> entries = new LinkedHashMap<>(16, 0.75f, true);

    public PhysicalJarAnalysisCache(int maxEntries) {
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES)
            throw new IllegalArgumentException("Analysis cache must have between 1 and 512 entries");
        this.maxEntries = maxEntries;
    }

    /**
     * Returns analysis for the verified content snapshot. The caller's full
     * SHA-512 is part of the key, so same-size, same-time replacements miss.
     * Analysis is serialized to avoid duplicate scans and bound in-flight work.
     */
    public synchronized T getOrAnalyze(Path gameDir, Path jar, String exactSha512,
                                        long expectedSize, FileTime expectedMtime,
                                        String configIdentity, String analyzerRuleVersion,
                                        Analyzer<T> analyzer) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(expectedMtime, "expectedMtime");
        Objects.requireNonNull(analyzer, "analyzer");
        if (exactSha512 == null || !SHA512.matcher(exactSha512).matches())
            throw new IllegalArgumentException("Invalid exact SHA-512");
        validateIdentity(configIdentity, "configIdentity");
        validateIdentity(analyzerRuleVersion, "analyzerRuleVersion");
        if (expectedSize <= 0) throw new IllegalArgumentException("Invalid JAR size");

        Path canonicalJar = PathSafety.existingJarInMods(gameDir, jar);
        verifySnapshot(gameDir, canonicalJar, expectedSize, expectedMtime);
        Key key = new Key(canonicalJar, exactSha512, configIdentity, analyzerRuleVersion);
        T cached = entries.get(key);
        if (cached != null) {
            try {
                verifyExactSnapshot(gameDir, canonicalJar, exactSha512, expectedSize, expectedMtime);
            } catch (IOException changed) {
                entries.remove(key);
                throw changed;
            }
            return cached;
        }

        T analyzed = Objects.requireNonNull(analyzer.analyze(canonicalJar), "analysis result");
        verifyExactSnapshot(gameDir, canonicalJar, exactSha512, expectedSize, expectedMtime);

        entries.put(key, analyzed);
        while (entries.size() > maxEntries) {
            Iterator<Key> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return analyzed;
    }

    private static void validateIdentity(String identity, String label) {
        if (identity == null || identity.isBlank() || identity.length() > 256)
            throw new IllegalArgumentException("Invalid " + label);
    }

    private static void verifyExactSnapshot(Path gameDir, Path canonicalJar, String expectedSha512,
                                            long expectedSize, FileTime expectedMtime) throws IOException {
        verifySnapshot(gameDir, canonicalJar, expectedSize, expectedMtime);
        if (!expectedSha512.equals(Hashing.sha512(canonicalJar)))
            throw new IOException("Physical mod JAR changed during analysis");
        verifySnapshot(gameDir, canonicalJar, expectedSize, expectedMtime);
    }

    private static void verifySnapshot(Path gameDir, Path canonicalJar, long expectedSize,
                                       FileTime expectedMtime) throws IOException {
        if (!canonicalJar.equals(PathSafety.existingJarInMods(gameDir, canonicalJar)))
            throw new IOException("Physical mod JAR origin changed during analysis");
        BasicFileAttributes attributes = Files.readAttributes(canonicalJar,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expectedSize
                || !attributes.lastModifiedTime().equals(expectedMtime))
            throw new IOException("Physical mod JAR changed during analysis");
    }
}
