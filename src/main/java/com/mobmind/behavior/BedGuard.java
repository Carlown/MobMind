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
	/** playerUuid -> 上次扫描日志时间（避免刷屏） */
	private static final Map<UUID, Long> LAST_SCAN_LOG = new ConcurrentHashMap<>();

	/** 每40tick调用一次 */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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
					MobMindMod.LOGGER.info("[MobMind] 村民 {} 不支持 AI", v.getUUID());
					continue;
				}
				boolean hisBed = isHisBed(v, bedPos);
				MobMindMod.LOGGER.info("[MobMind] 村民 {} isHisBed={} sleepingPos={} home={}",
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
				MobMindMod.LOGGER.info("[MobMind] {} 睡了村民认领的床，村民{}", player.getGameProfile().name(),
						kick ? "准备赶人" : "决定忍了");
				MobAiService.scoldBedThief(player, v, kick);
				break;
			}
			if (!matched && nearby > 0) {
				Long ll = LAST_SCAN_LOG.get(player.getUUID());
				if (ll == null || now - ll > 1200) {
					LAST_SCAN_LOG.put(player.getUUID(), now);
					MobMindMod.LOGGER.info("[MobMind] {} 入睡，16格内有 {} 个村民，但没人认领这张床", player.getGameProfile().name(), nearby);
				}
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
			MobMindMod.LOGGER.info("[MobMind] {} 试图睡村民正在睡的床，村民喝止", player.getGameProfile().name());
			MobAiService.scoldBedThief(player, v, kick);
			return;
		}
	}

	/** 村民的床：正在睡这张，或认领（HOME）的是这张；
	 *  兜底：村民附近 8 格内只有这一张床且他当前没睡别的床，也视为他认领的床 */
	private static boolean isHisBed(Villager v, BlockPos bedPos) {
		if (v.isSleeping() && v.getSleepingPos().map(bedPos::equals).orElse(false)) return true;
		Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isPresent() && home.get().pos().equals(bedPos)) return true;
		if (v.isSleeping()) return false; // 正睡别的床，不是这张
		// 兜底：附近 8 格内只有这一张床
		int bedCount = 0;
		for (BlockPos pos : BlockPos.betweenClosed(bedPos.offset(-8, -4, -8), bedPos.offset(8, 4, 8))) {
			if (v.level().getBlockState(pos).getBlock() instanceof BedBlock) {
				bedCount++;
				if (bedCount > 1) break;
			}
		}
		return bedCount == 1;
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
		MobMindMod.LOGGER.info("[MobMind] 村民把 {} 掀下了床", player.getGameProfile().name());
		MobAiService.bedKickResolved(player, v, true);
	}
}
