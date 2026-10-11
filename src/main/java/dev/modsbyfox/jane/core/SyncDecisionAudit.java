package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bounded local record of player decisions; it never determines Mod validity. */
public final class SyncDecisionAudit {
    static final long MAX_BYTES = 128 * 1024;
    private static final int BACKUPS = 3;
    private static final Pattern EVENT = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{12,128}");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,12}");
    private static final Set<String> FIELDS = Set.of(
            "serverId", "manifestDigest", "modId", "requiredVersion", "reason", "required",
            "missing", "versionMismatch", "fileError", "skippedRequired", "selectedRequired",
            "selectedOptional", "defaultOptional", "selectedCount", "downloadCount",
            "requiredBaseline", "joinAction", "selectionTimestamp", "status", "result",
            "matched", "extraClientMods", "preservedExtras", "hashMismatch",
            "comparisonStatus", "requiredHash");

    private SyncDecisionAudit() { }

    public static synchronized void record(Path gameDir, String event, Map<String, String> fields) throws IOException {
        record(gameDir, null, event, fields);
    }

    public static synchronized void record(Path gameDir, String version, String event,
                                           Map<String, String> fields) throws IOException {
        byte[] line = format(version, event, fields).getBytes(StandardCharsets.US_ASCII);
        Path target = PathSafety.janeDirectory(gameDir, "logs").resolve("sync-decisions.log");
        checkSafe(target);
        for (int i = 1; i <= BACKUPS; i++) checkSafe(backup(target, i));
        long current = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : 0;
        if (current > MAX_BYTES) throw new IOException("Jane sync audit file exceeds size limit");
        if (current + line.length > MAX_BYTES) rotate(target);
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) out.write(bytes);
        }
    }

    private static String format(String version, String event, Map<String, String> fields) throws IOException {
        if (event == null || !EVENT.matcher(event).matches() || fields == null || fields.size() > 16)
            throw new IOException("Invalid Jane sync audit event");
        if (version != null && !VERSION.matcher(version).matches())
            throw new IOException("Invalid Jane sync audit version");
        Map<String, String> ordered = new TreeMap<>();
        for (var field : fields.entrySet()) {
            String name = field.getKey();
            if (name == null || !KEY.matcher(name).matches() || !FIELDS.contains(name))
                throw new IOException("Invalid Jane sync audit field");
            ordered.put(name, field.getValue());
        }
        StringBuilder line = new StringBuilder(Instant.now().toString()).append(" [Jane");
        if (version != null) line.append(' ').append(version);
        line.append("][CLIENT] ").append(event);
        ordered.forEach((name, value) -> line.append(' ').append(name).append('=').append(safeValue(name, value)));
        return line.append('\n').toString();
    }

    private static String safeValue(String name, String value) {
        if (value == null) return "[REDACTED]";
        if (name.equals("serverId") || name.equals("manifestDigest"))
            return HEX_ID.matcher(value).matches()
                    ? value.substring(0, 12).toLowerCase(java.util.Locale.ROOT) : "[REDACTED]";
        if (name.equals("modId")) return MOD_ID.matcher(value).matches() ? value : "[REDACTED]";
        if (name.equals("requiredHash")) return value.matches("[0-9a-fA-F]{12}")
                ? value.toLowerCase(java.util.Locale.ROOT) : "[REDACTED]";
        if (name.equals("required") || name.equals("missing") || name.equals("versionMismatch")
                || name.equals("fileError") || name.equals("skippedRequired") || name.equals("selectedRequired")
                || name.equals("selectedOptional") || name.equals("defaultOptional")
                || name.equals("selectedCount") || name.equals("downloadCount") || name.equals("matched")
                || name.equals("extraClientMods") || name.equals("preservedExtras")
                || name.equals("hashMismatch"))
            return NUMBER.matcher(value).matches() ? value : "[REDACTED]";
        return value.length() <= 96 && value.matches("[A-Za-z0-9._+:-]{1,96}") ? value : "[REDACTED]";
    }

    private static void rotate(Path target) throws IOException {
        for (int i = BACKUPS; i > 1; i--) {
            Path previous = backup(target, i - 1);
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS))
                Files.move(previous, backup(target, i), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            Files.move(target, backup(target, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path backup(Path target, int index) {
        return target.resolveSibling(target.getFileName() + "." + index);
    }

    private static void checkSafe(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Jane sync audit target is unsafe");
    }
}
