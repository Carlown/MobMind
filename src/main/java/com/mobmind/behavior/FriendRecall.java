package com.mobmind.behavior;

import com.mobmind.config.MobMindConfig;
import com.mobmind.item.FriendSelectorItem;
import com.mobmind.state.MobMindState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ctrl+Z 一键召唤友好生物到玩家身边。
 * - 默认只召唤最近的 recallCount 只（配置文件默认 2），0 = 召唤全部
 * - 支持 Ctrl+X 将召唤来的生物送回原位
 * - 支持 /mobmind recall <数量> 指令临时指定数量并持久化到配置
 * - 支持跨维度传送，10秒冷却
 * - 友军不会互殴（见 LivingEntityMixin 的 areAllies 检查）
 */
public final class FriendRecall {
	private FriendRecall() {}

	private static final Map<UUID, Long> LAST_RECALL = new ConcurrentHashMap<>();
	private static final long COOLDOWN_MS = 10000;

	/** 记录被召唤生物的原始位置：mob uuid -> (level key, x, y, z) */
	private static final Map<UUID, RecallOrigin> RECALLED_MOBS = new ConcurrentHashMap<>();

	private record RecallOrigin(ResourceKey<Level> levelKey, double x, double y, double z) {}

	/** 召唤所有友好生物（Ctrl+Z 快捷键使用），数量从配置读取 */
	public static void recallAllFriends(ServerPlayer player) {
		int count = MobMindConfig.get().recallCount;
		recallFriends(player, count <= 0 ? Integer.MAX_VALUE : count, false);
	}

	/** 将本次玩家召唤来的友好生物送回原位（Ctrl+X） */
	public static void dismissAllFriends(ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long last = LAST_RECALL.get(player.getUUID());
		if (last != null && now - last < 1000) {
			// 防止按键抖动
			return;
		}

		ServerLevel currentLevel = (ServerLevel) player.level();
		net.minecraft.server.MinecraftServer server = currentLevel.getServer();
		int dismissed = 0;
		int crossDim = 0;
		List<UUID> toRemove = new ArrayList<>();

		for (Map.Entry<UUID, RecallOrigin> entry : RECALLED_MOBS.entrySet()) {
			RecallOrigin origin = entry.getValue();
			ServerLevel originLevel = server.getLevel(origin.levelKey);
			if (originLevel == null) continue;

			Mob mob = null;
			// 在所有维度查找该生物（可能已经被传送到玩家维度）
			for (ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(entry.getKey()) instanceof Mob m && m.isAlive()) {
					mob = m;
					break;
				}
			}
			if (mob == null) {
				toRemove.add(entry.getKey());
				continue;
			}

			// 检查该生物是否仍对玩家友好且认识过
			if (!MobMindState.hasMet(mob.getUUID(), player.getUUID()) || !MobMindState.isFriendlyTo(mob, player.getUUID())) {
				toRemove.add(entry.getKey());
				continue;
			}

			ServerLevel mobLevel = (ServerLevel) mob.level();
			boolean crossDimension = (mobLevel != originLevel);

			// 传送回原位（使用安全位置查找，避免卡墙）
			if (crossDimension) {
				Vec3 safeBack = findSafePosition(originLevel, new Vec3(origin.x, origin.y, origin.z), new ArrayList<>(), 0);
				if (safeBack != null) {
					mob.teleportTo(originLevel, safeBack.x, safeBack.y, safeBack.z, Set.of(), mob.getYRot(), mob.getXRot(), false);
				} else {
					double ty = originLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
							BlockPos.containing(origin.x, origin.y, origin.z)).getY() + 1;
					mob.teleportTo(originLevel, origin.x, ty, origin.z, Set.of(), mob.getYRot(), mob.getXRot(), false);
				}
				crossDim++;
			} else {
				Vec3 safeBack = findSafePosition(originLevel, new Vec3(origin.x, origin.y, origin.z), new ArrayList<>(), 0);
				if (safeBack != null) {
					mob.teleportTo(safeBack.x, safeBack.y, safeBack.z);
				} else {
					mob.teleportTo(origin.x, origin.y, origin.z);
				}
			}

