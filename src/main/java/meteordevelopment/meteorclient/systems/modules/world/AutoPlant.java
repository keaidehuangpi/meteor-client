/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.PlantBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoPlant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range in which to plant crops.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to plant crops when behind blocks.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between planting actions in ticks.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> maxPlantsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-plants-per-tick")
        .description("Maximum crops to plant per tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("The farmland positions you want to plant first.")
        .defaultValue(SortMode.Closest)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Automatically rotates towards farmland before planting.")
        .defaultValue(true)
        .build()
    );

    private final List<BlockPos> farmland = new ArrayList<>();
    private int timer;

    public AutoPlant() {
        super(Categories.World, "auto-plant", "Automatically plants crops on nearby farmland.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        farmland.clear();
    }

    @Override
    public void onDeactivate() {
        farmland.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (timer < delay.get()) {
            timer++;
            return;
        }
        timer = 0;

        Hand hand = getPlantingHand();
        if (hand == null) return;

        double pX = mc.player.getX();
        double pY = mc.player.getY();
        double pZ = mc.player.getZ();
        farmland.clear();

        BlockIterator.register((int) Math.ceil(range.get() + 1), (int) Math.ceil(range.get()), (blockPos, blockState) -> {
            if (!blockState.isOf(Blocks.FARMLAND)) return;
            if (isOutOfRange(blockPos)) return;

            BlockPos cropPos = blockPos.up();
            if (!mc.world.getBlockState(cropPos).isAir()) return;
            BlockItem blockItem = (BlockItem) (hand == Hand.MAIN_HAND
                ? mc.player.getMainHandStack().getItem()
                : mc.player.getOffHandStack().getItem());
            if (!blockItem.getBlock().getDefaultState().canPlaceAt(mc.world, cropPos)) return;
            farmland.add(blockPos.toImmutable());
        });

        BlockIterator.after(() -> {
            if (sortMode.get() == SortMode.TopDown || sortMode.get() == SortMode.BottomUp) {
                farmland.sort(Comparator.comparingInt(pos -> pos.getY() * (sortMode.get() == SortMode.BottomUp ? 1 : -1)));
            } else if (sortMode.get() != SortMode.None) {
                farmland.sort(Comparator.comparingDouble(pos -> Utils.squaredDistance(pX, pY, pZ,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5)
                    * (sortMode.get() == SortMode.Closest ? 1 : -1)));
            }

            int count = 0;
            for (BlockPos pos : farmland) {
                if (count >= maxPlantsPerTick.get()) break;
                plant(pos, hand);
                count++;
            }
            farmland.clear();
        });
    }

    private void plant(BlockPos farmlandPos, Hand hand) {
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(farmlandPos).add(0, 0.5, 0),
            Direction.UP,
            farmlandPos,
            false
        );

        Runnable action = () -> BlockUtils.interact(hit, hand, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(farmlandPos), Rotations.getPitch(farmlandPos), 0, action);
        else action.run();
    }

    private Hand getPlantingHand() {
        if (isPlantable(mc.player.getMainHandStack())) return Hand.MAIN_HAND;
        if (isPlantable(mc.player.getOffHandStack())) return Hand.OFF_HAND;
        return null;
    }

    private boolean isPlantable(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof PlantBlock;
    }

    private boolean isOutOfRange(BlockPos blockPos) {
        if (!PlayerUtils.isWithin(blockPos.toCenterPos(), range.get())) return true;

        RaycastContext context = new RaycastContext(mc.player.getEyePos(), blockPos.toCenterPos(),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        BlockHitResult result = mc.world.raycast(context);
        if (result == null || !result.getBlockPos().equals(blockPos))
            return !PlayerUtils.isWithin(blockPos.toCenterPos(), wallsRange.get());

        return false;
    }

    public enum SortMode {
        None,
        Closest,
        Furthest,
        TopDown,
        BottomUp
    }
}
