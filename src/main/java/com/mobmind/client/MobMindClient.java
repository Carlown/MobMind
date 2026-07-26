package com.mobmind.client;

import com.mobmind.MobMindMod;
import com.mobmind.client.gui.ConfigScreen;
import com.mobmind.client.voice.SherpaEngine;
import com.mobmind.client.voice.SherpaLocal;
import com.mobmind.client.voice.TtsPlayer;
import com.mobmind.ai.OpenAiClient;
import com.mobmind.config.MobMindConfig;
import com.mobmind.net.MobPackets;
import com.mobmind.util.MobMindExecutor;
import com.mobmind.voice.LocalVoice;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class MobMindClient implements ClientModInitializer {
	private static KeyMapping micKey;
	private static KeyMapping configKey;
	private static boolean prevConfigDown;

	@Override
	public void onInitializeClient() {
		micKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mobmind.mic", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));
		configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mobmind.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
		// 强制重置为 K，避免旧 options.txt 缓存成 1
		configKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MobMindMod.MOD_ID, "status"), new MobMindHud());

		ClientPlayNetworking.registerGlobalReceiver(MobPackets.ReplyPayload.ID, (payload, context) -> {
			context.client().execute(() -> {
				ClientDialogue.recordReply(payload.mobName(), payload.text(), payload.mood(),
						payload.action(), payload.friendship());
				// 生物语音合成（仅对自己触发的对话播放）
				MobMindConfig cfg = MobMindConfig.get();
				Minecraft mc = context.client();
				if (cfg.ttsEnabled && mc.player != null
						&& mc.player.getGameProfile().name().equals(payload.speakerName())) {
					playVoice(cfg, payload);
				}
			});
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean ctrl = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
					|| InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
			// Ctrl+K 打开设置（直接检测按键，不依赖键位绑定缓存）
			boolean kDown = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_K);
			if (kDown && ctrl && !prevConfigDown && mc.player != null) {
				mc.setScreenAndShow(new ConfigScreen(null));
			}
			prevConfigDown = kDown;
			// Ctrl+V 按住持续录音，松开发送
			if (micKey.isDown() && ctrl) {
				VoiceManager.holdStart(mc);
			} else {
				VoiceManager.holdStop(mc);
			}
			// 单独 V：按一下开始，再按一下结束（切换模式）
			if (!ctrl) {
				while (micKey.consumeClick()) {
					VoiceManager.toggle(mc);
				}
			}
		});

		MobMindMod.LOGGER.info("[MobMind] 客户端已初始化 (V=切换录音 Ctrl+V=按住说话 Ctrl+K=设置)");

		// 进入世界后后台预热本地语音模型，首次对话零等待
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				SherpaLocal.prewarm(MobMindConfig.get()));
	}

	/** 判断当前游戏界面语言是否为英文 */
	private static boolean isEnglishUi() {
		try {
			String title = net.minecraft.locale.Language.getInstance().getOrDefault("gui.mobmind.config.title", "");
			return title.endsWith("Settings");
		} catch (Exception e) {
			return false;
		}
	}

	/** 生物语音播放：JNI 常驻优先，其次 exe，最后云端 API */
	private static void playVoice(MobMindConfig cfg, MobPackets.ReplyPayload payload) {
		String speechText = LocalVoice.cleanSpeechText(payload.text());
		if (speechText.isEmpty()) return;
		boolean english = isEnglishUi();
		int voiceId = LocalVoice.voiceIdForLocale(payload.voiceId(), cfg, english);
		MobMindMod.LOGGER.info("[MobMind] 朗读准备: {} voice={} english={}", speechText, voiceId, english);
		if (LocalVoice.isTtsReady(cfg) && SherpaLocal.isTtsAvailable(cfg)) {
			MobMindExecutor.runAsync(() -> {
				long t0 = System.currentTimeMillis();
				com.k2fsa.sherpa.onnx.GeneratedAudio audio = SherpaLocal.synth(cfg, voiceId, speechText);
				long synthMs = System.currentTimeMillis() - t0;
				MobMindMod.LOGGER.info("[MobMind] 本地 TTS 合成 {}ms", synthMs);
				if (audio != null && audio.getSamples().length > 0) {
					TtsPlayer.play(audio.getSamples(), audio.getSampleRate());
				}
			});
		} else if (LocalVoice.isTtsReady(cfg)) {
			MobMindExecutor.runAsync(() -> {
				try {
					long t0 = System.currentTimeMillis();
					java.nio.file.Path wav = SherpaEngine.synthesize(cfg, voiceId, speechText);
					MobMindMod.LOGGER.info("[MobMind] exe TTS 合成 {}ms", System.currentTimeMillis() - t0);
					if (wav != null) {
						TtsPlayer.play(wav);
					}
				} catch (Exception e) {
					MobMindMod.LOGGER.debug("[MobMind] 本地 TTS 失败: {}", e.getMessage());
				}
			});
		} else if (cfg.isApiReady()) {
			MobMindExecutor.runAsync(() -> {
				try {
					long t0 = System.currentTimeMillis();
					byte[] wav = OpenAiClient.speak(cfg, speechText);
					MobMindMod.LOGGER.info("[MobMind] API TTS 合成 {}ms", System.currentTimeMillis() - t0);
					TtsPlayer.play(wav);
				} catch (Exception e) {
					MobMindMod.LOGGER.debug("[MobMind] TTS 失败: {}", e.getMessage());
				}
			});
		}
	}
}
