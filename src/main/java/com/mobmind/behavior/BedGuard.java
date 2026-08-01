package com.mobmind.behavior;

import com.mobmind.MobMindMod;
import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.Personality;
import com.mobmind.state.MobMindState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民护床：玩家睡了村民认领的床 → 村民 AI 喝止；
 * 几秒后玩家还赖着不起 → 多数村民会直接把玩家掀下床自己躺回去；
 * 少数（尤其善良型）会忍了，不赶人。
 * 右键点击有村民在睡的床，也会挨骂。
 */
public final class BedGuard {
	private BedGuard() {}

	/** 等待裁决的占床事件 */
	private record PendingKick(UUID villagerId, BlockPos bedPos, long decideAt, boolean kick) {}

	/** playerUuid -> 待裁决事件 */
	private static final Map<UUID, PendingKick> PENDING = new ConcurrentHashMap<>();
	/** villagerUuid -> 上次喝止 gameTime（10分钟冷却防刷屏） */
	private static final Map<UUID, Long> LAST_SCOLD = new ConcurrentHashMap<>();
	/** villagerUuid -> 上次被踩踏抱怨 gameTime（防刷屏） */
	private static final Map<UUID, Long> LAST_STEP_SCOLD = new ConcurrentHashMap<>();
	/** playerUuid -> 上次扫描日志时间（避免刷屏） */
	private static final Map<UUID, Long> LAST_SCAN_LOG = new ConcurrentHashMap<>();

	/** 每40tick调用一次 */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			checkVillagersInPlayerBed(player); // 防止村民睡玩家放的床
			checkSleepingVillagerSteppedOn(player, now); // 玩家踩在睡觉村民身上
			if (!player.isSleeping()) {
				PENDING.remove(player.getUUID());
				continue;
			}

			PendingKick pending = PENDING.get(player.getUUID());
			if (pending != null) {
				if (now >= pending.decideAt()) {
					PENDING.remove(player.getUUID());
					resolve(player, pending);
				}
				continue;
			}

			Optional<BlockPos> sleepingPos = player.getSleepingPos();
			if (sleepingPos.isEmpty()) continue;
			BlockPos bedPos = sleepingPos.get();

