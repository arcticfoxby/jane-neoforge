package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaneProviderConfigTest {
    @TempDir Path gameDir;

    @Test void absentConfigUsesExistingMinecraftPort() throws IOException {
        assertEquals(JaneProviderConfig.Mode.AUTO, JaneProviderConfig.read(gameDir).mode());
    }

    @Test void explicitSeparatePortRequiresBothPorts() throws IOException {
        JaneProviderConfig config = JaneProviderConfig.parse(
                "{\"mode\":\"SEPARATE_PORT\",\"bindPort\":25566,\"advertisedPort\":25567}");
        assertEquals(25566, config.bindPort());
        assertEquals(25567, config.advertisedPort());
        assertThrows(IOException.class, () -> JaneProviderConfig.parse(
                "{\"mode\":\"SEPARATE_PORT\",\"bindPort\":25566}"));
        assertThrows(IOException.class, () -> JaneProviderConfig.parse(
                "{\"mode\":\"AUTO\",\"bindPort\":25566}"));
    }

    @Test void rejectsDuplicateOrUnknownSettings() {
        assertThrows(IOException.class, () -> JaneProviderConfig.parse(
                "{\"mode\":\"AUTO\",\"mode\":\"DISABLED\"}"));
        assertThrows(IOException.class, () -> JaneProviderConfig.parse(
                "{\"mode\":\"AUTO\",\"token\":\"unexpected\"}"));
    }

    @Test void refusesOversizeConfig() throws IOException {
        Path jane = Files.createDirectories(gameDir.resolve("config").resolve("jane"));
        Files.writeString(jane.resolve("provider.json"), " ".repeat(64 * 1024 + 1));
        assertThrows(IOException.class, () -> JaneProviderConfig.read(gameDir));
    }
}
