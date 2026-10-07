package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServerIdentityTest {
    @Test
    void normalizesHostWhitespaceAndDefaultPortWithoutResolvingDns() {
        assertEquals("play.example.com:25565", ServerIdentity.normalize("  PLAY.Example.Com  "));
        assertEquals(ServerIdentity.id("PLAY.Example.Com"), ServerIdentity.id("play.example.com:25565"));
        assertEquals("frp-day.com:41476", ServerIdentity.normalize(" FRP-DAY.COM:41476 "));
        assertEquals("127.0.0.1:25565", ServerIdentity.normalize("127.0.0.1"));
    }

    @Test
    void differentEntriesAndPortsGetDifferentIds() {
        assertNotEquals(ServerIdentity.id("play.example.com:25565"), ServerIdentity.id("play.example.com:25566"));
        assertNotEquals(ServerIdentity.id("play.example.com:25565"), ServerIdentity.id("other.example.com:25565"));
    }

    @Test
    void supportsIpv6Literals() {
        assertEquals("[2001:db8::1]:25565", ServerIdentity.normalize(" [2001:DB8::1] "));
        assertEquals(ServerIdentity.id("[2001:DB8::1]"), ServerIdentity.id("[2001:db8::1]:25565"));
        assertEquals("[::1]:25565", ServerIdentity.normalize("::1"));
        org.junit.jupiter.api.Assertions.assertTrue(ServerIdentity.providerEndpoint("[::1]:25565", 41477)
                .getAddress() instanceof java.net.Inet6Address);
        assertEquals(41477, ServerIdentity.providerEndpoint("[::1]:25565", 41477).getPort());
    }

    @Test
    void rejectsMissingOrInvalidAddressesInsteadOfUsingSharedFallback() {
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.normalize(" "));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.normalize("play.example.com:0"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.normalize("play.example.com:65536"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.normalize("play.example.com:abc"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.providerEndpoint("example.org", 0));
    }
}
