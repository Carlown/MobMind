package com.mobmind.client;

import com.mobmind.client.voice.MicCapture;
import com.mobmind.client.voice.SherpaEngine;
import com.mobmind.client.voice.SherpaLocal;
import com.mobmind.ai.OpenAiClient;
import com.mobmind.config.MobMindConfig;
import com.mobmind.net.MobPackets;
import com.mobmind.util.MobMindExecutor;
import com.mobmind.voice.LocalVoice;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 语音输入流程：按键开关麦克风 → 录音 → STT → 发送给目标生物。
 */
public final class VoiceManager {
	public enum State { IDLE, RECORDING, TRANSCRIBING }

	private static final MicCapture capture = new MicCapture();
	private static State state = State.IDLE;

	static {
		capture.setPreferredMixerName(MobMindConfig.get().micMixerName);
	}
	private static String statusMessage = "";
	private static long statusExpire = 0;

	private VoiceManager() {}

	public static void updateMicDevice(String mixerName) {
		capture.setPreferredMixerName(mixerName);
	}

	public static State state() {
		return state;
	}

	public static String statusMessage() {
		if (System.currentTimeMillis() > statusExpire) return "";
		return statusMessage;
	}

	private static void status(String msg, long millis) {
		statusMessage = msg;
		statusExpire = System.currentTimeMillis() + millis;
	}

	private static boolean holdStarted = false;

	public static void toggle(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		switch (state) {
				case IDLE -> start(mc);
				case RECORDING -> stopAndSend(mc);
				case TRANSCRIBING -> status(Component.translatable("status.mobmind.transcribing").getString(), 2000);
			}
	}

	/** 按住说话：按住时开始录音，只在 IDLE 状态触发 */
	public static void holdStart(Minecraft mc) {
		if (holdStarted) return;
		holdStarted = true;
		if (state == State.IDLE) start(mc);
	}

	/** 松开结束录音 */
	public static void holdStop(Minecraft mc) {
		if (!holdStarted) return;
		holdStarted = false;
		if (state == State.RECORDING) stopAndSend(mc);
	}

	private static void start(Minecraft mc) {
		MobMindConfig cfg = MobMindConfig.get();
		if (!cfg.voiceEnabled) {
			chat(mc, "§7" + Component.translatable("status.mobmind.voice_disabled").getString());
			return;
		}
		if (!LocalVoice.isSttReady(cfg) && !cfg.isApiReady()) {
			chat(mc, "§7" + Component.translatable("status.mobmind.stt_not_configured").getString());
			return;
		}
		try {
			capture.start();
			state = State.RECORDING;
		} catch (Exception e) {
			chat(mc, "§c" + Component.translatable("status.mobmind.mic_error", e.getMessage()).getString());
		}
	}

	private static void stopAndSend(Minecraft mc) {
		byte[] wav = capture.stop();
		state = State.IDLE;
		if (wav == null) {
			status(Component.translatable("status.mobmind.no_sound").getString(), 2000);
			return;
		}
		Mob target = pickTarget(mc);
		if (target == null) {
			status(Component.translatable("status.mobmind.no_target").getString(), 2500);
			return;
		}
		state = State.TRANSCRIBING;
		status(Component.translatable("status.mobmind.transcribing").getString(), 15000);
		MobMindExecutor.runAsync(() -> {
			try {
				String text = transcribe(wav);
				mc.execute(() -> {
					state = State.IDLE;
					if (text.isBlank()) {
						status(Component.translatable("status.mobmind.not_clear").getString(), 2500);
						return;
					}
					ClientDialogue.recordPlayerSpeech(text);
					ClientPlayNetworking.send(new MobPackets.SpeakPayload(target.getId(), text));
				});
			} catch (Exception e) {
				mc.execute(() -> {
					state = State.IDLE;
					status(Component.translatable("status.mobmind.stt_error", e.getMessage()).getString(), 4000);
				});
			}
		});
	}

	/** 优先 JNI 常驻引擎，其次 exe 进程调用，最后云端 API */
	private static String transcribe(byte[] wav) throws Exception {
		MobMindConfig cfg = MobMindConfig.get();
		// 1) JNI 常驻（无进程/模型加载开销）
		if (LocalVoice.isSttReady(cfg) && SherpaLocal.isSttAvailable(cfg)) {
			String text = SherpaLocal.transcribe(cfg, wavToFloat(wav));
			if (text != null) return text;
		}
		// 2) exe 进程调用
		if (LocalVoice.isSttReady(cfg)) {
			Path tmp = SherpaEngine.tempDir().resolve("stt-" + java.util.UUID.randomUUID() + ".wav");
			try {
				java.nio.file.Files.write(tmp, wav);
				return SherpaEngine.transcribe(cfg, tmp);
			} finally {
				java.nio.file.Files.deleteIfExists(tmp);
			}
		}
		// 3) 云端 API
		return OpenAiClient.transcribe(cfg, wav);
	}

	/** WAV 字节（44字节头 + 16bit PCM）转浮点采样 */
	private static float[] wavToFloat(byte[] wav) {
		int offset = Math.min(44, wav.length);
		int n = (wav.length - offset) / 2;
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			int lo = wav[offset + i * 2] & 0xFF;
			int hi = wav[offset + i * 2 + 1];
			short s = (short) ((hi << 8) | lo);
			out[i] = s / 32768f;
		}
		return out;
	}

	/** 目标生物：优先准星指向的生物，否则取范围内最近的（仅设定包内的生物可对话） */
	public static Mob pickTarget(Minecraft mc) {
		if (mc.player == null || mc.level == null) return null;
		int radius = MobMindConfig.get().interactRadius;
		Entity crosshair = mc.crosshairPickEntity;
		if (crosshair instanceof Mob mob && mob.isAlive() && com.mobmind.persona.PersonaRegistry.supports(mob)) {
			if (isEnderDragon(mob)) {
				// 末影龙体型巨大且飞得高，准星指到即可在较大水平范围内对话
				double dx = mob.getX() - mc.player.getX();
				double dz = mob.getZ() - mc.player.getZ();
				if (dx * dx + dz * dz <= 128.0 * 128.0) return mob;
			} else if (mob.distanceTo(mc.player) <= radius + 6) {
				return mob;
			}
		}
		AABB box = mc.player.getBoundingBox().inflate(radius);
		List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && com.mobmind.persona.PersonaRegistry.supports(m));
		Mob nearest = mobs.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(mc.player))).orElse(null);
		if (nearest != null) return nearest;
		// 未找到近处目标时，额外搜索大范围末影龙
		List<Mob> dragons = mc.level.getEntitiesOfClass(Mob.class, mc.player.getBoundingBox().inflate(128.0),
				m -> m.isAlive() && com.mobmind.persona.PersonaRegistry.supports(m) && isEnderDragon(m));
		return dragons.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(mc.player))).orElse(null);
	}

	private static boolean isEnderDragon(Entity entity) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(entity.getType()).toString().equals("minecraft:ender_dragon");
	}

	public static void chat(Minecraft mc, String msg) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
	}
}
