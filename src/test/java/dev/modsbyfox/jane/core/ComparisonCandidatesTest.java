package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComparisonCandidatesTest {
    private static final String REQUIRED = "a".repeat(128);
    private static final String OLD = "b".repeat(128);
    private static final String OTHER = "c".repeat(128);
    private static final ManifestEntry TARGET = new ManifestEntry("example", "Example", "2.0", 100, REQUIRED);
    private static final RequiredManifest MANIFEST = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(TARGET));

    private static Comparison.LocalMod local(String filename, String version) {
        return new Comparison.LocalMod("example", version, Path.of(filename));
    }

    @Test void exactAmongDuplicatesIsSelectedAndOthersAreRetained() throws Exception {
        var old = local("a-old.jar", "1.0");
        var exact = local("z-exact.jar", "2.0");
        var result = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(exact, old)),
                path -> path.equals(exact.jar()) ? REQUIRED : OLD).get(0);
        assertEquals(Comparison.Status.OK, result.status());
        assertEquals(exact, result.local());
        assertEquals(List.of(old), result.retainedCandidates());
        assertNull(result.localIssue());
        assertTrue(Comparison.passed(List.of(result)));
        assertEquals(1, ResolutionPlan.resolve(List.of(result), target -> {
            fail("Exact duplicate must not start source lookup");
            return Optional.empty();
        }).count(ResolutionPlan.Availability.ALREADY_PRESENT));
    }

    @Test void twoExactCopiesChooseDeterministicFilename() {
        var first = local("a-exact.jar", "2.0");
        var second = local("z-exact.jar", "2.0");
        var result = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(second, first)),
                path -> REQUIRED).get(0);
        assertEquals(Comparison.Status.OK, result.status());
        assertEquals(first, result.local());
        assertEquals(List.of(second), result.retainedCandidates());
    }

    @Test void duplicateWithoutExactIsMissingAndLooksUpRequiredHash() throws Exception {
        var first = local("a-old.jar", "1.0");
        var second = local("z-old.jar", "1.1");
        var result = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(second, first)),
                path -> path.equals(first.jar()) ? OLD : OTHER).get(0);
        assertEquals(Comparison.Status.MISSING, result.status());
        assertNull(result.local());
        assertNull(result.localHash());
        assertEquals(List.of(first, second), result.retainedCandidates());
        assertNull(result.localIssue());
        ResolutionPlan plan = ResolutionPlan.resolve(List.of(result), target -> {
            assertEquals(REQUIRED, target.sha512());
            return Optional.of(new ResolutionPlan.Source("a-old.jar",
                    URI.create("https://cdn.modrinth.com/test.jar"), target.fileSize()));
        });
        assertEquals(1, plan.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
        assertEquals(0, plan.count(ResolutionPlan.Availability.LOCAL_ERROR));
    }

    @Test void unreadableCandidateFailsClosedEvenIfAnotherIsExact() throws Exception {
        var exact = local("a-exact.jar", "2.0");
        var unreadable = local("z-unreadable.jar", "1.0");
        var result = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(exact, unreadable)),
                path -> {
                    if (path.equals(unreadable.jar())) throw new IOException("unreadable");
                    return REQUIRED;
                }).get(0);
        assertEquals(Comparison.Status.FILE_ERROR, result.status());
        assertEquals(Comparison.ProblemKind.HASH_FAILED, result.localIssue().kind());
        assertEquals(1, ResolutionPlan.resolve(List.of(result), target -> {
            fail("Hash failure must not trigger source lookup");
            return Optional.empty();
        }).count(ResolutionPlan.Availability.LOCAL_ERROR));
    }

    @Test void oneCandidateStillReplacesVersionAndHashMismatches() {
        var old = local("old.jar", "1.0");
        var version = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(old)),
                path -> OLD).get(0);
        assertEquals(Comparison.Status.VERSION_MISMATCH, version.status());
        assertEquals(old, version.local());
        var modified = local("modified.jar", "2.0");
        var hash = Comparison.compareCandidates(MANIFEST, Map.of("example", List.of(modified)),
                path -> OLD).get(0);
        assertEquals(Comparison.Status.HASH_MISMATCH, hash.status());
        assertEquals(modified, hash.local());
        assertTrue(version.retainedCandidates().isEmpty());
        assertTrue(hash.retainedCandidates().isEmpty());
    }
}
