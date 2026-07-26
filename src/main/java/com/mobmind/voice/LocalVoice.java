package com.mobmind.voice;

import com.mobmind.config.MobMindConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * 本地语音引擎（sherpa-onnx）的路径解析与模型探测。
 * 服务端与客户端共用：不涉及任何客户端类。
 */
public final class LocalVoice {
	private static final String STT_EXE = "sherpa-onnx-offline.exe";
	private static final String TTS_EXE = "sherpa-onnx-offline-tts.exe";

	private static volatile Path cachedSttExe;
	private static volatile Path cachedTtsExe;
	private static volatile String cachedEngineDir;

	private LocalVoice() {}

	// ---------- 可执行文件定位 ----------

	private static Path findExe(String dir, String name) {
		if (dir == null || dir.isBlank()) return null;
		Path root = Path.of(dir);
		Path direct = root.resolve(name);
		if (Files.isRegularFile(direct)) return direct;
		Path inBin = root.resolve("bin").resolve(name);
		if (Files.isRegularFile(inBin)) return inBin;
		// 在子目录中搜索（最多两层），适配解压后多套一层目录的情况
		try (Stream<Path> s = Files.walk(root, 3)) {
			return s.filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(name))
					.findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static synchronized void resolveExes(MobMindConfig cfg) {
		if (!cfg.voiceEngineDir.equals(cachedEngineDir)) {
			cachedEngineDir = cfg.voiceEngineDir;
			cachedSttExe = findExe(cfg.voiceEngineDir, STT_EXE);
			cachedTtsExe = findExe(cfg.voiceEngineDir, TTS_EXE);
		}
	}

	public static Path sttExe(MobMindConfig cfg) {
		resolveExes(cfg);
		return cachedSttExe;
	}

	public static Path ttsExe(MobMindConfig cfg) {
		resolveExes(cfg);
		return cachedTtsExe;
	}

	// ---------- STT 模型 ----------

	/** SenseVoice 模型文件：优先 int8 量化版 */
	public static Path senseVoiceModel(MobMindConfig cfg) {
		if (cfg.sttModelDir.isBlank()) return null;
		Path dir = Path.of(cfg.sttModelDir);
		Path int8 = dir.resolve("model.int8.onnx");
		if (Files.isRegularFile(int8)) return int8;
		Path full = dir.resolve("model.onnx");
		if (Files.isRegularFile(full)) return full;
		return null;
	}

	public static Path senseVoiceTokens(MobMindConfig cfg) {
		if (cfg.sttModelDir.isBlank()) return null;
		Path p = Path.of(cfg.sttModelDir).resolve("tokens.txt");
		return Files.isRegularFile(p) ? p : null;
	}

	public static boolean isSttReady(MobMindConfig cfg) {
		return sttExe(cfg) != null && senseVoiceModel(cfg) != null && senseVoiceTokens(cfg) != null;
	}

	// ---------- TTS 模型 ----------

	/** 模型类型："kokoro" / "vits" / null（未识别） */
	public static String ttsType(MobMindConfig cfg) {
		if (cfg.ttsModelDir.isBlank()) return null;
		Path dir = Path.of(cfg.ttsModelDir);
		if (!Files.isDirectory(dir)) return null;
		if (Files.isRegularFile(dir.resolve("voices.bin"))) return "kokoro";
		if (firstOnnx(dir) != null && Files.isRegularFile(dir.resolve("tokens.txt"))) return "vits";
		return null;
	}

	public static Path firstOnnx(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".onnx"))
					.findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	public static Path ttsFile(MobMindConfig cfg, String name) {
		Path p = Path.of(cfg.ttsModelDir).resolve(name);
		return Files.isRegularFile(p) ? p : null;
	}

	public static Path ttsDir(MobMindConfig cfg, String name) {
		Path p = Path.of(cfg.ttsModelDir).resolve(name);
		return Files.isDirectory(p) ? p : null;
	}

	/** kokoro 词典列表：lexicon.txt 与 lexicon-*.txt，逗号连接（多语言 kokoro 必需） */
	public static String kokoroLexicons(MobMindConfig cfg) {
		if (cfg.ttsModelDir.isBlank()) return null;
		Path dir = Path.of(cfg.ttsModelDir);
		try (Stream<Path> s = Files.list(dir)) {
			String joined = s.filter(p -> {
						String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return n.equals("lexicon.txt") || (n.startsWith("lexicon-") && n.endsWith(".txt"));
					})
					.sorted()
					.map(Path::toString)
					.reduce((a, b) -> a + "," + b)
					.orElse(null);
			return joined;
		} catch (IOException e) {
			return null;
		}
	}

	public static boolean isTtsReady(MobMindConfig cfg) {
		return ttsExe(cfg) != null && ttsType(cfg) != null;
	}

	// ---------- 音色分配 ----------

	/** 可用音色数量：配置覆盖 > 按模型类型推断 */
	public static int voiceCount(MobMindConfig cfg) {
		if (cfg.ttsVoiceCount > 0) return cfg.ttsVoiceCount;
		String type = ttsType(cfg);
		if ("kokoro".equals(type)) return 53; // kokoro-multi-lang-v1_0
		if ("vits".equals(type)) {
			String d = cfg.ttsModelDir.toLowerCase(Locale.ROOT);
			if (d.contains("aishell3")) return 174;
			if (d.contains("vctk")) return 109;
			if (d.contains("theresa")) return 800;
			return 1;
		}
		return 1;
	}

