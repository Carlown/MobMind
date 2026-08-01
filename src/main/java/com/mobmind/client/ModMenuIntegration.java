package com.mobmind.client;

import com.mobmind.client.gui.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：在 Mods 界面为 MobMind 添加设置按钮。
 * 玩家安装 Mod Menu 后，可以通过模组列表里的齿轮图标直接打开配置界面。
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new ConfigScreen(parent);
	}
}
