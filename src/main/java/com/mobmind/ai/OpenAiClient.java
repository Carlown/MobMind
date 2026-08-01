package com.mobmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
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
	private static final Duration TIMEOUT = Duration.ofSeconds(180); // 本地大模型首次加载慢，延长到3分钟

	private OpenAiClient() {}

	public record ChatMessage(String role, String content) {}

	/** Chat completion, returns assistant text */
	public static String chat(MobMindConfig cfg, List<ChatMessage> messages) throws IOException, InterruptedException {
		// 本地 Ollama 端点：使用原生 /api/chat，而非 OpenAI 兼容端点 /v1/chat/completions。
		// 原因：Ollama 0.32.x 的 OpenAI 兼容端点不识别 think:false 参数，导致思考模型(qwen3.5等)
		// 继续在 reasoning 字段思考耗尽 max_tokens，content 为空（"空白未响应"主因）。
		// 原生 /api/chat 端点完整支持 think、keep_alive、options 参数。
		if (cfg.isLocalEndpoint()) {
			return chatOllamaNative(cfg, messages);
		}
		return chatOpenAi(cfg, messages);
	}

	/**
	 * Ollama 原生 /api/chat 端点：真正支持 think:false、keep_alive、options 等参数。
	 * 响应格式：{"message":{"role":"assistant","content":"..."},"done":true,"done_reason":"stop"}
	 */
	private static String chatOllamaNative(MobMindConfig cfg, List<ChatMessage> messages) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.chatModel);
		body.addProperty("stream", false);
		// 关键：禁用思考模式，否则 qwen3/gemma4 等模型会把 token 全用在 reasoning 上导致 content 为空
		body.addProperty("think", false);
		// 模型加载后在内存中保持30分钟，避免 Ollama 默认5分钟空闲后卸载导致下次请求冷启动（"时好时坏"主因）
		body.addProperty("keep_alive", "30m");

		// Ollama 原生 options：比 max_tokens 更底层，num_predict 限制生成长度，num_ctx 上下文窗口
		JsonObject options = new JsonObject();
		options.addProperty("temperature", cfg.temperature);
		options.addProperty("num_predict", cfg.maxTokens);
		options.addProperty("num_ctx", 8192);
		body.add("options", options);

		JsonArray arr = new JsonArray();
		for (ChatMessage m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			arr.add(o);
		}
		body.add("messages", arr);

		// 构造原生端点 URL：从归一化端点 http://127.0.0.1:11434/v1 去掉 /v1 得到 http://127.0.0.1:11434
		String base = cfg.normalizedEndpoint();
		String nativeUrl = base;
		if (nativeUrl.endsWith("/v1")) {
			nativeUrl = nativeUrl.substring(0, nativeUrl.length() - 3);
		}
		nativeUrl = nativeUrl + "/api/chat";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(nativeUrl))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		long t0 = System.currentTimeMillis();
		HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		long dt = System.currentTimeMillis() - t0;

		if (resp.statusCode() / 100 != 2) {
			MobMindMod.LOGGER.warn("[MobMind] Ollama native API HTTP {} body: {}", resp.statusCode(), resp.body());
			throw new IOException("Ollama API error HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
		}
		String respBody = resp.body();
		MobMindMod.LOGGER.info("[MobMind] Ollama native response {}ms, body length={}", dt, respBody == null ? 0 : respBody.length());
		if (respBody == null || respBody.isBlank()) {
			throw new IOException("Empty response from Ollama. Check if Ollama is running on localhost:11434");
		}

		JsonObject json;
		try {
			json = JsonParser.parseString(respBody).getAsJsonObject();
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] Failed to parse Ollama JSON, first 500 chars: {}", respBody.length() > 500 ? respBody.substring(0, 500) : respBody);
			throw new IOException("Non-JSON response from Ollama: " + truncate(respBody));
		}

		// Ollama 错误字段 {"error":"..."}
		if (json.has("error")) {
			String errMsg = json.get("error").isJsonObject()
					? (json.get("error").getAsJsonObject().has("message") ? json.get("error").getAsJsonObject().get("message").getAsString() : truncate(respBody))
					: json.get("error").getAsString();
			MobMindMod.LOGGER.warn("[MobMind] Ollama returned error: {}", errMsg);
			throw new IOException("Ollama error: " + errMsg);
		}

		// 原生响应：{"message":{"role":"assistant","content":"..."},"done":true,"done_reason":"stop"}
		if (!json.has("message")) {
			MobMindMod.LOGGER.warn("[MobMind] No message in Ollama response. Keys: {}, body: {}", json.keySet(), truncate(respBody));
			throw new IOException("No message in Ollama response. Response: " + truncate(respBody));
		}
		JsonObject msg = json.getAsJsonObject("message");
		String content = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";

		// done_reason: "stop"=正常结束, "length"=达到num_predict上限被截断
		String doneReason = json.has("done_reason") && !json.get("done_reason").isJsonNull() ? json.get("done_reason").getAsString() : "";

		if (content.isBlank()) {
			// think:false 生效时不会有 reasoning；如果 content 仍为空且 done_reason=length，
			// 说明旧版 Ollama 不支持 think 参数，模型仍在思考并耗尽 token
			if ("length".equals(doneReason)) {
				MobMindMod.LOGGER.warn("[MobMind] Ollama done_reason=length, content empty. Model may be thinking model not honoring think:false. Body: {}", truncate(respBody));
				throw new IOException("Model hit token limit with empty content. The model '" + cfg.chatModel
						+ "' may be a thinking model (e.g. qwen3.5) whose think:false is not honored by your Ollama version. "
						+ "Try: 1) run 'ollama list' and switch to a non-thinking model like qwen2.5:7b, 2) update Ollama to latest, 3) increase max_tokens in settings (Ctrl+K).");
			}
			MobMindMod.LOGGER.warn("[MobMind] Ollama content empty. Full body: {}", respBody.length() > 2000 ? respBody.substring(0, 2000) + "..." : respBody);
			throw new IOException("Empty content from Ollama. Check model name '" + cfg.chatModel + "' (run 'ollama list' to verify). Response: " + truncate(respBody));
		}

		// 如果 think:false 没完全生效，content 里可能残留 <think>...</think> 标签，需要清理
		content = stripThinkTags(content);

		return content;
	}

	/** 移除思考模型可能残留的 &lt;think&gt;...&lt;/think&gt; 标签内容 */
	private static String stripThinkTags(String content) {
		if (content == null || content.isEmpty()) return content;
		String result = content;
		int start = result.indexOf("<think>");
		while (start >= 0) {
			int end = result.indexOf("</think>", start);
			if (end >= 0) {
				result = result.substring(0, start) + result.substring(end + "</think>".length());
			} else {
				// 未闭合的 think 标签，删到结尾
				result = result.substring(0, start);
				break;
			}
			start = result.indexOf("<think>");
		}
		return result.trim();
	}

	/** OpenAI 兼容端点（云端API，或非Ollama的本地服务） */
	private static String chatOpenAi(MobMindConfig cfg, List<ChatMessage> messages) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.chatModel);
		body.addProperty("temperature", cfg.temperature);
		body.addProperty("max_tokens", cfg.maxTokens);
		// Ollama compatibility: stream=false for complete non-streaming response
		body.addProperty("stream", false);
		// Disable thinking mode for reasoning models (qwen3, gemma4, etc.) so content is not empty
		body.addProperty("think", false);

		JsonArray arr = new JsonArray();
		for (ChatMessage m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			arr.add(o);
		}
		body.add("messages", arr);

		var reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/chat/completions"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json");
		// Local endpoints like Ollama don't need API key; skip Auth header when key is blank
		if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
			reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
		}
		HttpRequest req = reqBuilder
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		long t0 = System.currentTimeMillis();
		HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		long dt = System.currentTimeMillis() - t0;

		if (resp.statusCode() / 100 != 2) {
			MobMindMod.LOGGER.warn("[MobMind] Chat API HTTP {} response body: {}", resp.statusCode(), resp.body());
			throw new IOException("Chat API error HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
		}
		String respBody = resp.body();
		MobMindMod.LOGGER.info("[MobMind] AI response {}ms, full body length={}", dt, respBody == null ? 0 : respBody.length());
		if (respBody == null || respBody.isBlank()) {
			throw new IOException("Empty response body. Check your endpoint URL.");
		}
		JsonObject json;
		try {
			json = JsonParser.parseString(respBody).getAsJsonObject();
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] Failed to parse JSON, raw response first 500 chars: {}", respBody.length() > 500 ? respBody.substring(0, 500) : respBody);
			throw new IOException("Non-JSON response. Response: " + truncate(respBody));
		}
		// Check for error field (Ollama returns {"error":"..."})
		if (json.has("error")) {
			String errMsg;
			var errEl = json.get("error");
			if (errEl.isJsonObject()) {
				errMsg = errEl.getAsJsonObject().has("message") ? errEl.getAsJsonObject().get("message").getAsString() : truncate(respBody);
			} else {
				errMsg = errEl.getAsString();
			}
			MobMindMod.LOGGER.warn("[MobMind] Model returned error: {}", errMsg);
			throw new IOException("Model error: " + errMsg);
		}
		if (!json.has("choices") || json.getAsJsonArray("choices").isEmpty()) {
			MobMindMod.LOGGER.warn("[MobMind] No choices in response. Keys: {}, body: {}", json.keySet(), truncate(respBody));
			throw new IOException("Empty choices. Model may not be loaded or model name is wrong (current: " + cfg.chatModel + "). Response: " + truncate(respBody));
		}
		String content;
		String reasoningContent = null;
		String finishReason = null;
		try {
			var choice0 = json.getAsJsonArray("choices").get(0).getAsJsonObject();
			if (choice0.has("finish_reason") && !choice0.get("finish_reason").isJsonNull()) {
				finishReason = choice0.get("finish_reason").getAsString();
			}
			var msg = choice0.getAsJsonObject("message");
			if (msg == null) {
				MobMindMod.LOGGER.warn("[MobMind] No message in choice. Keys: {}, body: {}", choice0.keySet(), truncate(respBody));
				throw new IOException("No message object in response. Response: " + truncate(respBody));
			}
			// Check reasoning fields: Ollama uses "reasoning", OpenAI uses "reasoning_content"
			if (msg.has("reasoning") && !msg.get("reasoning").isJsonNull()) {
				reasoningContent = msg.get("reasoning").getAsString();
			} else if (msg.has("reasoning_content") && !msg.get("reasoning_content").isJsonNull()) {
				reasoningContent = msg.get("reasoning_content").getAsString();
			}
			if (msg.get("content") == null || msg.get("content").isJsonNull()) {
				if (reasoningContent != null && !reasoningContent.isBlank()) {
					if ("length".equals(finishReason)) {
						throw new IOException("Model used all tokens for thinking and was cut off (finish_reason=length). Increase max_tokens in settings (Ctrl+K). Thinking preview: " + truncate(reasoningContent));
					}
					throw new IOException("Model returned only reasoning content (no reply). Try a non-thinking model.");
				}
				MobMindMod.LOGGER.warn("[MobMind] message.content is null. Message keys: {}", msg.keySet());
				throw new IOException("Message content is null. Response: " + truncate(respBody));
			}
			content = msg.get("content").getAsString();
		} catch (Exception e) {
			if (e instanceof IOException) throw e;
			MobMindMod.LOGGER.warn("[MobMind] Parse error", e);
			throw new IOException("Failed to parse response. Response: " + truncate(respBody), e);
		}
		if (content == null || content.isBlank()) {
			if (reasoningContent != null && !reasoningContent.isBlank()) {
				MobMindMod.LOGGER.warn("[MobMind] Content is empty but got reasoning ({} chars), finish_reason={}", reasoningContent.length(), finishReason);
				if ("length".equals(finishReason)) {
					throw new IOException("Model used all " + cfg.maxTokens + " tokens for thinking and was cut off. Increase max_tokens in settings (Ctrl+K, set to 2048+). Thinking preview: " + truncate(reasoningContent));
				}
				throw new IOException("Model returned only thinking content (no reply). Try a non-thinking model. Reasoning preview: " + truncate(reasoningContent));
			}
			MobMindMod.LOGGER.warn("[MobMind] Content is empty. Full response body: {}", respBody.length() > 2000 ? respBody.substring(0, 2000) + "..." : respBody);
			throw new IOException("Empty content from model. Check model name '" + cfg.chatModel + "'. Response: " + truncate(respBody));
		}
		return content;
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

		var reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/audio/transcriptions"))
				.timeout(TIMEOUT)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary);
		if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
			reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
		}
		HttpRequest req = reqBuilder
				.POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
				.build();

		HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() / 100 != 2) {
			throw new IOException("STT API error HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		return json.has("text") ? json.get("text").getAsString().trim() : "";
	}

	/** Text-to-Speech, returns WAV audio bytes */
	public static byte[] speak(MobMindConfig cfg, String text) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.ttsModel);
		body.addProperty("voice", cfg.ttsVoice);
		body.addProperty("input", text);
		body.addProperty("response_format", "wav");

		var reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(cfg.normalizedEndpoint() + "/audio/speech"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json");
		if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
			reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
		}
		HttpRequest req = reqBuilder
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() / 100 != 2) {
			String err = new String(resp.body(), StandardCharsets.UTF_8);
			throw new IOException("TTS API error HTTP " + resp.statusCode() + ": " + truncate(err));
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
