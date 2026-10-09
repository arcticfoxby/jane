package dev.modsbyfox.jane.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A per-connection installation choice, separate from the objective required-file comparison. */
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
        Map<String, Comparison.Result> byId = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Comparison.Result result = context.results().get(index);
            if (result == null || !entries.get(index).equals(result.required()))
                throw new IllegalArgumentException("Comparison does not match manifest");
            String modId = entries.get(index).modId();
            byId.put(modId, result);
            categories.put(modId, ClientInstallCategory.SERVER_REQUIRED);
            selected.add(modId);
        }
        comparisons = Map.copyOf(byId);
        applyCategories(initialCategories);
    }

    public String serverId() { return context.serverId(); }
    public String manifestDigest() { return manifestDigest; }
    public synchronized Instant selectionTimestamp() { return selectionTimestamp; }
    public synchronized long revision() { return revision; }
    public synchronized void freezeChoices() { choicesFrozen = true; }

    /** A partial update from exact-file source resolution; manually changed rows keep their choice. */
    public synchronized void applyCategories(Map<String, ClientInstallCategory> resolved) {
        Objects.requireNonNull(resolved, "resolved");
        for (Map.Entry<String, ClientInstallCategory> entry : resolved.entrySet()) {
            requireKnown(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "category");
        }
        for (Map.Entry<String, ClientInstallCategory> entry : resolved.entrySet()) {
            String modId = entry.getKey();
            // A local file error is never a safe optional installation decision.
            ClientInstallCategory category = comparisons.get(modId).status() == Comparison.Status.FILE_ERROR
                    ? ClientInstallCategory.SERVER_REQUIRED : entry.getValue();
            if (categories.put(modId, category) != category) {
                revision++;
                // Sync is an affirmative action: every unmatched manifest entry starts selected,
                // including candidates whose client necessity remains uncertain.
                if (!choicesFrozen && !touched.contains(modId) && selected.add(modId))
                    selectionTimestamp = Instant.now();
            }
        }
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

    /** Changing a matched row never changes the comparison or removes its physical JAR. */
    public synchronized void setSelected(String modId, boolean value) {
        requireKnown(modId);
        if (choicesFrozen) throw new IllegalStateException("Sync selection is frozen after transfer starts");
        touched.add(modId);
        if (value) selected.add(modId);
        else selected.remove(modId);
        revision++;
        selectionTimestamp = Instant.now();
    }

    public synchronized Set<String> selectedModIds() { return immutableIds(selected); }

    public synchronized Set<String> selectedUnmatchedModIds() {
        return filteredIds(true, null, false);
    }

    public synchronized Set<String> deselectedRequiredModIds() {
        return filteredIds(false, ClientInstallCategory.SERVER_REQUIRED, false);
    }

    public synchronized Set<String> defaultOptionalExclusionModIds() {
        return filteredIds(false, ClientInstallCategory.CLIENT_OPTIONAL, true);
    }

    /** Stable SHA-256 over this server, manifest and all current per-entry choices. */
    public synchronized String digest() {
        StringBuilder value = new StringBuilder(serverId()).append('\n').append(manifestDigest).append('\n');
        for (String modId : categories.keySet()) {
            value.append(modId).append(':').append(categories.get(modId).name()).append(':')
                    .append(selected.contains(modId) ? '1' : '0').append('\n');
        }
        return Hashing.sha256(value.toString());
    }

    private Set<String> filteredIds(boolean wantSelected, ClientInstallCategory category, boolean untouchedOnly) {
        Set<String> result = new LinkedHashSet<>();
        for (String modId : categories.keySet()) {
            if (comparisons.get(modId).status() == Comparison.Status.OK || selected.contains(modId) != wantSelected)
                continue;
            if (category != null && categories.get(modId) != category) continue;
            if (untouchedOnly && touched.contains(modId)) continue;
            result.add(modId);
        }
        return immutableIds(result);
    }

    private void requireKnown(String modId) {
        if (!categories.containsKey(modId)) throw new IllegalArgumentException("Unknown manifest mod ID");
    }

    private static Set<String> immutableIds(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
