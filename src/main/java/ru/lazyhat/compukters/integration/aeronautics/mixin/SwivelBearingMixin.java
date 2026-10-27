/* Copyright 2026 Vsevolod Petrov (lazyhat). Licensed under Apache-2.0. */
package ru.lazyhat.compukters.integration.aeronautics.mixin;

import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lazyhat.compukters.integration.aeronautics.BearingAccess;
import ru.lazyhat.compukters.integration.aeronautics.BearingPhysics;
import ru.lazyhat.compukters.integration.aeronautics.BearingSession;

/** Only Simulated is transformed. Native assembly, target integration and Sable motor coefficients remain intact. */
@Mixin(value = SwivelBearingBlockEntity.class, remap = false)
public abstract class SwivelBearingMixin implements BearingAccess {
    @Shadow private double targetAngleDegrees;
    @Shadow private double lastTargetAngleDegrees;
    @Shadow private double sequencedAngleLimit;
    @Unique private BearingSession compukters$session;

    @Override public BearingSession getServoSession() { return compukters$session; }
    @Override public void setServoSession(BearingSession session) { compukters$session = session; }
    @Override public double getServoSequenceLimit() { return sequencedAngleLimit; }
    @Override public void setServoNativeAngle(double degrees) {
        targetAngleDegrees = degrees;
        lastTargetAngleDegrees = degrees;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void compukters$observe(CallbackInfo ci) {
        if (compukters$session != null) BearingPhysics.tick((SwivelBearingBlockEntity) (Object) this, compukters$session);
    }

    @Inject(method = "limitCogSpeed", at = @At("RETURN"), cancellable = true)
    private void compukters$finiteSpeed(float input, CallbackInfoReturnable<Float> cir) {
        if (compukters$session == null || !compukters$session.getMotion().getActive()) return;
        double step = compukters$session.getMotion().driveStep(targetAngleDegrees, Math.abs(cir.getReturnValueF()) * 0.3);
        Direction facing = ((SwivelBearingBlockEntity) (Object) this).getBlockState().getValue(BlockStateProperties.FACING);
        double sign = facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? -1.0 : 1.0;
        cir.setReturnValue((float) (step / 0.3 * sign));
    }

    @Redirect(method = "updateServoCoefficients", at = @At(value = "INVOKE",
        target = "Ldev/simulated_team/simulated/content/blocks/swivel_bearing/SwivelBearingBlockEntity;isLocking()Z"))
    private boolean compukters$holdWhileControlled(SwivelBearingBlockEntity entity) {
        return (compukters$session != null && compukters$session.getMotion().getActive())
            || entity.getBlockState().getValue(BlockStateProperties.POWERED);
    }
}
