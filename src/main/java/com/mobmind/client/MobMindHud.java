package com.mobmind.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * HUD 覆盖层：右上角显示麦克风状态，快捷栏上方显示最近的生物对话。
 */
public class MobMindHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();

		// 麦克风状态（右上角）
		VoiceManager.State state = VoiceManager.state();
		if (state != VoiceManager.State.IDLE || !VoiceManager.statusMessage().isEmpty()) {
			String text;
			int color;
			switch (state) {
				case RECORDING -> { text = Component.translatable("hud.mobmind.recording").getString(); color = 0xFFFF5555; }
				case TRANSCRIBING -> { text = Component.translatable("hud.mobmind.transcribing").getString(); color = 0xFFFFFF55; }
				default -> { text = VoiceManager.statusMessage(); color = 0xFFAAAAAA; }
			}
			if (!text.isEmpty()) {
				int tw = mc.font.width(text);
				graphics.fill(width - tw - 14, 6, width - 6, 18, 0x66000000);
				graphics.text(mc.font, Component.literal(text), width - tw - 10, 9, color);
			}
		}

		// 最近对话（快捷栏上方）
		ClientDialogue.Exchange ex = ClientDialogue.last();
		if (ClientDialogue.visible(ex)) {
			int y = height - 72;
			if (!ex.playerText().isEmpty()) {
				String you = Component.translatable("hud.mobmind.you").getString();
				graphics.text(mc.font, Component.literal("§9" + you + ": §f" + ex.playerText()), 8, y, 0xFFFFFFFF);
				y += 11;
			}
			String mood = ex.mood().isEmpty() ? "" : " §8[" + ex.mood() + "]";
			graphics.text(mc.font, Component.literal("§e" + ex.mobName() + "§r: " + ex.replyText() + mood), 8, y, 0xFFFFFFFF);
			// 好感度为隐藏机制，不展示给玩家
			if (!"none".equals(ex.action())) {
				String action = Component.translatable("hud.mobmind.action").getString();
				graphics.text(mc.font, Component.literal("§7" + action + ": " + ex.action()), 8, y + 11, 0xFFFFFFFF);
			}
		}
	}
}
