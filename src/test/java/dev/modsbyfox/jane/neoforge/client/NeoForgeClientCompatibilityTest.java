package dev.modsbyfox.jane.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.neoforge.ClientRequirementClassifier;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
    void onlyCompleteDedicatedServerEntrypointsAreHashedAndRemainBlockedWithoutDependencyEvidence() {
        AtomicInteger hashes = new AtomicInteger();
        var report = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(
                jar("dedicated", true, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER),
                jar("incomplete", false, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER),
                jar("mixed", true, ClientRequirementClassifier.EntrypointSide.CLIENT,
                        ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER),
                jar("noentry", true)), path -> {
                    hashes.incrementAndGet();
                    return HASH;
                });
        assertEquals(1, hashes.get());
        assertEquals(List.of("dedicated"), report.declaredServerOnlyMods().stream()
                .map(mod -> mod.modId()).toList());
        assertEquals(ClientCompatibilityReport.Kind.EXPLICIT_SERVER,
                report.declaredServerOnlyMods().getFirst().kind());
        assertEquals(HASH, report.declaredServerOnlyMods().getFirst().sha512());
        assertFalse(report.declaredServerOnlyMods().getFirst().cleanupEligible());
        assertEquals(List.of("incomplete", "mixed", "noentry"), report.unclassifiedExtraMods().stream()
                .map(mod -> mod.modId()).toList());
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

    @Test
    void verifiedDedicatedJarIsPreselectedOnlyWithCompleteDependencyEvidence() {
        var graph = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "dedicated.jar", List.of("dedicated"), List.of(), true, HASH)), List.of(), true);
        var report = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(
                jar("dedicated", true, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)),
                path -> HASH, graph);
        var extra = report.declaredServerOnlyMods().getFirst();
        assertTrue(extra.cleanupEligible());
        assertTrue(extra.verified());
        assertEquals(HASH, extra.sha512());
        assertEquals(List.of("dedicated"), extra.containedModIds());
        assertNull(extra.cleanupBlockedReason());
    }

    @Test
    void missingObservedGraphHashBlocksServerCleanup() {
        var graph = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "dedicated.jar", List.of("dedicated"), List.of(), true)), List.of(), true);
        var report = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(
                jar("dedicated", true, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)),
                path -> HASH, graph);
        var extra = report.declaredServerOnlyMods().getFirst();
        assertNull(extra.sha512());
        assertFalse(extra.cleanupEligible());
        assertEquals("STALE_FILE", extra.cleanupBlockedReason());
    }

    @Test
    void changedAfterGraphInspectionLosesServerCleanupHash() {
        var graph = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "dedicated.jar", List.of("dedicated"), List.of(), true, "b".repeat(128))),
                List.of(), true);
        var report = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(
                jar("dedicated", true, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)),
                path -> HASH, graph);
        var extra = report.declaredServerOnlyMods().getFirst();
        assertNull(extra.sha512());
        assertFalse(extra.cleanupEligible());
        assertEquals("STALE_FILE", extra.cleanupBlockedReason());
    }

    @Test
    void anyRequiredIdInMultiModJarExcludesTheWholePhysicalFile() {
        var manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("second", "Second", "1", 3, HASH)));
        var multi = new NeoForgePhysicalDiscovery.PhysicalJar(gameDir.resolve("mods").resolve("multi.jar"),
                List.of("first", "second"), "first", "First", "1",
                new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER,
                                ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)));
        AtomicInteger hashes = new AtomicInteger();
        var report = NeoForgeClientCompatibility.classify(gameDir, manifest, List.of(multi), path -> {
            hashes.incrementAndGet();
            return HASH;
        });
        assertTrue(report.declaredServerOnlyMods().isEmpty());
        assertEquals(0, hashes.get());
    }

    @Test
    void currentSmallServerJarMatchesFmlAndStaleServerSideIsDowngraded() throws IOException {
        Path physicalPath = writeEntrypointJar(true);
        var staleFml = new NeoForgePhysicalDiscovery.PhysicalJar(physicalPath,
                List.of("dedicated"), "dedicated", "Dedicated", "1",
                new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)));
        var matching = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(staleFml));
        assertEquals(List.of("dedicated"), matching.declaredServerOnlyMods().stream()
                .map(ClientCompatibilityReport.ExtraMod::modId).toList());
        assertTrue(matching.declaredServerOnlyMods().getFirst().verified());

        writeEntrypointJar(false);
        var changed = NeoForgeClientCompatibility.classify(gameDir, empty(), List.of(staleFml));
        assertTrue(changed.declaredServerOnlyMods().isEmpty());
        assertEquals(List.of("dedicated"), changed.unclassifiedExtraMods().stream()
                .map(ClientCompatibilityReport.ExtraMod::modId).toList());
        assertNull(changed.unclassifiedExtraMods().getFirst().sha512());
    }

    private Path writeEntrypointJar(boolean dedicated) throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path jar = mods.resolve("dedicated.jar");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Dedicated", null,
                "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", "dedicated");
        if (dedicated) {
            AnnotationVisitor sides = annotation.visitArray("dist");
            sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER");
            sides.visitEnd();
        }
        annotation.visitEnd();
        writer.visitEnd();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"dedicated\"\nversion=\"1\"\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("example/Dedicated.class"));
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        return jar;
    }

    private NeoForgePhysicalDiscovery.PhysicalJar jar(String id, boolean complete,
            ClientRequirementClassifier.EntrypointSide... sides) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(gameDir.resolve("mods").resolve(id + ".jar"),
                List.of(id), id, id, "1", new ClientRequirementClassifier.FmlEvidence(complete, List.of(sides)));
    }

    private static RequiredManifest empty() {
        return new RequiredManifest(RequiredManifest.PROTOCOL, List.of());
    }
}
