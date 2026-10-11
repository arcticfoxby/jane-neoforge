package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncSelectionTest {
    private static final String HASH = "a".repeat(128);

    private static PendingSyncContext context() {
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(
                new ManifestEntry("required", "Required", "1", 10, HASH),
                new ManifestEntry("optional", "Optional", "1", 20, HASH),
                new ManifestEntry("present", "Present", "1", 30, HASH)));
        var compared = Comparison.compare(manifest,
                Map.of("present", new Comparison.LocalMod("present", "1", Path.of("present.jar"))),
                path -> HASH);
        return new PendingSyncContext("example.org", manifest, compared);
    }

    @Test void exactFileAdviceDoesNotWeakenRequiredComparisonOrDeselectByItself() {
        PendingSyncContext context = context();
        SyncSelection selection = new SyncSelection(context);
        assertEquals(Set.of("required", "optional"), selection.selectedUnmatchedModIds());
        selection.applyCategories(Map.of("optional", ClientInstallCategory.CLIENT_OPTIONAL));
        assertTrue(selection.isSelected("optional"));
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL, selection.category("optional"));
        selection.setSelected("optional", false);
        assertEquals(Set.of("required"), selection.selectedUnmatchedModIds());
        assertEquals(Comparison.Status.MISSING, context.results().get(1).status());
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL,
                ClientInstallCategory.fromModrinthEvidence(true, "optional"));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED,
                ClientInstallCategory.fromModrinthEvidence(false, "optional"));
    }

    @Test void userChoiceWinsOverLaterMetadataAndFreezeLocksTransferQueue() {
        SyncSelection selection = new SyncSelection(context());
        selection.setSelected("required", false);
        String changed = selection.digest();
        selection.applyCategories(Map.of("required", ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(selection.isSelected("required"));
        assertNotEquals(changed, selection.digest());
        selection.freezeChoices();
        assertThrows(IllegalStateException.class, () -> selection.setSelected("required", true));
        assertThrows(IllegalArgumentException.class,
                () -> selection.applyCategories(Map.of("unknown", ClientInstallCategory.CLIENT_OPTIONAL)));
    }

    @Test void fileErrorCannotBecomeOptionalCandidate() {
        ManifestEntry entry = new ManifestEntry("broken", "Broken", "1", 10, HASH);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        PendingSyncContext context = new PendingSyncContext("example.org", manifest,
                List.of(new Comparison.Result(entry, null, Comparison.Status.FILE_ERROR, null)));
        SyncSelection selection = new SyncSelection(context,
                Map.of("broken", ClientInstallCategory.CLIENT_OPTIONAL));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED, selection.category("broken"));
    }

    @Test void unverifiedExplicitClientHashHasStableFingerprint() {
        var mod = new ClientCompatibilityReport.ExtraMod("iris", "Iris", "1", "iris.jar", null,
                ClientCompatibilityReport.Kind.EXPLICIT_CLIENT, Path.of("iris.jar"));
        String first = ClientCompatibilityReport.fingerprint(List.of(mod));
        assertEquals(first, ClientCompatibilityReport.fingerprint(List.of(mod)));
        assertEquals(128, first.length());
    }
}
