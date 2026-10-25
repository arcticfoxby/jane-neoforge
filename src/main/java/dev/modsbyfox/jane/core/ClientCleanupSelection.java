package dev.modsbyfox.jane.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Local, connection-bound selection of physical client JARs to move after exit. */
public final class ClientCleanupSelection {
    public record SelectedJar(String filename, List<String> containedModIds, String sha512,
                              ClientCompatibilityReport.Kind kind) {
        public SelectedJar { containedModIds = List.copyOf(containedModIds); }
    }

    public record Snapshot(String serverId, String manifestDigest, String inventoryDigest,
                           String graphFingerprint, long revision, List<SelectedJar> selectedJars) {
        public Snapshot { selectedJars = List.copyOf(selectedJars); }
    }

    private final String serverId;
    private final String manifestDigest;
    private final ClientCompatibilityReport report;
    private final Map<String, ClientCompatibilityReport.ExtraMod> byFilename;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final Map<String, ClientCleanupGraph.Decision> decisionCache = new HashMap<>();
    private ClientCleanupGraph.Evaluation currentEvaluation;
    private long revision;

    public ClientCleanupSelection(PendingSyncContext context, ClientCompatibilityReport report) {
        this.serverId = Objects.requireNonNull(context, "context").serverId();
        this.manifestDigest = OneShotJoinOverride.manifestDigest(context.manifest());
        this.report = Objects.requireNonNull(report, "report");
        Map<String, ClientCompatibilityReport.ExtraMod> files = new HashMap<>();
        for (ClientCompatibilityReport.ExtraMod mod : inventory()) {
            if (files.putIfAbsent(mod.filename(), mod) != null)
                throw new IllegalArgumentException("Duplicate physical extra JAR");
        }
        byFilename = Map.copyOf(files);
        Set<String> candidates = new LinkedHashSet<>();
        for (ClientCompatibilityReport.ExtraMod mod : report.declaredServerOnlyMods())
            if (mod.verified()) candidates.add(mod.filename());
        selected.addAll(report.cleanupGraph().safeDefaultSelection(candidates));
        removeBlockedServerSelections();
    }

    private List<ClientCompatibilityReport.ExtraMod> inventory() {
        List<ClientCompatibilityReport.ExtraMod> all = new ArrayList<>(report.explicitClientMods());
        all.addAll(report.otherExtraMods());
        return all;
    }

    public synchronized Set<String> selectedFilenames() { return Set.copyOf(selected); }
    public synchronized boolean isSelected(String filename) { return selected.contains(filename); }
    public synchronized long revision() { return revision; }

    public synchronized List<ClientCompatibilityReport.ExtraMod> selectedExtras() {
        return inventory().stream().filter(mod -> selected.contains(mod.filename()))
                .sorted(Comparator.comparing(ClientCompatibilityReport.ExtraMod::filename)).toList();
    }

    public synchronized long selectedServerCount() {
        return selectedExtras().stream().filter(mod -> mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER).count();
    }

    public synchronized long blockedServerCount() {
        return report.declaredServerOnlyMods().stream().filter(mod -> {
            ClientCleanupGraph.Decision decision = serverDecision(mod.filename());
            return decision == null || !decision.eligible();
        }).count();
    }

    public synchronized ClientCleanupGraph.Decision serverDecision(String filename) {
        ClientCompatibilityReport.ExtraMod mod = byFilename.get(filename);
        if (mod == null || mod.kind() != ClientCompatibilityReport.Kind.EXPLICIT_SERVER || !mod.verified())
            return null;
        if (selected.contains(filename)) return currentEvaluation().decision(filename);
        if (decisionCache.containsKey(filename)) return decisionCache.get(filename);
        Set<String> proposed = new LinkedHashSet<>(selected);
        proposed.add(filename);
        ClientCleanupGraph.Decision decision = report.cleanupGraph().evaluate(proposed).decision(filename);
        decisionCache.put(filename, decision);
        return decision;
    }

    /** Returns false for an unsafe or unknown selection; no files are changed here. */
    public synchronized boolean setSelected(String filename, boolean wanted) {
        ClientCompatibilityReport.ExtraMod mod = byFilename.get(filename);
        if (mod == null || !mod.verified() || mod.kind() == ClientCompatibilityReport.Kind.OTHER_EXTRA)
            return false;
        if (selected.contains(filename) == wanted) return true;
        if (wanted) {
            Set<String> proposed = new LinkedHashSet<>(selected);
            proposed.add(filename);
            if (mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER) {
                ClientCleanupGraph.Decision decision = report.cleanupGraph().evaluate(proposed)
                        .byFilename().get(filename);
                if (decision == null || !decision.eligible()) return false;
            }
            selected.add(filename);
        } else {
            selected.remove(filename);
        }
        invalidateEvaluation();
        removeBlockedServerSelections();
        revision++;
        return true;
    }

