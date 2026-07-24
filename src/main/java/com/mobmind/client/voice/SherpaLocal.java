package com.mobmind.client.voice;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.mobmind.MobMindMod;
import com.mobmind.config.MobMindConfig;
import com.mobmind.voice.LocalVoice;

import java.nio.file.Path;

/**
 * JNI 常驻本地语音引擎：模型只在首次使用时加载一次，之后每次识别/合成都是纯推理，
 * 没有进程启动与模型加载开销，延迟最低。
 * 加载失败时返回 null，由上层回退到 exe 进程调用或云端 API。
 */
public final class SherpaLocal {
	private static volatile OfflineRecognizer recognizer;
	private static volatile OfflineTts tts;
	private static volatile boolean sttInitFailed;
	private static volatile boolean ttsInitFailed;
	private static volatile String loadedSttKey;
	private static volatile String loadedTtsKey;

	private SherpaLocal() {}

	// ---------- STT ----------

	public static boolean isSttAvailable(MobMindConfig cfg) {
		if (!LocalVoice.isSttReady(cfg)) return false;
		return recognizer(cfg) != null;
	}

	/** 识别 16kHz 单声道浮点采样，返回文本 */
	public static String transcribe(MobMindConfig cfg, float[] samples) {
		OfflineRecognizer r = recognizer(cfg);
		if (r == null) return null;
		synchronized (r) {
			OfflineStream stream = r.createStream();
			try {
				stream.acceptWaveform(samples, 16000);
				r.decode(stream);
				String text = r.getResult(stream).getText();
				return LocalVoice.sanitizeTranscript(text);
			} catch (Throwable t) {
				MobMindMod.LOGGER.warn("[MobMind] JNI 识别失败: {}", t.getMessage());
				return null;
			}
		}
	}

	private static OfflineRecognizer recognizer(MobMindConfig cfg) {
		String key = cfg.sttModelDir + "|" + cfg.voiceThreads;
		if (recognizer != null && key.equals(loadedSttKey)) return recognizer;
		if (sttInitFailed && key.equals(loadedSttKey)) return null;
		synchronized (SherpaLocal.class) {
			if (recognizer != null && key.equals(loadedSttKey)) return recognizer;
			try {
				Path model = LocalVoice.senseVoiceModel(cfg);
				Path tokens = LocalVoice.senseVoiceTokens(cfg);
				if (model == null || tokens == null) return null;

				OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
						.setModel(model.toString())
						.setLanguage(cfg.sttLanguage == null || cfg.sttLanguage.isBlank() ? "auto" : cfg.sttLanguage)
						.setInverseTextNormalization(true)
						.build();
				OfflineModelConfig modelCfg = OfflineModelConfig.builder()
						.setSenseVoice(senseVoice)
						.setTokens(tokens.toString())
						.setNumThreads(Math.max(1, cfg.voiceThreads))
						.setDebug(false)
						.build();
				FeatureConfig feats = FeatureConfig.builder()
						.setSampleRate(16000)
						.setFeatureDim(80)
						.build();
				OfflineRecognizerConfig rc = OfflineRecognizerConfig.builder()
						.setOfflineModelConfig(modelCfg)
						.setFeatureConfig(feats)
						.setDecodingMethod("greedy_search")
						.build();

				long t0 = System.currentTimeMillis();
				OfflineRecognizer r = new OfflineRecognizer(rc);
				recognizer = r;
				loadedSttKey = key;
				sttInitFailed = false;
				MobMindMod.LOGGER.info("[MobMind] SenseVoice 模型已常驻加载 ({}ms)", System.currentTimeMillis() - t0);
				return r;
			} catch (Throwable t) {
				sttInitFailed = true;
				loadedSttKey = key;
				MobMindMod.LOGGER.warn("[MobMind] JNI STT 初始化失败: {}", t.getMessage());
				return null;
			}
		}
	}

	// ---------- TTS ----------

