/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.WaypointsModule;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screen.DeathScreen;

import java.util.ArrayList;
import java.util.List;

public class AutoRespawn extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> commands = sgGeneral.add(new StringListSetting.Builder()
        .name("commands")
        .description("Commands to execute after respawning. Include the server or Meteor command prefix.")
        .build()
    );

    private final Setting<Integer> firstCommandDelay = sgGeneral.add(new IntSetting.Builder()
        .name("first-command-delay")
        .description("The delay in ticks between respawning and executing the first command.")
        .defaultValue(20)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> commandDelay = sgGeneral.add(new IntSetting.Builder()
        .name("command-delay")
        .description("The delay in ticks between executing commands.")
        .defaultValue(20)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final List<String> queuedCommands = new ArrayList<>();
    private boolean waitingForRespawn;
    private int commandIndex;
    private int commandTimer;

    public AutoRespawn() {
        super(Categories.Player, "auto-respawn", "Automatically respawns after death.");
    }

    @Override
    public void onDeactivate() {
        resetCommands();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onOpenScreenEvent(OpenScreenEvent event) {
        if (!(event.screen instanceof DeathScreen)) return;

        queueCommands();
        Modules.get().get(WaypointsModule.class).addDeath(mc.player.getEntityPos());
        mc.player.requestRespawn();
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (queuedCommands.isEmpty() || mc.player == null) return;

        if (waitingForRespawn) {
            if (!mc.player.isAlive()) return;

            waitingForRespawn = false;
            commandTimer = firstCommandDelay.get();

            if (commandTimer > 0) return;
        }

        if (commandTimer > 0 && --commandTimer > 0) return;

        while (commandIndex < queuedCommands.size()) {
            executeCommand(queuedCommands.get(commandIndex++));

            if (!isActive() || mc.player == null || mc.getNetworkHandler() == null) {
                resetCommands();
                return;
            }

            if (commandIndex >= queuedCommands.size()) {
                resetCommands();
                return;
            }

            commandTimer = commandDelay.get();
            if (commandTimer > 0) return;
        }
    }

    private void queueCommands() {
        resetCommands();

        for (String command : commands.get()) {
            String trimmedCommand = command.trim();
            if (!trimmedCommand.isEmpty()) queuedCommands.add(trimmedCommand);
        }

        waitingForRespawn = !queuedCommands.isEmpty();
    }

    private void executeCommand(String command) {
        ChatUtils.sendPlayerMsg(command);
    }

    private void resetCommands() {
        queuedCommands.clear();
        waitingForRespawn = false;
        commandIndex = 0;
        commandTimer = 0;
    }
}
