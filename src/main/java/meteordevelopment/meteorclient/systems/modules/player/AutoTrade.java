/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

public class AutoTrade extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How many ticks to wait between repeated trades.")
        .defaultValue(1)
        .min(0)
        .build()
    );

    private MerchantScreenHandler handler;
    private int[] offerUses;
    private int selectedOffer = -1;
    private int delayLeft;

    public AutoTrade() {
        super(Categories.Player, "auto-trade", "Repeats the trade selected by the player while a villager trading screen is open.");
    }

    @Override
    public void onActivate() {
        handler = null;
        offerUses = null;
        selectedOffer = -1;
        delayLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !(mc.player.currentScreenHandler instanceof MerchantScreenHandler merchant)
            || mc.currentScreen == null) {
            resetHandler();
            return;
        }

        if (merchant != handler) {
            handler = merchant;
            selectedOffer = -1;
            delayLeft = 0;
            snapshotUses(merchant.getRecipes());
        }

        TradeOfferList offers = merchant.getRecipes();
        if (offers == null || offers.isEmpty()) return;

        // A use count only changes after the server accepts a completed trade.
        if (selectedOffer == -1) {
            for (int i = 0; i < offers.size(); i++) {
                int uses = offers.get(i).getUses();
                if (i >= offerUses.length) break;
                if (uses > offerUses[i]) {
                    selectedOffer = i;
                    break;
                }
                offerUses[i] = uses;
            }
        }

        if (selectedOffer == -1 || selectedOffer >= offers.size()) return;

        TradeOffer offer = offers.get(selectedOffer);
        if (offer.isDisabled() || merchant.slots.get(2).getStack().isEmpty()) return;

        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        mc.interactionManager.clickSlot(merchant.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
        delayLeft = delay.get();
    }

    private void snapshotUses(TradeOfferList offers) {
        offerUses = new int[offers == null ? 0 : offers.size()];
        if (offers == null) return;

        for (int i = 0; i < offers.size(); i++) offerUses[i] = offers.get(i).getUses();
    }

    private void resetHandler() {
        handler = null;
        offerUses = null;
        selectedOffer = -1;
        delayLeft = 0;
    }
}
