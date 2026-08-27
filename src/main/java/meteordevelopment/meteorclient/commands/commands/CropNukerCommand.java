/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Nuker;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandSource;

public class CropNukerCommand extends Command {
    public CropNukerCommand() {
        super("cropnuker", "Sets the Nuker crop whitelist.", "crop-nuker");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        addCrop(builder, "wheat", Blocks.WHEAT);
        addCrop(builder, "potato", Blocks.POTATOES);
        addCrop(builder, "carrot", Blocks.CARROTS);
        addCrop(builder, "sweetberry", Blocks.SWEET_BERRY_BUSH);
        addCrop(builder, "cane", Blocks.SUGAR_CANE);
        addCrop(builder, "sugarcane", Blocks.SUGAR_CANE);
    }

    private void addCrop(LiteralArgumentBuilder<CommandSource> builder, String name, Block crop) {
        builder.then(literal(name).executes(context -> {
            Nuker nuker = Modules.get().get(Nuker.class);
            if (!nuker.setCropWhitelist(crop)) {
                error("Enable Nuker's crop-nuker setting first.");
                return 0;
            }

            info("Crop whitelist set to " + name + ".");
            return SINGLE_SUCCESS;
        }));
    }
}
