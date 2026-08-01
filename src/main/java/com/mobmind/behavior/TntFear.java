package com.mobmind.behavior;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TNT 恐惧：生物会主动远离附近的 TNT；村民会请求玩家拆除。
 */
public final class TntFear {
	private TntFear() {}

	private static final int CHECK_INTERVAL = 20; // 每秒一次
	private static final int TNT_SCAN_RADIUS = 16;
	private static final int FLEE_RADIUS = 14;
	private static final Map<UUID, Long> LAST_VILLAGER_PLEA = new ConcurrentHashMap<>();
	private static final long PLEA_COOLDOWN_MS = 15000;

	public static void tick(MinecraftServer server) {
		long gameTime = server.overworld().getLevelData().getGameTime();
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] TntFear.tick called, gameTime={}", gameTime);
		if (gameTime % CHECK_INTERVAL != 0) return;

		for (ServerLevel level : server.getAllLevels()) {
			Set<UUID> seen = new HashSet<>();
			int checked = 0, fleeCount = 0;
			// 以玩家为中心扫描附近生物，再检查这些生物周边是否有 TNT
			for (ServerPlayer player : level.players()) {
				for (Mob mob : level.getEntitiesOfClass(Mob.class,
						new AABB(player.blockPosition()).inflate(64),
						m -> m.isAlive() && PersonaRegistry.supports(m))) {
					if (!seen.add(mob.getUUID())) continue;
					checked++;
					BlockPos nearestTnt = findNearestTnt(level, mob.blockPosition());
					if (nearestTnt == null) continue;

					// 用水平距离判断，避免 Y 轴差异导致误判
					double dx = mob.getX() - (nearestTnt.getX() + 0.5);
					double dz = mob.getZ() - (nearestTnt.getZ() + 0.5);
					double distHorizSqr = dx * dx + dz * dz;
					if (distHorizSqr > FLEE_RADIUS * FLEE_RADIUS) continue;

					com.mobmind.MobMindMod.LOGGER.info("[MobMind] {} detected TNT at {}, preparing to flee",
						mob.getType().getDescription().getString(), nearestTnt);

					// 正在睡觉的会被炸醒
					if (mob.isSleeping()) {
						mob.stopSleeping();
					}

					Vec3 tntCenter = Vec3.atCenterOf(nearestTnt);
				// flee 持续 15 秒；TntFear 每秒扫描并在 TNT 仍在附近时刷新
				MobMindState.setFleeOrder(mob, tntCenter, gameTime + 300);
				fleeCount++;
					if (mob instanceof Villager villager) {
						requestTntRemoval(villager, nearestTnt, gameTime);
					}
				}
			}
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] TNT fear scan: level={}, checked={}, flee={}",
					level.dimension().toString(), checked, fleeCount);
		}
	}

	/** 寻找生物附近最近的 TNT（方块或已点燃实体） */
	private static BlockPos findNearestTnt(ServerLevel level, BlockPos center) {
		BlockPos nearest = null;
		double bestDist = Double.MAX_VALUE;
		int r = TNT_SCAN_RADIUS;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dy * dy + dz * dz > r * r) continue;
					BlockPos pos = center.offset(dx, dy, dz);
					if (level.getBlockState(pos).is(Blocks.TNT)) {
						double d = pos.distSqr(center);
						if (d < bestDist) {
							bestDist = d;
							nearest = pos;
						}
					}
				}
			}
		}
		// 已点燃的 TNT 实体
		for (var primed : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class,
				new AABB(center).inflate(r))) {
			BlockPos pos = primed.blockPosition();
			double d = pos.distSqr(center);
			if (d < bestDist) {
				bestDist = d;
				nearest = pos;
			}
		}
		return nearest;
	}

	private static void requestTntRemoval(Villager villager, BlockPos tntPos, long gameTime) {
		long now = System.currentTimeMillis();
		UUID vid = villager.getUUID();
		Long last = LAST_VILLAGER_PLEA.get(vid);
		if (last != null && now - last < PLEA_COOLDOWN_MS) return;

		Player nearest = null;
		double bestDist = Double.MAX_VALUE;
		for (Player p : villager.level().players()) {
			double d = p.distanceToSqr(villager);
			if (d < bestDist) {
				bestDist = d;
				nearest = p;
			}
		}
		if (!(nearest instanceof ServerPlayer player) || bestDist > 1024) return;

		com.mobmind.MobMindMod.LOGGER.info("[MobMind] Villager {} requested {} to remove TNT {}",
				villager.getUUID(), player.getGameProfile().name(), tntPos);
		LAST_VILLAGER_PLEA.put(vid, now);
		MobAiService.sendScaredTntPlea(villager, player, tntPos);
	}
}
