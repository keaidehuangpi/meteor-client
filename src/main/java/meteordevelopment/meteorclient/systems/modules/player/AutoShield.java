/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.Hand;

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
        if (killAura != null && killAura.isActive() && killAura.getTarget() != null) return true;

        TPAura tpAura = Modules.get().get(TPAura.class);
        return tpAura != null && tpAura.isActive() && tpAura.getTarget() != null;
    }

    public boolean shouldHideOffhandShield() {
        return isActive() && hideShield.get();
    }
}
