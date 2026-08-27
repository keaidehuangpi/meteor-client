/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.ClickTP;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.awt.AWTException;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TPAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // General

    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("attack-when-holding")
        .description("Only attacks an entity when a specified item is in your hand.")
        .defaultValue(AttackItems.Weapons)
        .build()
    );

    private final Setting<List<Item>> weapons = sgGeneral.add(new ItemListSetting.Builder()
        .name("selected-weapon-types")
        .description("Which types of weapons to attack with (if you select the diamond sword, any type of sword may be used to attack).")
        .defaultValue(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.TRIDENT)
        .filter(FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to an acceptable weapon when attacking the target.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches to your previous slot when done attacking the target.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("""
            What to do when your target is blocking with a shield:
            - Ignore:   Don't attack them if they are blocking
            - Break:    Swap to an axe to disable the shield (Only if Auto Switch is enabled)
            - None:     Attack them as normal
        """)
        .defaultValue(ShieldMode.None)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only attacks when holding left click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnLook = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-look")
        .description("Only attacks when looking at an entity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Freezes Baritone temporarily until you are finished attacking the entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<TeleportMode> teleportMode = sgGeneral.add(new EnumSetting.Builder<TeleportMode>()
        .name("teleport-mode")
        .description("Goto moves your client to the target. Stand only sends movement packets and returns after attacking.")
        .defaultValue(TeleportMode.Goto)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends system notifications when you die or no target is found for a while.")
        .defaultValue(true)
        .build()
    );

    private final Setting<NoTargetMode> noTargetMode = sgGeneral.add(new EnumSetting.Builder<NoTargetMode>()
        .name("no-target-mode")
        .description("What to do when no target is found in the normal range.")
        .defaultValue(NoTargetMode.Notify)
        .build()
    );

    private final Setting<Integer> noTargetTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("no-target-timeout")
        .description("How many seconds without a target before sending a notification.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 60)
        .visible(() -> notifications.get() && noTargetMode.get() == NoTargetMode.Notify)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many entities to target at once.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .visible(() -> !onlyOnLook.get())
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to attack it.")
        .defaultValue(10)
        .min(0)
        .sliderMax(30)
        .build()
    );

    private final Setting<Double> expandedRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("expanded-range")
        .description("The maximum search range used by the expand-range no-target mode.")
        .defaultValue(30)
        .min(0)
        .sliderMax(100)
        .visible(() -> noTargetMode.get() == NoTargetMode.ExpandRange)
        .build()
    );

    private final Setting<Boolean> excludeAirborne = sgTargeting.add(new BoolSetting.Builder()
        .name("exclude-airborne")
        .description("Excludes airborne entities from being selected as the teleport target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> attackRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("The range around you after teleporting in which entities can be attacked.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls.")
        .defaultValue(3.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<EntityAge> passiveMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("passive-mob-age-filter")
        .description("Determines the age of passive mobs to target (animals, villagers).")
        .defaultValue(EntityAge.Adult)
        .build()
    );

    private final Setting<EntityAge> hostileMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("hostile-mob-age-filter")
        .description("Determines the age of hostile mobs to target (zombies, piglins, hoglins, zoglins).")
        .defaultValue(EntityAge.Both)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Whether or not to attack mobs with a name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignorePassive = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-passive")
        .description("Will only attack sometimes passive mobs if they are targeting you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Will avoid attacking mobs you tamed.")
        .defaultValue(false)
        .build()
    );

    // Timing

    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses if the server is lagging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Does not attack while CA is placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Tries to sync attack delay with the server's TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Use a custom delay instead of the vanilla cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("How fast you hit the entity in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("How many ticks to wait before hitting an entity after switching hotbar slots.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final static ArrayList<Item> FILTER = new ArrayList<>(List.of(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.MACE, Items.DIAMOND_SPEAR, Items.TRIDENT));
    private final List<Entity> targets = new ArrayList<>();
    private final List<Entity> attackTargets = new ArrayList<>();
    private int switchTimer, hitTimer;
    private int noTargetTicks;
    private boolean deathNotified, noTargetNotified;
    private TrayIcon desktopTrayIcon;
    private boolean wasPathing = false;
    public boolean attacking, swapped;
    public static int previousSlot;

    public TPAura() {
        super(Categories.Combat, "tp-aura", "Teleports to and attacks specified entities around you.");
    }

    @Override
    public void onActivate() {
        previousSlot = -1;
        swapped = false;
        noTargetTicks = 0;
        deathNotified = false;
        noTargetNotified = false;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        attackTargets.clear();
        stopAttacking();
        removeDesktopNotificationIcon();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive()) {
            if (notifications.get() && !deathNotified) {
                sendDesktopNotification("Player died while TPAura was active.");
                deathNotified = true;
            }
            stopAttacking();
            return;
        }
        deathNotified = false;
        if (PlayerUtils.getGameMode() == GameMode.SPECTATOR) {
            stopAttacking();
            return;
        }
        if (pauseOnUse.get() && (mc.interactionManager.isBreakingBlock() || mc.player.isUsingItem())) {
            stopAttacking();
            return;
        }
        if (onlyOnClick.get() && !mc.options.attackKey.isPressed()) {
            stopAttacking();
            return;
        }
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f && pauseOnLag.get()) {
            stopAttacking();
            return;
        }
        if (pauseOnCA.get() && Modules.get().get(CrystalAura.class).isActive() && Modules.get().get(CrystalAura.class).kaTimer > 0) {
            stopAttacking();
            return;
        }
        if (onlyOnLook.get()) {
            Entity targeted = mc.targetedEntity;

            targets.clear();
            if (targeted != null && entityCheck(targeted, range.get(), excludeAirborne.get())) {
                targets.add(targeted);
            } else if (targeted != null && noTargetMode.get() == NoTargetMode.ExpandRange
                && entityCheck(targeted, expandedSearchRange(), excludeAirborne.get())) {
                targets.add(targeted);
            }
        } else {
            targets.clear();
            TargetUtils.getList(targets, entity -> entityCheck(entity, range.get(), excludeAirborne.get()), priority.get(), 1);

            if (targets.isEmpty() && noTargetMode.get() == NoTargetMode.ExpandRange) {
                TargetUtils.getList(targets, entity -> entityCheck(entity, expandedSearchRange(), excludeAirborne.get()), priority.get(), 1);
            }
        }

        if (targets.isEmpty()) {
            if (noTargetMode.get() == NoTargetMode.Notify) handleNoTarget();
            else {
                noTargetTicks = 0;
                noTargetNotified = false;
            }
            stopAttacking();
            return;
        }

        noTargetTicks = 0;
        noTargetNotified = false;

        Entity primary = targets.getFirst();

        if (autoSwitch.get()) {
            FindItemResult weaponResult = new FindItemResult(mc.player.getInventory().getSelectedSlot(), -1);
            if (attackWhenHolding.get() == AttackItems.Weapons) weaponResult = InvUtils.find(this::acceptableWeapon, 0, 8);

            if (shouldShieldBreak()) {
                FindItemResult axeResult = InvUtils.find(itemStack -> itemStack.getItem() instanceof AxeItem, 0, 8);
                if (axeResult.found()) weaponResult = axeResult;
            }

            if (!swapped) {
                previousSlot  = mc.player.getInventory().getSelectedSlot();
                swapped = true;
            }

            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!acceptableWeapon(mc.player.getMainHandStack())) {
            stopAttacking();
            return;
        }

        attacking = true;
        if (rotation.get() == RotationMode.Always) Rotations.rotate(Rotations.getYaw(primary), Rotations.getPitch(primary, Target.Body));
        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (delayCheck()) {
            Vec3d clientPos = mc.player.getEntityPos();
            Vec3d remotePos = teleportTo(primary, teleportMode.get() == TeleportMode.Goto);
            attackTargets.clear();
            if (onlyOnLook.get()) {
                if (entityCheck(primary, remotePos, attackRange.get(), false)) attackTargets.add(primary);
            } else {
                TargetUtils.getList(attackTargets, entity -> entityCheck(entity, remotePos, attackRange.get(), false), priority.get(), maxTargets.get());
            }
            attackTargets.forEach(this::attack);
            if (teleportMode.get() == TeleportMode.Stand) teleportBack(remotePos, clientPos);
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    private void stopAttacking() {
        if (!attacking) return;

        attacking = false;
        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
        if (swapBack.get() && swapped) {
            InvUtils.swap(previousSlot, false);
            swapped = false;
        }
    }

    private boolean shouldShieldBreak() {
        for (Entity target : targets) {
            if (target instanceof PlayerEntity player) {
                if (player.isBlocking() && shieldMode.get() == ShieldMode.Break) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean entityCheck(Entity entity, double targetRange) {
        return entityCheck(entity, targetRange, false);
    }

    private void handleNoTarget() {
        if (!notifications.get()) {
            noTargetTicks = 0;
            noTargetNotified = false;
            return;
        }

        noTargetTicks++;
        int timeoutTicks = noTargetTimeout.get() * 20;
        if (noTargetTicks >= timeoutTicks && !noTargetNotified) {
            sendDesktopNotification("No valid TPAura target found for " + noTargetTimeout.get() + " seconds.");
            noTargetNotified = true;
        }
    }

    private double expandedSearchRange() {
        return Math.max(range.get(), expandedRange.get());
    }

    private void sendDesktopNotification(String message) {
        try {
            if (!SystemTray.isSupported()) return;

            if (desktopTrayIcon == null) {
                Image image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                desktopTrayIcon = new TrayIcon(image, "Meteor Client");
                desktopTrayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(desktopTrayIcon);
            }

            desktopTrayIcon.displayMessage("TPAura", message, TrayIcon.MessageType.WARNING);
        } catch (AWTException | HeadlessException | SecurityException ignored) {
            // Desktop notifications are best-effort and may be unavailable in headless environments.
        }
    }

    private void removeDesktopNotificationIcon() {
        if (desktopTrayIcon == null) return;

        try {
            if (SystemTray.isSupported()) SystemTray.getSystemTray().remove(desktopTrayIcon);
        } catch (HeadlessException | SecurityException ignored) {
            // Ignore unavailable desktop notification services.
        }
        desktopTrayIcon = null;
    }

    private boolean entityCheck(Entity entity, double targetRange, boolean excludeAirborne) {
        return entityCheck(entity, mc.player.getEntityPos(), targetRange, excludeAirborne);
    }

    private boolean entityCheck(Entity entity, Vec3d origin, double targetRange, boolean excludeAirborne) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDead()) || !entity.isAlive()) return false;
        if (excludeAirborne && !entity.isOnGround()) return false;

        Box hitbox = entity.getBoundingBox();
        double x = MathHelper.clamp(origin.x, hitbox.minX, hitbox.maxX);
        double y = MathHelper.clamp(origin.y, hitbox.minY, hitbox.maxY);
        double z = MathHelper.clamp(origin.z, hitbox.minZ, hitbox.maxZ);
        double distanceSquared = origin.squaredDistanceTo(x, y, z);
        if (distanceSquared > targetRange * targetRange) return false;

        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (!PlayerUtils.canSeeEntity(entity) && distanceSquared > wallsRange.get() * wallsRange.get()) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwner() != null
                && tameable.getOwner().equals(mc.player)
            ) return false;
        }
        if (ignorePassive.get()) {
            if (entity instanceof EndermanEntity enderman && !enderman.isAngry()) return false;
            if ((entity instanceof PiglinEntity || entity instanceof ZombifiedPiglinEntity || entity instanceof WolfEntity) && !((MobEntity) entity).isAttacking()) return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && player.isBlocking()) return false;
            if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            // Hostile mobs with baby variants (zombies, piglins, hoglins, zoglins)
            if (entity instanceof ZombieEntity || entity instanceof PiglinEntity
                || entity instanceof HoglinEntity || entity instanceof ZoglinEntity) {
                return switch (hostileMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
            // Passive mobs with baby variants (animals, villagers)
            if (entity instanceof PassiveEntity && (!(entity instanceof FrogEntity || entity instanceof ParrotEntity))) {
                return switch (passiveMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
        }
        return true;
    }

    private boolean delayCheck() {
        if (switchTimer > 0) {
            switchTimer--;
            return false;
        }

        float delay = (customDelay.get()) ? hitDelay.get() : 0.5f;
        if (tpsSync.get()) delay /= (TickRate.INSTANCE.getTickRate() / 20);

        if (customDelay.get()) {
            if (hitTimer < delay) {
                hitTimer++;
                return false;
            } else return true;
        } else return mc.player.getAttackCooldownProgress(delay) >= 1;
    }

    private void attack(Entity target) {
        if (rotation.get() == RotationMode.OnHit) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body));

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        hitTimer = 0;
    }

    private Vec3d teleportTo(Entity target, boolean updateClientPosition) {
        Vec3d start = mc.player.getEntityPos();
        Vec3d end = target.getEntityPos();
        double maxStep = Math.max(0.1, range.get() * 0.8);
        int steps = (int) Math.ceil(start.distanceTo(end) / maxStep);
        Vec3d packetPos = start;

        for (int i = 1; i <= steps; i++) {
            if (i == steps) {
                BlockPos targetPos = BlockPos.ofFloored(target.getX(), target.getY() - 1, target.getZ());
                Vec3d nextPos = ClickTP.getTeleportPosition(targetPos, Direction.UP);
                ClickTP.teleport(packetPos, nextPos, updateClientPosition);
                packetPos = nextPos;
                continue;
            }

            Vec3d point = start.lerp(end, i / (double) steps);
            BlockPos landing = findLandingBlock(point);
            if (landing == null) landing = BlockPos.ofFloored(point.x, point.y - 1, point.z);
            Vec3d nextPos = ClickTP.getTeleportPosition(landing, Direction.UP);
            ClickTP.teleport(packetPos, nextPos, updateClientPosition);
            packetPos = nextPos;
        }

        return packetPos;
    }

    private void teleportBack(Vec3d remotePos, Vec3d clientPos) {
        double maxStep = Math.max(0.1, range.get() * 0.8);
        int steps = (int) Math.ceil(remotePos.distanceTo(clientPos) / maxStep);
        Vec3d packetPos = remotePos;

        for (int i = 1; i <= steps; i++) {
            Vec3d nextPos = remotePos.lerp(clientPos, i / (double) steps);
            ClickTP.teleport(packetPos, nextPos, false);
            packetPos = nextPos;
        }
    }

    private BlockPos findLandingBlock(Vec3d point) {
        int x = MathHelper.floor(point.x);
        int y = MathHelper.floor(point.y) - 1;
        int z = MathHelper.floor(point.z);

        for (int offset = -2; offset <= 2; offset++) {
            BlockPos floor = new BlockPos(x, y + offset, z);
            if (mc.world.getBlockState(floor).getCollisionShape(mc.world, floor).isEmpty()) continue;
            if (!mc.world.getBlockState(floor.up()).getCollisionShape(mc.world, floor.up()).isEmpty()) continue;
            if (!mc.world.getBlockState(floor.up(2)).getCollisionShape(mc.world, floor.up(2)).isEmpty()) continue;
            return floor;
        }

        return null;
    }

    private boolean acceptableWeapon(ItemStack stack) {
        if (shouldShieldBreak()) return stack.getItem() instanceof AxeItem;
        if (attackWhenHolding.get() == AttackItems.All) return true;

        if (weapons.get().contains(Items.DIAMOND_SWORD) && stack.isIn(ItemTags.SWORDS)) return true;
        if (weapons.get().contains(Items.DIAMOND_AXE) && stack.isIn(ItemTags.AXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_PICKAXE) && stack.isIn(ItemTags.PICKAXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_SHOVEL) && stack.isIn(ItemTags.SHOVELS)) return true;
        if (weapons.get().contains(Items.DIAMOND_HOE) && stack.isIn(ItemTags.HOES)) return true;
        if (weapons.get().contains(Items.MACE) && stack.getItem() instanceof MaceItem) return true;
        if (weapons.get().contains(Items.DIAMOND_SPEAR) && stack.isIn(ItemTags.SPEARS)) return true;
        return weapons.get().contains(Items.TRIDENT) && stack.getItem() instanceof TridentItem;
    }

    public Entity getTarget() {
        if (!targets.isEmpty()) return targets.getFirst();
        return null;
    }

    @Override
    public String getInfoString() {
        if (!targets.isEmpty()) return EntityUtils.getName(getTarget());
        return null;
    }

    public enum AttackItems {
        Weapons,
        All
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    public enum ShieldMode {
        Ignore,
        Break,
        None
    }

    public enum TeleportMode {
        Goto,
        Stand
    }

    public enum NoTargetMode {
        Notify,
        ExpandRange
    }

    public enum EntityAge {
        Baby,
        Adult,
        Both
    }
}
