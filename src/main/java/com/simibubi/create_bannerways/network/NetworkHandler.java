<<<<<<< HEAD
package com.simibubi.create_bannerways.network;

import com.simibubi.create_bannerways.FlagStationBlockEntity;
import com.simibubi.create_bannerways.ModRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

public class NetworkHandler {

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                NetworkPackets.FabricateFlagPacket.TYPE,
                NetworkPackets.FabricateFlagPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BlockPos pos = payload.pos();
                        if (player.level().getBlockEntity(pos) instanceof FlagStationBlockEntity station) {

                            int finalWidth = Math.clamp(payload.width(), 2, 10);
                            int finalHeight = Math.clamp(payload.height(), 1, 20);

                            int woolCost = finalWidth * finalHeight; // 1 per block

                            int[] dyeCounts = new int[16];
                            for (byte b : payload.pixels()) {
                                int idx = b & 0xFF;
                                if (idx < 16) dyeCounts[idx]++;
                            }

                            // White (index 0) is covered by the base wool; colored dyes cost 1 per 256 pixels (1 block)
                            int[] dyeCosts = new int[16];
                            for (int i = 1; i < 16; i++) {
                                dyeCosts[i] = (int) Math.ceil(dyeCounts[i] / 256.0);
                            }

                            // Phase 1: Verify player has enough wool + all required colored dyes
                            List<String> missing = getMissingResources(player, woolCost, dyeCosts);
                            if (!missing.isEmpty()) {
                                player.sendSystemMessage(Component.literal("§cMissing resources: " + String.join(", ", missing)));
                                return;
                            }

                            // Phase 2: Consume Items securely across main inventory + offhand
                            consumeItems(player, woolCost, dyeCosts);

                            station.flagWidth = finalWidth;
                            station.flagHeight = finalHeight;
                            station.currentHash = payload.imageHash();
                            station.pixelArray = payload.pixels();
                            station.setChanged();

                            ItemStack output = new ItemStack(ModRegistration.FLAG_ITEM.get());
                            output.set(ModRegistration.FLAG_DATA_COMPONENT.get(), new ModRegistration.FlagData(
                                    finalWidth, finalHeight, payload.imageHash(), java.util.Optional.of(payload.pixels())
                            ));

                            if (!player.getInventory().add(output)) {
                                player.drop(output, false);
                            }
                        }
                    }
                })
        );
    }

    private static List<String> getMissingResources(ServerPlayer player, int requiredWool, int[] dyeCosts) {
        int foundWool = 0;
        int[] foundDyes = new int[16];

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;

            if (stack.is(ItemTags.WOOL)) {
                foundWool += stack.getCount();
            }

            for (int i = 1; i < 16; i++) {
                if (dyeCosts[i] > 0 && stack.is(NetworkPackets.DYE_ITEMS[i])) {
                    foundDyes[i] += stack.getCount();
                }
            }
        }

        List<String> missing = new ArrayList<>();
        if (foundWool < requiredWool) {
            missing.add((requiredWool - foundWool) + "x Wool");
        }
        for (int i = 1; i < 16; i++) {
            if (foundDyes[i] < dyeCosts[i]) {
                String dyeName = new ItemStack(NetworkPackets.DYE_ITEMS[i]).getHoverName().getString();
                missing.add((dyeCosts[i] - foundDyes[i]) + "x " + dyeName);
            }
        }
        return missing;
    }

    private static void consumeItems(ServerPlayer player, int requiredWool, int[] dyeCosts) {
        int remainingWool = requiredWool;
        int[] remainingDyes = dyeCosts.clone();

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;

            if (remainingWool > 0 && stack.is(ItemTags.WOOL)) {
                int toTake = Math.min(stack.getCount(), remainingWool);
                stack.shrink(toTake);
                remainingWool -= toTake;
            }

            for (int i = 1; i < 16; i++) {
                if (remainingDyes[i] > 0 && stack.is(NetworkPackets.DYE_ITEMS[i])) {
                    int toTake = Math.min(stack.getCount(), remainingDyes[i]);
                    stack.shrink(toTake);
                    remainingDyes[i] -= toTake;
                }
            }
        }
    }
}
=======
package com.simibubi.create_bannerways.network;

