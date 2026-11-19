package com.simibubi.create_bannerways.network;

import com.simibubi.create_bannerways.gui.FlagStationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;

public class PacketHandler {
    public static void openScreen(Player player, BlockPos pos) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                (windowId, inv, p) -> new FlagStationMenu(windowId, inv, pos),
                Component.translatable("block.create_bannerways.flag_station")
            ), buf -> buf.writeBlockPos(pos));
        }
    }
}