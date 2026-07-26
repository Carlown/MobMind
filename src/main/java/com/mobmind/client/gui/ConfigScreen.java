package com.mobmind.client.gui;

import com.mobmind.client.VoiceManager;
import com.mobmind.client.voice.MicCapture;
import com.mobmind.config.MobMindConfig;
import com.mobmind.voice.LocalVoice;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MobMind 设置界面。
 * 支持滚动条、中英文本地化、麦克风选择。
 */
public class ConfigScreen extends Screen {
	private final Screen parent;
	private final List<EditBox> fields = new ArrayList<>();
	private final List<Scrollable> scrollables = new ArrayList<>();
	private final List<Renderable> scrollRenderables = new ArrayList<>();
	private final List<String> microphones = new ArrayList<>();
	private Button saveButton;
	private Button cancelButton;

	private int scrollY = 0;
	private int contentBottom = 0;
	private final int top = 40;
	private int bottom;
	private final int scrollbarX;
	private final int scrollbarW = 8;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("gui.mobmind.config.title"));
		this.parent = parent;
		this.scrollbarX = 0; // init 中根据屏幕宽度重新计算
	}

	@Override
	protected void init() {
		MobMindConfig cfg = MobMindConfig.get();
		fields.clear();
		scrollables.clear();
		scrollRenderables.clear();
		microphones.clear();
		this.clearWidgets();
		microphones.addAll(MicCapture.listMicrophones());
		if (microphones.isEmpty()) microphones.add(Component.translatable("gui.mobmind.config.mic.default").getString());

		bottom = this.height - 130;
		scrollY = 0;

		// 两栏宽度自适应，小屏幕下自动压缩，确保左侧不被裁掉
		int colW = Math.min(210, (this.width - 52) / 2);
		int gap = Math.max(16, (this.width - colW * 2) / 8);
		int lx1 = Math.max(10, this.width / 2 - colW - gap);
		int lx2 = Math.min(this.width - colW - 10, this.width / 2 + gap);
		int y = top + 12;

		addSectionTitle(lx1, top, "gui.mobmind.config.section.api");
		addSectionTitle(lx2, top, "gui.mobmind.config.section.local");

		// 左栏：云端 API
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.apiEndpoint", cfg.apiEndpoint, v -> cfg.apiEndpoint = v);
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.apiKey", cfg.apiKey, v -> cfg.apiKey = v, true, null);
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.chatModel", cfg.chatModel, v -> cfg.chatModel = v);
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.temperature", String.valueOf(cfg.temperature),
				v -> cfg.temperature = parseDouble(v, cfg.temperature));
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.maxTokens", String.valueOf(cfg.maxTokens),
				v -> cfg.maxTokens = parseInt(v, cfg.maxTokens));
		y = addRow(lx1, colW, y, "gui.mobmind.config.label.interactRadius", String.valueOf(cfg.interactRadius),
				v -> cfg.interactRadius = parseInt(v, cfg.interactRadius));

		// 右栏：本地语音引擎
		y = top + 12;
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.voiceEngineDir", cfg.voiceEngineDir, v -> cfg.voiceEngineDir = v, false, "gui.mobmind.config.hint.voiceEngineDir");
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.sttModelDir", cfg.sttModelDir, v -> cfg.sttModelDir = v, false, "gui.mobmind.config.hint.sttModelDir");
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.ttsModelDir", cfg.ttsModelDir, v -> cfg.ttsModelDir = v, false, "gui.mobmind.config.hint.ttsModelDir");
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.ttsVoicePool", cfg.ttsVoicePool, v -> cfg.ttsVoicePool = v, false, "gui.mobmind.config.hint.ttsVoicePool");
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.ttsVoiceCount", String.valueOf(cfg.ttsVoiceCount),
				v -> cfg.ttsVoiceCount = parseInt(v, cfg.ttsVoiceCount));
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.forceTtsVoiceId", String.valueOf(cfg.forceTtsVoiceId),
				v -> cfg.forceTtsVoiceId = parseInt(v, cfg.forceTtsVoiceId));
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.ttsSpeed", String.valueOf(cfg.ttsSpeed),
				v -> cfg.ttsSpeed = parseDouble(v, cfg.ttsSpeed));
		y = addRow(lx2, colW, y, "gui.mobmind.config.label.voiceThreads", String.valueOf(cfg.voiceThreads),
				v -> cfg.voiceThreads = parseInt(v, cfg.voiceThreads));

		// 麦克风选择（右栏）
		y = addMicSelector(lx2, colW, y, cfg);

		// 状态提示：放在内容底部，横跨两栏居中
		String sttState = LocalVoice.isSttReady(cfg)
				? Component.translatable("gui.mobmind.config.status.sttReady").getString()
				: Component.translatable("gui.mobmind.config.status.sttNotReady").getString();
		String ttsState;
		if (LocalVoice.isTtsReady(cfg)) {
			ttsState = Component.translatable("gui.mobmind.config.status.ttsReady",
					LocalVoice.ttsType(cfg), String.valueOf(LocalVoice.voiceCount(cfg))).getString();
		} else {
			ttsState = Component.translatable("gui.mobmind.config.status.ttsNotReady").getString();
		}
		StringWidget stateWidget = new StringWidget(Component.literal(sttState + "  " + ttsState), this.font);
		stateWidget.setPosition(this.width / 2 - this.font.width(sttState + "  " + ttsState) / 2, y + 6);
		addScrollable(stateWidget);
		contentBottom = y + 24;

		// 开关（底部固定，不滚动）
		int ty = this.height - 120;
		addToggle(lx1, ty, colW, "gui.mobmind.config.toggle.voiceEnabled", () -> cfg.voiceEnabled, v -> cfg.voiceEnabled = v);
		addToggle(lx2, ty, colW, "gui.mobmind.config.toggle.ttsEnabled", () -> cfg.ttsEnabled, v -> cfg.ttsEnabled = v);
		addToggle(lx1, ty + 25, colW, "gui.mobmind.config.toggle.greetingEnabled", () -> cfg.greetingEnabled, v -> cfg.greetingEnabled = v);
		addToggle(lx2, ty + 25, colW, "gui.mobmind.config.toggle.offlineFallback", () -> cfg.offlineFallback, v -> cfg.offlineFallback = v);
		addToggle(lx1, ty + 50, colW, "gui.mobmind.config.toggle.creativeTauntEnabled", () -> cfg.creativeTauntEnabled, v -> cfg.creativeTauntEnabled = v);

		// 保存/取消按钮固定在最下方
		int btnY = this.height - 35;
		saveButton = Button.builder(Component.translatable("gui.mobmind.config.button.save"), b -> {
			MobMindConfig.save();
			this.onClose();
		}).bounds(this.width / 2 - 105, btnY, 100, 20).build();
		cancelButton = Button.builder(Component.translatable("gui.mobmind.config.button.cancel"), b -> this.onClose())
				.bounds(this.width / 2 + 5, btnY, 100, 20).build();
		this.addRenderableWidget(saveButton);
		this.addRenderableWidget(cancelButton);

		applyScroll(0);
	}

	private void addSectionTitle(int x, int y, String key) {
		StringWidget widget = new StringWidget(Component.literal("§n").append(Component.translatable(key)), this.font);
		widget.setPosition(x, y);
		addScrollable(widget);
	}

	private int addRow(int x, int w, int y, String labelKey, String value, Consumer<String> setter) {
		return addRow(x, w, y, labelKey, value, setter, false, null);
	}

	private int addRow(int x, int w, int y, String labelKey, String value, Consumer<String> setter, boolean secret, String hintKey) {
		StringWidget widget = new StringWidget(Component.translatable(labelKey), this.font);
		widget.setPosition(x, y);
		addScrollable(widget);

		EditBox box = new EditBox(this.font, x, y + 12, w, 20, Component.translatable(labelKey));
		box.setMaxLength(512);
		box.setValue(value == null ? "" : value);
		if (hintKey != null) box.setHint(Component.translatable(hintKey));
		if (secret) {
			box.addFormatter((text, pos) -> net.minecraft.util.FormattedCharSequence.forward(
					"*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
		}
		box.setResponder(setter);
		addScrollable(box);
		fields.add(box);
		return y + 36;
	}

	private int addMicSelector(int x, int w, int y, MobMindConfig cfg) {
		StringWidget widget = new StringWidget(Component.translatable("gui.mobmind.config.label.micDevice"), this.font);
		widget.setPosition(x, y);
		addScrollable(widget);

		int startIdx = micIndex(cfg.micMixerName);
		Button btn = Button.builder(Component.literal(labelForMic(cfg.micMixerName)), b -> {
			int idx = (micIndex(cfg.micMixerName) + 1) % microphones.size();
			cfg.micMixerName = microphones.get(idx);
			VoiceManager.updateMicDevice(cfg.micMixerName);
			b.setMessage(Component.literal(labelForMic(cfg.micMixerName)));
		}).bounds(x, y + 12, w, 20).build();
		if (startIdx >= 0) cfg.micMixerName = microphones.get(startIdx);
		addScrollable(btn);
		return y + 36;
	}

	private int micIndex(String name) {
		if (name == null || name.isBlank()) {
			return microphones.isEmpty() ? -1 : 0;
		}
		for (int i = 0; i < microphones.size(); i++) {
			if (microphones.get(i).equals(name)) return i;
		}
		return 0;
	}

	private String labelForMic(String name) {
		if (name == null || name.isBlank()) {
			return Component.translatable("gui.mobmind.config.mic.default").getString();
		}
		return name;
	}

	private void addToggle(int x, int y, int w, String labelKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		Button btn = Button.builder(
				Component.translatable(labelKey).append(": ").append(toggleText(getter.get())),
				b -> {
					setter.accept(!getter.get());
					b.setMessage(Component.translatable(labelKey).append(": ").append(toggleText(getter.get())));
				}).bounds(x, y, w, 20).build();
		this.addRenderableWidget(btn);
	}

	private Component toggleText(boolean on) {
		return on ? Component.literal("§a").append(Component.translatable("options.on"))
				: Component.literal("§c").append(Component.translatable("options.off"));
	}

	private void addScrollable(AbstractWidget widget) {
		this.addWidget(widget);
		scrollRenderables.add(widget);
		scrollables.add(new Scrollable(widget, widget.getY()));
	}

	private boolean isScrollable(net.minecraft.client.gui.components.events.GuiEventListener child) {
		for (Scrollable s : scrollables) {
			if (s.widget() == child) return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		boolean inScroll = event.y() >= top && event.y() <= bottom;
		for (net.minecraft.client.gui.components.events.GuiEventListener child : this.children()) {
			// 裁剪区域外的滚动控件不响应点击，避免挡住底部固定按钮
			if (!inScroll && isScrollable(child)) continue;
			if (child.mouseClicked(event, doubleClick)) {
				this.setFocused(child);
				if (event.button() == 0) this.setDragging(true);
				return true;
			}
		}
		return false;
	}

	private void applyScroll(int delta) {
		scrollY += delta;
		int maxScroll = Math.max(0, contentBottom - (bottom - top));
		if (scrollY > 0) scrollY = 0;
		if (scrollY < -maxScroll) scrollY = -maxScroll;
		for (Scrollable s : scrollables) {
			s.widget.setY(s.originalY + scrollY);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		applyScroll((int) (-verticalAmount * 16));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// 裁剪中间可滚动区域
		graphics.enableScissor(0, top, this.width, bottom);
		for (Renderable r : scrollRenderables) {
			r.extractRenderState(graphics, mouseX, mouseY, delta);
		}
		graphics.disableScissor();

		// 标题固定
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

		// 底部固定按钮：交给父类渲染，这样悬停/点击状态会自动更新
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// 滚动条
		renderScrollbar(graphics);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics) {
		int maxScroll = Math.max(0, contentBottom - (bottom - top));
		if (maxScroll <= 0) return;

		int trackTop = top;
		int trackHeight = bottom - top;
		int sbX = this.width - scrollbarW - 2;
		int thumbHeight = Math.max(24, trackHeight * trackHeight / (trackHeight + maxScroll));
		int thumbY = trackTop + (int) ((-scrollY) * (trackHeight - thumbHeight) / maxScroll);

		// 轨道
		graphics.fill(sbX, trackTop, sbX + scrollbarW, trackTop + trackHeight, 0x55FFFFFF);
		// 滑块
		graphics.fill(sbX, thumbY, sbX + scrollbarW, thumbY + thumbHeight, 0xFFAAAAAA);
	}

	private static double parseDouble(String s, double fallback) {
		try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
	}

	private static int parseInt(String s, int fallback) {
		try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent);
	}

	private record Scrollable(AbstractWidget widget, int originalY) {}
}
