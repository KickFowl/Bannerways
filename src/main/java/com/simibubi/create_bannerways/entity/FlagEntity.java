package com.simibubi.create_bannerways.entity;

import com.simibubi.create_bannerways.ModRegistration;
import com.simibubi.create_bannerways.PhysicsFlagController;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Optional;

public class FlagEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<BlockPos> DATA_START_POS =
            SynchedEntityData.defineId(FlagEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> DATA_END_POS =
            SynchedEntityData.defineId(FlagEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> DATA_HAS_ROPE =
            SynchedEntityData.defineId(FlagEntity.class, EntityDataSerializers.BOOLEAN);

    public BlockPos startPos = null; // null until set: makeBoundingBox() runs inside super()
    public BlockPos endPos = null;
    public boolean hasRope = false;

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
        this.entityData.set(DATA_START_POS, start);
        this.entityData.set(DATA_END_POS, end);

        if (data != null) {
            this.flagWidth = Math.max(2, data.width());
            this.flagHeight = Math.max(1, data.height());
            this.imageHash = data.imageHash() != null ? data.imageHash() : "";
            this.pixels = data.customPixels().orElse(new byte[0]);
        }

        Vec3 worldAnchor = Vec3.atCenterOf(start);
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(this.level(), start);
        if (sub != null) {
            worldAnchor = sub.logicalPose().transformPosition(worldAnchor);
        }
        this.setPos(worldAnchor.x, worldAnchor.y, worldAnchor.z);
    }

    public void swapAnchors() {
        if (startPos == null || endPos == null) return;
        BlockPos oldStart = this.startPos;
        this.startPos = this.endPos;
        this.endPos = oldStart;
        this.entityData.set(DATA_START_POS, this.startPos);
        this.entityData.set(DATA_END_POS, this.endPos);
    }

    public void setHasRope(boolean rope) {
        this.hasRope = rope;
        this.entityData.set(DATA_HAS_ROPE, rope);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_START_POS, BlockPos.ZERO);
        builder.define(DATA_END_POS, BlockPos.ZERO);
        builder.define(DATA_HAS_ROPE, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_START_POS.equals(key)) {
            BlockPos synced = this.entityData.get(DATA_START_POS);
            if (!synced.equals(BlockPos.ZERO)) this.startPos = synced;
        } else if (DATA_END_POS.equals(key)) {
            BlockPos synced = this.entityData.get(DATA_END_POS);
            if (!synced.equals(BlockPos.ZERO)) this.endPos = synced;
        } else if (DATA_HAS_ROPE.equals(key)) {
            this.hasRope = this.entityData.get(DATA_HAS_ROPE);
        }
        if (physicsController != null && startPos != null && endPos != null) {
            physicsController.setConnectors(startPos, endPos);
            physicsController.setHasRope(hasRope);
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        if (startPos == null || endPos == null) return super.makeBoundingBox();
        Vec3 c1 = Vec3.atCenterOf(startPos);
        Vec3 c2 = Vec3.atCenterOf(endPos);
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(this.level(), startPos);
        if (sub != null) {
            c1 = sub.logicalPose().transformPosition(c1);
            c2 = sub.logicalPose().transformPosition(c2);
        }
        double minX = Math.min(c1.x, c2.x) - 0.5;
        double minY = Math.min(c1.y, c2.y) - flagHeight - 0.5;
        double minZ = Math.min(c1.z, c2.z) - 0.5;
        double maxX = Math.max(c1.x, c2.x) + 0.5;
        double maxY = Math.max(c1.y, c2.y) + 0.5;
        double maxZ = Math.max(c1.z, c2.z) + 0.5;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256.0 * 256.0;
    }

    /** Return false so players can always break, wrench, or attach ropes to the rope_connector blocks. */
    @Override
    public boolean isPickable() {
        return false;
    }

    private static boolean isRopeConnector(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock().getDescriptionId().contains("rope_connector");
    }

    @Override
    public void tick() {
        super.tick();

        if (startPos == null || endPos == null) return;

        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(this.level(), startPos);
        if (sub != null) {
            Vec3 worldAnchor = sub.logicalPose().transformPosition(Vec3.atCenterOf(startPos));
            this.setPos(worldAnchor.x, worldAnchor.y, worldAnchor.z);
        }

        // Destroy the banner and drop its item if either rope_connector is broken
        if (!this.level().isClientSide && (this.tickCount % 5) == 0) {
            Level checkLevel = this.level();
            // An unloaded chunk reads as air; never mistake "not loaded yet" for "connector broken"
            boolean loaded = checkLevel.hasChunkAt(startPos) && checkLevel.hasChunkAt(endPos);
            if (loaded && (!isRopeConnector(checkLevel, startPos) || !isRopeConnector(checkLevel, endPos))) {
                ItemStack drop = new ItemStack(ModRegistration.FLAG_ITEM.get());
                drop.set(ModRegistration.FLAG_DATA_COMPONENT.get(), new ModRegistration.FlagData(
                        flagWidth, flagHeight, imageHash != null ? imageHash : "",
                        pixels != null && pixels.length > 0 ? Optional.of(pixels) : Optional.empty()
                ));
                this.spawnAtLocation(drop);
                this.discard();
                return;
            }
        }

        // Cloth is purely visual, so it only simulates on the client.
        if (this.level().isClientSide) {
            if (physicsController == null) {
                byte[] effectivePixels = pixels.length > 0 ? pixels : new byte[Math.max(flagWidth * 16, 16) * Math.max(flagHeight * 16, 16)];
                ModRegistration.FlagData data = new ModRegistration.FlagData(flagWidth, flagHeight, imageHash, Optional.of(effectivePixels));
                physicsController = new PhysicsFlagController(startPos, endPos, data);
                physicsController.setHasRope(hasRope);
            }
            physicsController.tick(this.level());
        }
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();

        if (!isRopeConnector(level, pos)) return;

        String itemPath = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        boolean isWrench = itemPath.contains("wrench");
        boolean isRope = itemPath.contains("rope");
        if (!isWrench && !isRope) return;

        List<FlagEntity> nearby = level.getEntitiesOfClass(FlagEntity.class, new AABB(pos).inflate(32.0),
                f -> pos.equals(f.startPos) || pos.equals(f.endPos));
        if (nearby.isEmpty()) return;

        if (isWrench) {
            if (!level.isClientSide) {
                for (FlagEntity flag : nearby) {
                    flag.swapAnchors();
                }
                player.displayClientMessage(Component.literal("§eRotated banner anchors."), true);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        } else if (isRope) {
            // Let the rope mod handle attaching the rope, and enable rope-pinning on the banner
            if (!level.isClientSide) {
                for (FlagEntity flag : nearby) {
                    flag.setHasRope(true);
                }
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("StartPos") && tag.contains("EndPos")) {
            this.startPos = BlockPos.of(tag.getLong("StartPos"));
            this.endPos = BlockPos.of(tag.getLong("EndPos"));
            this.entityData.set(DATA_START_POS, this.startPos);
            this.entityData.set(DATA_END_POS, this.endPos);
        }
        this.hasRope = tag.getBoolean("HasRope");
        this.entityData.set(DATA_HAS_ROPE, this.hasRope);
        this.flagWidth = Math.max(2, tag.getInt("Width"));
        this.flagHeight = Math.max(1, tag.getInt("Height"));
        this.imageHash = tag.getString("Hash");
        this.pixels = tag.getByteArray("Pixels");
        this.setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (startPos != null && endPos != null) {
            tag.putLong("StartPos", startPos.asLong());
            tag.putLong("EndPos", endPos.asLong());
        }
        tag.putBoolean("HasRope", hasRope);
        tag.putInt("Width", flagWidth);
        tag.putInt("Height", flagHeight);
        tag.putString("Hash", imageHash != null ? imageHash : "");
        tag.putByteArray("Pixels", pixels != null ? pixels : new byte[0]);
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        boolean hasAnchors = startPos != null && endPos != null;
        buffer.writeBoolean(hasAnchors);
        if (hasAnchors) {
            buffer.writeBlockPos(startPos);
            buffer.writeBlockPos(endPos);
        }
        buffer.writeBoolean(hasRope);
        buffer.writeVarInt(flagWidth);
        buffer.writeVarInt(flagHeight);
        buffer.writeUtf(imageHash != null ? imageHash : "");
        buffer.writeByteArray(pixels != null ? pixels : new byte[0]);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        if (buffer.readBoolean()) {
            this.startPos = buffer.readBlockPos();
            this.endPos = buffer.readBlockPos();
            this.entityData.set(DATA_START_POS, this.startPos);
            this.entityData.set(DATA_END_POS, this.endPos);
        }
        this.hasRope = buffer.readBoolean();
        this.entityData.set(DATA_HAS_ROPE, this.hasRope);
        this.flagWidth = Math.max(2, buffer.readVarInt());
        this.flagHeight = Math.max(1, buffer.readVarInt());
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

    public String getImageHash() { return this.imageHash; }
    public byte[] getPixels() { return this.pixels; }
    public int getFlagWidth() { return this.flagWidth; }
    public int getFlagHeight() { return this.flagHeight; }
}
