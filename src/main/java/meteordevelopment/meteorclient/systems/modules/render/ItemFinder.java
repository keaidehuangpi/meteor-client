/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ItemFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEsp = settings.createGroup("ESP");
    private final SettingGroup sgNametag = settings.createGroup("Nametag");

    private final Setting<ListMode> listMode = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("How to use the item list.")
        .defaultValue(ListMode.Whitelist)
        .build()
    );

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items to include or exclude.")
        .build()
    );

    private final Setting<Boolean> ignoreDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-distance")
        .description("Ignores items beyond the maximum distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance at which items are rendered.")
        .defaultValue(128)
        .min(0)
        .sliderMax(256)
        .visible(ignoreDistance::get)
        .build()
    );

    private final Setting<Boolean> esp = sgEsp.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Highlights matching dropped items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> mode = sgEsp.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Rendering mode for matching items.")
        .defaultValue(Mode.Box)
        .visible(esp::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgEsp.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the ESP shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(esp::get)
        .build()
    );

    private final Setting<SettingColor> lineColorSetting = sgEsp.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of ESP lines.")
        .defaultValue(new SettingColor(255, 170, 0))
        .visible(esp::get)
        .build()
    );

    private final Setting<SettingColor> sideColorSetting = sgEsp.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of ESP sides.")
        .defaultValue(new SettingColor(255, 170, 0, 50))
        .visible(() -> esp.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<Boolean> nametags = sgNametag.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Displays nametags above matching dropped items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgNametag.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale of item nametags.")
        .defaultValue(1.1)
        .min(0.1)
        .visible(nametags::get)
        .build()
    );

    private final Setting<Boolean> showCount = sgNametag.add(new BoolSetting.Builder()
        .name("show-count")
        .description("Displays the number of items in the stack.")
        .defaultValue(true)
        .visible(nametags::get)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgNametag.add(new ColorSetting.Builder()
        .name("background-color")
        .description("The color of the nametag background.")
        .defaultValue(new SettingColor(0, 0, 0, 75))
        .visible(nametags::get)
        .build()
    );

    private final Setting<SettingColor> nameColor = sgNametag.add(new ColorSetting.Builder()
        .name("name-color")
        .description("The color of item names.")
        .defaultValue(new SettingColor())
        .visible(nametags::get)
        .build()
    );

    private final Setting<SettingColor> countColor = sgNametag.add(new ColorSetting.Builder()
        .name("count-color")
        .description("The color of item stack counts.")
        .defaultValue(new SettingColor(232, 185, 35))
        .visible(() -> nametags.get() && showCount.get())
        .build()
    );

    private final List<ItemEntity> itemEntities = new ArrayList<>();
    private final Vector3d pos = new Vector3d();
    private final Vector3d pos1 = new Vector3d();
    private final Vector3d pos2 = new Vector3d();

    public ItemFinder() {
        super(Categories.Render, "item-finder", "Highlights and labels selected items dropped on the ground.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        itemEntities.clear();
        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity) || shouldSkip(itemEntity)) continue;
            itemEntities.add(itemEntity);
        }

        itemEntities.sort(Comparator.comparing(entity -> entity.squaredDistanceTo(cameraPos)));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!esp.get() || mode.get() == Mode._2D) return;

        for (ItemEntity entity : itemEntities) {
            if (mode.get() == Mode.Wireframe) {
                WireframeEntityRenderer.render(event, entity, 1, sideColorSetting.get(), lineColorSetting.get(), shapeMode.get());
                continue;
            }

            double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
            double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
            double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();
            Box box = entity.getBoundingBox();

            event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColorSetting.get(), lineColorSetting.get(), shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (esp.get() && mode.get() == Mode._2D) renderEsp2D(event);
        if (!nametags.get()) return;

        boolean shadow = Config.get().customFont.get();
        for (int i = itemEntities.size() - 1; i >= 0; i--) {
            ItemEntity entity = itemEntities.get(i);
            Utils.set(pos, entity, event.tickDelta);
            pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.2, 0);

            if (NametagUtils.to2D(pos, scale.get())) renderNametag(entity.getStack(), shadow);
        }
    }

    private void renderEsp2D(Render2DEvent event) {
        Renderer2D.COLOR.begin();

        for (ItemEntity entity : itemEntities) {
            Box box = entity.getBoundingBox();
            double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
            double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
            double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();

            pos1.set(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
            pos2.set(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);

            if (!projectBox(box, x, y, z)) continue;

            if (shapeMode.get() != ShapeMode.Lines) {
                Renderer2D.COLOR.quad(pos1.x, pos1.y, pos2.x - pos1.x, pos2.y - pos1.y, sideColorSetting.get());
            }

            if (shapeMode.get() != ShapeMode.Sides) {
                Renderer2D.COLOR.line(pos1.x, pos1.y, pos1.x, pos2.y, lineColorSetting.get());
                Renderer2D.COLOR.line(pos2.x, pos1.y, pos2.x, pos2.y, lineColorSetting.get());
                Renderer2D.COLOR.line(pos1.x, pos1.y, pos2.x, pos1.y, lineColorSetting.get());
                Renderer2D.COLOR.line(pos1.x, pos2.y, pos2.x, pos2.y, lineColorSetting.get());
            }
        }

        Renderer2D.COLOR.render();
    }

    private boolean projectBox(Box box, double x, double y, double z) {
        return projectCorner(box.minX + x, box.minY + y, box.minZ + z)
            && projectCorner(box.maxX + x, box.minY + y, box.minZ + z)
            && projectCorner(box.minX + x, box.minY + y, box.maxZ + z)
            && projectCorner(box.maxX + x, box.minY + y, box.maxZ + z)
            && projectCorner(box.minX + x, box.maxY + y, box.minZ + z)
            && projectCorner(box.maxX + x, box.maxY + y, box.minZ + z)
            && projectCorner(box.minX + x, box.maxY + y, box.maxZ + z)
            && projectCorner(box.maxX + x, box.maxY + y, box.maxZ + z);
    }

    private boolean projectCorner(double x, double y, double z) {
        pos.set(x, y, z);
        if (!NametagUtils.to2D(pos, 1)) return false;

        pos1.min(pos);
        pos2.max(pos);
        return true;
    }

    private void renderNametag(ItemStack stack, boolean shadow) {
        if (stack.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        String name = Names.get(stack);
        String count = " x" + stack.getCount();
        double height = text.getHeight(shadow);
        double width = text.getWidth(name, shadow);
        if (showCount.get()) width += text.getWidth(count, shadow);
        double x = -width / 2;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 1, -height - 1, width + 2, height + 2, backgroundColor.get());
        Renderer2D.COLOR.render();

        text.beginBig();
        x = text.render(name, x, -height, nameColor.get(), shadow);
        if (showCount.get()) text.render(count, x, -height, countColor.get(), shadow);
        text.end();

        NametagUtils.end();
    }

    private boolean shouldSkip(ItemEntity entity) {
        if (!EntityUtils.isInRenderDistance(entity)) return true;
        if (ignoreDistance.get() && PlayerUtils.squaredDistanceToCamera(entity) > maxDistance.get() * maxDistance.get()) return true;

        boolean listed = items.get().contains(entity.getStack().getItem());
        return listMode.get() == ListMode.Whitelist ? !listed : listed;
    }

    @Override
    public String getInfoString() {
        return Integer.toString(itemEntities.size());
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum Mode {
        Box,
        Wireframe,
        _2D;

        @Override
        public String toString() {
            return this == _2D ? "2D" : super.toString();
        }
    }
}
