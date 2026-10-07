package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSafetyTest {
    @TempDir Path gameDir;

    @Test
    void hashAndCacheInvalidateOnMetadataChange() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = mods.resolve("sample.jar");
        Files.writeString(jar, "abc");
        HashCache cache = new HashCache(gameDir);
        String first = cache.hash(jar);
        assertEquals(Hashing.sha512(jar), first);
        FileTime time = Files.getLastModifiedTime(jar);
        Files.writeString(jar, "xyz");
        Files.setLastModifiedTime(jar, time);
        assertEquals(first, cache.hash(jar));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(time.toMillis() + 5000));
        assertNotEquals(first, cache.hash(jar));
        assertEquals(Hashing.sha512(jar), new HashCache(gameDir).hash(jar));
        Files.writeString(gameDir.resolve("jane/cache/hash-cache.json"), "{broken");
        assertEquals(Hashing.sha512(jar), new HashCache(gameDir).hash(jar));
    }

    @Test
    void rejectsTraversalShellCharactersAndForeignJar() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        for (String name : new String[]{"../x.jar", "C:\\x.jar", "evil&name.jar", "evil%name.jar", "evil^name.jar",
                "evil(name).jar", "evil!name.jar", "bad..jar", "CON.jar", "中文.jar"}) {
            assertThrows(IllegalArgumentException.class, () -> PathSafety.safeJarName(name), name);
        }
        assertEquals("fabric-api-0.92.8+1.20.1.jar", PathSafety.safeJarName("fabric-api-0.92.8+1.20.1.jar"));
        Path inside = Files.writeString(gameDir.resolve("mods/ok.jar"), "ok");
        assertEquals(inside.toRealPath(), PathSafety.existingJarInMods(gameDir, inside));
        Path outside = Files.writeString(gameDir.resolve("outside.jar"), "bad");
        assertThrows(java.io.IOException.class, () -> PathSafety.existingJarInMods(gameDir, outside));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.newJarInMods(gameDir, "../outside.jar"));
    }

    @Test
    void existingPhysicalUnicodeJarUsesItsOwnFailClosedNamePolicy() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        String name = "【农夫乐事】farmers-delight-fabric-1.4.3.jar";
        Path jar = Files.writeString(mods.resolve(name), "old");
        assertEquals(name, PathSafety.safeExistingJarName(name));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.safeJarName(name));
        assertEquals(jar.toRealPath(), PathSafety.existingJarInMods(gameDir, jar));
        assertEquals(jar.toRealPath(), PathSafety.existingJarNamedInMods(gameDir, name));
        for (String unsafe : new String[]{"", ".", "..", ".jar", "../evil.jar", "..\\evil.jar",
                "a/b.jar", "a\\b.jar", "evil\r\n.jar", "evil\u0000.jar", "evil\u202e.jar", "evil\".jar",
                "evil%.jar", "evil!.jar", "evil^.jar", "evil&.jar", "evil<.jar", "evil>.jar",
                "evil:.jar", "evil|.jar", "evil?.jar", "evil*.jar", "CON.jar", "a".repeat(201) + ".jar"}) {
            assertThrows(IllegalArgumentException.class, () -> PathSafety.safeExistingJarName(unsafe), unsafe);
        }
        assertThrows(IllegalArgumentException.class, () -> PathSafety.safeExistingJarName(null));
        Path sub = Files.createDirectory(mods.resolve("sub"));
        assertThrows(java.io.IOException.class, () -> PathSafety.existingJarInMods(gameDir,
                Files.writeString(sub.resolve("nested.jar"), "nested")));
        Path outside = Files.writeString(gameDir.resolve("outside.jar"), "outside");
        assertThrows(java.io.IOException.class, () -> PathSafety.existingJarInMods(gameDir, outside));
    }

    @Test
    void existingPhysicalJarRejectsSymlink() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path target = Files.writeString(mods.resolve("real.jar"), "real");
        Path link = mods.resolve("linked.jar");
        try { Files.createSymbolicLink(link, target); }
        catch (java.io.IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable without elevated privileges");
        }
        assertThrows(java.io.IOException.class, () -> PathSafety.existingJarInMods(gameDir, link));
    }
}
