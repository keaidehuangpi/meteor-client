/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoShield;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("HEAD"))
    private void hideOffhandBlockingPose(PlayerEntityRenderState state, CallbackInfo ci) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield == null || !autoShield.shouldHideOffhandShield() || mc.player == null || state.id != mc.player.getId()) return;
        if (mc.player.getOffHandStack().getItem() instanceof net.minecraft.item.ShieldItem) {
            if (state.mainArm == Arm.LEFT) state.rightArmPose = BipedEntityModel.ArmPose.EMPTY;
            else state.leftArmPose = BipedEntityModel.ArmPose.EMPTY;
        }
    }

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void applyBlockingArmPose(PlayerEntityRenderState state, CallbackInfo ci) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield == null || mc.player == null || state.id != mc.player.getId()) return;

        if (!autoShield.shouldAnimateSwordBlock(mc.player.getMainHandStack())) return;

        BipedEntityModel<?> model = (BipedEntityModel<?>) (Object) this;
        net.minecraft.client.model.ModelPart arm = state.mainArm == Arm.LEFT ? model.leftArm : model.rightArm;
        arm.pitch = -0.94f;
    }
}
