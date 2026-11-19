<<<<<<< HEAD
package com.simibubi.create_bannerways.gui;

import com.simibubi.create_bannerways.ModRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class FlagStationMenu extends AbstractContainerMenu {
    private final BlockPos pos;

    public FlagStationMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(ModRegistration.FLAG_STATION_MENU.get(), windowId);
        this.pos = pos;
        // No UI slots loaded - pure direct-to-inventory interactions!
    }

    public BlockPos getBlockPos() { return this.pos; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // No shift-clicking needed for a slotless GUI
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) != null; 
    }
=======
package com.simibubi.create_bannerways.gui;

import com.simibubi.create_bannerways.ModRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class FlagStationMenu extends AbstractContainerMenu {
    private final BlockPos pos;

    public FlagStationMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(ModRegistration.FLAG_STATION_MENU.get(), windowId);
        this.pos = pos;
        // No UI slots loaded - pure direct-to-inventory interactions!
    }

    public BlockPos getBlockPos() { return this.pos; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // No shift-clicking needed for a slotless GUI
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) != null; 
    }
>>>>>>> de6f1fb397100ea2d8eec953bcf83cbf139d181c
}