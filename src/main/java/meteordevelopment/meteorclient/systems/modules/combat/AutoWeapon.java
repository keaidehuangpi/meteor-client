/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.registry.tag.ItemTags;

public class AutoWeapon extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Weapon> weapon = sgGeneral.add(new EnumSetting.Builder<Weapon>()
        .name("weapon")
        .description("What type of weapon to use.")
        .defaultValue(Weapon.Sword)
        .build()
    );

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("threshold")
        .description("If the non-preferred weapon produces this much damage this will favor it over your preferred weapon.")
        .defaultValue(4)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Prevents you from breaking your weapon.")
        .defaultValue(false)
        .build()
    );

    public AutoWeapon() {
        super(Categories.Combat, "auto-weapon", "Finds the best weapon to use in your hotbar.");
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof LivingEntity livingEntity) {
            InvUtils.swap(getBestWeapon(livingEntity), false);
        }
    }

    private int getBestWeapon(LivingEntity target) {
        int slotS = mc.player.getInventory().getSelectedSlot();
        int slotA = mc.player.getInventory().getSelectedSlot();
        int slotM = mc.player.getInventory().getSelectedSlot();
        double damageS = 0;
        double damageA = 0;
        double damageM = 0;
        double currentDamageS;
        double currentDamageA;
        double currentDamageM;

        Criticals criticals = Modules.get().get(Criticals.class);
        boolean maceCriticals = criticals.isActive() && criticals.isMaceMode();
        double maceHeight = maceCriticals ? criticals.getMaceHeight() : 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isIn(ItemTags.SWORDS)
                && (!antiBreak.get() || (stack.getMaxDamage() - stack.getDamage()) > 10)) {
                currentDamageS = DamageUtils.getAttackDamage(mc.player, target, stack);
                if (currentDamageS > damageS) {
                    damageS = currentDamageS;
                    slotS = i;
                }
            } else if (stack.getItem() instanceof AxeItem
                && (!antiBreak.get() || (stack.getMaxDamage() - stack.getDamage()) > 10)) {
                currentDamageA = DamageUtils.getAttackDamage(mc.player, target, stack);
                if (currentDamageA > damageA) {
                    damageA = currentDamageA;
                    slotA = i;
                }
            } else if (weapon.get() == Weapon.Auto && maceCriticals && stack.getItem() instanceof MaceItem
                && (!antiBreak.get() || (stack.getMaxDamage() - stack.getDamage()) > 10)) {
                currentDamageM = DamageUtils.getAttackDamage(mc.player, target, stack, maceHeight);
                if (currentDamageM > damageM) {
                    damageM = currentDamageM;
                    slotM = i;
                }
            }
        }
        int swordModeSlot;
        double swordModeDamage;
        if (threshold.get() > damageA - damageS) {
            swordModeSlot = slotS;
            swordModeDamage = damageS;
        } else if (threshold.get() < damageA - damageS) {
            swordModeSlot = slotA;
            swordModeDamage = damageA;
        } else {
            swordModeSlot = mc.player.getInventory().getSelectedSlot();
            swordModeDamage = DamageUtils.getAttackDamage(mc.player, target, mc.player.getMainHandStack());
        }

        if (weapon.get() == Weapon.Auto) return damageM > swordModeDamage ? slotM : swordModeSlot;
        if (weapon.get() == Weapon.Sword && threshold.get() > damageA - damageS) return slotS;
        else if (weapon.get() == Weapon.Axe && threshold.get() > damageS - damageA) return slotA;
        else if (weapon.get() == Weapon.Sword && threshold.get() < damageA - damageS) return slotA;
        else if (weapon.get() == Weapon.Axe && threshold.get() < damageS - damageA) return slotS;
        else return mc.player.getInventory().getSelectedSlot();
    }

    public enum Weapon {
        Auto,
        Sword,
        Axe
    }
}
