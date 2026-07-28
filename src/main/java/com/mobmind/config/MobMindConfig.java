package com.mobmind.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mobmind.MobMindMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置：AI API 调用参数。保存在 config/mobmind.json
 */
public class MobMindConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mobmind.json");
	private static MobMindConfig INSTANCE = new MobMindConfig();

	/** OpenAI 兼容 API 端点（不含 /chat/completions） */
	public String apiEndpoint = "https://api.openai.com/v1";
	/** API 访问密钥 */
	public String apiKey = "";
	/** 聊天模型名称 */
	public String chatModel = "gpt-4o-mini";
	/** 语音识别模型 */
	public String sttModel = "whisper-1";
	/** 语音合成模型 */
	public String ttsModel = "tts-1";
	/** 合成音色 */
	public String ttsVoice = "alloy";
	/** 采样温度 */
	public double temperature = 0.8;
	/** 最大输出 token */
	public int maxTokens = 300;
	/** 语音识别语言代码，如 zh / en / ja */
	public String sttLanguage = "zh";
	/** 是否启用语音输入 */
	public boolean voiceEnabled = true;
	/** 是否启用生物语音合成 */
	public boolean ttsEnabled = true;
	/** 生物是否主动打招呼 */
	public boolean greetingEnabled = true;
	/** 是否启用创造模式嘲讽（敌对生物嘲讽/激将创造模式玩家换成生存模式） */
	public boolean creativeTauntEnabled = true;
	/** 可交互生物的搜索半径（格） */
	public int interactRadius = 12;
	/** API 不可用时是否使用内置离线回复 */
	public boolean offlineFallback = true;
	/** 是否启用群体关系网络（流言/议论）：被攻击后会向同族传播并降低好感度 */
	public boolean gossipEnabled = true;
	/** 流言传播半径（格） */
	public int gossipRadius = 24;
	/** 同族相信流言并降低好感度的概率（0-100） */
	public int gossipChance = 60;
	/** 流言导致的好感度下降值 */
	public int gossipPenalty = 6;
	/** 流言触发同族开口议论的概率（0-100） */
	public int gossipReactChance = 35;

	// ---------- 本地语音引擎（sherpa-onnx） ----------

	/** sherpa-onnx 安装目录（含 bin/sherpa-onnx-offline.exe 等） */
	public String voiceEngineDir = "";
	/** SenseVoice-Small 模型目录（含 model.int8.onnx 与 tokens.txt） */
	public String sttModelDir = "";
	/** TTS 模型目录（kokoro 含 voices.bin，或 vits 含 *.onnx + espeak-ng-data） */
	public String ttsModelDir = "";
	/** TTS 音色数量，0 = 按模型类型自动推断 */
	public int ttsVoiceCount = 0;
	/** >=0 时强制所有生物使用同一个音色 ID */
	public int forceTtsVoiceId = -1;
	/** 音色池，如 "45-52" 或 "0,3,45-48"；空 = 自动（kokoro 默认中文音色 45-52） */
	public String ttsVoicePool = "";
	/** TTS 语速，越大越快 */
	public double ttsSpeed = 1.15;
	/** 本地引擎推理线程数 */
	public int voiceThreads = 2;
	/** 偏好的麦克风名称（空 = 系统默认） */
	public String micMixerName = "";
	/** Ctrl+Z 召唤时默认召唤最近的朋友数量（0=召唤全部） */
	public int recallCount = 2;
	/** Ctrl+Z 召唤时是否包含村民 */
	public boolean recallVillagers = true;

	public static MobMindConfig get() {
		return INSTANCE;
	}

	public static synchronized void load() {
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				MobMindConfig loaded = GSON.fromJson(r, MobMindConfig.class);
				if (loaded != null) INSTANCE = loaded;
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] 配置文件读取失败，使用默认配置", e);
			}
		}
		save();
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(INSTANCE, w);
			}
		} catch (IOException e) {
			MobMindMod.LOGGER.warn("[MobMind] 配置文件保存失败", e);
		}
	}

	/** 端点是否配置完整 */
	public boolean isApiReady() {
		return apiKey != null && !apiKey.isBlank() && apiEndpoint != null && !apiEndpoint.isBlank();
	}

	public String normalizedEndpoint() {
		String e = apiEndpoint.trim();
		while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
		return e;
	}
}
