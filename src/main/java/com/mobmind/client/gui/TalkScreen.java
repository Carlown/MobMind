package com.mobmind.client.gui;

import com.mobmind.client.ClientDialogue;
import com.mobmind.net.MobPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 文字对话界面：对准或靠近生物按 Y 打开，回车发送。
 */
public class TalkScreen extends Screen {
	private final int entityId;
	private final String mobDesc;
	private EditBox input;

	public TalkScreen(int entityId, String mobDesc) {
		super(Component.translatable("gui.mobmind.talk.title", mobDesc));
		this.entityId = entityId;
		this.mobDesc = mobDesc;
	}

	@Override
	protected void init() {
		input = new EditBox(this.font, this.width / 2 - 150, this.height / 2, 300, 20,
				Component.translatable("gui.mobmind.talk.input"));
		input.setMaxLength(500);
		input.setHint(Component.translatable("gui.mobmind.talk.hint"));
		input.setBordered(true);
		this.addRenderableWidget(input);
		this.setInitialFocus(input);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			String text = input.getValue().trim();
			if (!text.isEmpty()) {
				ClientDialogue.recordPlayerSpeech(text);
				ClientPlayNetworking.send(new MobPackets.SpeakPayload(entityId, text));
			}
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 24, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
