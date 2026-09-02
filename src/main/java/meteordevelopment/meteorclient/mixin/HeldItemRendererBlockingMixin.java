/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoShield;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects the first-person swing animation for AutoShield's legacy blocking
 * pose. This is kept separate so it can be disabled when another mod (such as
 * Bring Blocking Back) already redirects the same invocation.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererBlockingMixin {
    @Unique
    private Hand meteor$blockingRenderHand;

    @Unique
    private ItemStack meteor$blockingRenderItem;

    @Shadow
    protected abstract void swingArm(float swingProgress, MatrixStack matrices, int armX, Arm arm);

    @Shadow
    protected abstract void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress);

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void setRenderHand(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, CallbackInfo ci) {
        meteor$blockingRenderHand = hand;
        meteor$blockingRenderItem = item;
    }

    @Redirect(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;swingArm(FLnet/minecraft/client/util/math/MatrixStack;ILnet/minecraft/util/Arm;)V"))
    private void blockingAttackAnimation(HeldItemRenderer instance, float swingProgress, MatrixStack matrices, int armX, Arm arm) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield != null && autoShield.shouldAnimateSwordBlock(meteor$blockingRenderItem) && meteor$blockingRenderHand == Hand.MAIN_HAND) {
            applySwingOffset(matrices, arm, swingProgress);
        }
        else {
            swingArm(swingProgress, matrices, armX, arm);
        }
    }
}
