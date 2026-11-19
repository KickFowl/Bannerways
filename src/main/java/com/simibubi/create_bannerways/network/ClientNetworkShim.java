package com.simibubi.create_bannerways.network;

import com.simibubi.create_bannerways.ModRegistration;
import com.simibubi.create_bannerways.client.FlagEntityRenderer;
import com.simibubi.create_bannerways.gui.FlagStationScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientNetworkShim {

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ClientNetworkShim::registerScreens);
        modEventBus.addListener(ClientNetworkShim::registerRenderers);
    }

    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModRegistration.FLAG_STATION_MENU.get(), FlagStationScreen::new);
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Changed to Entity Renderer targeting our new FlagEntity!
        event.registerEntityRenderer(ModRegistration.FLAG_ENTITY.get(), FlagEntityRenderer::new);
    }
}