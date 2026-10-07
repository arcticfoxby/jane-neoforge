package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exact-file availability, independent of the route later used to transfer the bytes. */
public record ResolutionPlan(List<Item> items) {
    public enum Availability { ALREADY_PRESENT, TRUSTED_AVAILABLE, SERVER_ONLY, LOOKUP_FAILED, UNRESOLVED, LOCAL_ERROR }
    public enum TransferGroup { TRUSTED, SERVER_ONLY }
    public enum TransferRoute { TRUSTED_SOURCE, CURRENT_SERVER }
    // Compatibility for existing callers; new work uses Availability and TransferGroup.
    @Deprecated public enum Classification { ALREADY_PRESENT, MODRINTH_DOWNLOADABLE, SERVER_DOWNLOADABLE, UNRESOLVED }
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
    public record Item(Comparison.Result comparison, Availability availability, Source trustedSource) {
        public Item {
            java.util.Objects.requireNonNull(comparison, "comparison");
            java.util.Objects.requireNonNull(availability, "availability");
            if ((availability == Availability.TRUSTED_AVAILABLE) != (trustedSource != null))
                throw new IllegalArgumentException("Only trusted availability may carry a public source");
            if (trustedSource != null && (trustedSource.provider() != Provider.MODRINTH
                    || trustedSource.size() != comparison.required().fileSize()))
                throw new IllegalArgumentException("Trusted source differs from required file");
        }
        @Deprecated public Classification classification() {
            return switch (availability) {
                case ALREADY_PRESENT -> Classification.ALREADY_PRESENT;
                case TRUSTED_AVAILABLE -> Classification.MODRINTH_DOWNLOADABLE;
                case SERVER_ONLY -> Classification.SERVER_DOWNLOADABLE;
                case LOOKUP_FAILED, UNRESOLVED, LOCAL_ERROR -> Classification.UNRESOLVED;
            };
        }
        @Deprecated public Source source() {
            return availability == Availability.SERVER_ONLY ? serverSource(comparison.required()) : trustedSource;
        }
    }
    @FunctionalInterface public interface Resolver {
        Optional<Source> find(ManifestEntry target) throws IOException, InterruptedException;
    }
    @FunctionalInterface public interface Progress { void processed(int count); }

    public ResolutionPlan { items = List.copyOf(items); }

    public static ResolutionPlan resolve(List<Comparison.Result> comparisons, Resolver resolver) throws InterruptedException {
        return resolve(comparisons, resolver, false, count -> { });
    }

    public static ResolutionPlan resolve(List<Comparison.Result> comparisons, Resolver resolver,
                                         boolean serverAvailable, Progress progress) throws InterruptedException {
        List<Item> items = new ArrayList<>(comparisons.size());
        for (Comparison.Result comparison : comparisons) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            items.add(resolveOne(comparison, resolver, serverAvailable));
            progress.processed(items.size());
        }
        return new ResolutionPlan(items);
    }

    public ResolutionPlan retryLookupFailures(Resolver resolver, boolean serverAvailable) throws InterruptedException {
        List<Item> refreshed = new ArrayList<>(items.size());
        for (Item item : items) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            refreshed.add(item.availability() == Availability.LOOKUP_FAILED
                    ? resolveOne(item.comparison(), resolver, serverAvailable) : item);
        }
        return new ResolutionPlan(refreshed);
    }

    private static Item resolveOne(Comparison.Result comparison, Resolver resolver, boolean serverAvailable)
            throws InterruptedException {
        if (comparison.status() == Comparison.Status.OK)
            return new Item(comparison, Availability.ALREADY_PRESENT, null);
        if (comparison.status() == Comparison.Status.FILE_ERROR)
            return new Item(comparison, Availability.LOCAL_ERROR, null);
        try {
            Optional<Source> source = resolver.find(comparison.required());
            return source.map(value -> new Item(comparison, Availability.TRUSTED_AVAILABLE, value))
                    .orElseGet(() -> new Item(comparison,
                            serverAvailable ? Availability.SERVER_ONLY : Availability.UNRESOLVED, null));
        } catch (IOException exception) {
            return new Item(comparison, Availability.LOOKUP_FAILED, null);
        }
    }

    public List<Item> queue(Availability availability) {
        return items.stream().filter(item -> item.availability() == availability).toList();
    }
    public long count(Availability availability) { return queue(availability).size(); }
    public long size(Availability availability) {
        return queue(availability).stream().mapToLong(item -> item.comparison().required().fileSize()).sum();
    }
    @Deprecated public List<Item> downloadQueue() { return queue(Availability.TRUSTED_AVAILABLE); }
    @Deprecated public List<Item> queue(Classification classification) {
        return items.stream().filter(item -> item.classification() == classification).toList();
    }
    @Deprecated public long count(Classification classification) { return queue(classification).size(); }
    @Deprecated public long size(Classification classification) {
        return queue(classification).stream().mapToLong(item -> item.comparison().required().fileSize()).sum();
    }
}
