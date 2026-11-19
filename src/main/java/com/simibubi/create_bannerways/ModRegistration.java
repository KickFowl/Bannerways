package com.simibubi.create_bannerways;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import com.simibubi.create_bannerways.entity.FlagEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create_bannerways.gui.FlagStationMenu;
import com.simibubi.create_bannerways.network.ClientNetworkShim;
import com.simibubi.create_bannerways.network.NetworkHandler;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

@Mod(ModRegistration.MODID)
public class ModRegistration {
    public static final String MODID = "create_bannerways";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<Block, FlagStationBlock> FLAG_STATION = BLOCKS.register("flag_station",
        FlagStationBlock::new);

    public static final DeferredHolder<Item, BlockItem> FLAG_STATION_ITEM = ITEMS.register("flag_station",
        () -> new BlockItem(FLAG_STATION.get(), new Item.Properties()));

    public static final DeferredHolder<Item, FlagItem> FLAG_ITEM = ITEMS.register("flag_item",
        () -> new FlagItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlagStationBlockEntity>> FLAG_STATION_BE = BLOCK_ENTITIES.register("flag_station",
        () -> BlockEntityType.Builder.of(FlagStationBlockEntity::new, FLAG_STATION.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<FlagStationMenu>> FLAG_STATION_MENU = MENUS.register("flag_station",
        () -> IMenuTypeExtension.create((windowId, inv, data) -> new FlagStationMenu(windowId, inv, data.readBlockPos())));

    public record FlagData(int width, int height, String imageHash, Optional<byte[]> customPixels) {
        public static final Codec<FlagData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("width").forGetter(FlagData::width),
            Codec.INT.fieldOf("height").forGetter(FlagData::height),
            Codec.STRING.fieldOf("image_hash").forGetter(FlagData::imageHash),
            Codec.BYTE_BUFFER.optionalFieldOf("custom_pixels").xmap(
                opt -> opt.map(java.nio.ByteBuffer::array),
                arr -> arr.map(java.nio.ByteBuffer::wrap)
            ).forGetter(FlagData::customPixels)
        ).apply(instance, FlagData::new));
    }

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FlagData>> FLAG_DATA_COMPONENT = DATA_COMPONENTS.register("flag_data",
        () -> DataComponentType.<FlagData>builder().persistent(FlagData.CODEC).build());

    public ModRegistration(IEventBus modEventBus, ModContainer container) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        MENUS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);

        modEventBus.addListener(NetworkHandler::registerPayloads);
        NeoForge.EVENT_BUS.addListener(FlagEntity::onRightClickBlock);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientNetworkShim.init(modEventBus);
        }
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FlagEntity>> FLAG_ENTITY = ENTITY_TYPES.register("flag",
        () -> EntityType.Builder.<FlagEntity>of(FlagEntity::new, MobCategory.MISC)
            .sized(1.0F, 1.0F)
            .clientTrackingRange(20)
            .updateInterval(2)
            .build("flag"));
}
