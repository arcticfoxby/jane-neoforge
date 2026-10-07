package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestTest {
    private static final String A = "a".repeat(128);
    private static final String B = "b".repeat(128);

    @Test
    void comparisonRequiresExactHashAndIgnoresExtras() {
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(new ManifestEntry("create", "Create", "1.2.0", 100, A)));
        Path jar = Path.of("create.jar");
        assertEquals(Comparison.Status.MISSING, Comparison.compare(manifest, Map.of(), path -> A).get(0).status());
        var installed = Map.of("create", new Comparison.LocalMod("create", "1.2.0", jar),
                "sodium", new Comparison.LocalMod("sodium", "any", Path.of("sodium.jar")));
        assertTrue(Comparison.passed(Comparison.compare(manifest, installed, path -> A)));
        assertEquals(Comparison.Status.HASH_MISMATCH, Comparison.compare(manifest, installed, path -> B).get(0).status());
        var older = Map.of("create", new Comparison.LocalMod("create", "1.1.0", jar));
        assertEquals(Comparison.Status.VERSION_MISMATCH, Comparison.compare(manifest, older, path -> A).get(0).status());
    }

    @Test void localModRejectsNullJarAndHashFailureIsTypedLocalError() throws Exception {
        assertThrows(NullPointerException.class, () -> new Comparison.LocalMod("create", "1", null));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1", 100, A)));
        var local = Map.of("create", new Comparison.LocalMod("create", "1", Path.of("create.jar")));
        Comparison.Result result = Comparison.compare(manifest, local, path -> {
            throw new IOException("unreadable");
        }).get(0);
        assertEquals(Comparison.Status.FILE_ERROR, result.status());
        assertEquals(Comparison.ProblemKind.HASH_FAILED, result.localIssue().kind());
        ResolutionPlan plan = ResolutionPlan.resolve(List.of(result), entry -> {
            fail("A local hash failure must not trigger a source lookup");
            return java.util.Optional.empty();
        });
        assertEquals(1, plan.count(ResolutionPlan.Availability.LOCAL_ERROR));
    }

    @Test
    void protocolCodecRejectsMalformedInput() throws Exception {
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(new ManifestEntry("create", "Create", "1.2.0", 100, A)));
        assertEquals(manifest, ManifestCodec.decode(ManifestCodec.encode(manifest)));
        byte[] unsupported = ManifestCodec.encode(manifest);
        ByteBuffer.wrap(unsupported).putInt(0, 1);
        assertThrows(ManifestCodec.UnsupportedProtocolException.class, () -> ManifestCodec.decode(unsupported));
        byte[] many = new byte[8];
        ByteBuffer.wrap(many).putInt(RequiredManifest.PROTOCOL).putInt(129);
        assertThrows(IOException.class, () -> ManifestCodec.decode(many));
        byte[] oversized = new byte[RequiredManifest.MAX_PAYLOAD + 1];
        assertThrows(IOException.class, () -> ManifestCodec.decode(oversized));
        byte[] badString = new byte[12];
        ByteBuffer.wrap(badString).putInt(RequiredManifest.PROTOCOL).putInt(1).putInt(999999);
        assertThrows(IOException.class, () -> ManifestCodec.decode(badString));
        assertThrows(IllegalArgumentException.class, () -> new ManifestEntry("../bad", "Bad", "1", 1, A));
        assertThrows(IllegalArgumentException.class, () -> new ManifestEntry("bad", "Bad", "1", 1, "x".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> new RequiredManifest(RequiredManifest.PROTOCOL, List.of(manifest.entries().get(0), manifest.entries().get(0))));
    }

    @Test
    void protocolThreeOfferIsBoundedAndValidated() throws Exception {
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1", 10, A)));
        var enabled = new ManifestCodec.LoginOffer(manifest, new ServerProviderOffer(41477, "a".repeat(64)));
        assertEquals(enabled, ManifestCodec.decodeOffer(ManifestCodec.encode(enabled)));
        var minecraft = new ManifestCodec.LoginOffer(manifest, ServerProviderOffer.minecraft("b".repeat(64)));
        assertEquals(minecraft, ManifestCodec.decodeOffer(ManifestCodec.encode(minecraft)));
        assertNull(ManifestCodec.decodeOffer(ManifestCodec.encode(manifest)).provider());
        assertThrows(IllegalArgumentException.class, () -> new ServerProviderOffer(0, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ServerProviderOffer(65536, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ServerProviderOffer(1, "a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ServerProviderOffer(1, "g".repeat(64)));
        byte[] trailing = java.util.Arrays.copyOf(ManifestCodec.encode(enabled), ManifestCodec.encode(enabled).length + 1);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(trailing));
        byte[] old = ManifestCodec.encode(manifest);
        ByteBuffer.wrap(old).putInt(2);
        assertThrows(ManifestCodec.UnsupportedProtocolException.class, () -> ManifestCodec.decodeOffer(old));
        byte[] badLength = ManifestCodec.encode(enabled);
        ByteBuffer.wrap(badLength).putInt(badLength.length - 72, 65);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(badLength));
        byte[] badToken = ManifestCodec.encode(enabled);
        badToken[badToken.length - 5] = 'g';
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(badToken));
        byte[] badPort = ManifestCodec.encode(enabled);
        ByteBuffer.wrap(badPort).putInt(badPort.length - 4, 0);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(badPort));
        byte[] unknownTransport = ManifestCodec.encode(enabled);
        unknownTransport[unknownTransport.length - 73] = 3;
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(unknownTransport));
        byte[] missingPort = java.util.Arrays.copyOf(ManifestCodec.encode(enabled), ManifestCodec.encode(enabled).length - 4);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(missingPort));
        byte[] minecraftWithPort = java.util.Arrays.copyOf(ManifestCodec.encode(minecraft), ManifestCodec.encode(minecraft).length + 4);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(minecraftWithPort));
        byte[] single = ManifestCodec.encode(manifest);
        var duplicate = new java.io.ByteArrayOutputStream();
        var duplicateOut = new java.io.DataOutputStream(duplicate);
        duplicateOut.writeInt(RequiredManifest.PROTOCOL);
        duplicateOut.writeInt(2);
        duplicateOut.write(single, 8, single.length - 9);
        duplicateOut.write(single, 8, single.length - 9);
        duplicateOut.writeByte(0);
        assertThrows(IOException.class, () -> ManifestCodec.decodeOffer(duplicate.toByteArray()));
    }
}
