/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.ArmRenderEvent;
import meteordevelopment.meteorclient.events.render.HeldItemRendererEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoShield;
import meteordevelopment.meteorclient.systems.modules.render.HandView;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Unique
    private Hand meteor$renderHand;

    @Unique
    private ItemStack meteor$renderItem;

    @Shadow
    private float equipProgressMainHand;

    @Shadow
    private float equipProgressOffHand;

    @Shadow
    private ItemStack mainHand;

    @Shadow
    private ItemStack offHand;

    @Shadow
    protected abstract boolean shouldSkipHandAnimationOnSwap(ItemStack from, ItemStack to);

    @Shadow
    protected abstract void swingArm(float swingProgress, MatrixStack matrices, int armX, Arm arm);

    @Shadow
    protected abstract void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress);

    @ModifyExpressionValue(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getHandSwingProgress(F)F"))
    private float modifySwing(float swingProgress) {
        HandView module = Modules.get().get(HandView.class);
        Hand hand = Objects.requireNonNullElse(mc.player.preferredHand, Hand.MAIN_HAND);

        if (module.isActive()) {
            if (module.swordSlash() && hand == Hand.MAIN_HAND && mainHand.isIn(ItemTags.SWORDS)) {
                return 0f;
            }
            if (hand == Hand.OFF_HAND && !offHand.isEmpty()) {
                return swingProgress + module.offSwing.get().floatValue();
            }
            if (hand == Hand.MAIN_HAND && !mainHand.isEmpty()) {
                return swingProgress + module.mainSwing.get().floatValue();
            }
        }

        return swingProgress;
    }

    @ModifyReturnValue(method = "shouldSkipHandAnimationOnSwap", at = @At("RETURN"))
    private boolean modifySkipSwapAnimation(boolean original) {
        return original || Modules.get().get(HandView.class).skipSwapping();
    }

    @ModifyArg(method = "updateHeldItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(FFF)F", ordinal = 2), index = 0)
    private float modifyEquipProgressMainhand(float value) {
        HandView handView = Modules.get().get(HandView.class);
        if (handView.swordSlash() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return value;

        float f = mc.player.getHandEquippingProgress(1f);
        float modified = handView.oldAnimations() ? 1 : f * f * f;

        return (shouldSkipHandAnimationOnSwap(mainHand, mc.player.getMainHandStack()) ? modified : 0) - equipProgressMainHand;
    }

    @ModifyArg(method = "updateHeldItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(FFF)F", ordinal = 3), index = 0)
    private float modifyEquipProgressOffhand(float value) {
        return (shouldSkipHandAnimationOnSwap(offHand, mc.player.getOffHandStack()) ? 1 : 0) - equipProgressOffHand;
    }

    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V", shift = At.Shift.BEFORE))
    private void onRenderItem(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, CallbackInfo ci) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield != null && autoShield.shouldAnimateSwordBlock(item) && hand == Hand.MAIN_HAND) {
            autoShield.applyFirstPersonBlockingTransform(matrices);
        }

        MeteorClient.EVENT_BUS.post(HeldItemRendererEvent.get(hand, matrices));
    }

    @Redirect(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;swingArm(FLnet/minecraft/client/util/math/MatrixStack;ILnet/minecraft/util/Arm;)V"))
    private void blockingAttackAnimation(HeldItemRenderer instance, float swingProgress, MatrixStack matrices, int armX, Arm arm) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield != null && autoShield.shouldAnimateSwordBlock(meteor$renderItem) && meteor$renderHand == Hand.MAIN_HAND) {
            applySwingOffset(matrices, arm, swingProgress);
        }
        else {
            swingArm(swingProgress, matrices, armX, arm);
        }
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void setRenderHand(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, CallbackInfo ci) {
        meteor$renderHand = hand;
        meteor$renderItem = item;
    }

    // Offhand shields make vanilla lower the main-hand equip height. Keep the sword's attack progress,
    // but remove that extra equip offset while rendering the legacy blockhit pose.
    @ModifyArg(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;applyEquipOffset(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/util/Arm;F)V", ordinal = 4), index = 2)
    private float stabilizeSwordBlockEquipOffset(float equipProgress, @Local(argsOnly = true) AbstractClientPlayerEntity player, @Local(argsOnly = true) Hand hand, @Local(argsOnly = true) ItemStack item) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        if (autoShield != null && player == mc.player && hand == Hand.MAIN_HAND && autoShield.shouldAnimateSwordBlock(item)) {
            return 0f;
        }

        return equipProgress;
    }

    @WrapWithCondition(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"))
    private boolean hideOffhandShield(HeldItemRenderer instance, LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light) {
        AutoShield autoShield = Modules.get().get(AutoShield.class);
        return !(autoShield != null && autoShield.shouldHideOffhandShield() && meteor$renderHand == Hand.OFF_HAND && entity == mc.player && stack.getItem() instanceof ShieldItem);
    }

    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderArmHoldingItem(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;IFFLnet/minecraft/util/Arm;)V"))
    private void onRenderArm(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue orderedRenderCommandQueue, int light, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ArmRenderEvent.get(hand, matrices));
    }

    @Inject(method = "applyEatOrDrinkTransformation", at = @At(value = "INVOKE", target = "Ljava/lang/Math;pow(DD)D", shift = At.Shift.BEFORE), cancellable = true)
    private void cancelTransformations(MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack, PlayerEntity player, CallbackInfo ci) {
        if (Modules.get().get(HandView.class).disableFoodAnimation()) ci.cancel();
    }
}
