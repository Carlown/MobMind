package com.mobmind.item;

import com.mobmind.ai.MobAiService;
import com.mobmind.state.MobMindState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 朋友选择器：右键友好生物选中/取消选中，选中的生物会被Ctrl+Z优先召唤。
 * 配方：斜放2个木棍+1个圆石（右上木棍，中心圆石，左下木棍）。
 */
public class FriendSelectorItem extends Item {

	/** 每个玩家选中的朋友UUID列表：player uuid -> set of mob uuid */
	private static final Map<UUID, Set<UUID>> SELECTED_FRIENDS = new ConcurrentHashMap<>();
	/** 光效显示：mob uuid -> 光效结束时间（game time） */
	private static final Map<UUID, Long> GLOW_EFFECT = new ConcurrentHashMap<>();

	private static final int MAX_SELECTED = 10;

	public FriendSelectorItem(Properties props) {
		super(props);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, net.minecraft.world.entity.LivingEntity target, InteractionHand hand) {
		if (player.level().isClientSide()) return InteractionResult.CONSUME;
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
		if (!(target instanceof Mob mob)) return InteractionResult.PASS;
		ServerLevel level = (ServerLevel) sp.level();
		if (!MobMindState.hasMet(mob.getUUID(), player.getUUID()) || !MobMindState.isFriendlyTo(mob, player.getUUID())) {
			player.sendSystemMessage(Component.translatable("item.mobmind.friend_selector.not_friendly")
					.withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}

		UUID pid = player.getUUID();
		UUID mid = mob.getUUID();
		Set<UUID> selected = SELECTED_FRIENDS.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());

		// 根据玩家语言选择生物显示名称
		boolean english = MobMindState.isPlayerEnglish(pid);
		MutableComponent mobName = english
				? Component.literal(MobAiService.getEnglishMobName(mob))
				: mob.getName().copy();

		if (selected.contains(mid)) {
			selected.remove(mid);
			GLOW_EFFECT.remove(mid);
			sp.sendSystemMessage(Component.translatable("item.mobmind.friend_selector.deselected",
					mobName).withStyle(ChatFormatting.YELLOW));
			level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
					SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 0.7f);
		} else {
			if (selected.size() >= MAX_SELECTED) {
				sp.sendSystemMessage(Component.translatable("item.mobmind.friend_selector.full", MAX_SELECTED)
						.withStyle(ChatFormatting.RED));
				return InteractionResult.FAIL;
			}
			selected.add(mid);
			long endTime = level.getLevelData().getGameTime() + 400;
			GLOW_EFFECT.put(mid, endTime);
			sp.sendSystemMessage(Component.translatable("item.mobmind.friend_selector.selected",
					mobName, selected.size()).withStyle(ChatFormatting.GREEN));
			level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
					SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.5f);
			spawnSelectParticles(level, mob);
		}

		player.swing(hand, true);
		return InteractionResult.CONSUME;
	}

	private void spawnSelectParticles(ServerLevel level, Mob mob) {
		Vec3 pos = mob.position().add(0, mob.getBbHeight() * 0.5, 0);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z,
				20, 0.5, 0.5, 0.5, 0.15);
		level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z,
				10, 0.3, 0.3, 0.3, 0.1);
	}

	/** 获取玩家选中的朋友UUID集合 */
	public static Set<UUID> getSelectedFriends(UUID playerId) {
		return SELECTED_FRIENDS.getOrDefault(playerId, Collections.emptySet());
	}

	/** 清除玩家所有选中 */
	public static void clearSelection(UUID playerId) {
		Set<UUID> s = SELECTED_FRIENDS.get(playerId);
		if (s != null) {
			for (UUID mid : s) {
				GLOW_EFFECT.remove(mid);
			}
			s.clear();
		}
	}

	/** tick光效粒子：选中的朋友持续发出光粒子 */
	public static void tickGlow(net.minecraft.server.MinecraftServer server) {
		long gameTime = server.overworld().getLevelData().getGameTime();
		GLOW_EFFECT.entrySet().removeIf(e -> e.getValue() < gameTime);
		if (GLOW_EFFECT.isEmpty()) return;

		for (ServerLevel level : server.getAllLevels()) {
			for (Map.Entry<UUID, Long> entry : GLOW_EFFECT.entrySet()) {
				if (level.getEntity(entry.getKey()) instanceof Mob mob && mob.isAlive()) {
					Vec3 pos = mob.position().add(0, mob.getBbHeight() * 0.5, 0);
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z,
							1, 0.3, 0.3, 0.3, 0.02);
				}
			}
		}
	}
}
