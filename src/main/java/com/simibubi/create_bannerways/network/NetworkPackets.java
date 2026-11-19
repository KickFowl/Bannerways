package com.simibubi.create_bannerways.network;

import com.simibubi.create_bannerways.ModRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class NetworkPackets {
    public static final ResourceLocation MOD_CHANNEL = ResourceLocation.fromNamespaceAndPath(ModRegistration.MODID, "main_channel");

    /**
     * Serverbound custom payloads are capped at about 32 KB, and a flag sends 256 bytes per block of area.
     * 125 blocks of area (32,000 bytes) is the most that safely fits in one packet.
     */
    public static final int MAX_FLAG_AREA = 125;
    public static final int MAX_PIXEL_BYTES = MAX_FLAG_AREA * 256;

    // 1. Open Screen Packet (Server -> Client)
    public record OpenScreenPacket(BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenScreenPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModRegistration.MODID, "open_screen"));
        
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreenPacket> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenScreenPacket::pos,
                OpenScreenPacket::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 2. Fabricate Flag Packet (Client -> Server)
    public record FabricateFlagPacket(BlockPos pos, int width, int height, String imageHash, byte[] pixels) implements CustomPacketPayload {
        public static final Type<FabricateFlagPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModRegistration.MODID, "fabricate_flag"));
        
        public static final StreamCodec<RegistryFriendlyByteBuf, FabricateFlagPacket> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, FabricateFlagPacket::pos,
                ByteBufCodecs.VAR_INT, FabricateFlagPacket::width,
                ByteBufCodecs.VAR_INT, FabricateFlagPacket::height,
                ByteBufCodecs.stringUtf8(128), FabricateFlagPacket::imageHash,
                ByteBufCodecs.byteArray(MAX_PIXEL_BYTES), FabricateFlagPacket::pixels,
                FabricateFlagPacket::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final net.minecraft.world.item.Item[] DYE_ITEMS = {
            net.minecraft.world.item.Items.WHITE_DYE, net.minecraft.world.item.Items.ORANGE_DYE, net.minecraft.world.item.Items.MAGENTA_DYE, net.minecraft.world.item.Items.LIGHT_BLUE_DYE,
            net.minecraft.world.item.Items.YELLOW_DYE, net.minecraft.world.item.Items.LIME_DYE, net.minecraft.world.item.Items.PINK_DYE, net.minecraft.world.item.Items.GRAY_DYE,
            net.minecraft.world.item.Items.LIGHT_GRAY_DYE, net.minecraft.world.item.Items.CYAN_DYE, net.minecraft.world.item.Items.PURPLE_DYE, net.minecraft.world.item.Items.BLUE_DYE,
            net.minecraft.world.item.Items.BROWN_DYE, net.minecraft.world.item.Items.GREEN_DYE, net.minecraft.world.item.Items.RED_DYE, net.minecraft.world.item.Items.BLACK_DYE
    };
}