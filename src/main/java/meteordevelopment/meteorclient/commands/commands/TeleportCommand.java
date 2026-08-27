/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.BlockPosArgumentType;
import meteordevelopment.meteorclient.systems.modules.movement.ClickTP;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.Direction;

public class TeleportCommand extends Command {
    public TeleportCommand() {
        super("teleport", "Teleports you to the specified coordinates.", "tp");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("pos", BlockPosArgumentType.blockPos()).executes(context -> {
            ClickTP.teleport(BlockPosArgumentType.getBlockPos(context, "pos"), Direction.UP);
            return SINGLE_SUCCESS;
        }));
    }
}
