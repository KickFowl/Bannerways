package com.simibubi.create_bannerways;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

public class FlagStationBlockEntity extends BlockEntity {
    // Create mechanics rely heavily on inventories
    public final ItemStackHandler inventory = new ItemStackHandler(3) { // 0: Fabric Input, 1: Dye/Tints, 2: Output
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    public int flagWidth = 1;
    public int flagHeight = 1;
    public String currentHash = "";
    public byte[] pixelArray = new byte[0];

    public FlagStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistration.FLAG_STATION_BE.get(), pos, state);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        this.flagWidth = tag.getInt("Width");
        this.flagHeight = tag.getInt("Height");
        this.currentHash = tag.getString("Hash");
        this.pixelArray = tag.getByteArray("Pixels");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", this.inventory.serializeNBT(registries));
        tag.putInt("Width", flagWidth);
        tag.putInt("Height", flagHeight);
        tag.putString("Hash", currentHash);
        tag.putByteArray("Pixels", pixelArray);
    }
}