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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

        double interactionRange = Math.min(range.get(), mc.player.getBlockInteractionRange());
        int radius = (int) Math.ceil(interactionRange);
        BlockIterator.register(radius, radius, (blockPos, blockState) -> {
            if (!isInfiniteWaterSource(blockPos)) return;

            Vec3d sourcePos = blockPos.toCenterPos();
            if (mc.player.getEyePos().squaredDistanceTo(sourcePos) > interactionRange * interactionRange) return;
            if (!canSeeWaterSource(blockPos, sourcePos)) return;

            double distance = mc.player.getEyePos().squaredDistanceTo(sourcePos);
            if (distance < targetDistance) {
                target = blockPos.toImmutable();
                targetDistance = distance;
            }
        });

        BlockIterator.after(() -> {
            Hand currentHand = getBucketHand();
            if (target == null || currentHand == null) return;

            BlockPos waterPos = target;
            Vec3d hitPos = getWaterHitPos(waterPos);
            Runnable action = () -> {
                Hand actionHand = getBucketHand();
                if (actionHand == null || !isInfiniteWaterSource(waterPos)) return;

                // Empty buckets are filled by BucketItem.use(), which performs its own source-only raycast.
                ActionResult result = mc.interactionManager.interactItem(mc.player, actionHand);
                if (!result.isAccepted()) return;

                if (swing.get()) mc.player.swingHand(actionHand);
                timer = 0;
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 0, action);
            else action.run();
        });
    }

    private boolean isInfiniteWaterSource(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.WATER) || !isStillWater(pos)) return false;

        int adjacentSources = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isStillWater(pos.offset(direction))) adjacentSources++;
        }
        if (adjacentSources < 2) return false;

        BlockPos below = pos.down();
        return mc.world.getBlockState(below).isSolidBlock(mc.world, below) || isStillWater(below);
    }

    private boolean isStillWater(BlockPos pos) {
        FluidState fluidState = mc.world.getFluidState(pos);
        return fluidState.getFluid() == Fluids.WATER && fluidState.isStill();
    }

    private Vec3d getWaterHitPos(BlockPos pos) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d center = pos.toCenterPos();

        // Aim at the visible side of the source. BucketItem's raycast only needs
        // the view to intersect the source block, so choosing a point between the
        // eye and the center avoids aiming through the far side of a pool.
        return eyePos.lerp(center, 0.75);
    }

    private boolean canSeeWaterSource(BlockPos pos, Vec3d hitPos) {
        BlockHitResult result = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(),
            hitPos,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.SOURCE_ONLY,
            mc.player
        ));

        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos);
    }

    private Hand getBucketHand() {
        if (mc.player.getMainHandStack().isOf(Items.BUCKET)) return Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().isOf(Items.BUCKET)) return Hand.OFF_HAND;
        return null;
    }
}
