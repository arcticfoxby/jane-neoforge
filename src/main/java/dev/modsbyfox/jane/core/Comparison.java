package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Comparison {
    public enum Status { MISSING, VERSION_MISMATCH, HASH_MISMATCH, FILE_ERROR, OK }
    public enum ProblemKind { HASH_FAILED }
    public record LocalIssue(ProblemKind kind, List<String> files) {
        public LocalIssue {
            Objects.requireNonNull(kind, "kind");
            files = List.copyOf(files);
        }
    }
    /** A verified direct physical JAR in this instance's mods directory. */
    public record LocalMod(String modId, String version, Path jar) {
        public LocalMod {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(jar, "jar");
        }
    }
    public record Result(ManifestEntry required, LocalMod local, Status status, String localHash,
                         LocalIssue localIssue, List<LocalMod> retainedCandidates) {
        public Result {
            retainedCandidates = List.copyOf(retainedCandidates);
        }
        public Result(ManifestEntry required, LocalMod local, Status status, String localHash) {
            this(required, local, status, localHash, null, List.of());
        }
        public Result(ManifestEntry required, LocalMod local, Status status, String localHash, LocalIssue localIssue) {
            this(required, local, status, localHash, localIssue, List.of());
        }
    }
    @FunctionalInterface public interface FileHasher { String hash(Path file) throws IOException; }

    private Comparison() { }

    public static List<Result> compare(RequiredManifest manifest, Map<String, LocalMod> installed, FileHasher hasher) {
        return compare(manifest, installed, Map.of(), hasher);
    }

    public static List<Result> compare(RequiredManifest manifest, Map<String, LocalMod> installed,
                                       Map<String, LocalIssue> problems, FileHasher hasher) {
        List<Result> results = new ArrayList<>();
        for (ManifestEntry required : manifest.entries()) {
            LocalIssue problem = problems.get(required.modId());
            if (problem != null) {
                results.add(new Result(required, null, Status.FILE_ERROR, null, problem));
                continue;
            }
            LocalMod local = installed.get(required.modId());
            results.add(compareOne(required, local == null ? List.of() : List.of(local), hasher));
        }
        return List.copyOf(results);
    }

    public static List<Result> compareCandidates(RequiredManifest manifest,
                                                  Map<String, List<LocalMod>> candidates, FileHasher hasher) {
        List<Result> results = new ArrayList<>();
        for (ManifestEntry required : manifest.entries())
            results.add(compareOne(required, candidates.getOrDefault(required.modId(), List.of()), hasher));
        return List.copyOf(results);
    }

    private static Result compareOne(ManifestEntry required, List<LocalMod> candidates, FileHasher hasher) {
        if (candidates.isEmpty()) return new Result(required, null, Status.MISSING, null);
        List<LocalMod> ordered = candidates.stream().sorted(Comparator
                .comparing((LocalMod local) -> local.jar().getFileName().toString())
                .thenComparing(local -> local.jar().toString())).toList();
        LocalMod exact = null;
        String singleHash = null;
        for (LocalMod local : ordered) {
            try {
                String hash = hasher.hash(local.jar());
                if (hash == null || !hash.matches("[0-9a-f]{128}")) throw new IOException("Invalid local JAR hash");
                if (ordered.size() == 1) singleHash = hash;
                if (exact == null && required.sha512().equals(hash)) exact = local;
            } catch (IOException | IllegalArgumentException exception) {
                return new Result(required, local, Status.FILE_ERROR, null,
                        new LocalIssue(ProblemKind.HASH_FAILED, List.of(local.jar().getFileName().toString())));
            }
        }
        if (ordered.size() == 1) {
            LocalMod local = ordered.get(0);
            Status status = !required.version().equals(local.version()) ? Status.VERSION_MISMATCH
                    : !required.sha512().equals(singleHash) ? Status.HASH_MISMATCH : Status.OK;
            return new Result(required, local, status, singleHash);
        }
        if (exact == null) return new Result(required, null, Status.MISSING, null, null, ordered);
        LocalMod selected = exact;
        return new Result(required, selected, Status.OK, required.sha512(), null,
                ordered.stream().filter(local -> local != selected).toList());
    }

    public static boolean passed(List<Result> results) {
        return results.stream().allMatch(result -> result.status() == Status.OK);
    }
}