	/** 解析音色池配置，如 "45-52,11" */
	private static int[] parsePool(String pool) {
		if (pool == null || pool.isBlank()) return null;
		try {
			java.util.List<Integer> ids = new java.util.ArrayList<>();
			for (String part : pool.split(",")) {
				part = part.trim();
				if (part.contains("-")) {
					String[] ab = part.split("-");
					int a = Integer.parseInt(ab[0].trim()), b = Integer.parseInt(ab[1].trim());
					for (int i = a; i <= b; i++) ids.add(i);
				} else if (!part.isEmpty()) {
					ids.add(Integer.parseInt(part));
				}
			}
			return ids.isEmpty() ? null : ids.stream().mapToInt(Integer::intValue).toArray();
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 可随机分配的音色 ID 列表 */
	public static int[] voicePool(MobMindConfig cfg) {
		int[] custom = parsePool(cfg.ttsVoicePool);
		if (custom != null) return custom;
		// kokoro 多语言模型默认只用中文音色（45-52 = zf/zm 系列），避免英文腔读中文
		if ("kokoro".equals(ttsType(cfg)) && cfg.ttsVoiceCount <= 0) {
			return new int[]{45, 46, 47, 48, 49, 50, 51, 52};
		}
		int count = Math.max(1, voiceCount(cfg));
		int[] all = new int[count];
		for (int i = 0; i < count; i++) all[i] = i;
		return all;
	}

	/** 为一只新生物分配音色 ID */
	public static int assignVoiceId(MobMindConfig cfg) {
		if (cfg.forceTtsVoiceId >= 0) return cfg.forceTtsVoiceId;
		int[] pool = voicePool(cfg);
		return pool[new Random().nextInt(pool.length)];
	}

	/**
	 * 根据当前游戏语言调整实际播放音色。
	 * 英文模式下，若使用默认中文池（45-52）且用户未强制/自定义，则映射到英文音色（0-7）。
	 */
	public static int voiceIdForLocale(int voiceId, MobMindConfig cfg, boolean english) {
		if (!english) return voiceId;
		if (cfg.forceTtsVoiceId >= 0) return voiceId; // 用户强制，不覆盖
		if (cfg.ttsVoicePool != null && !cfg.ttsVoicePool.isBlank()) return voiceId; // 自定义池，不覆盖
		if (voiceId >= 45 && voiceId <= 52) {
			return (voiceId - 45) % 44; // kokoro 英文音色 0-43
		}
		return voiceId;
	}

	/** 清洗 SenseVoice 输出中的富文本标签，如 <|zh|><|HAPPY|> */
	public static String sanitizeTranscript(String text) {
		if (text == null) return "";
		return text.replaceAll("<\\|[^|]*\\|>", "").replaceAll("\\s+", " ").trim();
	}

	/**
	 * TTS 朗读前的文本清洗：去掉情感标签、动作描述等不该读出来的内容。
	 * 如 "[开心]"、"（生气地说）"、"*叹气*"、残留的 JSON 片段。
	 */
	public static String cleanSpeechText(String text) {
		if (text == null) return "";
		String t = text;
		// 残留 JSON 片段防御：只取 say 字段值
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\"say\"\\s*:\\s*\"([^\"]{1,300})\"").matcher(t);
		if (m.find()) t = m.group(1);
		t = t.replaceAll("<\\|[^|]*\\|>", " ");
		// 去掉 *动作* 描述
		t = t.replaceAll("\\*[^*]{1,30}\\*", " ");
		// 去掉括号内的情绪/语气标注，如 [开心]（叹气）（温柔地说）
		t = t.replaceAll("[\\[\\(（【][^\\]\\)）】]{0,12}(?:说|道|笑|叹|气|开心|难过|生气|害怕|惊讶|平静|好奇|缓和|兴奋|温柔|冷冷|哭|笑)[^\\]\\)）】]{0,8}[\\]\\)）】]", " ");
		// 省略号替换为停顿，避免被音素化成怪音
		t = t.replaceAll("…{1,}|\\.{3,}", "，");
		// 去掉 TTS 无法处理的符号
		t = t.replaceAll("[~～#@^_|\\\\/<>【】\\[\\]{}「」『』\"'`]", " ");
		t = t.replaceAll("\\s+", " ").trim();
		// 清洗后为空则回退到去掉标签的原文
		if (t.isEmpty()) {
			t = text.replaceAll("<\\|[^|]*\\|>", " ").replaceAll("[\\[\\]（）()【】*]", " ").replaceAll("\\s+", " ").trim();
		}
		// 没有可朗读的文字内容（中文/字母/数字）则放弃朗读
		if (!t.matches(".*[\\u4e00-\\u9fffA-Za-z0-9].*")) return "";
		// 过长文本截断，控制合成耗时
		if (t.length() > 200) t = t.substring(0, 200);
		return t;
	}
}
