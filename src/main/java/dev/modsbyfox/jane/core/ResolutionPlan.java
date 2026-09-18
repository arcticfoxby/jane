package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Classification of the server's required files, independent of download progress. */
public record ResolutionPlan(List<Item> items) {
    public enum Classification { ALREADY_PRESENT, DOWNLOADABLE, UNRESOLVED }
    public record Source(String name, URI uri, long size) {
        public Source {
            PathSafety.safeJarName(name);
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                    || !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || size <= 0) {
                throw new IllegalArgumentException("Untrusted Modrinth source");
            }
        }
    }
    public record Item(Comparison.Result comparison, Classification classification, Source source) {
        public Item {
            if ((classification == Classification.DOWNLOADABLE) != (source != null)) {
                throw new IllegalArgumentException("Downloadable items require a trusted source");
            }
            if (source != null && source.size() != comparison.required().fileSize()) {
                throw new IllegalArgumentException("Trusted source size differs from required file");
            }
        }
    }
    @FunctionalInterface public interface Resolver {
        Optional<Source> find(ManifestEntry target) throws IOException, InterruptedException;
    }

    public ResolutionPlan {
        items = List.copyOf(items);
    }

    public static ResolutionPlan resolve(List<Comparison.Result> comparisons, Resolver resolver) throws InterruptedException {
        List<Item> items = new ArrayList<>(comparisons.size());
        for (Comparison.Result comparison : comparisons) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (comparison.status() == Comparison.Status.OK) {
                items.add(new Item(comparison, Classification.ALREADY_PRESENT, null));
                continue;
            }
            try {
                Optional<Source> source = resolver.find(comparison.required());
                items.add(source.map(value -> new Item(comparison, Classification.DOWNLOADABLE, value))
                        .orElseGet(() -> new Item(comparison, Classification.UNRESOLVED, null)));
            } catch (IOException exception) {
                // A lookup failure belongs to this file, not to the whole sync session.
                items.add(new Item(comparison, Classification.UNRESOLVED, null));
            }
        }
        return new ResolutionPlan(items);
    }

    public List<Item> downloadQueue() {
        return items.stream().filter(item -> item.classification() == Classification.DOWNLOADABLE).toList();
    }

    public long count(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification).count();
    }

    public long size(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification)
                .mapToLong(item -> item.comparison().required().fileSize()).sum();
    }
}
