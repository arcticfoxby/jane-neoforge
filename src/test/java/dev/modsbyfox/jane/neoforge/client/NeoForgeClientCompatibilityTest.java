package dev.modsbyfox.jane.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.neoforge.ClientRequirementClassifier;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoForgeClientCompatibilityTest {
    private static final String HASH = "a".repeat(128);

    @TempDir Path gameDir;

    @Test
    void onlyCompleteClientEntrypointsAreExplicitAndHashed() {
        AtomicInteger hashes = new AtomicInteger();
        var report = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(
                jar("clientonly", true, ClientRequirementClassifier.EntrypointSide.CLIENT),
                jar("universal", true, ClientRequirementClassifier.EntrypointSide.DEFAULT),
                jar("uncertain", false, ClientRequirementClassifier.EntrypointSide.CLIENT)), path -> {
                    hashes.incrementAndGet();
                    return HASH;
                });
        assertEquals(1, hashes.get());
        assertEquals(List.of("clientonly"), report.explicitClientMods().stream().map(mod -> mod.modId()).toList());
        assertEquals(HASH, report.explicitClientMods().get(0).sha512());
        assertEquals(List.of("universal", "uncertain"),
                report.otherExtraMods().stream().map(mod -> mod.modId()).toList());
        assertNull(report.otherExtraMods().get(0).sha512());
    }

    @Test
    void requiredPhysicalJarIsNotReportedAsExtraAndHashFailureStaysVisible() {
        var manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("needed", "Needed", "1", 3, HASH)));
        var report = NeoForgeClientCompatibility.classify(gameDir, manifest, List.of(
                jar("needed", true, ClientRequirementClassifier.EntrypointSide.CLIENT),
                jar("broken", true, ClientRequirementClassifier.EntrypointSide.CLIENT)),
                path -> { throw new IOException("Unreadable extra"); });
        assertEquals(1, report.explicitClientMods().size());
        assertEquals("broken", report.explicitClientMods().get(0).modId());
        assertNull(report.explicitClientMods().get(0).sha512());
    }

    private NeoForgePhysicalDiscovery.PhysicalJar jar(String id, boolean complete,
            ClientRequirementClassifier.EntrypointSide side) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(gameDir.resolve("mods").resolve(id + ".jar"),
                List.of(id), id, id, "1", new ClientRequirementClassifier.FmlEvidence(complete, List.of(side)));
    }

    private static RequiredManifest empty() {
        return new RequiredManifest(RequiredManifest.PROTOCOL, List.of());
    }
}
