package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * A bounded, read-only static inspection of one FML-resolved top-level JAR.
 * It never loads or initializes classes from the inspected file. Observations
 * which cannot be tied to a definite registrar or register are not negative
 * evidence; the classifier must retain UNKNOWN in the client manifest.
 */
public final class NeoForgeJarAnalyzer {
    public static final String RULE_VERSION = "1.1.8-jar-v1";

    private static final String PAYLOAD_REGISTRAR =
            "net/neoforged/neoforge/network/registration/PayloadRegistrar";
    private static final String PAYLOAD_EVENT =
            "net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent";
    private static final String CONFIG_TASK_EVENT =
            "net/neoforged/neoforge/network/event/RegisterConfigurationTasksEvent";
    private static final String DEFERRED_REGISTER =
            "net/neoforged/neoforge/registries/DeferredRegister";
    private static final String REGISTER_EVENT =
            "net/neoforged/neoforge/registries/RegisterEvent";
    private static final String MOD_ANNOTATION = "Lnet/neoforged/fml/common/Mod;";
    private static final String EVENT_BUS_SUBSCRIBER = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String SUBSCRIBE_EVENT = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final Pattern MOD_LOADER = Pattern.compile(
            "(?m)^\\s*modLoader\\s*=\\s*\"([^\"]+)\"");
    private static final int MAX_EVIDENCE = 1024;
    private static final int MAX_DIAGNOSTICS = 128;
    private static final int MAX_METHOD_INSTRUCTIONS = 25_000;
    private static final int MAX_TOTAL_INSTRUCTIONS = 1_000_000;

    private NeoForgeJarAnalyzer() {}

    record Limits(int maxEntries, int maxClasses, int maxClassBytes,
                  long maxTotalClassBytes, int maxMetadataBytes, long maxDurationNanos) {
        static final Limits DEFAULT = new Limits(50_000, 20_000, 4 * 1024 * 1024,
                96L * 1024 * 1024, 256 * 1024, TimeUnit.SECONDS.toNanos(8));

        Limits {
            if (maxEntries < 0 || maxClasses < 0 || maxClassBytes < 0
                    || maxTotalClassBytes < 0 || maxMetadataBytes < 0) {
                throw new IllegalArgumentException("Negative JAR analysis limit");
            }
        }
    }

    public static JarAnalysisResult analyze(Path gameDir,
                                             NeoForgePhysicalDiscovery.PhysicalJar physical) {
        return analyze(gameDir, physical, Limits.DEFAULT);
    }

    /** Package-private limit seam keeps resource and timeout behavior testable. */
    static JarAnalysisResult analyze(Path gameDir, NeoForgePhysicalDiscovery.PhysicalJar physical,
                                     Limits limits) {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(limits, "limits");
        Scan scan = new Scan(physical, limits);
        for (ClientRequirementClassifier.EntrypointSide side : physical.fmlEvidence().entrypoints()) {
            scan.evidence(JarEvidence.Source.FML_METADATA, JarEvidence.Kind.ENTRYPOINT,
                    true, physical.canonicalId(), "FML @Mod side=" + side);
        }
        for (String dependency : physical.dependencyDescriptions()) {
            // The dependency side describes where the target dependency is needed;
            // it does not describe whether this owner JAR belongs on clients.
            scan.evidence(JarEvidence.Source.FML_METADATA, JarEvidence.Kind.DEPENDENCY_METADATA,
                    physical.fmlEvidence().complete(), physical.canonicalId(),
                    "FML dependency " + dependency);
        }
        if (!physical.fmlEvidence().complete()) {
            scan.problem("FML entrypoint or JavaFML metadata is incomplete");
        }
        try {
            Path verified = PathSafety.existingJarInMods(gameDir, physical.jar());
            scan.scanZip(verified);
        } catch (IOException | RuntimeException failure) {
            scan.problem("Unable to inspect physical JAR: " + failure.getClass().getSimpleName());
        }
        return scan.result();
    }

    private static final class Scan {
        private final NeoForgePhysicalDiscovery.PhysicalJar physical;
        private final Limits limits;
        private final long started = System.nanoTime();
        private final List<JarEvidence> evidence = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();
        private final Map<FieldKey, RegistryTrack> registries = new HashMap<>();
        private final Set<String> classNames = new HashSet<>();
        private final Set<String> annotationIds = new HashSet<>();
        private final List<ClientRequirementClassifier.EntrypointSide> bytecodeEntrypoints = new ArrayList<>();
        private final Set<MethodKey> boundNetworkHandlers = new HashSet<>();
        private final List<PayloadCandidate> payloadCandidates = new ArrayList<>();
        private final List<HelperCall> helperCalls = new ArrayList<>();
        private boolean complete = true;
        private boolean metadataSeen;
        private int classes;
        private int optionalPayloads;
        private int requiredPayloads;
        private long totalClassBytes;
        private int totalInstructions;

        private Scan(NeoForgePhysicalDiscovery.PhysicalJar physical, Limits limits) {
            this.physical = physical;
            this.limits = limits;
        }

        private boolean timedOut() {
            if (limits.maxDurationNanos() == 0
                    || System.nanoTime() - started >= limits.maxDurationNanos()) {
                problem("JAR analysis time budget exceeded");
                return true;
            }
            return false;
        }