			// 清除攻击目标
			mob.setTarget(null);
			mob.getNavigation().stop();
			mob.setDeltaMovement(0, 0.3, 0);
			mob.hurtMarked = true;

			// 送回粒子
			originLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
					mob.getX(), mob.getY() + 1, mob.getZ(),
					20, 0.5, 0.5, 0.5, 0.2);

			toRemove.add(entry.getKey());
			dismissed++;
		}

		// 清理记录
		for (UUID id : toRemove) {
			RECALLED_MOBS.remove(id);
		}

		// 播放送回音效
		currentLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 0.8f);

		if (dismissed == 0) {
			player.sendSystemMessage(Component.translatable("status.mobmind.dismiss.none"));
		} else if (crossDim > 0) {
			player.sendSystemMessage(Component.translatable("status.mobmind.dismiss.success_crossdim",
					String.valueOf(dismissed), String.valueOf(crossDim)));
		} else {
			player.sendSystemMessage(Component.translatable("status.mobmind.dismiss.success", String.valueOf(dismissed)));
		}
	}

	/**
	 * 召唤友好生物
	 * @param count 召唤数量（最近的N个），0=全部
	 * @param fromCommand 是否来自指令（来自指令时保存到配置）
	 */
	public static void recallFriends(ServerPlayer player, int count, boolean fromCommand) {
		long now = System.currentTimeMillis();
		Long last = LAST_RECALL.get(player.getUUID());
		if (last != null && now - last < COOLDOWN_MS && !fromCommand) {
			int remainSec = (int) Math.ceil((COOLDOWN_MS - (now - last)) / 1000.0);
			player.sendSystemMessage(Component.translatable("status.mobmind.recall.cooldown", String.valueOf(remainSec)));
			return;
		}
		LAST_RECALL.put(player.getUUID(), now);

		// 如果是指令设置数量，持久化到配置
		if (fromCommand && count >= 0) {
			MobMindConfig.get().recallCount = count;
			MobMindConfig.save();
		}

		ServerLevel targetLevel = (ServerLevel) player.level();
		Vec3 targetPos = player.position();
		UUID playerId = player.getUUID();
		net.minecraft.server.MinecraftServer server = targetLevel.getServer();

		// 清理RECALLED_MOBS中已不存在的生物（防止残留）
		cleanupStaleRecords(server);

		// 检查玩家是否用朋友选择器选中了特定朋友
		java.util.Set<UUID> selectedIds = FriendSelectorItem.getSelectedFriends(playerId);
		boolean hasSelection = !selectedIds.isEmpty();

		// 收集当前维度的友好生物（不跨维度召唤）
		List<MobWithDist> friends = new ArrayList<>();
		AABB searchBox = new AABB(
				targetLevel.getWorldBorder().getMinX(), -64, targetLevel.getWorldBorder().getMinZ(),
				targetLevel.getWorldBorder().getMaxX(), 384, targetLevel.getWorldBorder().getMaxZ()
		);
		for (Mob mob : targetLevel.getEntitiesOfClass(Mob.class, searchBox)) {
			if (!mob.isAlive()) continue;
			// 只召唤玩家认识过的朋友（聊过天的生物），不是天生友好的
			if (!MobMindState.hasMet(mob.getUUID(), playerId)) continue;
			if (!MobMindState.isFriendlyTo(mob, playerId)) continue;
			if (mob.isPassenger() || mob.isVehicle()) continue;
			if (mob instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) continue;
			if (mob instanceof net.minecraft.world.entity.boss.wither.WitherBoss) continue;
			if (!MobMindConfig.get().recallVillagers && mob instanceof AbstractVillager) continue;

			// 如果有选中的朋友，只召唤选中的
			if (hasSelection && !selectedIds.contains(mob.getUUID())) continue;

			double dist = mob.distanceToSqr(player);
			friends.add(new MobWithDist(mob, targetLevel, dist, true));
		}

		// 按距离排序（最近的优先）
		friends.sort(Comparator.comparingDouble(m -> m.dist));

		// 如果有选中的朋友，召唤所有选中的；否则按配置数量召唤
		int maxCount = hasSelection ? friends.size() : ((count <= 0) ? friends.size() : count);
		int recalled = 0;
		int crossDim = 0;
		List<Vec3> usedPositions = new ArrayList<>();

		for (int i = 0; i < Math.min(maxCount, friends.size()); i++) {
			MobWithDist entry = friends.get(i);
			Mob mob = entry.mob;
			ServerLevel level = entry.level;

			// 记录原始位置（如果还没记录过，避免重复召唤覆盖原始位置）
			if (!RECALLED_MOBS.containsKey(mob.getUUID())) {
				ResourceKey<Level> originKey = level.dimension();
				RECALLED_MOBS.put(mob.getUUID(), new RecallOrigin(originKey, mob.getX(), mob.getY(), mob.getZ()));
			}

			// 寻找安全的传送位置（螺旋向外搜索，避免重叠和卡墙）
			Vec3 safePos = findSafePosition(targetLevel, targetPos, usedPositions, i);
			if (safePos == null) {
				// 找不到安全位置，跳过（空间太小）
				continue;
			}
			usedPositions.add(safePos);

			double tx = safePos.x;
			double ty = safePos.y;
			double tz = safePos.z;

			if (entry.sameDim) {
				mob.teleportTo(tx, ty, tz);
			} else {
				mob.teleportTo(targetLevel, tx, ty, tz, Set.of(), mob.getYRot(), mob.getXRot(), false);
				crossDim++;
			}

			// 清除攻击目标，专注跟随玩家
			mob.setTarget(null);
			mob.getNavigation().stop();
			mob.setDeltaMovement(0, 0.3, 0);
			mob.hurtMarked = true;

			// 传送粒子
			targetLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
					mob.getX(), mob.getY() + 1, mob.getZ(),
					20, 0.5, 0.5, 0.5, 0.2);

			recalled++;
		}

		// 播放召回音效
		targetLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.2f);

		// 发送提示
		String countStr;
		if (fromCommand) {
			if (count == 0) {
				countStr = "all";
			} else {
				countStr = String.valueOf(count);
			}
		} else {
			countStr = null;
		}

		if (recalled == 0) {
			player.sendSystemMessage(Component.translatable("status.mobmind.recall.none"));
		} else if (fromCommand) {
			player.sendSystemMessage(Component.translatable("status.mobmind.recall.set", countStr,
					String.valueOf(recalled), String.valueOf(crossDim > 0 ? crossDim : 0)));
		} else if (crossDim > 0) {
			player.sendSystemMessage(Component.translatable("status.mobmind.recall.success_crossdim",
					String.valueOf(recalled), String.valueOf(crossDim)));
		} else {
			player.sendSystemMessage(Component.translatable("status.mobmind.recall.success", String.valueOf(recalled)));
		}
	}

	/** 清理RECALLED_MOBS中已不存在于当前服务器的生物记录（防止跨存档残留） */
	private static void cleanupStaleRecords(net.minecraft.server.MinecraftServer server) {
		List<UUID> toRemove = new ArrayList<>();
		for (UUID mobId : RECALLED_MOBS.keySet()) {
			boolean found = false;
			for (ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(mobId) instanceof Mob m && m.isAlive()) {
					found = true;
					break;
				}
			}
			if (!found) {
				toRemove.add(mobId);
			}
		}
		for (UUID id : toRemove) {
			RECALLED_MOBS.remove(id);
		}
		// 同时清理7天前的召唤冷却（彻底防止残留）
		long cutoff = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L;
		LAST_RECALL.entrySet().removeIf(e -> e.getValue() < cutoff);
	}

	/**
	 * 螺旋向外搜索玩家周围的安全站立位置。
	 * 要求：脚下实心，两格高空气，与其他已放置位置间隔≥1.0格，不卡进墙里。
	 */
	private static Vec3 findSafePosition(ServerLevel level, Vec3 center, List<Vec3> used, int index) {
		double mobWidth = 0.6; // 大多数生物宽度
		double minDist = 1.2;  // 生物之间最小中心距离（避免重叠）
		int maxRadius = 8;     // 最多搜索半径8格

		// 候选位置按距离排序：先从第index个环开始，优先近的位置
		for (int r = 0; r <= maxRadius; r++) {
			// 每圈尝试的位置数：半径越大位置越多
			int pointsInRing = r == 0 ? 1 : Math.max(8, r * 8);
			for (int p = 0; p < pointsInRing; p++) {
				double angle;
				if (r == 0) {
					angle = 0;
				} else {
					angle = (p / (double) pointsInRing) * Math.PI * 2;
				}
				double dx = Math.cos(angle) * (r == 0 ? 0 : r);
				double dz = Math.sin(angle) * (r == 0 ? 0 : r);
				double px = center.x + dx;
				double pz = center.z + dz;

				// 找地面高度（从中心y向下/向上搜索）
				BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
				double py = -1;

				// 优先在玩家所在高度附近搜索（上下5格范围）
				int startY = (int) Math.floor(center.y);
				for (int dy = 0; dy <= 5; dy++) {
					// 先试同高度和更低，再试更高
					int[] tryY = {startY - dy, startY + dy};
					for (int ty : tryY) {
						if (dy == 0 && ty != startY) continue;
						bp.set(px, ty, pz);
						// 检查：脚下是实心，脚和头位置是空气（-64~384范围）
						if (ty < -63 || ty > 382) continue;
						if (level.getBlockState(bp).canBeReplaced()) continue; // 脚下不是实心
						bp.set(px, ty + 1, pz);
						if (!level.getBlockState(bp).canBeReplaced()) continue; // 脚位置有方块
						bp.set(px, ty + 2, pz);
						if (!level.getBlockState(bp).canBeReplaced()) continue; // 头位置有方块
						py = ty + 1; // 站立在方块上表面
						break;
					}
					if (py > 0) break;
				}

				// 如果附近没找到，尝试用Heightmap找地面（跨维度时常用）
				if (py < 0) {
					int surfaceY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
							BlockPos.containing(px, center.y, pz)).getY();
					bp.set(px, surfaceY + 1, pz);
					if (surfaceY > -64
							&& level.getBlockState(bp).canBeReplaced()
							&& level.getBlockState(bp.above()).canBeReplaced()
							&& !level.getBlockState(bp.below()).canBeReplaced()) {
						py = surfaceY + 1;
					}
				}

				if (py < 0) continue;

				Vec3 candidate = new Vec3(px, py, pz);

				// 检查是否与已放置的生物太近
				boolean tooClose = false;
				for (Vec3 u : used) {
					if (candidate.distanceToSqr(u) < minDist * minDist) {
						tooClose = true;
						break;
					}
				}
				if (tooClose) continue;

				// 检查是否与玩家本身重叠（第一个位置离玩家1格以上）
				if (r == 0 && used.isEmpty()) {
					// r=0是玩家自己位置，跳过，从r=1开始
					continue;
				}

				// 检查该位置是否已有其他实体（避免传送进其他生物里）
				AABB box = new AABB(px - mobWidth/2, py, pz - mobWidth/2,
						px + mobWidth/2, py + 2, pz + mobWidth/2);
				if (!level.getEntitiesOfClass(Mob.class, box, e -> e.isAlive()).isEmpty()) {
					continue;
				}

				return candidate;
			}
		}
		return null;
	}

	/**
	 * 同维度安全传送（备用，保持兼容性）。
	 * 找(x,z)位置处最高的可站立点，要求两格高空气。
	 */
	private static void safeTeleport(Mob mob, ServerLevel level, double x, double y, double z) {
		Vec3 safe = findSafePosition(level, new Vec3(x, y, z), new ArrayList<>(), 1);
		if (safe != null) {
			mob.teleportTo(safe.x, safe.y, safe.z);
		} else {
			// 兜底：直接传送
			mob.teleportTo(x, y, z);
		}
	}

	private record MobWithDist(Mob mob, ServerLevel level, double dist, boolean sameDim) {}
}