	public static boolean isTtsAvailable(MobMindConfig cfg) {
		if (!LocalVoice.isTtsReady(cfg)) return false;
		return tts(cfg) != null;
	}

	/** 合成语音，返回 GeneratedAudio（采样 + 采样率），失败返回 null */
	public static GeneratedAudio synth(MobMindConfig cfg, int voiceId, String text) {
		OfflineTts engine = tts(cfg);
		if (engine == null) return null;
		int count = Math.max(1, LocalVoice.voiceCount(cfg));
		int sid = Math.floorMod(voiceId, count);
		synchronized (engine) {
			try {
				return engine.generate(text, sid, (float) cfg.ttsSpeed);
			} catch (Throwable t) {
				MobMindMod.LOGGER.warn("[MobMind] JNI 合成失败: {}", t.getMessage());
				return null;
			}
		}
	}

	private static OfflineTts tts(MobMindConfig cfg) {
		String key = cfg.ttsModelDir + "|" + cfg.voiceThreads;
		if (tts != null && key.equals(loadedTtsKey)) return tts;
		if (ttsInitFailed && key.equals(loadedTtsKey)) return null;
		synchronized (SherpaLocal.class) {
			if (tts != null && key.equals(loadedTtsKey)) return tts;
			try {
				String type = LocalVoice.ttsType(cfg);
				if (type == null) return null;
				Path dir = Path.of(cfg.ttsModelDir);

				OfflineTtsModelConfig.Builder modelBuilder = OfflineTtsModelConfig.builder()
						.setNumThreads(Math.max(1, cfg.voiceThreads))
						.setDebug(false);
				if ("kokoro".equals(type)) {
					OfflineTtsKokoroModelConfig.Builder kokoro = OfflineTtsKokoroModelConfig.builder()
							.setModel(dir.resolve("model.onnx").toString())
							.setVoices(dir.resolve("voices.bin").toString())
							.setTokens(dir.resolve("tokens.txt").toString())
							.setDataDir(dir.resolve("espeak-ng-data").toString());
					String lexicons = LocalVoice.kokoroLexicons(cfg);
					if (lexicons != null) kokoro.setLexicon(lexicons);
					modelBuilder.setKokoro(kokoro.build());
				} else {
					Path onnx = LocalVoice.firstOnnx(dir);
					if (onnx == null) return null;
					OfflineTtsVitsModelConfig.Builder vits = OfflineTtsVitsModelConfig.builder()
							.setModel(onnx.toString())
							.setTokens(dir.resolve("tokens.txt").toString());
					Path dataDir = dir.resolve("espeak-ng-data");
					if (java.nio.file.Files.isDirectory(dataDir)) vits.setDataDir(dataDir.toString());
					Path dictDir = dir.resolve("dict");
					if (java.nio.file.Files.isDirectory(dictDir)) vits.setDictDir(dictDir.toString());
					modelBuilder.setVits(vits.build());
				}

				OfflineTtsConfig tc = OfflineTtsConfig.builder()
						.setModel(modelBuilder.build())
						.build();

				long t0 = System.currentTimeMillis();
				OfflineTts engine = new OfflineTts(tc);
				tts = engine;
				loadedTtsKey = key;
				ttsInitFailed = false;
				MobMindMod.LOGGER.info("[MobMind] TTS 模型已常驻加载 ({}ms, {} 音色)",
						System.currentTimeMillis() - t0, engine.getNumSpeakers());
				return engine;
			} catch (Throwable t) {
				ttsInitFailed = true;
				loadedTtsKey = key;
				MobMindMod.LOGGER.warn("[MobMind] JNI TTS 初始化失败: {}", t.getMessage());
				return null;
			}
		}
	}

	/** 进入世界后后台预热，让首次对话零等待 */
	public static void prewarm(MobMindConfig cfg) {
		com.mobmind.util.MobMindExecutor.runAsync(() -> {
			if (LocalVoice.isSttReady(cfg)) recognizer(cfg);
			if (LocalVoice.isTtsReady(cfg)) tts(cfg);
		});
	}
}
