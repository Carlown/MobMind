package com.mobmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.config.MobMindConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI 兼容 API 客户端：聊天补全、语音识别（STT）、语音合成（TTS）。
 * 所有方法均为阻塞式，必须在异步线程中调用。
 */
public final class OpenAiClient {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private OpenAiClient() {}

	public record ChatMessage(String role, String content) {}

	/** 聊天补全，返回助手文本 */
	public static String chat(MobMindConfig cfg, List<ChatMessage> messages) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.chatModel);
		body.addProperty("temperature", cfg.temperature);
		body.addProperty("max_tokens", cfg.maxTokens);
		JsonArray arr = new JsonArray();
		for (ChatMessage m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			arr.add(o);
		}
		body.add("messages", arr);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/chat/completions"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + cfg.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() / 100 != 2) {
			throw new IOException("聊天API错误 HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		return json.getAsJsonArray("choices").get(0).getAsJsonObject()
				.getAsJsonObject("message").get("content").getAsString();
	}

	/** 语音转文本（multipart/form-data），返回识别文本 */
	public static String transcribe(MobMindConfig cfg, byte[] wavBytes) throws IOException, InterruptedException {
		String boundary = "----mobmind" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		// model 字段
		writePart(bos, boundary, "model", null, cfg.sttModel.getBytes(StandardCharsets.UTF_8), null);
		// language 字段
		if (cfg.sttLanguage != null && !cfg.sttLanguage.isBlank()) {
			writePart(bos, boundary, "language", null, cfg.sttLanguage.getBytes(StandardCharsets.UTF_8), null);
		}
		// file 字段
		writePart(bos, boundary, "file", "voice.wav", wavBytes, "audio/wav");
		bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/audio/transcriptions"))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + cfg.apiKey)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
				.build();

		HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() / 100 != 2) {
			throw new IOException("语音识别API错误 HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		return json.has("text") ? json.get("text").getAsString().trim() : "";
	}

	/** 文本转语音，返回 WAV 音频字节 */
	public static byte[] speak(MobMindConfig cfg, String text) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.ttsModel);
		body.addProperty("voice", cfg.ttsVoice);
		body.addProperty("input", text);
		body.addProperty("response_format", "wav");

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/audio/speech"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + cfg.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() / 100 != 2) {
			String err = new String(resp.body(), StandardCharsets.UTF_8);
			throw new IOException("语音合成API错误 HTTP " + resp.statusCode() + ": " + truncate(err));
		}
		return resp.body();
	}

	private static void writePart(ByteArrayOutputStream bos, String boundary, String name,
								  String filename, byte[] data, String contentType) throws IOException {
		StringBuilder head = new StringBuilder();
		head.append("--").append(boundary).append("\r\n");
		head.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
		if (filename != null) head.append("; filename=\"").append(filename).append("\"");
		head.append("\r\n");
		if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
		head.append("\r\n");
		bos.write(head.toString().getBytes(StandardCharsets.UTF_8));
		bos.write(data);
		bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	private static String truncate(String s) {
		return s == null ? "" : s.substring(0, Math.min(200, s.length()));
	}
}
