/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoPlant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

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

    private final Setting<Integer> confirmationDelay = sgGeneral.add(new IntSetting.Builder()
        .name("confirmation-delay")
        .description("Ticks to wait before checking whether planting succeeded.")
        .defaultValue(2)
        .min(1)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders nearby farmland that can be planted on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How farmland targets are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of farmland targets.")
        .defaultValue(new SettingColor(0, 204, 0, 30))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of farmland targets.")
        .defaultValue(new SettingColor(0, 204, 0, 255))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> failedSideColor = sgRender.add(new ColorSetting.Builder()
        .name("failed-side-color")
        .description("The side color for failed planting attempts.")
        .defaultValue(new SettingColor(204, 0, 0, 45))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> failedLineColor = sgRender.add(new ColorSetting.Builder()
        .name("failed-line-color")
        .description("The line color for failed planting attempts.")
        .defaultValue(new SettingColor(204, 0, 0, 255))
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> failedRenderTicks = sgRender.add(new IntSetting.Builder()
        .name("failed-render-ticks")
        .description("How long failed planting positions remain highlighted.")
        .defaultValue(8)
        .min(1)
        .visible(render::get)
        .build()
    );

    private final List<BlockPos> farmland = new ArrayList<>();
    private final List<BlockPos> renderTargets = new ArrayList<>();
    private final Map<BlockPos, PendingPlant> pending = new HashMap<>();
    private int timer;

    public AutoPlant() {
        super(Categories.World, "auto-plant", "Automatically plants crops on nearby farmland.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        farmland.clear();
        renderTargets.clear();
        pending.clear();
    }

    @Override
    public void onDeactivate() {
        farmland.clear();
        renderTargets.clear();
        pending.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (BlockPos pos : renderTargets) {
            if (pending.containsKey(pos)) continue;
            if (!mc.world.getBlockState(pos).isOf(Blocks.FARMLAND) || !mc.world.getBlockState(pos.up()).isAir()) continue;
            event.renderer.box(pos.up(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        checkPendingPlants();

        if (timer < delay.get()) {
            timer++;
            return;
        }
        timer = 0;

        Hand hand = getPlantingHand();
        if (hand == null) {
            renderTargets.clear();
            return;
        }

        double pX = mc.player.getX();
        double pY = mc.player.getY();
        double pZ = mc.player.getZ();
        farmland.clear();

        BlockIterator.register((int) Math.ceil(range.get() + 1), (int) Math.ceil(range.get()), (blockPos, blockState) -> {
            if (!blockState.isOf(Blocks.FARMLAND)) return;
            if (isOutOfRange(blockPos)) return;
            if (pending.containsKey(blockPos)) return;

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

            renderTargets.clear();
            renderTargets.addAll(farmland);

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
        BlockItem blockItem = (BlockItem) (hand == Hand.MAIN_HAND
            ? mc.player.getMainHandStack().getItem()
            : mc.player.getOffHandStack().getItem());
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(farmlandPos).add(0, 0.5, 0),
            Direction.UP,
            farmlandPos,
            false
        );

        Runnable action = () -> {
            BlockUtils.interact(hit, hand, true);
            pending.put(farmlandPos.toImmutable(), new PendingPlant(blockItem.getBlock(), mc.player.age + confirmationDelay.get()));
        };
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(farmlandPos), Rotations.getPitch(farmlandPos), 0, action);
        else action.run();
    }

    private Hand getPlantingHand() {
        if (isPlantable(mc.player.getMainHandStack())) return Hand.MAIN_HAND;
        if (isPlantable(mc.player.getOffHandStack())) return Hand.OFF_HAND;
        return null;
    }

    private boolean isPlantable(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof PlantBlock
            && !blockItem.getBlock().equals(Blocks.SWEET_BERRY_BUSH);
    }

    private void checkPendingPlants() {
        pending.entrySet().removeIf(entry -> {
            PendingPlant plant = entry.getValue();
            if (mc.player.age < plant.confirmAtTick) return false;

            BlockPos cropPos = entry.getKey().up();
            boolean planted = mc.world.getBlockState(cropPos).isOf(plant.block);
            if (!planted && render.get()) {
                RenderUtils.renderTickingBlock(cropPos, failedSideColor.get(), failedLineColor.get(), shapeMode.get(), 0, failedRenderTicks.get(), true, false);
            }
            return true;
        });
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

    private static class PendingPlant {
        private final Block block;
        private final int confirmAtTick;

        private PendingPlant(Block block, int confirmAtTick) {
            this.block = block;
            this.confirmAtTick = confirmAtTick;
        }
    }
}
