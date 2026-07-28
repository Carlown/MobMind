package com.mobmind.net;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.FriendRecall;
import com.mobmind.state.MobMindState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 模组网络包定义与注册。
 */
public final class MobPackets {
	private MobPackets() {}

	/** C2S：玩家对某只生物说话 */
	public record SpeakPayload(int entityId, String text) implements CustomPacketPayload {
		public static final Type<SpeakPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("mobmind", "speak"));
		public static final StreamCodec<FriendlyByteBuf, SpeakPayload> CODEC =
				CustomPacketPayload.codec(SpeakPayload::write, SpeakPayload::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeUtf(text, 512);
		}

		private static SpeakPayload read(FriendlyByteBuf buf) {
			return new SpeakPayload(buf.readVarInt(), buf.readUtf(512));
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** C2S：Ctrl+Z 召唤所有友好生物到身边 */
	public record RecallFriendsPayload() implements CustomPacketPayload {
		public static final Type<RecallFriendsPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("mobmind", "recall_friends"));
		public static final StreamCodec<FriendlyByteBuf, RecallFriendsPayload> CODEC =
				StreamCodec.unit(new RecallFriendsPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** C2S：Ctrl+X 将召唤来的友好生物送回原位 */
	public record DismissFriendsPayload() implements CustomPacketPayload {
		public static final Type<DismissFriendsPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("mobmind", "dismiss_friends"));
		public static final StreamCodec<FriendlyByteBuf, DismissFriendsPayload> CODEC =
				StreamCodec.unit(new DismissFriendsPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** C2S：客户端同步语言设置到服务端 */
	public record LanguagePayload(String languageCode) implements CustomPacketPayload {
		public static final Type<LanguagePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("mobmind", "language"));
		public static final StreamCodec<FriendlyByteBuf, LanguagePayload> CODEC =
				CustomPacketPayload.codec(LanguagePayload::write, LanguagePayload::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(languageCode, 16);
		}

		private static LanguagePayload read(FriendlyByteBuf buf) {
			return new LanguagePayload(buf.readUtf(16));
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** S2C：生物的回复（文本 + 情绪 + 动作 + 当前好感度 + 音色） */
	public record ReplyPayload(int entityId, String mobName, String text, String mood,
							   String action, int friendship, String speakerName, int voiceId) implements CustomPacketPayload {
		public static final Type<ReplyPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("mobmind", "reply"));
		public static final StreamCodec<FriendlyByteBuf, ReplyPayload> CODEC =
				CustomPacketPayload.codec(ReplyPayload::write, ReplyPayload::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeUtf(mobName, 64);
			buf.writeUtf(text, 1024);
			buf.writeUtf(mood, 32);
			buf.writeUtf(action, 16);
			buf.writeVarInt(friendship);
			buf.writeUtf(speakerName, 64);
			buf.writeVarInt(voiceId);
		}

		private static ReplyPayload read(FriendlyByteBuf buf) {
			return new ReplyPayload(buf.readVarInt(), buf.readUtf(64), buf.readUtf(1024),
					buf.readUtf(32), buf.readUtf(16), buf.readVarInt(), buf.readUtf(64), buf.readVarInt());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public static void registerCommon() {
		PayloadTypeRegistry.serverboundPlay().register(SpeakPayload.ID, SpeakPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RecallFriendsPayload.ID, RecallFriendsPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DismissFriendsPayload.ID, DismissFriendsPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LanguagePayload.ID, LanguagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ReplyPayload.ID, ReplyPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SpeakPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> MobAiService.handleSpeak(player, payload.entityId(), payload.text()));
		});

		ServerPlayNetworking.registerGlobalReceiver(RecallFriendsPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> FriendRecall.recallAllFriends(player));
		});

		ServerPlayNetworking.registerGlobalReceiver(DismissFriendsPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> FriendRecall.dismissAllFriends(player));
		});

		ServerPlayNetworking.registerGlobalReceiver(LanguagePayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> MobMindState.setPlayerLanguage(player, payload.languageCode()));
		});
	}
}
