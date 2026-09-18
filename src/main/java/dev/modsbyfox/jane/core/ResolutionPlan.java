package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Classification of the server's required files, independent of download progress. */
public record ResolutionPlan(List<Item> items) {
    public enum Classification { ALREADY_PRESENT, MODRINTH_DOWNLOADABLE, SERVER_DOWNLOADABLE, UNRESOLVED }
    public enum Provider { MODRINTH, SERVER }
    public record Source(Provider provider, String name, URI uri, long size) {
        public Source(String name, URI uri, long size) { this(Provider.MODRINTH, name, uri, size); }
        public Source {
            java.util.Objects.requireNonNull(provider, "provider");
            PathSafety.safeJarName(name);
            if (size <= 0) throw new IllegalArgumentException("Invalid source size");
            if (provider == Provider.SERVER && uri != null) throw new IllegalArgumentException("Server source cannot have a URL");
            if (provider == Provider.MODRINTH && (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                    || !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1)) {
                throw new IllegalArgumentException("Untrusted Modrinth source");
            }
        }
    }
    public static Source serverSource(ManifestEntry entry) {
        return new Source(Provider.SERVER, PathSafety.safeJarName("jane-" + entry.modId() + "-"
                + entry.sha512().substring(0, 12) + ".jar"), null, entry.fileSize());
    }
    public record Item(Comparison.Result comparison, Classification classification, Source source) {
        public Item {
            java.util.Objects.requireNonNull(comparison, "comparison");
            java.util.Objects.requireNonNull(classification, "classification");
            if ((classification == Classification.MODRINTH_DOWNLOADABLE || classification == Classification.SERVER_DOWNLOADABLE)
                    != (source != null)) {
                throw new IllegalArgumentException("Downloadable items require a source");
            }
            if (source != null && source.size() != comparison.required().fileSize()) {
                throw new IllegalArgumentException("Source size differs from required file");
            }
            if (source != null && ((classification == Classification.MODRINTH_DOWNLOADABLE) !=
                    (source.provider() == Provider.MODRINTH))) throw new IllegalArgumentException("Source provider mismatch");
        }
    }
    @FunctionalInterface public interface Resolver {
        Optional<Source> find(ManifestEntry target) throws IOException, InterruptedException;
    }
    @FunctionalInterface public interface Progress { void processed(int count); }

    public ResolutionPlan {
        items = List.copyOf(items);
    }

    public static ResolutionPlan resolve(List<Comparison.Result> comparisons, Resolver resolver) throws InterruptedException {
        return resolve(comparisons, resolver, false, count -> { });
    }

    public static ResolutionPlan resolve(List<Comparison.Result> comparisons, Resolver resolver,
                                         boolean serverAvailable, Progress progress) throws InterruptedException {
        List<Item> items = new ArrayList<>(comparisons.size());
        for (Comparison.Result comparison : comparisons) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (comparison.status() == Comparison.Status.OK) {
                items.add(new Item(comparison, Classification.ALREADY_PRESENT, null));
                progress.processed(items.size());
                continue;
            }
            try {
                Optional<Source> source = resolver.find(comparison.required());
                items.add(source.map(value -> new Item(comparison, Classification.MODRINTH_DOWNLOADABLE, value))
                        .orElseGet(() -> serverAvailable
                                ? new Item(comparison, Classification.SERVER_DOWNLOADABLE, serverSource(comparison.required()))
                                : new Item(comparison, Classification.UNRESOLVED, null)));
            } catch (IOException exception) {
                // A lookup failure belongs to this file, not to the whole sync session.
                items.add(new Item(comparison, Classification.UNRESOLVED, null));
            }
            progress.processed(items.size());
        }
        return new ResolutionPlan(items);
    }

    public List<Item> downloadQueue() {
        return queue(Classification.MODRINTH_DOWNLOADABLE);
    }

    public List<Item> queue(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification).toList();
    }

    public long count(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification).count();
    }

    public long size(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification)
                .mapToLong(item -> item.comparison().required().fileSize()).sum();
    }
}
