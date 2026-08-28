/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class LightOverlay extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSmartLighting = settings.createGroup("Smart Lighting");
    private final SettingGroup sgColors = settings.createGroup("Colors");

    // General

    private final Setting<Integer> horizontalRange = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-range")
        .description("Horizontal range in blocks.")
        .defaultValue(8)
        .min(0)
        .build()
    );

    private final Setting<Integer> verticalRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("Vertical range in blocks.")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Boolean> seeThroughBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("see-through-blocks")
        .description("Allows you to see the lines through blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> lightLevel = sgGeneral.add(new IntSetting.Builder()
        .name("light-level")
        .description("Which light levels to render. Old spawning light: 7.")
        .defaultValue(0)
        .min(0)
        .sliderMax(15)
        .build()
    );

    private final Setting<Boolean> smartLighting = sgSmartLighting.add(new BoolSetting.Builder()
        .name("smart-lighting")
        .description("Suggests the best positions for the luminous block in your hand to prevent mob spawning.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> renderPlacements = sgSmartLighting.add(new BoolSetting.Builder()
        .name("render-placements")
        .description("Renders the suggested luminous block positions.")
        .defaultValue(true)
        .visible(smartLighting::get)
        .build()
    );

    private final Setting<Boolean> autoPlace = sgSmartLighting.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically places the luminous block at the suggested positions.")
        .defaultValue(false)
        .visible(smartLighting::get)
        .build()
    );

    private final Setting<Integer> placeDelay = sgSmartLighting.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between automatic placements in ticks.")
        .defaultValue(2)
        .min(0)
        .visible(autoPlace::get)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgSmartLighting.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum luminous blocks to place in one tick.")
        .defaultValue(1)
        .min(1)
        .sliderMax(8)
        .visible(autoPlace::get)
        .build()
    );

    private final Setting<Double> placeRange = sgSmartLighting.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Maximum distance for automatic luminous block placement.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .visible(autoPlace::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgSmartLighting.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the suggested position before placing.")
        .defaultValue(true)
        .visible(autoPlace::get)
        .build()
    );

    // Colors

    private final Setting<SettingColor> color = sgColors.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of places where mobs can currently spawn.")
        .defaultValue(new SettingColor(225, 25, 25))
        .build()
    );

    private final Setting<SettingColor> potentialColor = sgColors.add(new ColorSetting.Builder()
        .name("potential-color")
        .description("Color of places where mobs can potentially spawn (eg at night).")
        .defaultValue(new SettingColor(225, 225, 25))
        .build()
    );

    private final Setting<SettingColor> placementColor = sgColors.add(new ColorSetting.Builder()
        .name("placement-color")
        .description("Color of suggested luminous block positions.")
        .defaultValue(new SettingColor(25, 225, 25, 255))
        .visible(smartLighting::get)
        .build()
    );

    private final Pool<Cross> crossPool = new Pool<>(Cross::new);
    private final List<Cross> crosses = new ArrayList<>();
    private final List<BlockPos> spawnPositions = new ArrayList<>();
    private final List<BlockPos> candidatePositions = new ArrayList<>();
    private final List<BlockPos> placementPositions = new ArrayList<>();
    private int placementTimer;

    public LightOverlay() {
        super(Categories.Render, "light-overlay", "Shows blocks where mobs can spawn.");
    }

    @Override
    public void onActivate() {
        clearPositions();
        placementTimer = 0;
    }

    @Override
    public void onDeactivate() {
        clearPositions();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        crossPool.freeAll(crosses);
        crosses.clear();

        spawnPositions.clear();
        candidatePositions.clear();
        placementPositions.clear();

        HeldLight heldLight = smartLighting.get() ? getHeldLight() : null;

        BlockIterator.register(horizontalRange.get(), verticalRange.get(), (blockPos, blockState) -> {
            BlockUtils.MobSpawn spawn = BlockUtils.isValidMobSpawn(blockPos, blockState, lightLevel.get());

            switch (spawn) {
                case Potential -> crosses.add(crossPool.get().set(blockPos, true));
                case Always -> crosses.add((crossPool.get().set(blockPos, false)));
            }

            if (heldLight == null) return;

            if (spawn != BlockUtils.MobSpawn.Never) spawnPositions.add(blockPos.toImmutable());
            if (isPlacementCandidate(blockPos, blockState, heldLight.block)
                && (!autoPlace.get() || PlayerUtils.isWithin(blockPos.toCenterPos(), placeRange.get()))) {
                candidatePositions.add(blockPos.toImmutable());
            }
        });

        if (heldLight != null) BlockIterator.after(() -> {
            inferPlacements(heldLight.luminance);
            if (autoPlace.get()) place(heldLight);
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (crosses.isEmpty()) return;

        Renderer3D renderer = seeThroughBlocks.get() ? event.renderer : event.depthRenderer;

        for (Cross cross : crosses) {
            cross.render(renderer);
        }

        if (!smartLighting.get() || !renderPlacements.get()) return;

        for (BlockPos blockPos : placementPositions) {
            renderer.box(blockPos, placementColor.get(), placementColor.get(), ShapeMode.Lines, 0);
        }
    }

    private void inferPlacements(int luminance) {
        placementPositions.clear();
        if (spawnPositions.isEmpty() || candidatePositions.isEmpty()) return;

        BitSet remaining = new BitSet(spawnPositions.size());
        remaining.set(0, spawnPositions.size());

        int[] spawnLightLevels = new int[spawnPositions.size()];
        for (int i = 0; i < spawnPositions.size(); i++) {
            spawnLightLevels[i] = mc.world.getLightLevel(LightType.BLOCK, spawnPositions.get(i));
        }

        BitSet[] coverage = new BitSet[candidatePositions.size()];
        for (int i = 0; i < candidatePositions.size(); i++) {
            BlockPos candidate = candidatePositions.get(i);
            BitSet covered = new BitSet(spawnPositions.size());

            for (int j = 0; j < spawnPositions.size(); j++) {
                if (illuminates(candidate, spawnPositions.get(j), luminance, spawnLightLevels[j])) covered.set(j);
            }

            coverage[i] = covered;
        }

        boolean[] selected = new boolean[candidatePositions.size()];
        while (!remaining.isEmpty()) {
            int best = -1;
            int bestGain = 0;
            double bestDistance = Double.MAX_VALUE;

            for (int i = 0; i < coverage.length; i++) {
                if (selected[i]) continue;

                BitSet gain = (BitSet) coverage[i].clone();
                gain.and(remaining);
                int gainSize = gain.cardinality();
                if (gainSize == 0) continue;

                double distance = distanceToPlayer(candidatePositions.get(i));
                if (gainSize > bestGain || gainSize == bestGain && distance < bestDistance) {
                    best = i;
                    bestGain = gainSize;
                    bestDistance = distance;
                }
            }

            if (best == -1) break;

            selected[best] = true;
            placementPositions.add(candidatePositions.get(best));
            remaining.andNot(coverage[best]);
        }
    }

    private boolean illuminates(BlockPos source, BlockPos target, int luminance, int currentLightLevel) {
        if (source.equals(target)) return true;

        int distance = Math.abs(source.getX() - target.getX())
            + Math.abs(source.getY() - target.getY())
            + Math.abs(source.getZ() - target.getZ());

        int projectedLight = Math.max(currentLightLevel, luminance - distance);
        return projectedLight > lightLevel.get();
    }

    private boolean isPlacementCandidate(BlockPos blockPos, BlockState blockState, Block block) {
        if (!blockState.isReplaceable()) return false;
        if (!BlockUtils.canPlaceBlock(blockPos, true, block)) return false;
        if (!block.getDefaultState().canPlaceAt(mc.world, blockPos)) return false;
        return BlockUtils.getPlaceSide(blockPos) != null;
    }

    private void place(HeldLight heldLight) {
        if (placementTimer > 0) {
            placementTimer--;
            return;
        }

        int placed = 0;
        for (BlockPos blockPos : placementPositions) {
            if (placed >= blocksPerTick.get()) break;
            if (!PlayerUtils.isWithin(blockPos.toCenterPos(), placeRange.get())) continue;
            if (!isPlacementCandidate(blockPos, mc.world.getBlockState(blockPos), heldLight.block)) continue;

            if (BlockUtils.place(blockPos, heldLight.item, rotate.get(), -50, false, true, true)) placed++;
        }

        if (placed > 0) placementTimer = placeDelay.get();
    }

    private HeldLight getHeldLight() {
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.getItem() instanceof BlockItem blockItem && blockItem.getBlock().getDefaultState().getLuminance() > 0) {
            return new HeldLight(blockItem.getBlock(), blockItem.getBlock().getDefaultState().getLuminance(),
                new FindItemResult(mc.player.getInventory().getSelectedSlot(), mainHand.getCount()));
        }

        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand.getItem() instanceof BlockItem blockItem && blockItem.getBlock().getDefaultState().getLuminance() > 0) {
            return new HeldLight(blockItem.getBlock(), blockItem.getBlock().getDefaultState().getLuminance(),
                new FindItemResult(SlotUtils.OFFHAND, offHand.getCount()));
        }

        return null;
    }

    private double distanceToPlayer(BlockPos blockPos) {
        double x = blockPos.getX() + 0.5 - mc.player.getX();
        double y = blockPos.getY() + 0.5 - mc.player.getY();
        double z = blockPos.getZ() + 0.5 - mc.player.getZ();
        return x * x + y * y + z * z;
    }

    private void clearPositions() {
        crossPool.freeAll(crosses);
        crosses.clear();
        spawnPositions.clear();
        candidatePositions.clear();
        placementPositions.clear();
    }

    private record HeldLight(Block block, int luminance, FindItemResult item) {
    }

    private class Cross {
        private double x, y, z;
        private boolean potential;

        public Cross set(BlockPos blockPos, boolean potential) {
            x = blockPos.getX();
            y = blockPos.getY() + 0.0075;
            z = blockPos.getZ();

            this.potential = potential;

            return this;
        }

        public void render(Renderer3D renderer) {
            Color c = potential ? potentialColor.get() : color.get();

            renderer.line(x, y, z, x + 1, y, z + 1, c);
            renderer.line(x + 1, y, z, x, y, z + 1, c);
        }
    }

    public enum Spawn {
        Never,
        Potential,
        Always
    }
}