    private void removeBlockedServerSelections() {
        boolean changed;
        do {
            changed = false;
            Map<String, ClientCleanupGraph.Decision> decisions = currentEvaluation().byFilename();
            for (String filename : List.copyOf(selected)) {
                ClientCompatibilityReport.ExtraMod mod = byFilename.get(filename);
                if (mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER) {
                    ClientCleanupGraph.Decision decision = decisions.get(filename);
                    if (decision == null || !decision.eligible()) {
                        selected.remove(filename);
                        changed = true;
                    }
                }
            }
            if (changed) invalidateEvaluation();
        } while (changed);
    }

    /** Captures all selected physical files and the complete dependency/inventory evidence. */
    public synchronized Snapshot snapshot() {
        List<SelectedJar> jars = selectedExtras().stream()
                .map(mod -> new SelectedJar(mod.filename(), mod.containedModIds(), mod.sha512(), mod.kind()))
                .toList();
        return new Snapshot(serverId, manifestDigest, inventoryDigest(),
                report.cleanupGraph().fingerprint(), revision, jars);
    }

    public synchronized boolean stillMatches(Snapshot expected, PendingSyncContext context,
                                             ClientCompatibilityReport currentReport) {
        if (expected == null || context == null || currentReport == null
                || !serverId.equals(context.serverId())
                || !manifestDigest.equals(OneShotJoinOverride.manifestDigest(context.manifest()))
                || !snapshot().equals(expected)
                || !inventoryDigest(currentReport).equals(expected.inventoryDigest())
                || !currentReport.cleanupGraph().fingerprint().equals(expected.graphFingerprint())) return false;
        Map<String, ClientCompatibilityReport.ExtraMod> fresh = new HashMap<>();
        for (ClientCompatibilityReport.ExtraMod mod : inventory(currentReport))
            if (fresh.putIfAbsent(mod.filename(), mod) != null) return false;
        for (SelectedJar jar : expected.selectedJars()) {
            ClientCompatibilityReport.ExtraMod mod = fresh.get(jar.filename());
            if (mod == null || !mod.verified() || !mod.sha512().equals(jar.sha512())
                    || mod.kind() != jar.kind() || !mod.containedModIds().equals(jar.containedModIds()))
                return false;
        }
        Map<String, ClientCleanupGraph.Decision> decisions = currentReport.cleanupGraph()
                .evaluate(Set.copyOf(selected)).byFilename();
        for (SelectedJar jar : expected.selectedJars())
            if (jar.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER) {
                ClientCleanupGraph.Decision decision = decisions.get(jar.filename());
                if (decision == null || !decision.eligible()) return false;
            }
        return true;
    }

    public synchronized boolean selectedServerDecisionsSafe() {
        Map<String, ClientCleanupGraph.Decision> decisions = currentEvaluation().byFilename();
        for (String filename : selected) {
            ClientCompatibilityReport.ExtraMod mod = byFilename.get(filename);
            if (mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER) {
                ClientCleanupGraph.Decision decision = decisions.get(filename);
                if (decision == null || !decision.eligible()) return false;
            }
        }
        return true;
    }

    private ClientCleanupGraph.Evaluation currentEvaluation() {
        if (currentEvaluation == null)
            currentEvaluation = report.cleanupGraph().evaluate(Set.copyOf(selected));
        return currentEvaluation;
    }

    private void invalidateEvaluation() {
        currentEvaluation = null;
        decisionCache.clear();
    }

    private String inventoryDigest() { return inventoryDigest(report); }

    private static List<ClientCompatibilityReport.ExtraMod> inventory(ClientCompatibilityReport report) {
        List<ClientCompatibilityReport.ExtraMod> all = new ArrayList<>(report.explicitClientMods());
        all.addAll(report.otherExtraMods());
        return all;
    }

    private static String inventoryDigest(ClientCompatibilityReport report) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (ClientCompatibilityReport.ExtraMod mod : inventory(report).stream()
                    .sorted(Comparator.comparing(ClientCompatibilityReport.ExtraMod::filename)).toList()) {
                update(digest, mod.filename());
                update(digest, mod.modId());
                update(digest, mod.version());
                update(digest, mod.kind().name());
                update(digest, mod.sha512() == null ? "" : mod.sha512());
                for (String id : mod.containedModIds().stream().sorted().toList()) update(digest, id);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
