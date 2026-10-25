package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoForgeCleanupGraphTest {
    @TempDir Path tempDir;

    @Test
    void currentDiskDependencyMustMatchFmlSnapshot() throws IOException {
        Path jar = jar("owner.jar", "target");
        NeoForgePhysicalDiscovery.PhysicalJar fml = fmlJar(jar, "target");
        assertTrue(NeoForgeCleanupGraph.corroboratesCurrentJar(fml));

        // The FML snapshot is still the old one after an in-place file change.
        jar("owner.jar", "different");
        assertFalse(NeoForgeCleanupGraph.corroboratesCurrentJar(fml));
    }

    @Test
    void currentDiskModIdMustMatchFmlSnapshot() throws IOException {
        Path jar = jar("owner.jar", "target");
        NeoForgePhysicalDiscovery.PhysicalJar wrongId = new NeoForgePhysicalDiscovery.PhysicalJar(
                jar, List.of("another"), "another", "Another", "1.0",
                new ClientRequirementClassifier.FmlEvidence(true, List.of(
                        ClientRequirementClassifier.EntrypointSide.DEFAULT)),
                List.of(), List.of(), true);
        assertFalse(NeoForgeCleanupGraph.corroboratesCurrentJar(wrongId));
    }

    private NeoForgePhysicalDiscovery.PhysicalJar fmlJar(Path jar, String target) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of("owner"), "owner",
                "Owner", "1.0", new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEFAULT)), List.of(),
                List.of(new ClientCleanupGraph.Dependency("owner", target,
                        ClientCleanupGraph.DependencyType.REQUIRED,
                        ClientCleanupGraph.DependencySide.CLIENT, "[1,)")), true);
    }

    private Path jar(String name, String target) throws IOException {
        Path path = tempDir.resolve(name);
        String toml = "modLoader=\"javafml\"\nlicense=\"Test\"\n"
                + "[[mods]]\nmodId=\"owner\"\nversion=\"${file.jarVersion}\"\n"
                + "[[dependencies.owner]]\nmodId=\"" + target + "\"\n"
                + "type=\"required\"\nversionRange=\"[1,)\"\nside=\"CLIENT\"\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
