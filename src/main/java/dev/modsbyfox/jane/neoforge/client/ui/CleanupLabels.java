package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import net.minecraft.network.chat.Component;

/** Shared local-only diagnostic wording for both cleanup entry screens. */
final class CleanupLabels {
    private CleanupLabels() { }

    static Component status(ClientCompatibilityReport.ExtraMod mod,
                            ClientCleanupGraph.Decision decision, boolean selected) {
        if (!mod.verified()) return Component.translatable("jane.cleanup.unverified");
        if (decision == null) return Component.translatable("jane.cleanup.unknown");
        if (!decision.eligible()) return blocked(decision.block());
        if (!decision.optionalImpacts().isEmpty())
            return Component.translatable("jane.cleanup.optional_impact", decision.optionalImpacts().size());
        return Component.translatable(selected ? "jane.cleanup.selected" : "jane.cleanup.available");
    }

    private static Component blocked(ClientCleanupGraph.Block block) {
        if (block == null) return Component.translatable("jane.cleanup.unknown");
        return switch (block.reason()) {
            case REQUIRED_BY_OTHER_MOD -> Component.translatable("jane.cleanup.required_by",
                    block.ownerModId(), block.targetModId());
            case UNKNOWN_DEPENDENCY -> Component.translatable("jane.cleanup.unknown_dependency",
                    block.ownerModId(), block.targetModId());
            case DUPLICATE_MOD_ID -> Component.translatable("jane.cleanup.duplicate_id", block.targetModId());
            case UNKNOWN_METADATA -> Component.translatable("jane.cleanup.unknown_metadata");
            case UNKNOWN_SELECTION -> Component.translatable("jane.cleanup.unknown");
        };
    }
}
