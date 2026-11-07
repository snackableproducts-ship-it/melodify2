package net.melodify;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.melodify.gui.MelodifyScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Fabric client entrypoint: registers the keybind and the HUD layer, starts the background services. */
public final class MelodifyClient implements ClientModInitializer {
    public static final String MOD_ID = "melodify";
    public static KeyMapping OPEN_GUI;

    @Override
    public void onInitializeClient() {
        MelodifyCore.init(FabricLoader.getInstance().getConfigDir());

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_GUI = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.melodify.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "lyrics_hud"),
                (graphics, deltaTracker) -> {
                    if (MelodifyCore.hudEditing()) return; // the position editor draws its own preview
                    MelodifyCore.hud().render(graphics, MelodifyCore.hudData());
                });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_GUI.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new MelodifyScreen());
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register((Minecraft mc) -> MelodifyCore.shutdown());
    }
}
