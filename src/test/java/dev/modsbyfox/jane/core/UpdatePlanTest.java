package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UpdatePlanTest {
    private static final String HASH = "a".repeat(128);
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String SERVER = "b".repeat(64);

    @Test
    void batchUsesSpecificPidAndHasRollbackForMixedTransaction() {
        UpdatePlan plan = new UpdatePlan(ID, SERVER, "2026-09-18_02-30-15", List.of(
                new UpdatePlan.Operation("create", UpdatePlan.Kind.REPLACE, "create-old.jar", HASH, "create-new.jar", HASH, 100),
                new UpdatePlan.Operation("addon", UpdatePlan.Kind.ADD, null, null, "addon.jar", HASH, 200)));
        String bat = WindowsBatch.generate(plan, 12345);
        assertTrue(bat.contains("PID eq 12345"));
        assertTrue(bat.contains("goto rollback_backup"));
        assertTrue(bat.contains(":rollback"));
        assertTrue(bat.contains("mods\\create-old.jar"));
        assertTrue(bat.contains("mods\\addon.jar"));
        assertFalse(bat.toLowerCase().contains("taskkill"));
        assertFalse(bat.toLowerCase().contains("powershell"));
        assertFalse(bat.contains("C:\\"));
    }

    @Test
    void planRejectsDuplicateOrUnsafeTargets() {
        assertThrows(IllegalArgumentException.class, () -> new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD, null, null, "x&y.jar", HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> new UpdatePlan(ID, SERVER, "2026-09-18_02-30-15", List.of(
                new UpdatePlan.Operation("one", UpdatePlan.Kind.ADD, null, null, "same.jar", HASH, 1),
                new UpdatePlan.Operation("two", UpdatePlan.Kind.ADD, null, null, "SAME.jar", HASH, 1))));
    }

    @Test
    void replaceAcceptsUnicodeOldNameButNeverUnicodeNewNameOrCmdMetacharacters() {
        String old = "【农夫乐事】farmers-delight-fabric-1.4.3.jar";
        var operation = new UpdatePlan.Operation("farmersdelight", UpdatePlan.Kind.REPLACE,
                old, HASH, "farmers-delight-refabricated-2.5.4.jar", HASH, 100);
        assertEquals(old, operation.oldFile());
        assertThrows(IllegalArgumentException.class, () -> new UpdatePlan.Operation("farmersdelight",
                UpdatePlan.Kind.REPLACE, old, HASH, "【新】farmers-delight.jar", HASH, 100));
        for (String unsafe : new String[]{"old%.jar", "old!.jar", "old^.jar", "old&.jar"})
            assertThrows(IllegalArgumentException.class, () -> new UpdatePlan.Operation("farmersdelight",
                    UpdatePlan.Kind.REPLACE, unsafe, HASH, "new.jar", HASH, 100));
    }

    @Test
    void retentionKeepsFiveSuccessfulBackupsPerServer() {
        List<String> five = List.of("1", "2", "3", "4", "5");
        assertTrue(BackupRetention.expired(five, 5).isEmpty());
        assertEquals(List.of("1"), BackupRetention.expired(List.of("1", "2", "3", "4", "5", "6"), 5));
        // A failed backup is not passed to the retention policy; another server is evaluated separately.
        assertTrue(BackupRetention.expired(List.of("1", "2"), 5).isEmpty());
    }
}
