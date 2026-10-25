package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PathSafety {
    private static final Pattern SAFE_JAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private static final int WINDOWS_REPARSE_POINT = 0x400;
    private PathSafety() { }

    public static String safeJarName(String name) {
        if (name == null || !SAFE_JAR.matcher(name).matches() || name.contains("..") || RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe JAR file name");
        }
        return name;
    }

    /** A basename for a physical JAR already verified inside this instance's mods directory. */
    public static String safeExistingJarName(String name) {
        if (name == null || name.length() <= 4 || name.length() > 200
                || !name.toLowerCase(Locale.ROOT).endsWith(".jar") || RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe existing JAR file name");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if ("/\\<>:\"|?*%!^&".indexOf(character) >= 0 || Character.isISOControl(character)
                    || Character.getType(character) == Character.LINE_SEPARATOR
                    || Character.getType(character) == Character.PARAGRAPH_SEPARATOR
                    || Character.getType(character) == Character.FORMAT
                    || (Character.isSurrogate(character) && (Character.isHighSurrogate(character)
                    ? index + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(index + 1))
                    : index == 0 || !Character.isHighSurrogate(name.charAt(index - 1))))) {
                throw new IllegalArgumentException("Unsafe existing JAR file name");
            }
        }
        return name;
    }

    public static Path existingJarNamedInMods(Path gameDir, String name) throws IOException {
        String safeName = safeExistingJarName(name);
        return existingJarInMods(gameDir, gameDir.resolve("mods").resolve(safeName));
    }

    /** Exact, read-only inventory for an offline cleanup dependency snapshot. */
    public static Set<String> topLevelJarNames(Path gameDir) throws IOException {
        rejectReparsePoint(gameDir);
        Path mods = gameDir.resolve("mods");
        rejectReparsePoint(mods);
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)
                || !mods.toRealPath().getParent().equals(gameDir.toRealPath()))
            throw new IOException("mods directory escaped current instance");
        Set<String> names = new HashSet<>();
        Set<String> folded = new HashSet<>();
        try (var children = Files.list(mods)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                try {
                    safeExistingJarName(name);
                } catch (IllegalArgumentException unsafe) {
                    throw new IOException("Unsafe JAR in mods inventory", unsafe);
                }
                existingJarInMods(gameDir, child);
                if (!names.add(name) || !folded.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException("Duplicate JAR name in mods inventory");
            }
        }
        return Set.copyOf(names);
    }

    public static Path existingJarChild(Path parent, String name) throws IOException {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path child = normalizedParent.resolve(safeExistingJarName(name)).normalize();
        if (!normalizedParent.equals(child.getParent())) throw new IOException("JAR escaped parent directory");
        return child;
    }

    public static Path existingJarInMods(Path gameDir, Path jar) throws IOException {
        rejectReparsePoint(gameDir);
        Path modsPath = gameDir.resolve("mods");
        rejectReparsePoint(modsPath);
        Path mods = modsPath.toRealPath();
        if (!mods.getParent().equals(gameDir.toRealPath())) {
            throw new IOException("mods directory escaped current instance");
        }
        Path candidate = jar.toAbsolutePath().normalize();
        Path lexicalMods = modsPath.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(lexicalMods) && !candidate.getParent().equals(mods)) {
            throw new IOException("Mod origin is not a direct JAR in this instance's mods directory");
        }
        rejectReparsePoint(candidate);
        Path real = jar.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                || !real.getParent().equals(mods) || !real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Mod origin is not a direct JAR in this instance's mods directory");
        }
        return real;
    }

    public static Path newJarInMods(Path gameDir, String name) throws IOException {
        rejectReparsePoint(gameDir);
        Path mods = gameDir.resolve("mods").toRealPath();
        rejectReparsePoint(gameDir.resolve("mods"));
        if (!mods.getParent().equals(gameDir.toRealPath())) {
            throw new IOException("mods directory escaped current instance");
        }
        Path candidate = mods.resolve(safeJarName(name)).normalize();
        if (!candidate.getParent().equals(mods)) throw new IOException("JAR escaped mods directory");
        rejectReparsePoint(candidate);
        return candidate;
    }

    public static Path janeDirectory(Path gameDir, String... segments) throws IOException {
        rejectReparsePoint(gameDir);
        Path current = gameDir.toRealPath();
        String[] all = new String[segments.length + 1];
        all[0] = "jane";
        System.arraycopy(segments, 0, all, 1, segments.length);
        for (String segment : all) {
            if (segment == null || !segment.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Unsafe Jane directory name");
            Path next = current.resolve(segment);
            rejectReparsePoint(next);
            if (!Files.exists(next)) Files.createDirectory(next);
            rejectReparsePoint(next);
            Path real = next.toRealPath();
            if (!real.getParent().equals(current)) throw new IOException("Jane directory escaped current instance");
            current = real;
        }
        return current;
    }

    /** Validate a prospective Jane directory without creating it before user confirmation. */
    public static Path janeDirectoryPath(Path gameDir, String... segments) throws IOException {
        rejectReparsePoint(gameDir);
        Path current = gameDir.toRealPath();
        String[] all = new String[segments.length + 1];
        all[0] = "jane";
        System.arraycopy(segments, 0, all, 1, segments.length);
        for (String segment : all) {
            if (segment == null || !segment.matches("[A-Za-z0-9_-]{1,80}"))
                throw new IOException("Unsafe Jane directory name");
            Path next = current.resolve(segment);
            rejectReparsePoint(next);
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Jane directory path is occupied");
                Path real = next.toRealPath();
                if (!real.getParent().equals(current))
                    throw new IOException("Jane directory escaped current instance");
                current = real;
            } else {
                current = next;
            }
        }
        return current;
    }

    public static boolean cmdSafeJarName(String name) {
        try {
            safeJarName(name);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Reject any Windows reparse point, including junctions and non-symlink tags. */
    public static void rejectReparsePoint(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Path is a symbolic link");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return;
        try {
            Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            if (!(attributes instanceof Integer flags) || (flags & WINDOWS_REPARSE_POINT) != 0)
                throw new IOException("Path is a Windows reparse point or cannot be verified");
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            throw new IOException("Cannot verify Windows reparse point status", exception);
        }
    }
}
