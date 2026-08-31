/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoShield;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(HeldItemFeatureRenderer.class)
public abstract class HeldItemFeatureRendererMixin<S extends ArmedEntityRenderState> {
    @WrapWithCondition(method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"))
    private boolean hideOffhandShield(ItemRenderState instance, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, int overlay, int outlineColor, @Local(argsOnly = true) ArmedEntityRenderState state, @Local(argsOnly = true) ItemStack itemStack, @Local(argsOnly = true) Arm arm) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield == null || !autoShield.shouldHideOffhandShield() || mc.player == null) return true;
        if (!(state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState playerState) || playerState.id != mc.player.getId()) return true;

        return arm == mc.player.getMainArm() || !(itemStack.getItem() instanceof net.minecraft.item.ShieldItem);
    }

    @Inject(method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V", shift = At.Shift.BEFORE))
    private void applyBlockingTransform(S state, ItemRenderState itemRenderState, ItemStack itemStack, Arm arm, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, CallbackInfo ci) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield == null || !autoShield.shouldAnimateSwordBlock(itemStack) || !(state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState playerState)) return;
        if (mc.player == null || playerState.id != mc.player.getId() || !autoShield.isMainArm(arm)) return;

        autoShield.applyThirdPersonBlockingTransform(matrices);
    }
}
