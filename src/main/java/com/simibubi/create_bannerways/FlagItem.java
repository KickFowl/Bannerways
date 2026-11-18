package com.simibubi.create_bannerways;

import com.simibubi.create_bannerways.entity.FlagEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

public class FlagItem extends Item {
    public FlagItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (player == null) return InteractionResult.FAIL;

        boolean isConnector = level.getBlockState(pos).getBlock().getDescriptionId().contains("rope_connector");

        if (isConnector) {
            CompoundTag nbt = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            
            if (!nbt.contains("FirstPos")) {
                nbt.putLong("FirstPos", pos.asLong());
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(nbt));
                
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal("§eAnchor 1 locked. Select the second connector."), true);
                }
                return InteractionResult.SUCCESS;
            } else {
                BlockPos firstPos = BlockPos.of(nbt.getLong("FirstPos"));
                
                // Direct distance calculation between the two outer top corners
                double distance = Math.sqrt(firstPos.distToCenterSqr(pos.getX(), pos.getY(), pos.getZ()));
                
                ModRegistration.FlagData data = stack.get(ModRegistration.FLAG_DATA_COMPONENT.get());
                int flagEdgeWidth = data != null ? data.width() : 2;

                if (distance > flagEdgeWidth) {
                    if (!level.isClientSide) {
                        player.displayClientMessage(Component.literal("§cConnectors are too far apart! Max distance: " + flagEdgeWidth + "m"), true);
                    }
                    return InteractionResult.FAIL;
                }

                if (!level.isClientSide) {
                    FlagEntity flagEntity = ModRegistration.FLAG_ENTITY.get().create(level);
                    if (flagEntity != null) {
                        flagEntity.setFlagData(firstPos, pos, data);
                        level.addFreshEntity(flagEntity);
                        
                        stack.shrink(1);
                    }
                }
                
                // Clear state for next use
                nbt.remove("FirstPos");
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(nbt));
                
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }
}