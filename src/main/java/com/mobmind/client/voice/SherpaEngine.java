package com.mobmind.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
import com.mobmind.config.MobMindConfig;
import com.mobmind.voice.LocalVoice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * sherpa-onnx 本地语音引擎的进程调用封装。
 * STT：sherpa-onnx-offline.exe（SenseVoice-Small）
 * TTS：sherpa-onnx-offline-tts.exe（kokoro / vits，--sid 选择音色）
 */
public final class SherpaEngine {
	private static final long TIMEOUT_SECONDS = 120;

	private SherpaEngine() {}

	public static Path tempDir() throws IOException {
		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mobmind");
		Files.createDirectories(dir);
		return dir;
	}

	// ---------- STT ----------

	/** 调用 SenseVoice 识别 WAV 文件，返回识别文本 */
	public static String transcribe(MobMindConfig cfg, Path wavFile) throws IOException, InterruptedException {
		Path exe = LocalVoice.sttExe(cfg);
		Path model = LocalVoice.senseVoiceModel(cfg);
		Path tokens = LocalVoice.senseVoiceTokens(cfg);
		if (exe == null || model == null || tokens == null) {
			throw new IOException("sherpa-onnx STT 未配置完整（引擎目录/SenseVoice 模型）");
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());
		cmd.add("--sense-voice-model=" + model);
		cmd.add("--tokens=" + tokens);
		cmd.add("--num-threads=" + Math.max(1, cfg.voiceThreads));
		cmd.add(wavFile.toString());

		ProcessResult r = run(cmd);
		if (r.exitCode != 0 || r.stdout.isBlank()) {
			throw new IOException("语音识别失败(exit=" + r.exitCode + "): " + tail(r.stderr));
		}
		// stdout 每行一个 JSON：{"text":"...", ...}
		String lastJson = null;
		for (String line : r.stdout.split("\\R")) {
			String t = line.trim();
			if (t.startsWith("{")) lastJson = t;
		}
		if (lastJson == null) return "";
		JsonObject json = JsonParser.parseString(lastJson).getAsJsonObject();
		String text = json.has("text") ? json.get("text").getAsString() : "";
		return LocalVoice.sanitizeTranscript(text);
	}

	// ---------- TTS ----------

	/** 调用本地 TTS 合成语音，返回生成的 WAV 文件路径；失败返回 null */
	public static Path synthesize(MobMindConfig cfg, int voiceId, String text) throws IOException, InterruptedException {
		Path exe = LocalVoice.ttsExe(cfg);
		String type = LocalVoice.ttsType(cfg);
		if (exe == null || type == null) {
			throw new IOException("sherpa-onnx TTS 未配置完整（引擎目录/TTS 模型）");
		}
		int count = Math.max(1, LocalVoice.voiceCount(cfg));
		int sid = Math.floorMod(voiceId, count);

		Path out = tempDir().resolve("tts-" + UUID.randomUUID() + ".wav");
		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());

		if ("kokoro".equals(type)) {
			addIf(cmd, "--kokoro-model=", LocalVoice.ttsFile(cfg, "model.onnx"));
			addIf(cmd, "--kokoro-voices=", LocalVoice.ttsFile(cfg, "voices.bin"));
			addIf(cmd, "--kokoro-tokens=", LocalVoice.ttsFile(cfg, "tokens.txt"));
			addIfDir(cmd, "--kokoro-data-dir=", LocalVoice.ttsDir(cfg, "espeak-ng-data"));
			// 多语言 kokoro 必须传词典（sherpa-onnx >= v1.12.15 不再需要 dict-dir）
			String lexicons = LocalVoice.kokoroLexicons(cfg);
			if (lexicons != null) {
				cmd.add("--kokoro-lexicon=" + lexicons);
			}
		} else { // vits
			addIf(cmd, "--vits-model=", LocalVoice.firstOnnx(Path.of(cfg.ttsModelDir)));
			addIf(cmd, "--vits-tokens=", LocalVoice.ttsFile(cfg, "tokens.txt"));
			addIfDir(cmd, "--vits-data-dir=", LocalVoice.ttsDir(cfg, "espeak-ng-data"));
			addIfDir(cmd, "--vits-dict-dir=", LocalVoice.ttsDir(cfg, "dict"));
		}

		cmd.add("--sid=" + sid);
		cmd.add("--speed=" + cfg.ttsSpeed);
		cmd.add("--num-threads=" + Math.max(1, cfg.voiceThreads));
		cmd.add("--output-filename=" + out);
		cmd.add(text);

		ProcessResult r = run(cmd);
		if (r.exitCode != 0 || !Files.isRegularFile(out)) {
			MobMindMod.LOGGER.warn("[MobMind] TTS 合成失败(exit={}): {}", r.exitCode, tail(r.stderr));
			return null;
		}
		return out;
	}

	// ---------- 进程工具 ----------

	private record ProcessResult(int exitCode, String stdout, String stderr) {}

	private static ProcessResult run(List<String> cmd) throws IOException, InterruptedException {
		MobMindMod.LOGGER.debug("[MobMind] 调用: {}", String.join(" ", cmd));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		Process process = pb.start();
		// 分别读取 stdout/stderr，防止缓冲区写满死锁
		ReadResult out = new ReadResult(process.getInputStream());
		ReadResult err = new ReadResult(process.getErrorStream());
		boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IOException("引擎调用超时（>" + TIMEOUT_SECONDS + "s）");
		}
		return new ProcessResult(process.exitValue(), out.get(), err.get());
	}

	private static class ReadResult {
		private final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		private final Thread thread;

		ReadResult(java.io.InputStream in) {
			thread = new Thread(() -> {
				try {
					in.transferTo(bos);
				} catch (IOException ignored) {}
			}, "mobmind-pipe");
			thread.setDaemon(true);
			thread.start();
		}

		String get() throws InterruptedException {
			thread.join(5000);
			return bos.toString(StandardCharsets.UTF_8);
		}
	}

	private static void addIf(List<String> cmd, String flag, Path file) {
		if (file != null) cmd.add(flag + file);
	}

	private static void addIfDir(List<String> cmd, String flag, Path dir) {
		if (dir != null) cmd.add(flag + dir);
	}

	private static String tail(String s) {
		if (s == null || s.isBlank()) return "";
		String[] lines = s.split("\\R");
		return lines[lines.length - 1].substring(0, Math.min(200, lines[lines.length - 1].length()));
	}
}