			// 找把这张床当家的村民
			int nearby = 0;
			boolean matched = false;
			for (Villager v : player.level().getEntitiesOfClass(Villager.class,
					player.getBoundingBox().inflate(16), Entity::isAlive)) {
				nearby++;
				if (!PersonaRegistry.supports(v)) {
					MobMindMod.LOGGER.info("[MobMind] Villager {} does not support AI", v.getUUID());
					continue;
				}
				boolean hisBed = isHisBed(v, bedPos);
				MobMindMod.LOGGER.info("[MobMind] Villager {} isHisBed={} sleepingPos={} home={}",
					v.getUUID(), hisBed, v.getSleepingPos(),
					v.getBrain().getMemory(MemoryModuleType.HOME).map(GlobalPos::toString).orElse("null"));
				if (!hisBed) continue;
				matched = true;

				Long last = LAST_SCOLD.get(v.getUUID());
				if (last != null && now - last < 12000) break;
				LAST_SCOLD.put(v.getUUID(), now);

				Personality p = MobMindState.personalityOf(v);
				boolean goodTempered = p.alignment != null && p.alignmentGood;
				boolean kick = v.getRandom().nextInt(100) < (goodTempered ? 30 : 75);
				PENDING.put(player.getUUID(), new PendingKick(v.getUUID(), bedPos, now + 120, kick));
				MobMindMod.LOGGER.info("[MobMind] {} slept in villager's claimed bed, villager {}", player.getGameProfile().name(),
					kick ? "will kick" : "will tolerate");
				MobAiService.scoldBedThief(player, v, kick);
				break;
			}
			if (!matched && nearby > 0) {
				Long ll = LAST_SCAN_LOG.get(player.getUUID());
				if (ll == null || now - ll > 1200) {
					LAST_SCAN_LOG.put(player.getUUID(), now);
					MobMindMod.LOGGER.info("[MobMind] {} went to sleep, {} villagers within 16 blocks, but none claimed this bed", player.getGameProfile().name(), nearby);
				}
			}
		}
	}

	/**
	 * 防止村民睡玩家放的床：
	 * 1. 村民正在睡玩家床 → 赶起
	 * 2. 村民 HOME 指向玩家床 → 清 HOME（防止反复去睡玩家床，让村民重新认领村庄里的床）
	 * 解决"村民隔门进玩家家睡觉"的问题。
	 */
	private static void checkVillagersInPlayerBed(ServerPlayer player) {
		for (Villager v : player.level().getEntitiesOfClass(Villager.class,
				player.getBoundingBox().inflate(32), Entity::isAlive)) {
			if (!PersonaRegistry.supports(v)) continue;
			// 1. 村民正在睡玩家放的床 → 赶起
			Optional<BlockPos> sleepPos = v.getSleepingPos();
			if (sleepPos.isPresent() && HouseGuard.isPlayerPlaced(sleepPos.get())) {
				v.stopSleeping();
				MobMindMod.LOGGER.info("[MobMind] Villager {} was sleeping in player-placed bed at {}, evicted",
						v.getUUID(), sleepPos.get());
			}
			// 2. 村民 HOME 指向玩家床 → 清 HOME（防止反复去睡玩家床）
			Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
			if (home.isPresent() && home.get().dimension().equals(player.level().dimension())
					&& HouseGuard.isPlayerPlaced(home.get().pos())) {
				v.getBrain().eraseMemory(MemoryModuleType.HOME);
				MobMindMod.LOGGER.info("[MobMind] Villager {} had HOME pointing to player-placed bed at {}, HOME cleared",
						v.getUUID(), home.get().pos());
			}
		}
	}

	/** 右键点击有村民在睡的床（进不去的情况）也会挨骂 */
	public static void tryScoldOnClick(ServerPlayer player, Level level, BlockPos bedPos) {
		if (!(level.getBlockState(bedPos).getBlock() instanceof BedBlock)) return;
		long now = level.getLevelData().getGameTime();
		for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(bedPos).inflate(6), Entity::isAlive)) {
			if (!PersonaRegistry.supports(v)) continue;
			if (!isHisBed(v, bedPos)) continue;

			Long last = LAST_SCOLD.get(v.getUUID());
			if (last != null && now - last < 12000) return;
			LAST_SCOLD.put(v.getUUID(), now);

			Personality p = MobMindState.personalityOf(v);
			boolean goodTempered = p.alignment != null && p.alignmentGood;
			boolean kick = v.getRandom().nextInt(100) < (goodTempered ? 30 : 75);
			MobMindMod.LOGGER.info("[MobMind] {} tried to sleep in bed villager is sleeping in, villager scolded", player.getGameProfile().name());
			MobAiService.scoldBedThief(player, v, kick);
			return;
		}
	}

	/**
	 * 检测玩家是否站在/跳在正在睡觉的村民身上。
	 * 如果玩家与睡觉村民的碰撞箱有重叠，且不是创造/旁观者模式，村民会醒来抱怨。
	 */
	private static void checkSleepingVillagerSteppedOn(ServerPlayer player, long now) {
		if (player.isSpectator() || player.isCreative()) return;

		// 玩家碰撞箱稍微扩大一点，更容易检测到踩踏/跳跃
		AABB playerBox = player.getBoundingBox().inflate(0.2);

		// 找附近8格内正在睡觉的村民
		AABB searchBox = player.getBoundingBox().inflate(8.0);
		for (Villager v : player.level().getEntitiesOfClass(Villager.class, searchBox, Entity::isAlive)) {
			if (!PersonaRegistry.supports(v)) continue;
			if (!v.isSleeping()) continue;

			// 检测碰撞箱是否重叠（玩家站在村民身上/在村民身上跳）
			if (!playerBox.intersects(v.getBoundingBox())) continue;

			// 冷却：同一村民30秒内不重复抱怨
			Long last = LAST_STEP_SCOLD.get(v.getUUID());
			if (last != null && now - last < 600) continue;
			LAST_STEP_SCOLD.put(v.getUUID(), now);

			MobMindMod.LOGGER.info("[MobMind] Player {} is standing/jumping on sleeping villager {}",
					player.getGameProfile().name(), v.getUUID());

			// 村民醒来抱怨
			v.stopSleeping();
			MobAiService.scoldSleepDisturbance(player, v);
			return; // 一次只处理一个村民
		}
	}

	/** 村民的床：正在睡这张，或认领（HOME）的是这张；
	 *  兜底1：附近 8 格内只有这一张床且他当前没睡别的床；
	 *  兜底2：在村庄结构内 + 床在村民 16 格内（村庄里的床都属于村民） */
	private static boolean isHisBed(Villager v, BlockPos bedPos) {
		// 玩家自己放的床不算村民的床（优先检查床的两半，即使村民 HOME 指向它）
		if (HouseGuard.isPlayerPlaced(bedPos)) return false;
		BlockState bedState = v.level().getBlockState(bedPos);
		if (bedState.getBlock() instanceof BedBlock) {
			var part = bedState.getValue(net.minecraft.world.level.block.BedBlock.PART);
			net.minecraft.core.Direction facing = bedState.getValue(net.minecraft.world.level.block.BedBlock.FACING);
			BlockPos otherHalf = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
					? bedPos.relative(facing.getOpposite())
					: bedPos.relative(facing);
			if (HouseGuard.isPlayerPlaced(otherHalf)) return false;
		}

		if (v.isSleeping() && v.getSleepingPos().map(bedPos::equals).orElse(false)) return true;
		Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isPresent() && home.get().pos().equals(bedPos)) return true;
		if (v.isSleeping()) return false; // 正睡别的床，不是这张

		// 兜底1：附近 8 格内只有这一张床
		int bedCount = 0;
		for (BlockPos pos : BlockPos.betweenClosed(bedPos.offset(-8, -4, -8), bedPos.offset(8, 4, 8))) {
			if (v.level().getBlockState(pos).getBlock() instanceof BedBlock) {
				bedCount++;
				if (bedCount > 1) break;
			}
		}
		if (bedCount == 1) return true;

		// 兜底2：床在村庄结构内 + 村民离床 16 格内（村庄里的床村民会护）
		double distSqr = v.distanceToSqr(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5);
		if (distSqr < 256.0 && isInsideVillageStructure(v.level(), bedPos)) return true;

		return false;
	}

	/** 检查位置是否在村庄结构内（通过 StructureManager 检测结构 ID 含 "village"） */
	private static boolean isInsideVillageStructure(net.minecraft.world.level.Level level, BlockPos pos) {
		try {
			if (!(level instanceof ServerLevel sl)) return false;
			var sm = sl.structureManager();
			var map = sm.getAllStructuresAt(pos);
			if (map == null || map.isEmpty()) return false;
			for (Object struct : map.keySet()) {
				String id = getStructureId(struct, sl);
				if (id != null && id.toLowerCase().contains("village")) return true;
			}
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] BedGuard structure detection failed: {}", e.getMessage());
		}
		return false;
	}

	/** 通过直接API获取Structure的注册ID（不再使用反射） */
	private static String getStructureId(Object structure, ServerLevel level) {
		try {
			var registry = level.registryAccess()
					.lookup(net.minecraft.core.registries.Registries.STRUCTURE).orElse(null);
			if (registry == null) return null;
			@SuppressWarnings("unchecked")
			var key = registry.getKey((net.minecraft.world.level.levelgen.structure.Structure) structure);
			return key != null ? key.toString() : null;
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] BedGuard failed to get structure ID: {}", e.getMessage());
		}
		return null;
	}

	/** 6秒后玩家仍赖在床上：赶或不赶 */
	private static void resolve(ServerPlayer player, PendingKick pending) {
		if (!(((ServerLevel) player.level()).getEntity(pending.villagerId()) instanceof Villager v) || !v.isAlive()) return;
		if (!pending.kick()) {
			MobAiService.bedKickResolved(player, v, false);
			return;
		}
		player.stopSleeping(); // 掀下床
		if (player.level().getBlockState(pending.bedPos()).getBlock() instanceof BedBlock) {
			v.startSleeping(pending.bedPos()); // 村民自己躺回去
		}
		MobMindMod.LOGGER.info("[MobMind] Villager kicked {} out of bed", player.getGameProfile().name());
		MobAiService.bedKickResolved(player, v, true);
	}
}
