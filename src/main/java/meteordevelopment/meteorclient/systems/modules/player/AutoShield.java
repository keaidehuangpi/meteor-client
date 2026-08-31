/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.combat.TPAura;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoShield extends Module {
    public enum Mode {
        Always,
        Sometimes
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("When to block with the offhand shield. Sometimes blocks only while KillAura or TPAura has a target in range.")
        .defaultValue(Mode.Always)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to a KillAura or TPAura target before blocking in Sometimes mode.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .visible(() -> mode.get() == Mode.Sometimes)
        .build()
    );

    private final Setting<Boolean> blockingAnimations = sgGeneral.add(new BoolSetting.Builder()
        .name("blocking-animations")
        .description("Plays the classic 1.7 blocking and blocking-attack animations while using the shield.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideShield = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-shield")
        .description("Hides the shield in your offhand while it is being used.")
        .defaultValue(false)
        .build()
    );

    private boolean shielding;
    private boolean useKeyWasPressed;

    public AutoShield() {
        super(Categories.Player, "auto-shield", "Automatically blocks with a shield in your offhand.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        ItemStack offhand = mc.player.getOffHandStack();
        if (!(offhand.getItem() instanceof ShieldItem) || shouldPauseForEating() || !shouldShield()) {
            stopShielding();
            return;
        }

        if (!shielding) useKeyWasPressed = mc.options.useKey.isPressed();
        mc.options.useKey.setPressed(true);

        if (!mc.player.isUsingItem() || mc.player.getActiveHand() != Hand.OFF_HAND) {
            if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }

        shielding = true;
    }

    @Override
    public void onDeactivate() {
        stopShielding();
    }

    private void stopShielding() {
        if (shielding && mc.options != null) mc.options.useKey.setPressed(useKeyWasPressed);

        if (shielding && mc.player != null && mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.OFF_HAND) {
            mc.interactionManager.stopUsingItem(mc.player);
        }

        shielding = false;
    }

    private boolean shouldPauseForEating() {
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        AutoGap autoGap = Modules.get().get(AutoGap.class);

        if (autoEat != null && autoEat.isActive() && (autoEat.eating || autoEat.shouldEat())) return true;
        if (autoGap != null && autoGap.isActive() && (autoGap.isEating() || autoGap.shouldEatNow())) return true;

        if (!mc.player.isUsingItem()) return false;
        return mc.player.getActiveItem().get(DataComponentTypes.FOOD) != null;
    }

    private boolean shouldShield() {
        if (mode.get() == Mode.Always) return true;

        KillAura killAura = Modules.get().get(KillAura.class);
        if (killAura != null && killAura.isActive() && isInShieldRange(killAura.getTarget())) return true;

        TPAura tpAura = Modules.get().get(TPAura.class);
        return tpAura != null && tpAura.isActive() && isInShieldRange(tpAura.getTarget());
    }

    private boolean isInShieldRange(Entity target) {
        return target != null && mc.player.squaredDistanceTo(target) <= range.get() * range.get();
    }

    public boolean shouldHideOffhandShield() {
        return isActive() && hideShield.get();
    }

    public boolean shouldAnimateBlocking() {
        return isActive()
            && blockingAnimations.get()
            && mc.player != null
            && mc.player.isUsingItem()
            && mc.player.getActiveHand() == Hand.OFF_HAND
            && mc.player.getOffHandStack().getItem() instanceof ShieldItem;
    }

    public boolean shouldAnimateSwordBlock(ItemStack itemStack) {
        return shouldAnimateBlocking() && itemStack != null && (itemStack.isIn(ItemTags.SWORDS) || itemStack.getItem() instanceof MaceItem);
    }

    public void applyFirstPersonBlockingTransform(MatrixStack matrices) {
        matrices.translate(-0.15f, 0.16f, 0.15f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-18.0f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(82.0f));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(112.0f));
    }

    public void applyThirdPersonBlockingTransform(MatrixStack matrices) {
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-90.0f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-40.0f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(51.0f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(197.2f));
        matrices.translate(-0.22f, 0.13f, -0.22f);
    }

    public boolean isMainArm(Arm arm) {
        return mc.player != null && mc.player.getMainArm() == arm;
    }
}
