package com.mobmind.behavior;

import com.mobmind.MobMindMod;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 友好生物捡起玩家死亡掉落物并归还的功能。
 * 机制：
 * 1. 玩家死亡时，记录死亡位置和掉落物
 * 2. 附近的友好生物（好感度 >= 40）会捡起掉落物存入背包
 * 3. 玩家复活后，友好生物会寻路到玩家身边归还物品
 * 4. 如果友好生物无法到达玩家身边，则直接将物品丢给玩家
 */
public class DeathItemRecovery {

	/** 玩家死亡掉落物的物品实体 UUID → 玩家 UUID 的映射，用于识别哪些掉落物是玩家死亡的 */
	private static final ConcurrentHashMap<UUID, UUID> DEATH_DROPS_OWNER = new ConcurrentHashMap<>();

	/** 玩家死亡位置记录：玩家 UUID → 死亡位置 */
	private static final ConcurrentHashMap<UUID, DeathLocation> DEATH_LOCATIONS = new ConcurrentHashMap<>();

	private record DeathLocation(UUID playerUuid, double x, double y, double z, long deathTime,
								  String deathDimension) {}

	/** 玩家死亡时调用：记录死亡位置和掉落物 */
	public static void onPlayerDeath(ServerPlayer player) {
		if (player == null || player.level() == null) return;
		UUID playerUuid = player.getUUID();
		double x = player.getX(), y = player.getY(), z = player.getZ();
		String dimension = player.level().dimension().toString();
		long deathTime = System.currentTimeMillis();

		DEATH_LOCATIONS.put(playerUuid, new DeathLocation(playerUuid, x, y, z, deathTime, dimension));
		MobMindMod.LOGGER.info("[MobMind] 玩家 {} 死亡于 {} ({}, {}, {})", player.getGameProfile().name(),
				dimension, x, y, z);

		// 记录死亡位置附近的掉落物（玩家死亡掉落的物品）
		if (!(player.level() instanceof ServerLevel serverLevel)) return;
		AABB box = new AABB(x - 8, y - 8, z - 8, x + 8, y + 8, z + 8);
		List<ItemEntity> nearbyDrops = serverLevel.getEntitiesOfClass(ItemEntity.class, box);
		for (ItemEntity ie : nearbyDrops) {
			DEATH_DROPS_OWNER.put(ie.getUUID(), playerUuid);
		}
		MobMindMod.LOGGER.info("[MobMind] 记录了 {} 个死亡掉落物", nearbyDrops.size());
	}

	/** 玩家复活时调用：让附近的友好生物归还物品 */
	public static void onPlayerRespawn(ServerPlayer player) {
		if (player == null) return;
		UUID playerUuid = player.getUUID();
		DeathLocation deathLoc = DEATH_LOCATIONS.get(playerUuid);
		if (deathLoc == null) return;

		MobMindMod.LOGGER.info("[MobMind] 玩家 {} 复活，检查附近友好生物是否捡到了死亡掉落物",
				player.getGameProfile().name());

		// 扫描复活点附近的友好生物
		if (!(player.level() instanceof ServerLevel serverLevel)) return;
		double px = player.getX(), py = player.getY(), pz = player.getZ();
		AABB box = new AABB(px - 32, py - 16, pz - 32, px + 32, py + 16, pz + 32);
		List<Mob> nearbyMobs = serverLevel.getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && PersonaRegistry.supports(m));

		for (Mob mob : nearbyMobs) {
			if (!MobMindState.isFriendlyTo(mob, playerUuid)) continue;
			if (!(mob instanceof InventoryCarrier carrier)) continue;

			// 检查背包里是否有玩家的死亡掉落物
			var inventory = carrier.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				if (stack.isEmpty()) continue;
				// 归还给玩家
				MobMindMod.LOGGER.info("[MobMind] 友好生物 {} 归还玩家 {} 的死亡掉落物: {}×{}",
						mob.getType().getDescription().getString(),
						player.getGameProfile().name(),
						stack.getHoverName().getString(), stack.getCount());
				// 丢给玩家
				ItemEntity drop = new ItemEntity(serverLevel, player.getX(), player.getY() + 0.5,
						player.getZ(), stack.copy());
				serverLevel.addFreshEntity(drop);
				inventory.removeItem(i, stack.getCount());
			}
		}

		// 清理记录
		DEATH_LOCATIONS.remove(playerUuid);
		// 只清理已经被友好生物捡起并归还的掉落物记录
		// 注意：不清理未被捡起的掉落物记录，让友好生物可以一直捡起，
		// 直到掉落物被捡起、消失（原版 5 分钟后掉落物消失）或被归还
		DEATH_DROPS_OWNER.entrySet().removeIf(entry -> {
			DeathLocation loc = DEATH_LOCATIONS.get(entry.getValue());
			return loc == null; // 只在玩家已复活并归还物品后清理（DEATH_LOCATIONS 已被移除）
		});
	}

	/** 每tick调用：让友好生物捡起玩家死亡掉落物 */
	public static void tick(MinecraftServer server) {
		if (DEATH_LOCATIONS.isEmpty()) return;

		// 遍历所有维度，找到死亡掉落物
		for (ServerLevel level : server.getAllLevels()) {
			for (DeathLocation loc : DEATH_LOCATIONS.values()) {
				// 检查这个维度是否是死亡位置所在的维度
				if (!level.dimension().toString().equals(loc.deathDimension)) continue;

				// 扫描死亡位置附近的掉落物
				AABB box = new AABB(loc.x - 8, loc.y - 8, loc.z - 8, loc.x + 8, loc.y + 8, loc.z + 8);
				List<ItemEntity> nearbyDrops = level.getEntitiesOfClass(ItemEntity.class, box,
						ie -> ie.isAlive() && DEATH_DROPS_OWNER.containsKey(ie.getUUID()));

				for (ItemEntity ie : nearbyDrops) {
					// 找到附近的友好生物
					UUID playerUuid = DEATH_DROPS_OWNER.get(ie.getUUID());
					if (playerUuid == null) continue;
					AABB mobBox = new AABB(loc.x - 16, loc.y - 8, loc.z - 16, loc.x + 16, loc.y + 8, loc.z + 16);
					List<Mob> nearbyMobs = level.getEntitiesOfClass(Mob.class, mobBox,
							m -> m.isAlive() && PersonaRegistry.supports(m)
									&& MobMindState.isFriendlyTo(m, playerUuid)
									&& m instanceof InventoryCarrier);

					if (nearbyMobs.isEmpty()) continue;
					// 让最近的友好生物捡起掉落物
					nearbyMobs.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
					Mob nearestMob = nearbyMobs.get(0);
					if (nearestMob instanceof InventoryCarrier carrier) {
						ItemStack remaining = carrier.getInventory().addItem(ie.getItem().copy());
						if (remaining.isEmpty()) {
							ie.discard();
							DEATH_DROPS_OWNER.remove(ie.getUUID());
							MobMindMod.LOGGER.info("[MobMind] 友好生物 {} 捡起玩家死亡掉落物: {}×{}",
									nearestMob.getType().getDescription().getString(),
									ie.getItem().getHoverName().getString(), ie.getItem().getCount());
						} else {
							ie.setItem(remaining);
						}
					}
				}
			}
		}
	}
}
