package com.simibubi.create_bannerways.entity;

import com.simibubi.create_bannerways.ModRegistration;
import com.simibubi.create_bannerways.PhysicsFlagController;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import java.util.Optional;

public class FlagEntity extends Entity implements IEntityWithComplexSpawn {
    public BlockPos startPos = null; // null until set: makeBoundingBox() runs inside super()
    public BlockPos endPos = null;

    private int flagWidth = 2;
    private int flagHeight = 1;
    private String imageHash = "";
    private byte[] pixels = new byte[0];

    public PhysicsFlagController physicsController;

    public FlagEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public void setFlagData(BlockPos start, BlockPos end, ModRegistration.FlagData data) {
        this.startPos = start;
        this.endPos = end;
        this.flagWidth = data.width();
        this.flagHeight = data.height();
        this.imageHash = data.imageHash();
        this.pixels = data.customPixels().orElse(new byte[0]);
        this.setPos(start.getX() + 0.5, start.getY() + 0.5, start.getZ() + 0.5);
    }

    /**
     * setPos() ALWAYS rebuilds the hitbox through this method, which used to wipe the custom box
     * back to 1x1. Overriding it is the only way for the box to stick.
     */
    @Override
    protected AABB makeBoundingBox() {
        if (startPos == null || endPos == null) return super.makeBoundingBox();
        double minX = Math.min(startPos.getX(), endPos.getX()) - 1.0;
        double minY = Math.min(startPos.getY(), endPos.getY()) - flagHeight - 1.0;
        double minZ = Math.min(startPos.getZ(), endPos.getZ()) - 1.0;
        double maxX = Math.max(startPos.getX(), endPos.getX()) + 2.0;
        double maxY = Math.max(startPos.getY(), endPos.getY()) + 1.0;
        double maxZ = Math.max(startPos.getZ(), endPos.getZ()) + 2.0;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();

        // Cloth is purely visual, so it only simulates on the client.
        if (this.level().isClientSide && startPos != null && endPos != null) {
            if (physicsController == null && pixels.length > 0) {
                ModRegistration.FlagData data = new ModRegistration.FlagData(flagWidth, flagHeight, imageHash, Optional.of(pixels));
                physicsController = new PhysicsFlagController(startPos, endPos, data);
            }
            if (physicsController != null) {
                physicsController.tick(this.level());
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.startPos = BlockPos.of(tag.getLong("StartPos"));
        this.endPos = BlockPos.of(tag.getLong("EndPos"));
        this.flagWidth = tag.getInt("Width");
        this.flagHeight = tag.getInt("Height");
        this.imageHash = tag.getString("Hash");
        this.pixels = tag.getByteArray("Pixels");
        this.setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("StartPos", startPos.asLong());
        tag.putLong("EndPos", endPos.asLong());
        tag.putInt("Width", flagWidth);
        tag.putInt("Height", flagHeight);
        tag.putString("Hash", imageHash);
        tag.putByteArray("Pixels", pixels);
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(startPos);
        buffer.writeBlockPos(endPos);
        buffer.writeVarInt(flagWidth);
        buffer.writeVarInt(flagHeight);
        buffer.writeUtf(imageHash);
        buffer.writeByteArray(pixels);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        this.startPos = buffer.readBlockPos();
        this.endPos = buffer.readBlockPos();
        this.flagWidth = buffer.readVarInt();
        this.flagHeight = buffer.readVarInt();
        this.imageHash = buffer.readUtf();
        this.pixels = buffer.readByteArray();
        this.setBoundingBox(makeBoundingBox());
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (PhysicsFlagController.DEBUG && this.level().isClientSide) {
            com.mojang.logging.LogUtils.getLogger().info("[create_bannerways] Client flag entity removed: {} at {}", reason, this.position());
        }
        super.remove(reason);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    public String getImageHash() { return this.imageHash; }
    public byte[] getPixels() { return this.pixels; }
    public int getFlagWidth() { return this.flagWidth; }
    public int getFlagHeight() { return this.flagHeight; }
}