        private void scanZip(Path jar) throws IOException {
            if (timedOut()) return;
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                problem("Physical JAR is no longer a regular file");
                return;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                Set<String> names = new HashSet<>();
                int entryCount = 0;
                while (entries.hasMoreElements()) {
                    if (timedOut()) break;
                    if (++entryCount > limits.maxEntries()) {
                        problem("ZIP entry limit exceeded");
                        break;
                    }
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!safeEntryName(name)) {
                        problem("Unsafe ZIP entry name observed");
                        continue;
                    }
                    if (!names.add(name)) {
                        problem("Duplicate ZIP entry observed: " + safeText(name));
                        continue;
                    }
                    if (entry.isDirectory()) continue;
                    if (name.equals("META-INF/neoforge.mods.toml")) {
                        inspectMetadata(zip, entry);
                    } else if (name.endsWith(".class")) {
                        if (name.startsWith("META-INF/versions/")) {
                            problem("Multi-release class needs version-aware inspection: " + safeText(name));
                            continue;
                        }
                        if (classes >= limits.maxClasses()) {
                            problem("Class file count limit exceeded");
                            break;
                        }
                        inspectClass(zip, entry);
                    } else if (name.endsWith(".jar")) {
                        // FML nested dependencies are not separate physical sync units.
                        // Their registrations cannot be attributed by this scanner.
                        problem("Embedded JAR requires separate FML attribution: " + safeText(name));
                    }
                }
            } catch (ZipException invalidZip) {
                problem("Physical JAR is not a valid ZIP");
            }
            if (!metadataSeen) problem("neoforge.mods.toml was not found in physical JAR");
            if (classes == 0) problem("No class files were inspected");
            if (!annotationIds.containsAll(physical.modIds())) {
                problem("Not every FML mod ID has a readable @Mod class in this JAR");
            }
            completeRegistries();
            completeNetwork();
            confirmEntrypoints();
        }

        private void inspectMetadata(ZipFile zip, ZipEntry entry) throws IOException {
            metadataSeen = true;
            byte[] bytes = readBounded(zip, entry, limits.maxMetadataBytes());
            if (bytes == null) {
                problem("neoforge.mods.toml exceeds metadata byte limit");
                return;
            }
            String toml = new String(bytes, StandardCharsets.UTF_8);
            Matcher loader = MOD_LOADER.matcher(toml);
            if (!loader.find()) {
                problem("neoforge.mods.toml modLoader could not be confirmed");
            } else if (!"javafml".equals(loader.group(1))) {
                problem("Non-JavaFML modLoader is not statically understood");
            }
        }

        private void inspectClass(ZipFile zip, ZipEntry entry) throws IOException {
            byte[] bytes = readBounded(zip, entry, limits.maxClassBytes());
            if (bytes == null) {
                problem("Class exceeds byte limit: " + safeText(entry.getName()));
                return;
            }
            if (totalClassBytes + bytes.length > limits.maxTotalClassBytes()) {
                problem("Aggregate class byte limit exceeded");
                return;
            }
            totalClassBytes += bytes.length;
            classes++;
            try {
                ClassNode node = new ClassNode(Opcodes.ASM9);
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
                if (timedOut()) return;
                if (!entry.getName().equals(node.name + ".class")) {
                    problem("ZIP class path and bytecode class name disagree: " + safeText(entry.getName()));
                    return;
                }
                classNames.add(node.name);
                inspectAnnotations(node);
                boolean autoSubscriber = isServerSubscriber(node);
                for (MethodNode method : node.methods) {
                    if (timedOut()) break;
                    inspectMethod(node, method, autoSubscriber);
                }
            } catch (RuntimeException invalidClass) {
                problem("Class inspection failed (" + invalidClass.getClass().getSimpleName()
                        + "): " + safeText(entry.getName()));
            }
        }

        private void inspectAnnotations(ClassNode node) {
            List<AnnotationNode> all = new ArrayList<>();
            if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
            if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);
            for (AnnotationNode annotation : all) {
                if (!MOD_ANNOTATION.equals(annotation.desc)) continue;
                String id = annotationString(annotation, "value");
                if (id == null || !physical.modIds().contains(id)) {
                    problem("@Mod ID disagrees with FML physical JAR metadata: " + safeText(node.name));
                } else {
                    annotationIds.add(id);
                    ClientRequirementClassifier.EntrypointSide side = modEntrypointSide(annotation);
                    if (side == null) {
                        problem("@Mod side cannot be parsed: " + safeText(node.name));
                    } else {
                        bytecodeEntrypoints.add(side);
                    }
                }
            }
        }

        private void confirmEntrypoints() {
            List<ClientRequirementClassifier.EntrypointSide> fmlSides =
                    new ArrayList<>(physical.fmlEvidence().entrypoints());
            List<ClientRequirementClassifier.EntrypointSide> currentSides =
                    new ArrayList<>(bytecodeEntrypoints);
            fmlSides.sort(Comparator.naturalOrder());
            currentSides.sort(Comparator.naturalOrder());
            if (!fmlSides.equals(currentSides)) {
                problem("FML @Mod entrypoint sides do not match the current physical JAR");
                return;
            }
            if (complete && !fmlSides.isEmpty() && annotationIds.containsAll(physical.modIds())) {
                evidence(JarEvidence.Source.BYTECODE_ANALYSIS, JarEvidence.Kind.ENTRYPOINT_MATCH,
                        true, physical.canonicalId(), "Current JAR @Mod IDs and sides match FML discovery");
            }
        }

        private boolean isServerSubscriber(ClassNode node) {
            AnnotationNode subscriber = findAnnotation(node.visibleAnnotations, EVENT_BUS_SUBSCRIBER);
            if (subscriber == null)
                subscriber = findAnnotation(node.invisibleAnnotations, EVENT_BUS_SUBSCRIBER);
            if (subscriber == null) return false;
            String specifiedModId = annotationString(subscriber, "modid");
            if (specifiedModId != null && !specifiedModId.isBlank()
                    && !physical.modIds().contains(specifiedModId)) {
                problem("EventBusSubscriber modid is outside this physical JAR: " + safeText(node.name));
                return false;
            }
            Object dist = annotationValue(subscriber, "value");
            // In 21.1.256 FML chooses the bus from the event type. The old
            // annotation bus parameter is ignored, including an explicit GAME.
            if (dist == null) return true; // Default annotation is not side restricted.
            List<?> values = dist instanceof List<?> list ? list : List.of(dist);
            boolean server = false;
            for (Object value : values) {
                if (!(value instanceof String[] holder) || holder.length != 2
                        || !holder[0].equals("Lnet/neoforged/api/distmarker/Dist;")) {
                    problem("EventBusSubscriber side cannot be parsed: " + safeText(node.name));
                    return false;
                }
                if (holder[1].equals("DEDICATED_SERVER")) server = true;
                else if (!holder[1].equals("CLIENT")) {
                    problem("EventBusSubscriber has unknown side: " + safeText(node.name));
                    return false;
                }
            }
            return server;
        }

        private void inspectMethod(ClassNode owner, MethodNode method, boolean autoSubscriber) {
            InsnList instructions = method.instructions;
            if (instructions == null || instructions.size() == 0) return;
            if (instructions.size() > MAX_METHOD_INSTRUCTIONS
                    || totalInstructions > MAX_TOTAL_INSTRUCTIONS - instructions.size()) {
                problem("Method instruction budget exceeded: " + safeText(owner.name + "." + method.name));
                return;
            }
            totalInstructions += instructions.size();
            if (timedOut()) return;
            MethodKey methodKey = new MethodKey(owner.name, method.name, method.desc);
            int registrarParameter = registrarParameterSlot(method);
            if (autoSubscriber && (method.access & Opcodes.ACC_STATIC) != 0
                    && (hasAnnotation(method.visibleAnnotations, SUBSCRIBE_EVENT)
                    || hasAnnotation(method.invisibleAnnotations, SUBSCRIBE_EVENT))) {
                boundNetworkHandlers.add(methodKey);
            }
            boolean modConstructor = "<init>".equals(method.name)
                    && isOwnModClass(owner);
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
            } catch (AnalyzerException | RuntimeException unresolvable) {
                problem("Unable to resolve method data flow: " + safeText(owner.name + "." + method.name));
                return;
            }
            if (timedOut()) return;
            boolean branched = false;
            for (AbstractInsnNode insn : instructions) {
                if (insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode
                        || insn instanceof TableSwitchInsnNode) {
                    branched = true;
                    break;
                }
            }
            String location = safeText(owner.name + "." + method.name);
            for (int i = 0; i < instructions.size(); i++) {
                if ((i & 255) == 0 && timedOut()) return;
                AbstractInsnNode insn = instructions.get(i);
                if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    if (dynamic.desc.contains("PayloadRegistrar")
                            || dynamic.desc.contains("RegisterPayloadHandlersEvent")
                            || dynamic.desc.contains("DeferredRegister")) {
                        unresolved(location, "Dynamic registration invocation");
                    }
                    continue;
                }
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                    inspectRegistryFieldWrite(field, i, frames, instructions, owner, method);
                    continue;
                }
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (isReflective(call)) {
                    evidence(JarEvidence.Source.BYTECODE_ANALYSIS,
                            JarEvidence.Kind.DYNAMIC_OR_REFLECTIVE, false, location,
                            "Reflection, method handles or dynamic class loading");
                    problem("Dynamic class behavior cannot be followed: " + location);
                }
                if (PAYLOAD_REGISTRAR.equals(call.owner) && isPayloadRegistration(call)) {
                    inspectPayloadCall(call, i, frames, instructions, branched, location,
                            methodKey, registrarParameter);
                } else if (PAYLOAD_REGISTRAR.equals(call.owner)
                        && !isRegistrarConfiguration(call.name)) {
                    unresolved(location, "Unrecognized PayloadRegistrar API: " + safeText(call.name));
                } else if (CONFIG_TASK_EVENT.equals(call.owner) && "register".equals(call.name)) {
                    evidence(JarEvidence.Source.NETWORK_ANALYSIS,
                            JarEvidence.Kind.CONFIGURATION_TASK, false, location,
                            "Configuration task registration needs runtime negotiation review");
                    problem("Configuration task cannot be proven optional: " + location);
                } else if (call.owner.equals("net/neoforged/neoforge/network/PacketDistributor")
                        && call.name.startsWith("sendTo") && !call.name.equals("sendToServer")) {
                    evidence(JarEvidence.Source.NETWORK_ANALYSIS,
                            JarEvidence.Kind.POTENTIAL_UNGUARDED_SEND, false, location,
                            "Clientbound PacketDistributor send needs hasChannel/negotiation review");
                    problem("Clientbound payload send may be unguarded: " + location);
                } else if (isRegistryCall(call)) {
                    inspectRegistryCall(call, i, frames, instructions, branched, location,
                            owner, method);
                } else if (REGISTER_EVENT.equals(call.owner) && "register".equals(call.name)) {
                    unresolved(location, "Direct RegisterEvent content cannot be attributed statically");
                } else if (call.owner.equals("net/neoforged/bus/api/IEventBus")
                        && (call.name.equals("addListener") || call.name.equals("register"))) {
                    if (modConstructor && "addListener".equals(call.name)
                            && mustRunOnNormalReturn(method, i, frames)
                            && isModBusParameterReceiver(call, i, frames, instructions,
                                    eventBusParameterSlot(method))
                            && bindDirectListener(call, i, frames)) {
                        // The method reference is tied to the actual mod bus.
                    } else {
                        // Event listeners can indirectly register arbitrary content.
                        unresolved(location, "Event-bus listener or dynamic registration requires runtime review");
                    }
                } else if (hasArgumentType(call, PAYLOAD_REGISTRAR)
                        || hasArgumentType(call, PAYLOAD_EVENT)) {
                    if (!branched) {
                        recordHelperCall(call, i, frames, instructions, methodKey,
                                registrarParameter, location);
                    } else {
                        unresolved(location, "Registrar/event passed through an untraced wrapper");
                    }
                }
            }
        }

        private void inspectPayloadCall(MethodInsnNode call, int index, Frame<SourceValue>[] frames,
                                        InsnList instructions, boolean branched, String location,
                                        MethodKey handler, int registrarParameter) {
            Origin origin = receiverOrigin(call, index, frames, instructions,
                    registrarParameter, 0, new HashSet<>());
            if (branched || origin == Origin.UNKNOWN) {
                unresolved(location, "Payload registrar state or conditional registration is unresolved: "
                        + safeText(call.name));
                return;
            }
            if (payloadCandidates.size() >= MAX_EVIDENCE) {
                problem("Payload registration count limit exceeded");
                return;
            }
            payloadCandidates.add(new PayloadCandidate(handler, origin, location, call.name));
        }

        private void completeNetwork() {
            Map<MethodKey, Origin> helperOrigins = new HashMap<>();
            for (int depth = 0; depth < 4; depth++) {
                boolean changed = false;
                for (HelperCall helper : helperCalls) {
                    if (!classNames.contains(helper.target().owner())) continue;
                    Origin callerOrigin = boundNetworkHandlers.contains(helper.caller())
                            ? Origin.REQUIRED_REGISTRAR : helperOrigins.get(helper.caller());
                    if (callerOrigin == null) continue;
                    Origin transmitted = helper.origin() == Origin.PARAMETER_REGISTRAR
                            ? callerOrigin : helper.origin();
                    if (transmitted == Origin.OPTIONAL_FROM_PARAMETER)
                        transmitted = Origin.OPTIONAL_REGISTRAR;
                    if (transmitted != Origin.REQUIRED_REGISTRAR
                            && transmitted != Origin.OPTIONAL_REGISTRAR) continue;
                    Origin prior = helperOrigins.putIfAbsent(helper.target(), transmitted);
                    if (prior == null) changed = true;
                    else if (prior != transmitted) {
                        helperOrigins.put(helper.target(), Origin.UNKNOWN);
                        problem("Conflicting registrar origins for helper " + safeText(helper.target().name()));
                    }
                }
                if (!changed) break;
            }
            for (PayloadCandidate candidate : payloadCandidates) {
                boolean directlyBound = boundNetworkHandlers.contains(candidate.handler());
                Origin helperOrigin = helperOrigins.get(candidate.handler());
                if (!directlyBound && helperOrigin == null) {
                    unresolved(candidate.location(), "Payload method is not proven bound to a mod event bus: "
                            + safeText(candidate.callName()));
                    continue;
                }
                Origin origin = candidate.origin();
                if (origin == Origin.PARAMETER_REGISTRAR) origin = helperOrigin;
                else if (origin == Origin.OPTIONAL_FROM_PARAMETER && helperOrigin != null)
                    origin = Origin.OPTIONAL_REGISTRAR;
                if (origin != Origin.REQUIRED_REGISTRAR && origin != Origin.OPTIONAL_REGISTRAR) {
                    unresolved(candidate.location(), "Payload registrar argument could not be resolved: "
                            + safeText(candidate.callName()));
                    continue;
                }
                String location = candidate.location();
                if (origin == Origin.OPTIONAL_REGISTRAR) {
                    optionalPayloads++;
                    evidence(JarEvidence.Source.NETWORK_ANALYSIS, JarEvidence.Kind.OPTIONAL_NETWORK,
                            true, location, "PayloadRegistrar.optional() -> " + safeText(candidate.callName()));
                } else {
                    requiredPayloads++;
                    evidence(JarEvidence.Source.NETWORK_ANALYSIS, JarEvidence.Kind.REQUIRED_NETWORK,
                            true, location, "Default mandatory PayloadRegistrar -> "
                                    + safeText(candidate.callName()));
                }
            }
        }

        private void recordHelperCall(MethodInsnNode call, int index, Frame<SourceValue>[] frames,
                                      InsnList instructions, MethodKey caller, int registrarParameter,
                                      String location) {
            Type[] args = Type.getArgumentTypes(call.desc);
            Frame<SourceValue> frame = frames[index];
            if (frame == null) {
                unresolved(location, "Helper registrar argument has no data-flow frame");
                return;
            }
            int first = frame.getStackSize() - args.length;
            if (first < 0) {
                unresolved(location, "Helper registrar argument stack is incomplete");
                return;
            }
            boolean found = false;
            for (int argument = 0; argument < args.length; argument++) {
                if (args[argument].getSort() != Type.OBJECT
                        || !PAYLOAD_REGISTRAR.equals(args[argument].getInternalName())) continue;
                found = true;
                Origin origin = registrarOrigin(frame.getStack(first + argument), frames,
                        instructions, registrarParameter, 0, new HashSet<>());
                if (origin == Origin.UNKNOWN || helperCalls.size() >= MAX_EVIDENCE) {
                    unresolved(location, "Helper registrar argument cannot be traced");
                } else {
                    helperCalls.add(new HelperCall(caller,
                            new MethodKey(call.owner, call.name, call.desc), origin));
                }
            }
            if (!found) unresolved(location, "Event wrapper invocation is not statically traced");
        }

        private boolean bindDirectListener(MethodInsnNode call, int index, Frame<SourceValue>[] frames) {
            if (frames[index] == null) return false;
            Frame<SourceValue> frame = frames[index];
            Type[] argumentTypes = Type.getArgumentTypes(call.desc);
            if (argumentTypes.length == 0 || frame.getStackSize() < argumentTypes.length + 1)
                return false;
            MethodKey target = null;
            int consumerCount = 0;
            int first = frame.getStackSize() - argumentTypes.length;
            for (int i = 0; i < argumentTypes.length; i++) {
                if (argumentTypes[i].getSort() != Type.OBJECT
                        || !"java/util/function/Consumer".equals(argumentTypes[i].getInternalName()))
                    continue;
                consumerCount++;
                SourceValue value = frame.getStack(first + i);
                // A merged conditional lambda has multiple possible sources.
                if (value == null || value.insns.size() != 1) return false;
                AbstractInsnNode source = value.insns.iterator().next();
                if (!(source instanceof InvokeDynamicInsnNode dynamic)
                        || !dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory"))
                    return false;
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getDesc().contains(PAYLOAD_EVENT)) {
                        if (target != null) return false;
                        target = new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc());
                    }
                }
            }
            if (consumerCount != 1 || target == null) return false;
            boundNetworkHandlers.add(target);
            return true;
        }

        private void inspectRegistryFieldWrite(FieldInsnNode field, int index,
                                               Frame<SourceValue>[] frames, InsnList instructions,
                                               ClassNode owner, MethodNode method) {
            if (!field.desc.startsWith("L" + DEFERRED_REGISTER)) return;
            FieldKey key = new FieldKey(field.owner, field.name);
            RegistryTrack track = registries.computeIfAbsent(key, ignored -> new RegistryTrack());
            if (!"<clinit>".equals(method.name) || !owner.name.equals(field.owner)
                    || frames[index] == null || frames[index].getStackSize() == 0) {
                track.unknownWrite = true;
                return;
            }
            RegistryType type = registryCreation(frames[index].getStack(frames[index].getStackSize() - 1),
                    frames, instructions, 0, new HashSet<>());
            if (type == RegistryType.NONE) track.unknownWrite = true;
            else if (track.type != RegistryType.NONE) track.unknownWrite = true;
            else track.type = type;
        }

        private void inspectRegistryCall(MethodInsnNode call, int index, Frame<SourceValue>[] frames,
                                         InsnList instructions, boolean branched, String location,
                                         ClassNode owner, MethodNode method) {
            if (branched) {
                unresolved(location, "Conditional DeferredRegister invocation");
                return;
            }
            FieldKey field = receiverField(call, index, frames, instructions, 0, new HashSet<>());
            if (field == null) {
                unresolved(location, "DeferredRegister instance cannot be traced to this JAR");
                return;
            }
            RegistryTrack track = registries.computeIfAbsent(field, ignored -> new RegistryTrack());
            if ("register".equals(call.name)) {
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length == 1 && args[0].getInternalName() != null
                        && args[0].getInternalName().equals("net/neoforged/bus/api/IEventBus")) {
                    Frame<SourceValue> frame = frames[index];
                    boolean modBusArgument = frame != null && frame.getStackSize() > 0
                            && sourceIsParameter(frame.getStack(frame.getStackSize() - 1), frames,
                                    instructions, eventBusParameterSlot(method), 0, new HashSet<>());
                    if ("<init>".equals(method.name) && isOwnModClass(owner) && modBusArgument)
                        track.attached = true;
                    else unresolved(location, "DeferredRegister attachment is not proven on @Mod constructor's mod bus");
                } else if (args.length >= 2 && args[0].getSort() == Type.OBJECT
                        && "java/lang/String".equals(args[0].getInternalName())) {
                    if ("<clinit>".equals(method.name) && owner.name.equals(field.owner()))
                        track.entries++;
                    else unresolved(location, "DeferredRegister entry is outside field owner initializer");
                } else {
                    unresolved(location, "Unknown DeferredRegister.register overload");
                }
            } else if (call.name.startsWith("register") && Type.getArgumentTypes(call.desc).length > 0
                    && Type.getArgumentTypes(call.desc)[0].getSort() == Type.OBJECT
                    && "java/lang/String".equals(Type.getArgumentTypes(call.desc)[0].getInternalName())) {
                if ("<clinit>".equals(method.name) && owner.name.equals(field.owner()))
                    track.entries++;
                else unresolved(location, "DeferredRegister helper entry is outside field owner initializer");
            } else {
                unresolved(location, "Unknown DeferredRegister method: " + safeText(call.name));
            }
        }

        private void completeRegistries() {
            for (Map.Entry<FieldKey, RegistryTrack> item : registries.entrySet()) {
                FieldKey field = item.getKey();
                RegistryTrack track = item.getValue();
                String location = safeText(field.owner() + "." + field.name());
                if (track.type == RegistryType.CLIENT_CONTENT && track.entries > 0
                        && track.attached && !track.unknownWrite
                        && classNames.contains(field.owner())) {
                    evidence(JarEvidence.Source.REGISTRY_ANALYSIS,
                            JarEvidence.Kind.REQUIRED_REGISTRY, true, location,
                            "Same DeferredRegister field creates client content, adds entries and attaches to mod bus");
                } else if (track.entries > 0 || track.attached || track.type != RegistryType.NONE
                        || track.unknownWrite) {
                    unresolved(location, "DeferredRegister creation, entries or bus attachment is incomplete");
                }
            }
        }

        private byte[] readBounded(ZipFile zip, ZipEntry entry, int maxBytes) throws IOException {
            if (entry.getSize() > maxBytes) return null;
            try (InputStream input = zip.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (timedOut()) return null;
                    if (output.size() > maxBytes - read) return null;
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }

        private void unresolved(String location, String detail) {
            evidence(JarEvidence.Source.BYTECODE_ANALYSIS,
                    JarEvidence.Kind.UNRESOLVED_REGISTRATION, false, location, detail);
            problem(detail + " at " + location);
        }

        private void evidence(JarEvidence.Source source, JarEvidence.Kind kind, boolean itemComplete,
                              String location, String detail) {
            boolean required = kind == JarEvidence.Kind.REQUIRED_NETWORK
                    || kind == JarEvidence.Kind.REQUIRED_REGISTRY;
            if (evidence.size() >= MAX_EVIDENCE) {
                problem("Evidence count limit exceeded");
                if (!required) return;
                int disposable = -1;
                for (int i = 0; i < evidence.size(); i++) {
                    JarEvidence.Kind existing = evidence.get(i).kind();
                    if (existing != JarEvidence.Kind.REQUIRED_NETWORK
                            && existing != JarEvidence.Kind.REQUIRED_REGISTRY) {
                        disposable = i;
                        break;
                    }
                }
                if (disposable < 0) return; // A required observation is already retained.
                evidence.remove(disposable);
            }
            evidence.add(new JarEvidence(source, kind, itemComplete, safeText(location), safeText(detail)));
        }

        private void problem(String message) {
            complete = false;
            String normalized = safeText(message);
            if (diagnostics.size() < MAX_DIAGNOSTICS && !diagnostics.contains(normalized)) {
                diagnostics.add(normalized);
            }
        }

        private JarAnalysisResult result() {
            if (!complete && diagnostics.isEmpty()) diagnostics.add("Static inspection is incomplete");
            return new JarAnalysisResult(evidence, complete, diagnostics,
                    classes, optionalPayloads, requiredPayloads);
        }

        private boolean isOwnModClass(ClassNode node) {
            AnnotationNode mod = findAnnotation(node.visibleAnnotations, MOD_ANNOTATION);
            if (mod == null) mod = findAnnotation(node.invisibleAnnotations, MOD_ANNOTATION);
            if (mod == null) return false;
            String id = annotationString(mod, "value");
            if (id == null || !physical.modIds().contains(id)) return false;
            Object dist = annotationValue(mod, "dist");
            if (dist == null) return true;
            List<?> sides = dist instanceof List<?> list ? list : List.of(dist);
            boolean server = false;
            for (Object side : sides) {
                if (!(side instanceof String[] holder) || holder.length != 2
                        || !holder[0].equals("Lnet/neoforged/api/distmarker/Dist;")) {
                    problem("@Mod side cannot be parsed: " + safeText(node.name));
                    return false;
                }
                if (holder[1].equals("DEDICATED_SERVER")) server = true;
                else if (!holder[1].equals("CLIENT")) {
                    problem("@Mod has unknown side: " + safeText(node.name));
                    return false;
                }
            }
            return server;
        }
    }

    private record FieldKey(String owner, String name) {}
    private record MethodKey(String owner, String name, String descriptor) {}
    private record PayloadCandidate(MethodKey handler, Origin origin, String location, String callName) {}
    private record HelperCall(MethodKey caller, MethodKey target, Origin origin) {}
    private enum RegistryType { NONE, CLIENT_CONTENT, UNKNOWN }
    private static final class RegistryTrack {
        private RegistryType type = RegistryType.NONE;
        private boolean attached;
        private int entries;
        private boolean unknownWrite;
    }
    private enum Origin {
        UNKNOWN, REQUIRED_REGISTRAR, OPTIONAL_REGISTRAR,
        PARAMETER_REGISTRAR, OPTIONAL_FROM_PARAMETER
    }

    private static Origin receiverOrigin(MethodInsnNode call, int index, Frame<SourceValue>[] frames,
                                         InsnList instructions, int registrarParameter,
                                         int depth, Set<AbstractInsnNode> visited) {
        SourceValue value = receiverValue(call, index, frames);
        return registrarOrigin(value, frames, instructions, registrarParameter, depth, visited);
    }

    private static Origin registrarOrigin(SourceValue value, Frame<SourceValue>[] frames,
                                          InsnList instructions, int registrarParameter, int depth,
                                          Set<AbstractInsnNode> visited) {
        if (value == null || depth > 16 || value.insns.isEmpty()) return Origin.UNKNOWN;
        Origin result = null;
        for (AbstractInsnNode source : value.insns) {
            if (!visited.add(source)) return Origin.UNKNOWN;
            Origin next = Origin.UNKNOWN;
            if (source instanceof VarInsnNode local && local.getOpcode() == Opcodes.ALOAD) {
                int at = instructions.indexOf(source);
                if (at >= 0 && frames[at] != null && local.var < frames[at].getLocals()) {
                    SourceValue localValue = frames[at].getLocal(local.var);
                    next = registrarParameter == local.var && localValue != null
                            && localValue.insns.isEmpty() ? Origin.PARAMETER_REGISTRAR
                            : registrarOrigin(localValue, frames, instructions,
                                    registrarParameter, depth + 1, new HashSet<>(visited));
                }
            } else if (source instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
                int at = instructions.indexOf(source);
                if (at >= 0 && frames[at] != null && frames[at].getStackSize() > 0) {
                    next = registrarOrigin(frames[at].getStack(frames[at].getStackSize() - 1),
                            frames, instructions, registrarParameter, depth + 1,
                            new HashSet<>(visited));
                }
            } else if (source instanceof MethodInsnNode call) {
                if (PAYLOAD_EVENT.equals(call.owner) && "registrar".equals(call.name)) {
                    next = Origin.REQUIRED_REGISTRAR;
                } else if (PAYLOAD_REGISTRAR.equals(call.owner)) {
                    int at = instructions.indexOf(source);
                    Origin parent = at < 0 ? Origin.UNKNOWN
                            : receiverOrigin(call, at, frames, instructions, registrarParameter, depth + 1,
                                    new HashSet<>(visited));
                    if ("optional".equals(call.name) && parent != Origin.UNKNOWN) {
                        next = parent == Origin.PARAMETER_REGISTRAR
                                || parent == Origin.OPTIONAL_FROM_PARAMETER
                                ? Origin.OPTIONAL_FROM_PARAMETER : Origin.OPTIONAL_REGISTRAR;
                    } else if ("executesOn".equals(call.name) || "versioned".equals(call.name)) {
                        next = parent;
                    }
                }
            }
            if (result != null && result != next) return Origin.UNKNOWN;
            result = next;
        }
        return result == null ? Origin.UNKNOWN : result;
    }

    private static FieldKey receiverField(MethodInsnNode call, int index, Frame<SourceValue>[] frames,
                                          InsnList instructions, int depth,
                                          Set<AbstractInsnNode> visited) {
        return sourceField(receiverValue(call, index, frames), frames, instructions, depth, visited);
    }

    private static FieldKey sourceField(SourceValue value, Frame<SourceValue>[] frames,
                                        InsnList instructions, int depth,
                                        Set<AbstractInsnNode> visited) {
        if (value == null || depth > 16 || value.insns.size() != 1) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        if (!visited.add(source)) return null;
        if (source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
            return new FieldKey(field.owner, field.name);
        }
        if (source instanceof VarInsnNode local && local.getOpcode() == Opcodes.ALOAD) {
            int at = instructions.indexOf(source);
            if (at >= 0 && frames[at] != null && local.var < frames[at].getLocals()) {
                return sourceField(frames[at].getLocal(local.var), frames, instructions,
                        depth + 1, visited);
            }
        }
        if (source instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
            int at = instructions.indexOf(source);
            if (at >= 0 && frames[at] != null && frames[at].getStackSize() > 0) {
                return sourceField(frames[at].getStack(frames[at].getStackSize() - 1),
                        frames, instructions, depth + 1, visited);
            }
        }
        return null;
    }

    private static RegistryType registryCreation(SourceValue value, Frame<SourceValue>[] frames,
                                                 InsnList instructions, int depth,
                                                 Set<AbstractInsnNode> visited) {
        if (value == null || depth > 16 || value.insns.size() != 1) return RegistryType.NONE;
        AbstractInsnNode source = value.insns.iterator().next();
        if (!visited.add(source)) return RegistryType.NONE;
        if (source instanceof MethodInsnNode call && DEFERRED_REGISTER.equals(call.owner)) {
            return switch (call.name) {
                case "createBlocks", "createItems", "createEntities" -> RegistryType.CLIENT_CONTENT;
                case "create", "createDataComponents" -> RegistryType.UNKNOWN;
                default -> RegistryType.NONE;
            };
        }
        if (source instanceof VarInsnNode local && local.getOpcode() == Opcodes.ALOAD) {
            int at = instructions.indexOf(source);
            if (at >= 0 && frames[at] != null && local.var < frames[at].getLocals()) {
                return registryCreation(frames[at].getLocal(local.var), frames, instructions,
                        depth + 1, visited);
            }
        }
        if (source instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
            int at = instructions.indexOf(source);
            if (at >= 0 && frames[at] != null && frames[at].getStackSize() > 0) {
                return registryCreation(frames[at].getStack(frames[at].getStackSize() - 1),
                        frames, instructions, depth + 1, visited);
            }
        }
        return RegistryType.NONE;
    }

    private static SourceValue receiverValue(MethodInsnNode call, int index,
                                             Frame<SourceValue>[] frames) {
        if (index < 0 || frames[index] == null || call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Frame<SourceValue> frame = frames[index];
        int at = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
        return at < 0 ? null : frame.getStack(at);
    }

    private static boolean isPayloadRegistration(MethodInsnNode call) {
        if (!(call.name.startsWith("play") || call.name.startsWith("configuration")
                || call.name.startsWith("common"))) return false;
        if (!(call.name.endsWith("ToClient") || call.name.endsWith("ToServer")
                || call.name.endsWith("Bidirectional"))) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        return args.length >= 2 && args[0].getSort() == Type.OBJECT
                && args[0].getInternalName().equals("net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type");
    }

    private static boolean hasArgumentType(MethodInsnNode call, String internalName) {
        for (Type argument : Type.getArgumentTypes(call.desc)) {
            if (argument.getSort() == Type.OBJECT && internalName.equals(argument.getInternalName()))
                return true;
        }
        return false;
    }

    /** The call must dominate every normal return in this single constructor. */
    private static boolean mustRunOnNormalReturn(MethodNode method, int callIndex,
                                                  Frame<SourceValue>[] frames) {
        InsnList instructions = method.instructions;
        if (callIndex < 0 || frames[callIndex] == null || method.tryCatchBlocks.size() > 0)
            return false;
        boolean[] visited = new boolean[instructions.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        boolean anyNormalReturn = false;
        while (!queue.isEmpty()) {
            int at = queue.removeFirst();
            if (at < 0 || at >= instructions.size() || visited[at] || at == callIndex) continue;
            visited[at] = true;
            AbstractInsnNode insn = instructions.get(at);
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                anyNormalReturn = true;
                return false;
            }
            if (opcode == Opcodes.ATHROW) continue;
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) return false;
            if (insn instanceof JumpInsnNode jump) {
                queue.add(instructions.indexOf(jump.label));
                if (opcode != Opcodes.GOTO) queue.add(at + 1);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                queue.add(instructions.indexOf(lookup.dflt));
                for (org.objectweb.asm.tree.LabelNode label : lookup.labels)
                    queue.add(instructions.indexOf(label));
            } else if (insn instanceof TableSwitchInsnNode table) {
                queue.add(instructions.indexOf(table.dflt));
                for (org.objectweb.asm.tree.LabelNode label : table.labels)
                    queue.add(instructions.indexOf(label));
            } else {
                queue.add(at + 1);
            }
        }
        // A constructor with no normal return is not a usable binding proof.
        return !anyNormalReturn && method.instructions.size() > callIndex + 1;
    }

    private static int eventBusParameterSlot(MethodNode method) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int result = -1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT
                    && "net/neoforged/bus/api/IEventBus".equals(argument.getInternalName())) {
                if (result != -1) return -1;
                result = slot;
            }
            slot += argument.getSize();
        }
        return result;
    }

    private static boolean isModBusParameterReceiver(MethodInsnNode call, int index,
                                                      Frame<SourceValue>[] frames,
                                                      InsnList instructions, int busSlot) {
        return busSlot >= 0 && sourceIsParameter(receiverValue(call, index, frames),
                frames, instructions, busSlot, 0, new HashSet<>());
    }

    private static boolean sourceIsParameter(SourceValue value, Frame<SourceValue>[] frames,
                                             InsnList instructions, int slot, int depth,
                                             Set<AbstractInsnNode> visited) {
        if (value == null || depth > 16 || value.insns.size() != 1) return false;
        AbstractInsnNode source = value.insns.iterator().next();
        if (!visited.add(source)) return false;
        int at = instructions.indexOf(source);
        if (at < 0 || frames[at] == null) return false;
        if (source instanceof VarInsnNode local && local.getOpcode() == Opcodes.ALOAD) {
            SourceValue prior = frames[at].getLocal(local.var);
            if (local.var == slot && prior != null && prior.insns.isEmpty()) return true;
            return sourceIsParameter(prior, frames, instructions, slot, depth + 1, visited);
        }
        if (source instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                && frames[at].getStackSize() > 0) {
            return sourceIsParameter(frames[at].getStack(frames[at].getStackSize() - 1),
                    frames, instructions, slot, depth + 1, visited);
        }
        return false;
    }

    private static boolean isRegistrarConfiguration(String name) {
        return name.equals("optional") || name.equals("executesOn") || name.equals("versioned");
    }

    private static boolean isRegistryCall(MethodInsnNode call) {
        return (DEFERRED_REGISTER.equals(call.owner) || call.owner.startsWith(DEFERRED_REGISTER + "$"))
                && call.name.startsWith("register");
    }

    private static boolean isReflective(MethodInsnNode call) {
        return call.owner.startsWith("java/lang/reflect/")
                || call.owner.startsWith("java/lang/invoke/MethodHandles")
                || call.owner.equals("java/lang/Class") && call.name.equals("forName")
                || call.owner.equals("java/lang/ClassLoader")
                        && (call.name.equals("loadClass") || call.name.startsWith("defineClass"));
    }

    private static String annotationString(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        return value instanceof String text ? text : null;
    }

    private static ClientRequirementClassifier.EntrypointSide modEntrypointSide(
            AnnotationNode annotation) {
        Object dist = annotationValue(annotation, "dist");
        if (dist == null) return ClientRequirementClassifier.EntrypointSide.DEFAULT;
        if (!(dist instanceof List<?> sides) || sides.isEmpty()) return null;
        boolean client = false;
        boolean server = false;
        for (Object side : sides) {
            if (!(side instanceof String[] holder) || holder.length != 2
                    || !"Lnet/neoforged/api/distmarker/Dist;".equals(holder[0])) return null;
            switch (holder[1]) {
                case "CLIENT" -> client = true;
                case "DEDICATED_SERVER" -> server = true;
                default -> { return null; }
            }
        }
        if (client && server) return ClientRequirementClassifier.EntrypointSide.BOTH;
        return client ? ClientRequirementClassifier.EntrypointSide.CLIENT
                : ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String modId(ClassNode node) {
        List<AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);
        for (AnnotationNode annotation : all) {
            if (MOD_ANNOTATION.equals(annotation.desc)) return annotationString(annotation, "value");
        }
        return null;
    }

    private static int registrarParameterSlot(MethodNode method) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT && PAYLOAD_REGISTRAR.equals(argument.getInternalName()))
                return slot;
            slot += argument.getSize();
        }
        return -1;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations != null && annotations.stream().anyMatch(item -> descriptor.equals(item.desc));
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isBlank() || name.length() > 512 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String component : name.split("/")) {
            if (component.equals("..") || component.equals(".")) return false;
        }
        return true;
    }

    private static String safeText(String value) {
        if (value == null) return "unknown";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 512));
        for (int i = 0; i < value.length() && safe.length() < 512; i++) {
            char c = value.charAt(i);
            safe.append(Character.isISOControl(c) ? '?' : c);
        }
        return safe.toString();
    }
}
