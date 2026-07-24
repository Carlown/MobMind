package com.mobmind.client;

/**
 * 客户端对话状态：记录最近一次玩家发言与生物回复，供 HUD 展示。
 */
public final class ClientDialogue {
	public record Exchange(String mobName, String playerText, String replyText,
						   String mood, String action, int friendship, long timestamp) {}

	private static volatile Exchange last;
	private static volatile String pendingPlayerText = "";

	private ClientDialogue() {}

	public static void recordPlayerSpeech(String text) {
		pendingPlayerText = text;
	}

	public static void recordReply(String mobName, String replyText, String mood,
								   String action, int friendship) {
		last = new Exchange(mobName, pendingPlayerText, replyText, mood, action, friendship,
				System.currentTimeMillis());
		pendingPlayerText = "";
	}

	public static Exchange last() {
		return last;
	}

	/** HUD 展示时长（毫秒） */
	public static boolean visible(Exchange e) {
		return e != null && System.currentTimeMillis() - e.timestamp() < 12000;
	}
}
