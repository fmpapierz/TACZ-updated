package com.tacz.guns.neoforge.client;

import com.tacz.guns.compat.cloth.MenuIntegration;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * The mod list's config button, backed by the Cloth Config screen. Only registered when Cloth Config is installed.
 */
final class ClothConfigScreenFactory implements IConfigScreenFactory {
    @Override
    public Screen createScreen(ModContainer container, Screen modListScreen) {
        return MenuIntegration.getConfigBuilder().setParentScreen(modListScreen).build();
    }
}
