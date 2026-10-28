/* Copyright 2026 Vsevolod Petrov (lazyhat). Licensed under Apache-2.0. */
package ru.lazyhat.compukters.integration.aeronautics.mixin;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retire a restored association when the native connector has already fully retracted. */
@Mixin(value = DockingConnectorBlockEntity.class, remap = false)
public abstract class DockingConnectorMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void compukters$releaseRetractedAssociation(CallbackInfo ci) {
        var connector = (DockingConnectorBlockEntity) (Object) this;
        var level = connector.getLevel();
        if (level == null || level.isClientSide || connector.powered
                || !connector.isRetracted() || !connector.hasOtherConnector()) return;
        // Native read restores the peer position but starts its state machine UNPOWERED.
        // That state skips the ordinary release transition. Use native cleanup so constraints,
        // shared buffers, inventory and the peer follow the same path as normal undocking.
        var pair = DockingConnectorBlockEntity.MAGNET_CONTROLLER.getPair(
                level, connector.getBlockPos(), connector.otherConnectorPosition);
        if (pair instanceof DockingConnectorPair docking) docking.unDock();
        else connector.unDock();
    }
}
