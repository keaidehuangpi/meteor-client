/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoWater extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away infinite water sources can be used.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between filling buckets in ticks.")
        .defaultValue(2)
        .min(0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the water before filling the bucket.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders your client-side swing.")
        .defaultValue(true)
        .build()
    );

    private BlockPos target;
    private double targetDistance;
    private int timer;

    public AutoWater() {
        super(Categories.Player, "auto-water", "Automatically fills empty buckets from nearby infinite water sources.");
    }

    @Override
    public void onActivate() {
        target = null;
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Hand hand = getBucketHand();
        if (hand == null) {
            target = null;
            timer = 0;
            return;
        }

        if (timer < delay.get()) {
            timer++;
            return;
        }

        target = null;
        targetDistance = Double.MAX_VALUE;

        int radius = (int) Math.ceil(range.get());
        BlockIterator.register(radius, radius, (blockPos, blockState) -> {
            if (!isInfiniteWaterSource(blockPos)) return;

            Vec3d hitPos = blockPos.toCenterPos();
            if (!PlayerUtils.isWithin(hitPos, range.get())) return;

            double distance = mc.player.getEyePos().squaredDistanceTo(hitPos);
            if (distance < targetDistance) {
                target = blockPos.toImmutable();
                targetDistance = distance;
            }
        });

        BlockIterator.after(() -> {
            Hand currentHand = getBucketHand();
            if (target == null || currentHand == null) return;

            BlockPos waterPos = target;
            Vec3d hitPos = waterPos.toCenterPos();
            BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, waterPos, false);
            Runnable action = () -> {
                Hand actionHand = getBucketHand();
                if (actionHand == null || !isInfiniteWaterSource(waterPos)) return;

                BlockUtils.interact(hit, actionHand, swing.get());
                timer = 0;
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 0, action);
            else action.run();
        });
    }

    private boolean isInfiniteWaterSource(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.WATER)) return false;
        if (!isStillWater(pos)) return false;

        int adjacentSources = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isStillWater(pos.offset(direction))) adjacentSources++;
        }
        if (adjacentSources < 2) return false;

        BlockPos below = pos.down();
        return isStillWater(below) || mc.world.getBlockState(below).isSolidBlock(mc.world, below);
    }

    private boolean isStillWater(BlockPos pos) {
        FluidState fluidState = mc.world.getFluidState(pos);
        return fluidState.getFluid() == Fluids.WATER && fluidState.isStill();
    }

    private Hand getBucketHand() {
        if (mc.player.getMainHandStack().isOf(Items.BUCKET)) return Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().isOf(Items.BUCKET)) return Hand.OFF_HAND;
        return null;
    }
}
