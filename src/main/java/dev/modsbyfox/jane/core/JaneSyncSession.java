package dev.modsbyfox.jane.core;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe state shared by the background services and read-only screens. */
public final class JaneSyncSession {
    public enum RuntimeState { WAITING, RESOLVING, DOWNLOADING, VERIFYING, READY, FAILED }
    public record ItemState(ResolutionPlan.Item item, RuntimeState state, long downloadedBytes) { }
    public record Snapshot(ResolutionPlan resolution, List<ItemState> items, boolean running, boolean started,
                           String error) {
        public long totalBytes() {
            return items.stream().filter(i -> i.item().classification() == ResolutionPlan.Classification.DOWNLOADABLE)
                    .mapToLong(i -> i.item().comparison().required().fileSize()).sum();
        }
        public long downloadedBytes() {
            return items.stream().filter(i -> i.item().classification() == ResolutionPlan.Classification.DOWNLOADABLE)
                    .mapToLong(ItemState::downloadedBytes).sum();
        }
        public int progressPercent() {
            long total = totalBytes();
            return total == 0 ? 0 : (int) Math.min(100, downloadedBytes() * 100 / total);
        }
        public long readyCount() {
            return items.stream().filter(i -> i.state() == RuntimeState.READY).count();
        }
        public long failedCount() {
            return items.stream().filter(i -> i.state() == RuntimeState.FAILED).count();
        }
        public long processedCount() {
            return items.stream().filter(i -> i.item().classification() == ResolutionPlan.Classification.DOWNLOADABLE
                    && i.state() != RuntimeState.WAITING).count();
        }
        public boolean canInstall() {
            return resolution != null && !running && error == null && resolution.count(ResolutionPlan.Classification.UNRESOLVED) == 0
                    && items.stream().allMatch(i -> i.item().classification() == ResolutionPlan.Classification.ALREADY_PRESENT
                    || i.state() == RuntimeState.READY);
        }
    }

    private final PendingSyncContext context;
    private volatile Snapshot snapshot = new Snapshot(null, List.of(), false, false, null);

    public JaneSyncSession(PendingSyncContext context) { this.context = context; }
    public PendingSyncContext context() { return context; }
    public Snapshot snapshot() { return snapshot; }

    public synchronized void publishResolution(ResolutionPlan resolution) {
        if (snapshot.resolution() != null) throw new IllegalStateException("Resolution already published");
        List<ItemState> states = resolution.items().stream().map(item -> new ItemState(item,
                item.classification() == ResolutionPlan.Classification.DOWNLOADABLE ? RuntimeState.WAITING : null, 0)).toList();
        snapshot = new Snapshot(resolution, states, false, false, null);
    }

    public synchronized boolean startDownloads() {
        if (snapshot.resolution() == null || snapshot.running() || snapshot.started()
                || snapshot.resolution().downloadQueue().isEmpty()) return false;
        snapshot = new Snapshot(snapshot.resolution(), snapshot.items(), true, true, null);
        return true;
    }

    public synchronized void update(String modId, RuntimeState state, long bytes) {
        if (!snapshot.running()) throw new IllegalStateException("Download task is not running");
        List<ItemState> next = new ArrayList<>(snapshot.items());
        for (int i = 0; i < next.size(); i++) {
            ItemState old = next.get(i);
            if (old.item().comparison().required().modId().equals(modId)) {
                if (old.item().classification() != ResolutionPlan.Classification.DOWNLOADABLE) throw new IllegalArgumentException("Not downloadable");
                long total = old.item().comparison().required().fileSize();
                next.set(i, new ItemState(old.item(), state, Math.max(0, Math.min(total, bytes))));
                snapshot = new Snapshot(snapshot.resolution(), List.copyOf(next), true, true, snapshot.error());
                return;
            }
        }
        throw new IllegalArgumentException("Unknown required mod");
    }

    public synchronized void finish(String error) {
        snapshot = new Snapshot(snapshot.resolution(), snapshot.items(), false, snapshot.started(), error);
    }
}
