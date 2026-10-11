package dev.modsbyfox.jane.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Per-connection install choices. The comparison against Required remains unchanged. */
public final class SyncSelection {
    private final PendingSyncContext context;
    private final String manifestDigest;
    private final Map<String, Comparison.Result> comparisons;
    private final Map<String, ClientInstallCategory> categories = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> touched = new LinkedHashSet<>();
    private Instant selectionTimestamp = Instant.now();
    private long revision;
    private boolean choicesFrozen;

    public SyncSelection(PendingSyncContext context) { this(context, Map.of()); }

    public SyncSelection(PendingSyncContext context, Map<String, ClientInstallCategory> initialCategories) {
        this.context = Objects.requireNonNull(context, "context");
        manifestDigest = OneShotJoinOverride.manifestDigest(context.manifest());
        List<ManifestEntry> entries = context.manifest().entries();
        if (entries.size() != context.results().size())
            throw new IllegalArgumentException("Comparison does not match manifest");
        Map<String, Comparison.Result> indexed = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Comparison.Result result = context.results().get(i);
            if (result == null || !entries.get(i).equals(result.required()))
                throw new IllegalArgumentException("Comparison does not match manifest");
            String id = entries.get(i).modId();
            indexed.put(id, result);
            categories.put(id, ClientInstallCategory.SERVER_REQUIRED);
            selected.add(id);
        }
        comparisons = Map.copyOf(indexed);
        applyCategories(initialCategories);
    }

    public String serverId() { return context.serverId(); }
    public String manifestDigest() { return manifestDigest; }
    public synchronized Instant selectionTimestamp() { return selectionTimestamp; }
    public synchronized long revision() { return revision; }
    public synchronized void freezeChoices() { choicesFrozen = true; }

    /** Exact-file metadata may offer advice, but cannot silently deselect a Required entry. */
    public synchronized void applyCategories(Map<String, ClientInstallCategory> resolved) {
        Objects.requireNonNull(resolved, "resolved");
        resolved.forEach((id, category) -> {
            requireKnown(id);
            Objects.requireNonNull(category, "category");
        });
        resolved.forEach((id, advice) -> {
            ClientInstallCategory category = comparisons.get(id).status() == Comparison.Status.FILE_ERROR
                    ? ClientInstallCategory.SERVER_REQUIRED : advice;
            if (categories.put(id, category) != category) {
                revision++;
                if (!choicesFrozen && !touched.contains(id) && selected.add(id))
                    selectionTimestamp = Instant.now();
            }
        });
    }

    public synchronized ClientInstallCategory category(String modId) {
        requireKnown(modId);
        return categories.get(modId);
    }

    public synchronized Map<String, ClientInstallCategory> categories() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(categories));
    }

    public synchronized boolean isSelected(String modId) {
        requireKnown(modId);
        return selected.contains(modId);
    }

    public synchronized void setSelected(String modId, boolean value) {
        requireKnown(modId);
        if (choicesFrozen) throw new IllegalStateException("Sync selection is frozen after transfer starts");
        touched.add(modId);
        if (value) selected.add(modId); else selected.remove(modId);
        revision++;
        selectionTimestamp = Instant.now();
    }

    public synchronized Set<String> selectedModIds() { return immutable(selected); }
    public synchronized Set<String> selectedUnmatchedModIds() { return filtered(true, null, false); }
    public synchronized Set<String> deselectedRequiredModIds() {
        return filtered(false, ClientInstallCategory.SERVER_REQUIRED, false);
    }
    public synchronized Set<String> defaultOptionalExclusionModIds() {
        return filtered(false, ClientInstallCategory.CLIENT_OPTIONAL, true);
    }

    public synchronized String digest() {
        StringBuilder value = new StringBuilder(serverId()).append('\n').append(manifestDigest).append('\n');
        categories.forEach((id, category) -> value.append(id).append(':').append(category.name()).append(':')
                .append(selected.contains(id) ? '1' : '0').append('\n'));
        return Hashing.sha256(value.toString());
    }

    private Set<String> filtered(boolean wantSelected, ClientInstallCategory category, boolean untouchedOnly) {
        Set<String> ids = new LinkedHashSet<>();
        categories.forEach((id, advice) -> {
            if (comparisons.get(id).status() == Comparison.Status.OK || selected.contains(id) != wantSelected)
                return;
            if (category != null && category != advice) return;
            if (untouchedOnly && touched.contains(id)) return;
            ids.add(id);
        });
        return immutable(ids);
    }

    private void requireKnown(String modId) {
        if (!categories.containsKey(modId)) throw new IllegalArgumentException("Unknown manifest mod ID");
    }

    private static Set<String> immutable(Set<String> ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }
}