import com.simibubi.create_bannerways.FlagStationBlockEntity;
import com.simibubi.create_bannerways.ModRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                NetworkPackets.FabricateFlagPacket.TYPE,
                NetworkPackets.FabricateFlagPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BlockPos pos = payload.pos();
                        if (player.level().getBlockEntity(pos) instanceof FlagStationBlockEntity station) {
                            
                            int finalWidth = Math.clamp(payload.width(), 2, 10);
                            int finalHeight = Math.clamp(payload.height(), 1, 20);

                            // Calculate exact costs based on the payload arrays
                            int woolCost = finalWidth * finalHeight; // 1 per block
                            
                            int[] dyeCounts = new int[16];
                            for (byte b : payload.pixels()) dyeCounts[b]++;
                            
                            int[] dyeCosts = new int[16];
                            for (int i = 0; i < 16; i++) {
                                dyeCosts[i] = (int) Math.ceil(dyeCounts[i] / 8.0); // 1 dye per 8 pixels
                            }

                            // Phase 1: Verify player has enough inventory resources
                            if (!hasRequiredItems(player, woolCost, dyeCosts)) {
                                player.sendSystemMessage(Component.literal("§cNot enough resources! You need " + woolCost + " Wool and matching Dyes."));
                                return;
                            }

                            // Phase 2: Consume Items securely
                            consumeItems(player, woolCost, dyeCosts);

                            // Build output properties
                            station.flagWidth = finalWidth;
                            station.flagHeight = finalHeight;
                            station.currentHash = payload.imageHash();
                            station.pixelArray = payload.pixels();
                            station.setChanged();

                            ItemStack output = new ItemStack(ModRegistration.FLAG_ITEM.get());
                            output.set(ModRegistration.FLAG_DATA_COMPONENT.get(), new ModRegistration.FlagData(
                                    finalWidth, finalHeight, payload.imageHash(), java.util.Optional.of(payload.pixels())
                            ));

                            // Give the generated physical banner straight back into their inventory
                            if (!player.getInventory().add(output)) {
                                player.drop(output, false);
                            }
                        }
                    }
                })
        );
    }

    private static boolean hasRequiredItems(ServerPlayer player, int requiredWool, int[] dyeCosts) {
        int foundWool = 0;
        int[] foundDyes = new int[16];

        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            
            if (stack.is(ItemTags.WOOL)) {
                foundWool += stack.getCount();
            }
            
            for (int i = 0; i < 16; i++) {
                if (dyeCosts[i] > 0 && stack.is(NetworkPackets.DYE_ITEMS[i])) {
                    foundDyes[i] += stack.getCount();
                }
            }
        }

        if (foundWool < requiredWool) return false;
        for (int i = 0; i < 16; i++) {
            if (foundDyes[i] < dyeCosts[i]) return false;
        }
        return true;
    }

    private static void consumeItems(ServerPlayer player, int requiredWool, int[] dyeCosts) {
        int remainingWool = requiredWool;
        int[] remainingDyes = dyeCosts.clone();

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;

            // Consume Wool
            if (remainingWool > 0 && stack.is(ItemTags.WOOL)) {
                int toTake = Math.min(stack.getCount(), remainingWool);
                stack.shrink(toTake);
                remainingWool -= toTake;
            }

            // Consume specific Dyes
            for (int i = 0; i < 16; i++) {
                if (remainingDyes[i] > 0 && stack.is(NetworkPackets.DYE_ITEMS[i])) {
                    int toTake = Math.min(stack.getCount(), remainingDyes[i]);
                    stack.shrink(toTake);
                    remainingDyes[i] -= toTake;
                }
            }
        }
    }
}
>>>>>>> de6f1fb397100ea2d8eec953bcf83cbf139d181c
