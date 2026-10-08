package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class NeoForgeJarAnalyzerTest {
    private static final String METADATA = "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"example\"\nversion=\"1\"\n";
    private static final String ENTRY = "example/ExampleMod";
    private static final String EVENT = "net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent";
    private static final String REGISTRAR = "net/neoforged/neoforge/network/registration/PayloadRegistrar";
    private static final String TYPE = "net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type";
    private static final String DEFERRED = "net/neoforged/neoforge/registries/DeferredRegister";

    @TempDir Path gameDir;

    @Test
    void optionalNetworkIsObservedWithoutClaimingClientRequirement() throws IOException {
        Path jar = jar("optional.jar", Map.of(ENTRY + ".class", networkClass(true, false)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.OPTIONAL_NETWORK), result.toString());
        assertFalse(result.has(JarEvidence.Kind.REQUIRED_NETWORK));
        assertEquals(1, result.optionalPayloads());
        assertEquals(0, result.requiredPayloads());
    }

    @Test
    void directDefaultRegistrarIsRequiredNetworkEvidence() throws IOException {
        Path jar = jar("required.jar", Map.of(ENTRY + ".class", networkClass(false, false)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.REQUIRED_NETWORK), result.toString());
        assertEquals(1, result.requiredPayloads());
        ServerManifest.Prepared prepared = ServerManifest.prepare(gameDir, List.of(physical(jar)),
                (item, sha512) -> ServerManifest.Evidence.NONE);
        assertEquals(ClientRequirement.REQUIRED, prepared.classified().getFirst().decision().requirement());
        assertEquals(1, prepared.manifest().entries().size());
    }

    @Test
    void oneOptionalCallDoesNotMakeAnotherRequiredCallOptional() throws IOException {
        Path jar = jar("mixed.jar", Map.of(ENTRY + ".class", networkClass(true, true)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.OPTIONAL_NETWORK), result.toString());
        assertTrue(result.has(JarEvidence.Kind.REQUIRED_NETWORK), result.toString());
        assertEquals(1, result.optionalPayloads());
        assertEquals(1, result.requiredPayloads());
    }

    @Test
    void unboundNetworkMethodCannotProveRequiredChannel() throws IOException {
        Path jar = jar("unbound.jar", Map.of(ENTRY + ".class", unboundNetworkClass()));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(result.has(JarEvidence.Kind.REQUIRED_NETWORK));
        assertTrue(result.has(JarEvidence.Kind.UNRESOLVED_REGISTRATION));
        assertFalse(result.complete());
    }

    @Test
    void clientOnlyAutoSubscriberDoesNotProveServerNetworkRequirement() throws IOException {
        Path jar = jar("client-subscriber.jar", Map.of(ENTRY + ".class", clientOnlySubscriberClass()));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(result.has(JarEvidence.Kind.REQUIRED_NETWORK));
        assertTrue(result.has(JarEvidence.Kind.UNRESOLVED_REGISTRATION));
    }

    @Test
    void clientboundSendWithoutProvenGuardRemainsIncomplete() throws IOException {
        Path jar = jar("send.jar", Map.of(ENTRY + ".class", sendClass()));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.POTENTIAL_UNGUARDED_SEND));
        assertFalse(result.complete());
    }

    @Test
    void deferredRegisterNeedsCreationEntryAndBusAttachment() throws IOException {
        Path jar = jar("blocks.jar", Map.of(ENTRY + ".class", blockRegistryClass(true)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.REQUIRED_REGISTRY));
    }

    @Test
    void unattachedDeferredRegisterDoesNotProveRequiredRegistry() throws IOException {
        Path jar = jar("unattached.jar", Map.of(ENTRY + ".class", blockRegistryClass(false)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(result.has(JarEvidence.Kind.REQUIRED_REGISTRY));
        assertTrue(result.has(JarEvidence.Kind.UNRESOLVED_REGISTRATION));
        assertFalse(result.complete());
    }

    @Test
    void reflectionMakesStaticAnalysisIncomplete() throws IOException {
        Path jar = jar("reflective.jar", Map.of(ENTRY + ".class", reflectionClass()));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(result.has(JarEvidence.Kind.DYNAMIC_OR_REFLECTIVE));
        assertFalse(result.complete());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void badZipAndUnsafeEntriesFailClosed() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path invalid = mods.resolve("bad.jar");
        Files.writeString(invalid, "not a ZIP");
        JarAnalysisResult bad = NeoForgeJarAnalyzer.analyze(gameDir, physical(invalid));
        assertFalse(bad.complete());
        assertFalse(bad.diagnostics().isEmpty());

        Path malicious = jar("unsafe.jar", Map.of("../escape.class", validClass()));
        JarAnalysisResult unsafe = NeoForgeJarAnalyzer.analyze(gameDir, physical(malicious));
        assertFalse(unsafe.complete());
        assertTrue(unsafe.diagnostics().stream().anyMatch(value -> value.contains("Unsafe ZIP")));
    }

    @Test
    void byteClassCountMetadataAndTimeBudgetsFailClosed() throws IOException {
        Path jar = jar("bounded.jar", Map.of(ENTRY + ".class", validClass()));
        NeoForgePhysicalDiscovery.PhysicalJar physical = physical(jar);
        assertFalse(NeoForgeJarAnalyzer.analyze(gameDir, physical,
                new NeoForgeJarAnalyzer.Limits(100, 0, 10000, 10000, 10000, 1000000000)).complete());
        assertFalse(NeoForgeJarAnalyzer.analyze(gameDir, physical,
                new NeoForgeJarAnalyzer.Limits(100, 10, 8, 10000, 10000, 1000000000)).complete());
        assertFalse(NeoForgeJarAnalyzer.analyze(gameDir, physical,
                new NeoForgeJarAnalyzer.Limits(100, 10, 10000, 10000, 4, 1000000000)).complete());
        assertFalse(NeoForgeJarAnalyzer.analyze(gameDir, physical,
                new NeoForgeJarAnalyzer.Limits(100, 10, 10000, 10000, 10000, 0)).complete());
    }

    @Test
    void multipleModIdsRemainOnePhysicalAnalysisUnit() throws IOException {
        Path jar = jar("many.jar", Map.of(ENTRY + ".class", validClass(),
                "example/SecondMod.class", simpleModClass("example/SecondMod", "second")));
        NeoForgePhysicalDiscovery.PhysicalJar physical = new NeoForgePhysicalDiscovery.PhysicalJar(
                jar, List.of("example", "second"), "example", "Example", "1",
                new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEFAULT,
                                ClientRequirementClassifier.EntrypointSide.DEFAULT)));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical);
        assertEquals(2, result.scannedClasses());
        assertEquals(2, result.evidence().stream()
                .filter(item -> item.kind() == JarEvidence.Kind.ENTRYPOINT).count());
    }

    @Test
    void ordinaryHelperConstructorDoesNotAbortJarInspection() throws IOException {
        Path jar = jar("helper.jar", Map.of(ENTRY + ".class", validClass(),
                "example/Helper.class", helperClassWithConstructor()));
        JarAnalysisResult result = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertEquals(2, result.scannedClasses());
        assertFalse(result.diagnostics().stream().anyMatch(value -> value.contains("Class inspection failed")),
                result.toString());
    }

    @Test
    void requiredEvidenceSurvivesMetadataEvidenceLimit() throws IOException {
        Path jar = jar("many-evidence.jar", Map.of(ENTRY + ".class", networkClass(false, false)));
        var fml = new ClientRequirementClassifier.FmlEvidence(true,
                java.util.Collections.nCopies(1024,
                        ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER));
        var physical = new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of("example"), "example",
                "Example", "1", fml);
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical);
        assertTrue(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK), analysis.toString());
        var decision = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                ClientRequirementClassifier.Override.NONE, fml, null, analysis));
        assertEquals(ClientRequirement.REQUIRED, decision.requirement());
    }

    @Test
    void mismatchedClassPathCannotContributeRequiredEvidence() throws IOException {
        Path jar = jar("mismatch.jar", Map.of("example/Decoy.class", networkClass(false, false)));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK));
        assertFalse(analysis.complete());
    }

    @Test
    void replacedJarWithSameModIdButBothEntrypointCannotUseOldDedicatedSnapshot() throws IOException {
        Path jar = jar("replaced.jar", Map.of(ENTRY + ".class", dedicatedModClass()));
        var discovered = physical(jar, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER);
        var before = ServerManifest.prepare(gameDir, List.of(discovered),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertTrue(before.manifest().entries().isEmpty());
        assertEquals(ClientRequirement.NOT_REQUIRED, before.classified().getFirst().decision().requirement());

        jar("replaced.jar", Map.of(ENTRY + ".class", validClass()));
        var after = ServerManifest.prepare(gameDir, List.of(discovered),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertEquals(ClientRequirement.UNKNOWN, after.classified().getFirst().decision().requirement());
        assertEquals(1, after.manifest().entries().size());
        assertTrue(after.classified().getFirst().decision().reason().contains("current physical JAR"));
    }

    @Test
    void cachedEntrypointMatchCannotBeReusedForDifferentFmlSnapshot() throws IOException {
        Path jar = jar("snapshot.jar", Map.of(ENTRY + ".class", validClass()));
        var current = physical(jar, ClientRequirementClassifier.EntrypointSide.DEFAULT);
        var stale = physical(jar, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER);
        var first = ServerManifest.prepare(gameDir, List.of(current),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertTrue(first.classified().getFirst().decision().evidence().stream()
                .anyMatch(item -> item.type().equals("ENTRYPOINT_MATCH")));

        var second = ServerManifest.prepare(gameDir, List.of(stale),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertEquals(ClientRequirement.UNKNOWN, second.classified().getFirst().decision().requirement());
        assertEquals(1, second.manifest().entries().size());
        assertTrue(second.classified().getFirst().decision().diagnostics().stream()
                .anyMatch(item -> item.contains("entrypoint sides do not match")));
    }

    @Test
    void unreadableDedicatedJarCannotBeExcludedFromManifest() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path jar = Files.writeString(mods.resolve("broken-dedicated.jar"), "not a ZIP");
        var prepared = ServerManifest.prepare(gameDir,
                List.of(physical(jar, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertEquals(ClientRequirement.UNKNOWN, prepared.classified().getFirst().decision().requirement());
        assertEquals(1, prepared.manifest().entries().size());
    }

    @Test
    void embeddedJarPreventsDedicatedOnlyExclusion() throws IOException {
        Path jar = jar("embedded.jar", Map.of(ENTRY + ".class", dedicatedModClass(),
                "META-INF/jarjar/embedded.jar", new byte[] {1, 2, 3}));
        var prepared = ServerManifest.prepare(gameDir,
                List.of(physical(jar, ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)),
                (item, hash) -> ServerManifest.Evidence.NONE);
        assertEquals(ClientRequirement.UNKNOWN, prepared.classified().getFirst().decision().requirement());
        assertEquals(1, prepared.manifest().entries().size());
        assertTrue(prepared.classified().getFirst().decision().diagnostics().stream()
                .anyMatch(item -> item.contains("Embedded JAR")));
    }

    @Test
    void clientOnlyModConstructorCannotBindServerPayload() throws IOException {
        Path jar = jar("client-only-entry.jar", Map.of(ENTRY + ".class",
                networkClass(false, false, true, true)));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK), analysis.toString());
    }

    @Test
    void registryFieldReassignmentInvalidatesRequiredEvidence() throws IOException {
        Path jar = jar("reassigned.jar", Map.of(ENTRY + ".class", blockRegistryClass(true, true)));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(analysis.has(JarEvidence.Kind.REQUIRED_REGISTRY), analysis.toString());
        assertFalse(analysis.complete());
    }

    @Test
    void legacyGameBusAttributeStillUsesEventTypeForPayloadHandler() throws IOException {
        Path jar = jar("game-bus.jar", Map.of(ENTRY + ".class", serverSubscriberGameBusClass()));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertTrue(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK), analysis.toString());
    }

    @Test
    void conditionalListenerHandleDoesNotProveServerRegistration() throws IOException {
        Path jar = jar("conditional-listener.jar", Map.of(ENTRY + ".class", conditionalListenerClass()));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(analysis.has(JarEvidence.Kind.REQUIRED_NETWORK), analysis.toString());
        assertFalse(analysis.complete());
    }

    @Test
    void oversizedMethodStopsBeforeDataflowAnalysis() throws IOException {
        Path jar = jar("large-method.jar", Map.of(ENTRY + ".class", largeMethodClass()));
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, physical(jar));
        assertFalse(analysis.complete());
        assertTrue(analysis.diagnostics().stream().anyMatch(item -> item.contains("instruction budget")),
                analysis.toString());
    }

    private NeoForgePhysicalDiscovery.PhysicalJar physical(Path jar) {
        return physical(jar, ClientRequirementClassifier.EntrypointSide.DEFAULT);
    }

    private NeoForgePhysicalDiscovery.PhysicalJar physical(Path jar,
            ClientRequirementClassifier.EntrypointSide side) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of("example"), "example",
                "Example", "1", new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(side)));
    }

    private Path jar(String name, Map<String, byte[]> extra) throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path jar = mods.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(METADATA.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> entry : extra.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] validClass() {
        return simpleModClass(ENTRY, "example");
    }

    private static byte[] dedicatedModClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ENTRY, null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", "example");
        AnnotationVisitor sides = annotation.visitArray("dist");
        sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER");
        sides.visitEnd();
        annotation.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter baseClass(String name, String id) {
        return baseClass(name, id, false);
    }

    private static ClassWriter baseClass(String name, String id, boolean clientOnly) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", id);
        if (clientOnly) {
            AnnotationVisitor sides = annotation.visitArray("dist");
            sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
            sides.visitEnd();
        }
        annotation.visitEnd();
        return writer;
    }

    private static byte[] simpleModClass(String name, String id) {
        ClassWriter writer = baseClass(name, id);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helperClassWithConstructor() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Helper", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] reflectionClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dynamic", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("example.Dynamic");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] networkClass(boolean optional, boolean includeRequired) {
        return networkClass(optional, includeRequired, true);
    }

    private static byte[] unboundNetworkClass() {
        return networkClass(false, false, false);
    }

    private static byte[] clientOnlySubscriberClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        AnnotationVisitor subscriber = writer.visitAnnotation(
                "Lnet/neoforged/fml/common/EventBusSubscriber;", true);
        AnnotationVisitor sides = subscriber.visitArray("value");
        sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
        sides.visitEnd();
        subscriber.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "network", "(L" + EVENT + ";)V", null, null);
        method.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("1");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "registrar",
                "(Ljava/lang/String;)L" + REGISTRAR + ";", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        payloadCall(method, 1);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] serverSubscriberGameBusClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        AnnotationVisitor subscriber = writer.visitAnnotation(
                "Lnet/neoforged/fml/common/EventBusSubscriber;", true);
        subscriber.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "GAME");
        AnnotationVisitor sides = subscriber.visitArray("value");
        sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER");
        sides.visitEnd();
        subscriber.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "network", "(L" + EVENT + ";)V", null, null);
        method.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("1");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "registrar",
                "(Ljava/lang/String;)L" + REGISTRAR + ";", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        payloadCall(method, 1);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] conditionalListenerClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        MethodVisitor network = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "network", "(L" + EVENT + ";)V", null, null);
        network.visitCode();
        network.visitVarInsn(Opcodes.ALOAD, 0);
        network.visitLdcInsn("1");
        network.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "registrar",
                "(Ljava/lang/String;)L" + REGISTRAR + ";", false);
        network.visitVarInsn(Opcodes.ASTORE, 1);
        payloadCall(network, 1);
        network.visitInsn(Opcodes.RETURN);
        network.visitMaxs(0, 0);
        network.visitEnd();
        MethodVisitor other = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "other", "(L" + EVENT + ";)V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/neoforged/bus/api/IEventBus;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitInsn(Opcodes.ICONST_0);
        Label otherBranch = new Label();
        Label join = new Label();
        constructor.visitJumpInsn(Opcodes.IFEQ, otherBranch);
        lambdaForNetworkMethod(constructor, "network");
        constructor.visitJumpInsn(Opcodes.GOTO, join);
        constructor.visitLabel(otherBranch);
        lambdaForNetworkMethod(constructor, "other");
        constructor.visitLabel(join);
        constructor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/neoforged/bus/api/IEventBus",
                "addListener", "(Ljava/util/function/Consumer;)V", true);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] largeMethodClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "manyInstructions", "()V", null, null);
        method.visitCode();
        for (int i = 0; i < 25_001; i++) method.visitInsn(Opcodes.NOP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void lambdaForNetworkMethod(MethodVisitor constructor, String methodName) {
        constructor.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Ljava/lang/Object;)V"),
                new Handle(Opcodes.H_INVOKESTATIC, ENTRY, methodName, "(L" + EVENT + ";)V", false),
                Type.getType("(L" + EVENT + ";)V"));
    }

    private static byte[] networkClass(boolean optional, boolean includeRequired, boolean bound) {
        return networkClass(optional, includeRequired, bound, false);
    }

    private static byte[] networkClass(boolean optional, boolean includeRequired,
                                       boolean bound, boolean clientOnly) {
        ClassWriter writer = baseClass(ENTRY, "example", clientOnly);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "network", "(L" + EVENT + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("1");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "registrar",
                "(Ljava/lang/String;)L" + REGISTRAR + ";", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        if (optional) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRAR, "optional",
                    "()L" + REGISTRAR + ";", false);
            method.visitVarInsn(Opcodes.ASTORE, 2);
            payloadCall(method, 2);
        } else {
            payloadCall(method, 1);
        }
        if (includeRequired) payloadCall(method, 1);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        if (bound) {
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(Lnet/neoforged/bus/api/IEventBus;)V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitVarInsn(Opcodes.ALOAD, 1);
            constructor.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                    + "Ljava/lang/invoke/CallSite;", false),
                    Type.getType("(Ljava/lang/Object;)V"),
                    new Handle(Opcodes.H_INVOKESTATIC, ENTRY, "network", "(L" + EVENT + ";)V", false),
                    Type.getType("(L" + EVENT + ";)V"));
            constructor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/neoforged/bus/api/IEventBus",
                    "addListener", "(Ljava/util/function/Consumer;)V", true);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] sendClass() {
        ClassWriter writer = baseClass(ENTRY, "example");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "send", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/neoforged/neoforge/network/PacketDistributor", "sendToPlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void payloadCall(MethodVisitor method, int local) {
        method.visitVarInsn(Opcodes.ALOAD, local);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRAR, "playToClient",
                "(L" + TYPE + ";Lnet/minecraft/network/codec/StreamCodec;"
                        + "Lnet/neoforged/neoforge/network/handling/IPayloadHandler;)L"
                        + REGISTRAR + ";", false);
        method.visitInsn(Opcodes.POP);
    }

    private static byte[] blockRegistryClass(boolean attach) {
        return blockRegistryClass(attach, false);
    }

    private static byte[] blockRegistryClass(boolean attach, boolean reassign) {
        ClassWriter writer = baseClass(ENTRY, "example");
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BLOCKS",
                "L" + DEFERRED + "$Blocks;", null, null).visitEnd();
        MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitLdcInsn("example");
        initializer.visitMethodInsn(Opcodes.INVOKESTATIC, DEFERRED, "createBlocks",
                "(Ljava/lang/String;)L" + DEFERRED + "$Blocks;", false);
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, ENTRY, "BLOCKS", "L" + DEFERRED + "$Blocks;");
        initializer.visitFieldInsn(Opcodes.GETSTATIC, ENTRY, "BLOCKS", "L" + DEFERRED + "$Blocks;");
        initializer.visitLdcInsn("thing");
        initializer.visitInsn(Opcodes.ACONST_NULL);
        initializer.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DEFERRED + "$Blocks", "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)"
                        + "Lnet/neoforged/neoforge/registries/DeferredBlock;", false);
        initializer.visitInsn(Opcodes.POP);
        if (reassign) {
            initializer.visitInsn(Opcodes.ACONST_NULL);
            initializer.visitFieldInsn(Opcodes.PUTSTATIC, ENTRY, "BLOCKS", "L" + DEFERRED + "$Blocks;");
        }
        initializer.visitInsn(Opcodes.RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        if (attach) {
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(Lnet/neoforged/bus/api/IEventBus;)V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitFieldInsn(Opcodes.GETSTATIC, ENTRY, "BLOCKS", "L" + DEFERRED + "$Blocks;");
            constructor.visitVarInsn(Opcodes.ALOAD, 1);
            constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DEFERRED, "register",
                    "(Lnet/neoforged/bus/api/IEventBus;)V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
