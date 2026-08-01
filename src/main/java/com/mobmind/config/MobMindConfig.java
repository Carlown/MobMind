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
	/** API 访问密钥（本地Ollama等不需要Key，留空即可） */
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
	public int maxTokens = 2048;
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
				MobMindMod.LOGGER.warn("[MobMind] Failed to read config file, using defaults", e);
			}
		}
		// 自动检测本地 Ollama 服务（不覆盖用户已手动配置的端点）
		INSTANCE.autoDetectOllama();
		save();
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(INSTANCE, w);
			}
		} catch (IOException e) {
			MobMindMod.LOGGER.warn("[MobMind] Failed to save config file", e);
		}
	}

	/** 端点是否配置完整（本地端点如Ollama不需要API Key） */
	public boolean isApiReady() {
		if (apiEndpoint == null || apiEndpoint.isBlank()) return false;
		String ep = normalizedEndpoint().toLowerCase();
		boolean isLocal = ep.contains("localhost") || ep.contains("127.0.0.1") || ep.contains("0.0.0.0");
		// 本地端点不需要API Key，远程端点需要
		return isLocal || (apiKey != null && !apiKey.isBlank());
	}

	/** 是否是本地端点（localhost/127.0.0.1），对应 Ollama 等本地部署 */
	public boolean isLocalEndpoint() {
		if (apiEndpoint == null) return false;
		String ep = normalizedEndpoint().toLowerCase();
		return ep.contains("localhost") || ep.contains("127.0.0.1") || ep.contains("0.0.0.0");
	}

	/** 自动检测并配置本地 Ollama（如果Ollama在默认端口运行且端点未修改过） */
	public void autoDetectOllama() {
		boolean isDefaultEndpoint = apiEndpoint.equals("https://api.openai.com/v1");
		boolean noApiKey = apiKey == null || apiKey.isBlank();

		try {
			// 检测localhost:11434是否可连
			java.net.Socket socket = new java.net.Socket();
			socket.connect(new java.net.InetSocketAddress("127.0.0.1", 11434), 500);
			socket.close();

			// Ollama在运行
			boolean isAlreadyLocal = isLocalEndpoint();
			if (isDefaultEndpoint && noApiKey) {
				// 首次检测到Ollama：自动切换端点
				apiEndpoint = "http://127.0.0.1:11434/v1";
				apiKey = "";
			}

			// 如果端点是本地Ollama，验证模型名是否有效
			if (isLocalEndpoint()) {
				String availableModels = listLocalOllamaModels();
				if (availableModels != null) {
					// 如果当前模型不在本地已安装列表中，自动切换到第一个可用模型
					String[] models = availableModels.split(", ");
					boolean modelExists = false;
					for (String m : models) {
						if (m.equals(chatModel)) { modelExists = true; break; }
					}
					if (!modelExists && models.length > 0) {
						String oldModel = chatModel;
						chatModel = models[0];
						MobMindMod.LOGGER.info("[MobMind] Model '{}' not found in local Ollama, auto-switched to: {} (available models: {})", oldModel, chatModel, availableModels);
					}
				}
			} else if (isDefaultEndpoint && noApiKey) {
				// 端点刚切换，还没设模型
				if (chatModel.equals("gpt-4o-mini")) {
					String detected = detectLocalOllamaModel();
					chatModel = detected != null ? detected : "qwen2.5:7b";
					MobMindMod.LOGGER.info("[MobMind] Detected local Ollama, auto-selected model: {}", chatModel);
				}
			}

			if (isDefaultEndpoint) {
				MobMindMod.LOGGER.info("[MobMind] Detected local Ollama service, auto-switched to local endpoint");
			}
		} catch (Exception ignored) {
			// Ollama没运行，保持默认配置
		}
	}

	/** 调用Ollama /api/tags获取本地第一个可用模型名 */
	private static String detectLocalOllamaModel() {
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
					.connectTimeout(java.time.Duration.ofSeconds(2))
					.build();
			java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create("http://127.0.0.1:11434/api/tags"))
					.timeout(java.time.Duration.ofSeconds(3))
					.GET()
					.build();
			java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
			if (resp.statusCode() == 200) {
				com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				if (json.has("models") && json.getAsJsonArray("models").size() > 0) {
					return json.getAsJsonArray("models").get(0).getAsJsonObject().get("name").getAsString();
				}
			}
		} catch (Exception ignored) {}
		return null;
	}

	/** 获取本地Ollama已安装模型列表（用于错误提示），返回逗号分隔的模型名 */
	public static String listLocalOllamaModels() {
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
					.connectTimeout(java.time.Duration.ofSeconds(2))
					.build();
			java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create("http://127.0.0.1:11434/api/tags"))
					.timeout(java.time.Duration.ofSeconds(3))
					.GET()
					.build();
			java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
			if (resp.statusCode() == 200) {
				com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				if (json.has("models") && json.getAsJsonArray("models").size() > 0) {
					StringBuilder sb = new StringBuilder();
					var models = json.getAsJsonArray("models");
					for (int i = 0; i < models.size(); i++) {
						if (i > 0) sb.append(", ");
						sb.append(models.get(i).getAsJsonObject().get("name").getAsString());
					}
					return sb.toString();
				}
			}
		} catch (Exception ignored) {}
		return null;
	}

	/**
	 * 归一化 API 端点：自动识别用户填的各种格式，统一转换为正确的 OpenAI 兼容端点根路径。
	 * 支持：
	 *  - http://localhost:11434/v1           → http://localhost:11434/v1  (标准写法)
	 *  - http://localhost:11434              → http://localhost:11434/v1  (Ollama默认，自动加/v1)
	 *  - http://localhost:11434/api/chat     → http://localhost:11434/v1  (Ollama原生路径，转为OpenAI兼容)
	 *  - http://localhost:11434/api/generate → http://localhost:11434/v1
	 *  - http://localhost:11434/v1/chat/completions → http://localhost:11434/v1  (去后缀)
	 *  - https://api.openai.com/v1           → 原样保持
	 */
	public String normalizedEndpoint() {
		String e = apiEndpoint.trim();
		while (e.endsWith("/")) e = e.substring(0, e.length() - 1);

		String lower = e.toLowerCase();

		// 如果是Ollama地址（localhost:11434或127.0.0.1:11434），确保用的是/v1路径
		boolean isOllamaHost = lower.contains("localhost:11434")
				|| lower.contains("127.0.0.1:11434")
				|| lower.contains("0.0.0.0:11434");

		if (isOllamaHost) {
			// 去掉所有已知的后缀路径
			e = removeSuffix(e, "/api/chat");
			e = removeSuffix(e, "/api/generate");
			e = removeSuffix(e, "/api/embeddings");
			e = removeSuffix(e, "/v1/chat/completions");
			e = removeSuffix(e, "/v1/audio/transcriptions");
			e = removeSuffix(e, "/v1/audio/speech");
			// 如果末尾没有/v1，就自动加上
			String lowerE = e.toLowerCase();
			if (!lowerE.endsWith("/v1")) {
				e = e + "/v1";
			}
		} else {
			// 非Ollama地址，只去掉末尾重复的/chat/completions等后缀即可
			e = removeSuffix(e, "/chat/completions");
			e = removeSuffix(e, "/audio/transcriptions");
			e = removeSuffix(e, "/audio/speech");
		}

		return e;
	}

	private static String removeSuffix(String s, String suffix) {
		String lower = s.toLowerCase();
		if (lower.endsWith(suffix.toLowerCase())) {
			return s.substring(0, s.length() - suffix.length());
		}
		return s;
	}
}
