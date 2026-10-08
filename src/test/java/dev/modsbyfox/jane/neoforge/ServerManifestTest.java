package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ServerManifestTest {
    @TempDir Path gameDir;

    @Test
    void onePhysicalJarWithMultipleModIdsBecomesOneManifestEntry() throws IOException {
        Path jar = jar("bundle.jar", "bundle");
        var physical = physical(jar, List.of("alpha", "beta"), true,
                ClientRequirementClassifier.EntrypointSide.DEFAULT,
                ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER);

        var prepared = ServerManifest.prepare(gameDir, List.of(physical), (item, hash) -> ServerManifest.Evidence.NONE);

        assertEquals(1, prepared.manifest().entries().size());
        assertEquals("alpha", prepared.manifest().entries().getFirst().modId());
        assertEquals(Hashing.sha512(jar), prepared.manifest().entries().getFirst().sha512());
        assertEquals(jar.toRealPath(), prepared.filesByHash().get(Hashing.sha512(jar)));
    }

    @Test
    void allDedicatedServerEntrypointsAreExcludedButUnknownIsIncluded() throws IOException {
        Path server = validDedicatedServerJar();
        Path unknown = jar("unknown.jar", "unknown");
        var prepared = ServerManifest.prepare(gameDir, List.of(
                physical(server, List.of("server"), true,
                        ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER),
                physical(unknown, List.of("unknown"), false)),
                (item, hash) -> ServerManifest.Evidence.NONE);

        assertEquals(List.of("unknown"), prepared.manifest().entries().stream().map(entry -> entry.modId()).toList());
        assertEquals(ClientRequirement.UNKNOWN, prepared.classified().get(1).decision().requirement());
    }

    @Test
    void exactVersionEnvironmentCanExcludeDefaultEntrypointJar() throws IOException {
        Path optional = jar("optional.jar", "optional");
        Path conflict = jar("conflict.jar", "conflict");
        var evidence = new ClientRequirementClassifier.ModrinthEvidence(
                ClientRequirementClassifier.MatchKind.EXACT_SHA512,
                ClientRequirementClassifier.VersionEnvironment.SERVER_ONLY_CLIENT_OPTIONAL);
        var prepared = ServerManifest.prepare(gameDir, List.of(
                physical(optional, List.of("optional"), false),
                physical(conflict, List.of("conflict"), true,
                        ClientRequirementClassifier.EntrypointSide.DEFAULT)),
                (item, hash) -> new ServerManifest.Evidence(ClientRequirementClassifier.Override.NONE, evidence));

        assertTrue(prepared.manifest().entries().isEmpty());
        assertTrue(prepared.classified().stream().allMatch(item ->
                item.decision().requirement() == ClientRequirement.NOT_REQUIRED));
    }

    private Path jar(String name, String content) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        return Files.writeString(mods.resolve(name), content);
    }

    private Path validDedicatedServerJar() throws IOException {
        Path jar = Files.createDirectories(gameDir.resolve("mods")).resolve("server.jar");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/ServerMod", null,
                "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", "server");
        AnnotationVisitor sides = annotation.visitArray("dist");
        sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER");
        sides.visitEnd();
        annotation.visitEnd();
        writer.visitEnd();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(("modLoader=\"javafml\"\n[[mods]]\nmodId=\"server\"\nversion=\"1\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("example/ServerMod.class"));
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        return jar;
    }

    private static NeoForgePhysicalDiscovery.PhysicalJar physical(Path jar, List<String> ids, boolean complete,
            ClientRequirementClassifier.EntrypointSide... entrypoints) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(jar, ids, ids.getFirst(), ids.getFirst(), "1",
                new ClientRequirementClassifier.FmlEvidence(complete, List.of(entrypoints)));
    }
}
