package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OneShotPermissionsTest {
    private static final String SERVER = ServerIdentity.id("one.example.org");
    private static final String HASH = "a".repeat(128);
    private static final ManifestEntry ENTRY = new ManifestEntry("example", "Example", "1", 12, HASH);
    private static final RequiredManifest MANIFEST = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(ENTRY));

    private static List<Comparison.Result> missing() {
        return Comparison.compare(MANIFEST, Map.of(), path -> HASH);
    }

    private static SyncSelection skip(List<Comparison.Result> results) {
        SyncSelection selection = new SyncSelection(new PendingSyncContext("one.example.org", MANIFEST, results));
        selection.setSelected("example", false);
        return selection;
    }

    @Test void protocolFourSeparatesExactAndDeclaredOverride() {
        assertEquals(4, RequiredManifest.PROTOCOL);
        assertEquals(0, LoginStatus.EXACT_PASS.code());
        assertEquals(3, LoginStatus.USER_OVERRIDE.code());
        assertTrue(LoginStatus.EXACT_PASS.allowsJaneGate());
        assertTrue(LoginStatus.USER_OVERRIDE.allowsJaneGate());
        assertFalse(LoginStatus.ACTION_REQUIRED.allowsJaneGate());
        assertFalse(LoginStatus.PROTOCOL_ERROR.allowsJaneGate());
        assertThrows(IllegalArgumentException.class, () -> LoginStatus.fromCode(4));
        assertThrows(ManifestCodec.UnsupportedProtocolException.class, () -> {
            byte[] offer = ManifestCodec.encode(MANIFEST);
            offer[3] = 3;
            ManifestCodec.decodeOffer(offer);
        });
    }

    @Test void requiredOverrideIsBoundToFreshAssessmentAndSingleConnection() {
        List<Comparison.Result> results = missing();
        OneShotJoinOverride permission = new OneShotJoinOverride();
        Object login = new Object();
        assertFalse(permission.consume(login, SERVER, MANIFEST, results));
        assertFalse(permission.arm(SERVER, MANIFEST,
                new SyncSelection(new PendingSyncContext("one.example.org", MANIFEST, results)), results));
        assertTrue(permission.arm(SERVER, MANIFEST, skip(results), results));
        permission.beginConnection(login);
        assertTrue(permission.consume(login, SERVER, MANIFEST, results));
        assertFalse(permission.consume(login, SERVER, MANIFEST, results));
        assertFalse(Comparison.passed(results));

        assertTrue(permission.arm(SERVER, MANIFEST, skip(results), results));
        permission.beginConnection(login);
        permission.beginConnection(new Object());
        assertFalse(permission.consume(login, SERVER, MANIFEST, results));
    }

    @Test void unsafeFilesAndChangedChoicesCannotBeOverridden() {
        Comparison.Result error = new Comparison.Result(ENTRY, null, Comparison.Status.FILE_ERROR, null);
        OneShotJoinOverride permission = new OneShotJoinOverride();
        assertFalse(permission.arm(SERVER, MANIFEST, skip(List.of(error)), List.of(error)));
        var ambiguous = new Comparison.Result(ENTRY, null, Comparison.Status.MISSING, null,
                null, List.of(new Comparison.LocalMod("example", "0", Path.of("duplicate.jar"))));
        assertFalse(permission.arm(SERVER, MANIFEST, skip(List.of(ambiguous)), List.of(ambiguous)));

        List<Comparison.Result> results = missing();
        SyncSelection selection = skip(results);
        assertTrue(permission.arm(SERVER, MANIFEST, selection, results));
        Object login = new Object();
        permission.beginConnection(login);
        selection.setSelected("example", true);
        selection.setSelected("example", false);
        assertFalse(permission.consume(login, SERVER, MANIFEST, results));
    }

    @Test void changedLocalHashRequiresAnotherDirectJoinConfirmation() {
        Comparison.LocalMod local = new Comparison.LocalMod("example", "1", Path.of("example.jar"));
        var before = List.of(new Comparison.Result(ENTRY, local, Comparison.Status.HASH_MISMATCH,
                "b".repeat(128)));
        var after = List.of(new Comparison.Result(ENTRY, local, Comparison.Status.HASH_MISMATCH,
                "c".repeat(128)));
        OneShotJoinOverride permission = new OneShotJoinOverride();
        Object login = new Object();
        assertTrue(permission.arm(SERVER, MANIFEST, skip(before), before));
        permission.beginConnection(login);
        assertFalse(permission.consume(login, SERVER, MANIFEST, after));
        assertFalse(permission.consume(login, SERVER, MANIFEST, before));
    }

    @Test void extraOnlyPermissionPreservesExactRequiredStatusAndExpires() {
        var extra = new ClientCompatibilityReport.ExtraMod("iris", "Iris", "1", "iris.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("iris.jar"));
        var report = new ClientCompatibilityReport(List.of(), List.of(extra),
                ClientCompatibilityReport.fingerprint(List.of()));
        MutableClock clock = new MutableClock();
        OneShotExtraJoin permission = new OneShotExtraJoin(clock);
        Object login = new Object();
        assertTrue(permission.arm(SERVER, MANIFEST, report));
        permission.beginConnection(login);
        assertTrue(permission.consume(login, SERVER, MANIFEST, report));
        assertFalse(permission.consume(login, SERVER, MANIFEST, report));
        assertTrue(permission.arm(SERVER, MANIFEST, report));
        permission.beginConnection(login);
        clock.advance(Duration.ofSeconds(91));
        assertFalse(permission.consume(login, SERVER, MANIFEST, report));
    }

    @Test void changingAnExtraToDedicatedServerRequiresAnotherConfirmation() {
        var extra = new ClientCompatibilityReport.ExtraMod("sample", "Sample", "1", "sample.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("sample.jar"));
        var dedicated = new ClientCompatibilityReport.ExtraMod("sample", "Sample", "1", "sample.jar",
                null, ClientCompatibilityReport.Kind.EXPLICIT_SERVER, Path.of("sample.jar"));
        var before = new ClientCompatibilityReport(List.of(), List.of(extra),
                ClientCompatibilityReport.fingerprint(List.of()));
        var after = new ClientCompatibilityReport(List.of(), List.of(dedicated),
                ClientCompatibilityReport.fingerprint(List.of()));
        OneShotExtraJoin permission = new OneShotExtraJoin();
        Object login = new Object();
        assertTrue(permission.arm(SERVER, MANIFEST, before));
        permission.beginConnection(login);
        assertFalse(permission.consume(login, SERVER, MANIFEST, after));
    }

    @Test void changingContainedIdsRequiresAnotherExtraJoinConfirmation() {
        var oneId = new ClientCompatibilityReport.ExtraMod("sample", "Sample", "1", "sample.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("sample.jar"));
        var twoIds = new ClientCompatibilityReport.ExtraMod("sample", "Sample", "1", "sample.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("sample.jar"),
                List.of("sample", "helper"), false, null);
        var before = new ClientCompatibilityReport(List.of(), List.of(oneId),
                ClientCompatibilityReport.fingerprint(List.of()));
        var after = new ClientCompatibilityReport(List.of(), List.of(twoIds),
                ClientCompatibilityReport.fingerprint(List.of()));
        OneShotExtraJoin permission = new OneShotExtraJoin();
        Object login = new Object();
        assertTrue(permission.arm(SERVER, MANIFEST, before));
        permission.beginConnection(login);
        assertFalse(permission.consume(login, SERVER, MANIFEST, after));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
