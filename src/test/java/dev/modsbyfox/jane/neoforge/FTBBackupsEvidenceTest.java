package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** A synthetic JAR with FTB Backups 3 v21.1.5's relevant registration shape. */
class FTBBackupsEvidenceTest {
    private static final String MOD_ID = "ftbbackups3";
    private static final String ENTRY = "fixture/BackupsMod";
    private static final String HELPER = "fixture/BackupsNetwork";
    private static final String EVENT =
            "net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent";
    private static final String REGISTRAR =
            "net/neoforged/neoforge/network/registration/PayloadRegistrar";
    private static final String PAYLOAD_TYPE =
            "net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type";
    private static final String PLAY_TO_CLIENT = "(L" + PAYLOAD_TYPE
            + ";Lnet/minecraft/network/codec/StreamCodec;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadHandler;)L" + REGISTRAR + ";";

    @TempDir Path gameDir;

    @Test
    void defaultBothEntrypointWithDelegatedOptionalPayloadsRemainsUnknown() throws IOException {
        Path jar = syntheticJar();
        ClientRequirementClassifier.FmlEvidence fml =
                new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEFAULT));
        NeoForgePhysicalDiscovery.PhysicalJar physical =
                new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of(MOD_ID), MOD_ID,
                        "FTB Backups 3 fixture", "21.1.5", fml);

        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical);
        assertEquals(2, analysis.optionalPayloads(), analysis.diagnostics().toString());
        assertEquals(0, analysis.requiredPayloads(), analysis.diagnostics().toString());
        assertTrue(analysis.has(JarEvidence.Kind.OPTIONAL_NETWORK));
        assertFalse(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK));

        ClientRequirementClassifier.Result decision = ClientRequirementClassifier.classify(
                new ClientRequirementClassifier.Input(
                        ClientRequirementClassifier.Override.NONE, fml, null, analysis));
        assertEquals(ClientRequirement.UNKNOWN, decision.requirement());
        assertTrue(decision.includeInClientManifest());
        assertFalse(decision.reason().isBlank());
        assertTrue(decision.reason().contains("Optional payloads"), decision.reason());

        ServerManifest.Prepared prepared = ServerManifest.prepare(gameDir, List.of(physical),
                (item, sha512) -> ServerManifest.Evidence.NONE);
        assertEquals(1, prepared.manifest().entries().size());
        assertEquals(MOD_ID, prepared.manifest().entries().getFirst().modId());
        assertEquals(ClientRequirement.UNKNOWN, prepared.classified().getFirst().decision().requirement());
    }

    /**
     * Optional local verification against the official published JAR. It is
     * deliberately absent from the repository and never downloaded by tests.
     */
    @Test
    void officialReleaseJarWhenLocallySuppliedRemainsUnknown() throws IOException {
        String suppliedPath = System.getProperty("jane.ftbVerificationJar", "");
        Assumptions.assumeTrue(!suppliedPath.isBlank());
        Path supplied = Path.of(suppliedPath);
        Assumptions.assumeTrue(Files.isRegularFile(supplied));
        Path jar = Files.createDirectories(gameDir.resolve("mods")).resolve(supplied.getFileName());
        Files.copy(supplied, jar, StandardCopyOption.REPLACE_EXISTING);
        assertEquals("fd621376ab7e08a042878052ef27d3d18d3e91b9e39089fa871703d4f3289684b720a3c7ad3874b6b593083452fbf645d77040aa03f8c77390779b05982fb7b2",
                Hashing.sha512(jar));
        var fml = new ClientRequirementClassifier.FmlEvidence(true,
                List.of(ClientRequirementClassifier.EntrypointSide.DEFAULT));
        var physical = new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of(MOD_ID), MOD_ID,
                "FTB Backups 3", "21.1.5", fml);
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical);
        assertEquals(2, analysis.optionalPayloads(), analysis.diagnostics().toString());
        assertEquals(0, analysis.requiredPayloads(), analysis.diagnostics().toString());
        assertTrue(analysis.has(JarEvidence.Kind.POTENTIAL_UNGUARDED_SEND));
        var decision = ClientRequirementClassifier.classify(
                new ClientRequirementClassifier.Input(ClientRequirementClassifier.Override.NONE,
                        fml, null, analysis));
        assertEquals(ClientRequirement.UNKNOWN, decision.requirement());
        assertTrue(decision.reason().contains("Optional payloads"), decision.reason());
    }

    private Path syntheticJar() throws IOException {
        Path jar = Files.createDirectories(gameDir.resolve("mods"))
                .resolve("ftb-backups-3-21.1.5-synthetic.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(zip, "META-INF/neoforge.mods.toml", (
                    "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n"
                            + "[[mods]]\nmodId=\"" + MOD_ID + "\"\nversion=\"21.1.5\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            add(zip, ENTRY + ".class", entryClass());
            add(zip, HELPER + ".class", helperClass());
        }
        return jar;
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] entryClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ENTRY, null, "java/lang/Object", null);
        // No dist element: FML's @Mod default loads on both physical sides.
        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", MOD_ID);
        annotation.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/neoforged/bus/api/IEventBus;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInvokeDynamicInsn("accept", "(L" + ENTRY + ";)Ljava/util/function/Consumer;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("(Ljava/lang/Object;)V"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, ENTRY, "registerNetwork", "(L" + EVENT + ";)V", false),
                Type.getMethodType("(L" + EVENT + ";)V"));
        constructor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/neoforged/bus/api/IEventBus",
                "addListener", "(Ljava/util/function/Consumer;)V", true);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor network = writer.visitMethod(Opcodes.ACC_PUBLIC, "registerNetwork",
                "(L" + EVENT + ";)V", null, null);
        network.visitCode();
        network.visitVarInsn(Opcodes.ALOAD, 1);
        network.visitLdcInsn("3");
        network.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "registrar",
                "(Ljava/lang/String;)L" + REGISTRAR + ";", false);
        network.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "init",
                "(L" + REGISTRAR + ";)V", false);
        network.visitInsn(Opcodes.RETURN);
        network.visitMaxs(0, 0);
        network.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helperClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
        MethodVisitor helper = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init",
                "(L" + REGISTRAR + ";)V", null, null);
        helper.visitCode();
        optionalPlayToClient(helper);
        optionalPlayToClient(helper);
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void optionalPlayToClient(MethodVisitor helper) {
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRAR, "optional",
                "()L" + REGISTRAR + ";", false);
        helper.visitInsn(Opcodes.ACONST_NULL);
        helper.visitInsn(Opcodes.ACONST_NULL);
        helper.visitInsn(Opcodes.ACONST_NULL);
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRAR, "playToClient", PLAY_TO_CLIENT, false);
        helper.visitInsn(Opcodes.POP);
    }
}
