/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class AutoTill extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away trampled farmland can be repaired.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically swaps to a hoe before tilling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the dirt before tilling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders your client-side swing.")
        .defaultValue(true)
        .build()
    );

    private final Set<BlockPos> pending = new LinkedHashSet<>();

    public AutoTill() {
        super(Categories.World, "auto-till", "Automatically tills farmland again after it is trampled.");
    }

    @Override
    public void onActivate() {
        pending.clear();
    }

    @Override
    public void onDeactivate() {
        pending.clear();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!event.oldState.isOf(Blocks.FARMLAND) || !event.newState.isOf(Blocks.DIRT)) return;

        pending.add(event.pos.toImmutable());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Iterator<BlockPos> iterator = pending.iterator();

        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();

            if (!mc.world.getBlockState(pos).isOf(Blocks.DIRT)) {
                iterator.remove();
                continue;
            }

            if (!PlayerUtils.isWithin(pos.toCenterPos(), range.get())) {
                iterator.remove();
                continue;
            }

            if (!mc.world.getBlockState(pos.up()).isAir()) {
                continue;
            }

            FindItemResult hoe = InvUtils.findInHotbar(stack -> stack.isIn(ItemTags.HOES));
            if (!hoe.found()) return;

            Hand hand = hoe.getHand();
            if (hand == null && !autoSwitch.get()) return;

            till(pos, hoe, hand == null ? Hand.MAIN_HAND : hand);
            iterator.remove();
            return;
        }
    }

    private void till(BlockPos pos, FindItemResult hoe, Hand hand) {
        Vec3d hitPos = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);

        Runnable action = () -> {
            boolean switched = hoe.getHand() == null;
            if (switched) InvUtils.swap(hoe.slot(), true);

            BlockUtils.interact(hit, hand, swing.get());

            if (switched) InvUtils.swapBack();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 0, action);
        else action.run();
    }
}
