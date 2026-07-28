package com.mobmind.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * HUD 覆盖层：右上角显示麦克风状态，快捷栏上方显示最近的生物对话。
 */
public class MobMindHud implements HudElement {
	private static final Map<String, String> ZH_TO_EN_MOOD = new HashMap<>();
	static {
		ZH_TO_EN_MOOD.put("开心", "happy");
		ZH_TO_EN_MOOD.put("高兴", "happy");
		ZH_TO_EN_MOOD.put("快乐", "happy");
		ZH_TO_EN_MOOD.put("生气", "angry");
		ZH_TO_EN_MOOD.put("愤怒", "angry");
		ZH_TO_EN_MOOD.put("恼火", "angry");
		ZH_TO_EN_MOOD.put("害怕", "scared");
		ZH_TO_EN_MOOD.put("恐惧", "scared");
		ZH_TO_EN_MOOD.put("惊恐", "scared");
		ZH_TO_EN_MOOD.put("好奇", "curious");
		ZH_TO_EN_MOOD.put("疑惑", "curious");
		ZH_TO_EN_MOOD.put("困惑", "curious");
		ZH_TO_EN_MOOD.put("平静", "calm");
		ZH_TO_EN_MOOD.put("镇定", "calm");
		ZH_TO_EN_MOOD.put("冷静", "calm");
		ZH_TO_EN_MOOD.put("悲伤", "sad");
		ZH_TO_EN_MOOD.put("难过", "sad");
		ZH_TO_EN_MOOD.put("伤心", "sad");
		ZH_TO_EN_MOOD.put("惊讶", "surprised");
		ZH_TO_EN_MOOD.put("吃惊", "surprised");
		ZH_TO_EN_MOOD.put("震惊", "shocked");
		ZH_TO_EN_MOOD.put("兴奋", "excited");
		ZH_TO_EN_MOOD.put("激动", "excited");
		ZH_TO_EN_MOOD.put("讨厌", "disgusted");
		ZH_TO_EN_MOOD.put("厌恶", "disgusted");
		ZH_TO_EN_MOOD.put("骄傲", "proud");
		ZH_TO_EN_MOOD.put("自豪", "proud");
		ZH_TO_EN_MOOD.put("尴尬", "embarrassed");
		ZH_TO_EN_MOOD.put("害羞", "shy");
		ZH_TO_EN_MOOD.put("羞涩", "shy");
		ZH_TO_EN_MOOD.put("无聊", "bored");
		ZH_TO_EN_MOOD.put("厌烦", "bored");
		ZH_TO_EN_MOOD.put("疲惫", "tired");
		ZH_TO_EN_MOOD.put("累", "tired");
		ZH_TO_EN_MOOD.put("饥饿", "hungry");
		ZH_TO_EN_MOOD.put("饿", "hungry");
		ZH_TO_EN_MOOD.put("满意", "satisfied");
		ZH_TO_EN_MOOD.put("满足", "satisfied");
		ZH_TO_EN_MOOD.put("感激", "grateful");
		ZH_TO_EN_MOOD.put("感谢", "grateful");
		ZH_TO_EN_MOOD.put("犹豫", "hesitant");
		ZH_TO_EN_MOOD.put("迟疑", "hesitant");
		ZH_TO_EN_MOOD.put("警惕", "alert");
		ZH_TO_EN_MOOD.put("警觉", "alert");
		ZH_TO_EN_MOOD.put("放松", "relaxed");
		ZH_TO_EN_MOOD.put("轻松", "relaxed");
		ZH_TO_EN_MOOD.put("忧郁", "melancholy");
		ZH_TO_EN_MOOD.put("忧伤", "melancholy");
		ZH_TO_EN_MOOD.put("阴险", "sinister");
		ZH_TO_EN_MOOD.put("狡猾", "sly");
		ZH_TO_EN_MOOD.put("狡诈", "sly");
		ZH_TO_EN_MOOD.put("得意", "smug");
		ZH_TO_EN_MOOD.put("傲慢", "arrogant");
		ZH_TO_EN_MOOD.put("高傲", "arrogant");
		ZH_TO_EN_MOOD.put("亲切", "kind");
		ZH_TO_EN_MOOD.put("友善", "friendly");
		ZH_TO_EN_MOOD.put("友好", "friendly");
		ZH_TO_EN_MOOD.put("冷淡", "cold");
		ZH_TO_EN_MOOD.put("冷漠", "indifferent");
		ZH_TO_EN_MOOD.put("热情", "enthusiastic");
		ZH_TO_EN_MOOD.put("热烈", "enthusiastic");
		ZH_TO_EN_MOOD.put("无奈", "helpless");
		ZH_TO_EN_MOOD.put("无语", "speechless");
		ZH_TO_EN_MOOD.put("羡慕", "envious");
		ZH_TO_EN_MOOD.put("嫉妒", "jealous");
	}

	private static String translateMood(String mood) {
		if (mood == null || mood.isEmpty()) return mood;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getLanguageManager() == null) return mood;
		String lang = mc.getLanguageManager().getSelected();
		if (lang == null || !lang.startsWith("en")) return mood;
		String lower = mood.toLowerCase();
		String translated = ZH_TO_EN_MOOD.get(mood);
		if (translated != null) return translated;
		for (Map.Entry<String, String> e : ZH_TO_EN_MOOD.entrySet()) {
			if (mood.contains(e.getKey())) {
				return e.getValue();
			}
		}
		return mood;
	}

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
			String moodStr = translateMood(ex.mood());
			String mood = moodStr.isEmpty() ? "" : " §8[" + moodStr + "]";
			graphics.text(mc.font, Component.literal("§e" + ex.mobName() + "§r: " + ex.replyText() + mood), 8, y, 0xFFFFFFFF);
			// 好感度为隐藏机制，不展示给玩家
			if (!"none".equals(ex.action())) {
				String action = Component.translatable("hud.mobmind.action").getString();
				graphics.text(mc.font, Component.literal("§7" + action + ": " + ex.action()), 8, y + 11, 0xFFFFFFFF);
			}
		}
	}
}
