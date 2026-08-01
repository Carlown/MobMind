package com.mobmind.behavior;

import com.mobmind.MobMindMod;
import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领地守卫：
 * - 村民房屋守卫：玩家在村庄结构内破坏任何方块/开容器 → 村民过来抱怨（整个房子都管，不只是门/床）
 * - 猪灵堡垒守卫：玩家在下界挖金块/开容器 → 猪灵/猪灵蛮兵愤怒攻击
 * - 林地府邸守卫：玩家在林地府邸内破坏方块/开容器 → 卫道士/唤魔者施法攻击
 * - 女巫小屋守卫：玩家在女巫小屋内破坏方块/开容器 → 女巫扔药水攻击
 * - 沉船/海底废墟守卫：玩家破坏方块/开战利品箱 → 溺尸冲过来攻击
 *
 * 触发条件：必须在自然生成的对应结构内（通过 StructureManager.getAllStructuresAt 检测结构 ID）
 */
public final class HouseGuard {
	private HouseGuard() {}

	/** 冷却：同一村民30秒内不会因为同一个玩家重复抱怨（连续破坏会递增次数但不会太频繁） */
	private static final long SCOLD_COOLDOWN = 600; // 30秒 = 600 ticks
	/** 好感度降低值 */
	private static final int FRIENDSHIP_PENALTY_BREAK = -5;
	private static final int FRIENDSHIP_PENALTY_OPEN = -3;
	/** 领地范围：以村民的床/工作站点为中心的半径 */
	private static final double HOME_RADIUS = 10.0;
	private static final double JOB_RADIUS = 8.0;
	/** 结构守卫检测范围 */
	private static final double STRUCTURE_GUARD_RANGE = 16.0;
	private static final long STRUCTURE_GUARD_COOLDOWN_MS = 6000;

	/** villagerUuid -> 上次喝止 gameTime */
	private static final Map<UUID, Long> LAST_SCOLD = new ConcurrentHashMap<>();
	/** villagerUuid -> 上次使用工作方块喝止 gameTime（单独冷却） */
	private static final Map<UUID, Long> LAST_JOB_USE_SCOLD = new ConcurrentHashMap<>();
	/** villagerUuid -> 上次因为床被破坏喝止 gameTime（床是最重要的私人财产，独立冷却，更短） */
	private static final Map<UUID, Long> LAST_BED_SCOLD = new ConcurrentHashMap<>();
	private static final long BED_SCOLD_COOLDOWN = 200; // 10秒

	/** 清除指定玩家的连续作案计数（玩家死亡/重生后调用，给玩家一个冷却期） */
	public static void clearOffenseCount(UUID playerId) {
		OFFENSE_COUNT.remove(playerId);
	}
	/** ironGolemUuid -> 上次过来看询 gameTime（30秒冷却，避免同一铁傀儡反复触发） */
	private static final Map<UUID, Long> LAST_GOLEM_INVESTIGATE = new ConcurrentHashMap<>();
	private static final long GOLEM_COOLDOWN = 600; // 30秒
	private static final double GOLEM_SENSE_RANGE = 16.0;
	/** playerUuid -> 连续作案计数 */
	private static final Map<UUID, Integer> OFFENSE_COUNT = new ConcurrentHashMap<>();
	/** 上次衰减计数的时间 */
	private static long lastDecayTick = 0;

	/** 记录玩家放置的方块位置（排除自己种的菜/放的方块被误判）。
	 *  实际存储在 MobMindState 中以持久化到存档，避免重启后玩家自己放的栅栏/床被误判为村庄财产。 */
	public static void markPlayerPlaced(BlockPos pos) {
		com.mobmind.state.MobMindState.markPlayerPlaced(pos);
	}

	/** 检查方块是否是玩家放置的 */
	public static boolean isPlayerPlaced(BlockPos pos) {
		return com.mobmind.state.MobMindState.isPlayerPlaced(pos);
	}

	/** 检查方块是否是容器（可以存放物品） */
	public static boolean isContainerBlock(BlockState state) {
		Block block = state.getBlock();
		String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
		return block instanceof ChestBlock
				|| block instanceof BarrelBlock
				|| block instanceof ShulkerBoxBlock
				|| block instanceof DispenserBlock
				|| block instanceof DropperBlock
				|| block instanceof HopperBlock
				|| block instanceof TrappedChestBlock
				|| blockId.contains("chest_boat");
	}

	/** 检查方块是否是农作物（村民种植的作物：小麦/胡萝卜/土豆/甜菜根/西瓜/南瓜/浆果糕/火焰花等） */
	public static boolean isCropBlock(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock              // 小麦、胡萝卜、土豆、甜菜根、火焰花
				|| block instanceof StemBlock       // 西瓜茎、南瓜茎
				|| block instanceof SweetBerryBushBlock) {
			return true;
		}
		// 西瓜/南瓜/下界疣在 MC 26.2 中可能改类名，用 Block ID 兜底匹配
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return id.equals("melon")              // 西瓜
				|| id.equals("pumpkin")        // 南瓜
				|| id.equals("carved_pumpkin") // 雕刻南瓜
				|| id.equals("jack_o_lantern") // 南瓜灯
				|| id.equals("nether_wart")    // 下界疣
				|| id.equals("pitcher_crop");  // 投掷植物
	}

	/** 检查方块是否是村民的工作站点（破坏会失业） */
	public static boolean isJobBlock(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof ComposterBlock           // 农民
				|| block instanceof BlastFurnaceBlock  // 盔甲匠
				|| block instanceof SmokerBlock         // 屠夫
				|| block instanceof CartographyTableBlock // 制图师
				|| block instanceof GrindstoneBlock    // 武器匠
				|| block instanceof StonecutterBlock    // 石匠
				|| block instanceof LoomBlock           // 牧羊人
				|| block instanceof BrewingStandBlock   // 牧师
				|| block instanceof LecternBlock        // 图书管理员
				|| block instanceof net.minecraft.world.level.block.CraftingTableBlock) { // 工作台（通用）
			return true;
		}
		// 兜底：用 Block ID 匹配（部分类名在 MC 26.2 中可能变化）
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return id.equals("composter")       // 农民（堆肥桶）
				|| id.equals("fletching_table")    // 制箭师
				|| id.equals("smithing_table")  // 工具匠
				|| id.equals("crafting_table")  // 工作台
				|| id.equals("barrel");          // 渔夫（同时也是容器）
	}

	/** 判断工作方块是否属于该村民的职业（如酿造台只属于牧师，农民不能说"是我的"） */
	private static boolean isJobBlockForProfession(Villager villager, BlockState state) {
		var profHolder = villager.getVillagerData().profession();
		var keyOpt = profHolder.unwrapKey();
		if (keyOpt.isEmpty()) return false;
		String profId = keyOpt.get().identifier().getPath();
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return switch (profId) {
			case "farmer"      -> blockId.equals("composter");          // 堆肥桶
			case "armorer"      -> blockId.equals("blast_furnace");      // 高炉
			case "butcher"      -> blockId.equals("smoker");             // 烟熏炉
			case "cartographer" -> blockId.equals("cartography_table"); // 制图台
			case "weaponsmith"  -> blockId.equals("grindstone");        // 砂轮
			case "mason"        -> blockId.equals("stonecutter");       // 切石机
			case "shepherd"     -> blockId.equals("loom");               // 织布机
			case "cleric"       -> blockId.equals("brewing_stand");     // 酿造台
			case "librarian"    -> blockId.equals("lectern");           // 讲台
			case "fletcher"     -> blockId.equals("fletching_table");   // 制箭台
			case "toolsmith"    -> blockId.equals("smithing_table");    // 锻造台
			case "fisherman"    -> blockId.equals("barrel");            // 木桶
			case "nitwit", "none" -> false; // 傻子/无业没有工作方块
			default -> false;
		};
	}

	/** 检查方块是否是村庄公共财产（干草捆、村庄道路、炼药锅、钟、栅栏、火把、营火、水井的水等）
	 *  注意：普通建筑方块（圆石、砂岩、木板、石头、楼梯、台阶等）不在这里——它们属于房屋结构，通过房屋claim来保护 */
	public static boolean isVillageProperty(BlockState state) {
		Block block = state.getBlock();
		// 所有类型的炼药锅（普通/水/岩浆/细雪炼药锅都是炼药锅）
		if (block instanceof AbstractCauldronBlock) return true;
		// 所有栅栏（橡木/云杉/白桦/丛林/金合欢/深色橡木/绯红/诡异木栅栏）
		if (block instanceof net.minecraft.world.level.block.FenceBlock) return true;
		// 栅栏门
		if (block instanceof net.minecraft.world.level.block.FenceGateBlock) return true;
		// 所有火把（普通火把/墙壁火把/灵魂火把/灵魂墙壁火把）
		if (block instanceof net.minecraft.world.level.block.TorchBlock) return true;
		// 营火/灵魂营火
		if (block instanceof net.minecraft.world.level.block.CampfireBlock) return true;
		// 围墙方块（圆石墙/苔石墙/砂岩墙/砖墙等——这些是栅栏式的墙，不是房屋墙壁）
		if (block instanceof net.minecraft.world.level.block.WallBlock) return true;
		// 床（村民的床）
		if (block instanceof net.minecraft.world.level.block.BedBlock) return true;
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return id.equals("hay_block")           // 干草捆（村民粮食储备）
				|| id.equals("dirt_path")       // 草径/村庄道路
				|| id.equals("grass_path")      // 旧版草径
				|| id.equals("gravel")          // 村庄道路（砾石路）
				|| id.equals("cauldron")        // 炼药锅（兼容ID匹配）
				|| id.equals("water_cauldron")
				|| id.equals("lava_cauldron")
				|| id.equals("powder_snow_cauldron")
				|| id.equals("bell")            // 钟（村庄警报器/集会点）
				|| id.equals("campfire")        // 营火（兼容ID匹配）
				|| id.equals("soul_campfire")
				|| id.equals("torch")           // 火把（兼容ID匹配）
				|| id.equals("wall_torch")
				|| id.equals("soul_torch")
				|| id.equals("soul_wall_torch")
				|| id.equals("water");          // 水（水井/水池水源——填水井会被骂）
				// 注意：cobblestone/mossy_cobblestone/sandstone等是普通建筑材料，村民房子大量使用，不在这里保护
				// 楼梯(StairBlock)、台阶(SlabBlock)也是普通建筑材料，不在这里保护
	}

	/** 记录已被打开过的自然战利品箱位置，避免开箱后 lootTable 被清空导致二次检测失败 */
	private static final Set<Long> OPENED_NATURAL_CHESTS = ConcurrentHashMap.newKeySet();

	private static long posKey(BlockPos pos) {
		return ((long) pos.getX() & 0x3FFFFFFL) << 38
				| ((long) pos.getZ() & 0x3FFFFFFL) << 12
				| ((long) pos.getY() & 0xFFFL);
	}

	/**
	 * 判断某个位置是否属于某村民的领地（家或工作站点附近）。
	 */
	private record VillagerClaim(Villager villager, double dist) {}

	private static VillagerClaim findClaimant(ServerLevel level, BlockPos pos, ServerPlayer player) {
		return findClaimant(level, pos, player, false);
	}

	/**
	 * 查找应触发的村民。
	 * @param anyNearby true=附近任何村民都算（用于自然战利品箱/农作物/工作方块/村庄公共设施）
	 */
	private static VillagerClaim findClaimant(ServerLevel level, BlockPos pos, ServerPlayer player, boolean anyNearby) {
		boolean inVillage = isInsideVillage(level, pos);
		double searchRadius = (inVillage || anyNearby) ? 24.0 : HOME_RADIUS + 4;
		AABB searchBox = new AABB(pos).inflate(searchRadius);
		List<Villager> villagers = level.getEntitiesOfClass(Villager.class, searchBox, Entity::isAlive);
		VillagerClaim best = null;

		for (Villager v : villagers) {
			if (!PersonaRegistry.supports(v)) continue;
			if (v.getTarget() != null) continue; // 正在战斗中不管

			// 1. 检查村民的家/工作站点是否在破坏点附近（精准 claim）
			Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
			Optional<GlobalPos> job = v.getBrain().getMemory(MemoryModuleType.JOB_SITE);
			double homeDist = home.map(h -> Math.sqrt(h.pos().distSqr(pos))).orElse(Double.MAX_VALUE);
			double jobDist = job.map(j -> Math.sqrt(j.pos().distSqr(pos))).orElse(Double.MAX_VALUE);
			double claimDist = Math.min(homeDist, jobDist);
			boolean hasClaim = claimDist <= HOME_RADIUS;

			// 2. 玩家与村民的实际距离（用于感知判断）
			double distToPlayerSqr = v.distanceToSqr(player);
			double distToPosSqr = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			double minDistSqr = Math.min(distToPlayerSqr, distToPosSqr);

			// 感知范围：村庄内或 anyNearby 时放宽到16格（能看到/听到破坏声），其他情况8格
		double senseRangeSqr = (inVillage || anyNearby) ? 256.0 : 64.0; // 16格 vs 8格
			boolean canSense = v.hasLineOfSight(player) || minDistSqr < senseRangeSqr;
			boolean shouldTrigger = (hasClaim || inVillage || anyNearby) && canSense;

			if (shouldTrigger) {
				if (best == null || minDistSqr < best.dist) {
					best = new VillagerClaim(v, minDistSqr);
				}
			}
		}
		return best;
	}

	/** 玩家破坏了方块：检查所有结构守卫 */
	public static void onBlockBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		if (player.isSpectator() || player.isCreative()) return;

		boolean isContainer = isContainerBlock(state);
		boolean isCrop = isCropBlock(state);
		boolean isJob = isJobBlock(state);
		boolean isVillageProp = isVillageProperty(state);
		String structId = getStructureIdAt(level, pos);
		boolean inVillage = structId != null && structId.toLowerCase().contains("village");
		boolean inNaturalLootStruct = isInNaturalLootStructureById(structId);

		// 在自然战利品结构（下界遗迹/地牢/神殿等）内且不在村庄中：村民不管
		// 如果同时在村庄内（结构重叠），村民还是会管
		boolean skipVillager = inNaturalLootStruct && !inVillage;

		if (!skipVillager) {
			// 保护范围：
			// 1. 容器/农作物/工作方块/村庄公共设施（干草捆/道路/钟/床/栅栏/火把等）→ 总是保护
			// 2. 在自然生成的村庄结构内 → 任何非玩家放置的方块都保护（包括圆石/木板/石头/楼梯/台阶等房屋建筑材料）
			//    这样挖村民房子的墙壁/屋顶/地板都会被村民阻止
			boolean isVillageRelated = isContainer || isCrop || isJob || isVillageProp || inVillage;
			// 玩家自己放的方块（自己造的房子/种的菜/放的火把等）一律不触发
			// 床有两半(HEAD+FOOT)：玩家放床只标记了一半(getClickedPos)，拆另一半时也要识别为玩家放置
			boolean playerPlaced = isPlayerPlaced(pos);
			if (!playerPlaced && state.getBlock() instanceof BedBlock) {
				var part = state.getValue(BedBlock.PART);
				net.minecraft.core.Direction facing = state.getValue(BedBlock.FACING);
				BlockPos otherHalf = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
						? pos.relative(facing.getOpposite())
						: pos.relative(facing);
				if (isPlayerPlaced(otherHalf)) playerPlaced = true;
			}
			if (playerPlaced) isVillageRelated = false;

			// 床是村民最重要的私人财产——单独处理，优先找床的主人，独立冷却
			boolean isBed = state.getBlock() instanceof BedBlock;
			if (isBed && !playerPlaced) {
				checkVillagerBedBreak(player, level, pos, state);
				// 村庄内破坏床 → 铁傀儡也过来
				if (inVillage) {
					triggerVillageGolemGuard(player, level, pos, state, false, false, true);
				}
			} else if (isVillageRelated) {
				// 公共财产（钟、道路、水井、干草捆等）：用anyNearby=true + fallback找最近村民，和容器/工作方块一致
				if (isVillageProp && !isContainer && !isJob && !isCrop) {
					checkVillagerVillagePropBreak(player, level, pos, state, inVillage);
				} else {
					checkVillagerScold(player, level, pos, state, isContainer, false, isVillageRelated, isJob, isVillageProp, isCrop, inVillage);
				}
				// 村庄内搞破坏 → 铁傀儡村庄守卫也会过来询问/警告
				if (inVillage) {
					triggerVillageGolemGuard(player, level, pos, state, isContainer, isJob, isVillageProp);
				}
			}
			// 破坏后从玩家放置记录中移除（床有两半，要一起清，避免留下残留标记）
			com.mobmind.state.MobMindState.removePlayerPlaced(pos);
			if (state.getBlock() instanceof BedBlock) {
				var part = state.getValue(BedBlock.PART);
				net.minecraft.core.Direction facing = state.getValue(BedBlock.FACING);
				BlockPos otherHalf = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
						? pos.relative(facing.getOpposite())
						: pos.relative(facing);
				com.mobmind.state.MobMindState.removePlayerPlaced(otherHalf);
			}
		}

		// 通用结构守卫
		if (structId == null) return;
		String lowerId = structId.toLowerCase();
		String blockName = getBlockName(state);

		if (lowerId.contains("mansion")) {
			triggerStructureGuards(player, level, pos, 1, 1, blockName);
		} else if (lowerId.contains("swamp_hut") || lowerId.contains("witch_hut")) {
			triggerStructureGuards(player, level, pos, 2, 1, blockName);
		} else if (lowerId.contains("shipwreck") || lowerId.contains("ocean_ruin")) {
			triggerStructureGuards(player, level, pos, 3, 1, blockName);
		} else if (lowerId.contains("ruined_portal") || lowerId.contains("fortress")) {
			// 下界传送门遗迹/下界要塞：僵尸猪灵
			triggerNetherGuards(player, level, pos, blockName);
		} else if (lowerId.contains("dungeon") || lowerId.contains("desert_pyramid")
				|| lowerId.contains("jungle_temple") || lowerId.contains("trial_chambers")) {
			// 地牢/沙漠神殿/丛林神庙/试炼密室：附近怪物
			triggerMonsterGuards(player, level, pos, blockName);
		}
	}

	/** 玩家右键方块：检查所有结构守卫 */
	public static void onUseBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (player.isSpectator()) return;

		BlockState state = level.getBlockState(pos);
		boolean isContainer = isContainerBlock(state);
		boolean isJob = isJobBlock(state);
		// 调试日志：右键任何容器/工作方块时记录
		if (isContainer || isJob) {
			String bid = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			MobMindMod.LOGGER.info("[MobMind] onUseBlock: {} at {} isContainer={} isJob={}",
					bid, pos, isContainer, isJob);
		}
		// 只处理容器和工作方块，其他方块不触发
		if (!isContainer && !isJob) return;

		String structId = getStructureIdAt(level, pos);
		boolean inVillage = structId != null && structId.toLowerCase().contains("village");
		boolean inNaturalLootStruct = isInNaturalLootStructureById(structId);

		// 在自然战利品结构内且不在村庄中：村民不管
		boolean skipVillager = inNaturalLootStruct && !inVillage;

		if (!skipVillager) {
			if (isContainer) {
				checkVillagerContainerSnoop(player, level, pos, state);
				// 在村庄内翻箱子 → 铁傀儡村庄守卫也会过来询问
				if (inVillage) {
					triggerVillageGolemGuard(player, level, pos, state, true, false, false);
				}
			}
			if (isJob) {
				checkVillagerJobBlockUse(player, level, pos, state);
			}
		}

		// 通用结构守卫
		if (structId == null) return;
		String lowerId = structId.toLowerCase();
		String containerName = getContainerName(state);

		if (lowerId.contains("mansion")) {
			triggerStructureGuards(player, level, pos, 1, 2, containerName);
		} else if (lowerId.contains("swamp_hut") || lowerId.contains("witch_hut")) {
			triggerStructureGuards(player, level, pos, 2, 2, containerName);
		} else if (lowerId.contains("shipwreck") || lowerId.contains("ocean_ruin")) {
			triggerStructureGuards(player, level, pos, 3, 2, containerName);
		} else if (lowerId.contains("ruined_portal") || lowerId.contains("fortress")) {
			triggerNetherGuards(player, level, pos, containerName);
		} else if (lowerId.contains("dungeon") || lowerId.contains("desert_pyramid")
				|| lowerId.contains("jungle_temple") || lowerId.contains("trial_chambers")) {
			triggerMonsterGuards(player, level, pos, containerName);
		}
	}

	/** 村民喝止：整个村庄结构内破坏任何方块都触发 */
	private static void checkVillagerScold(ServerPlayer player, ServerLevel level, BlockPos pos,
										   BlockState state, boolean isContainer, boolean isHouse) {
		checkVillagerScold(player, level, pos, state, isContainer, isHouse, false, false, false, false, false);
	}

	/**
	 * 村民喝止
	 * @param anyNearby true=放宽到附近任何村民都能感知
	 * @param isJob 是否是工作方块
	 * @param isVillageProp 是否是村庄公共财产（干草捆/道路）
	 * @param isCrop 是否是农作物
	 * @param inVillage 是否在村庄结构内
	 */
	private static void checkVillagerScold(ServerPlayer player, ServerLevel level, BlockPos pos,
										   BlockState state, boolean isContainer, boolean isHouse,
										   boolean anyNearby, boolean isJob, boolean isVillageProp, boolean isCrop, boolean inVillage) {
		VillagerClaim claim = findClaimant(level, pos, player, anyNearby);
		if (claim == null) return;
		// 农作物只有农民才管
		if (isCrop) {
			var profHolder = claim.villager().getVillagerData().profession();
			var keyOpt = profHolder.unwrapKey();
			if (keyOpt.isEmpty() || !keyOpt.get().identifier().getPath().equals("farmer")) return;
		}

		long now = level.getLevelData().getGameTime();
		Long last = LAST_SCOLD.get(claim.villager().getUUID());
		if (last != null && now - last < SCOLD_COOLDOWN) return;

		LAST_SCOLD.put(claim.villager().getUUID(), now);
		UUID pid = player.getUUID();
		int offenses = OFFENSE_COUNT.getOrDefault(pid, 0) + 1;
		OFFENSE_COUNT.put(pid, Math.min(offenses, 10));

		com.mobmind.state.MobMindState.adjustFriendship(claim.villager(), pid,
				isContainer ? FRIENDSHIP_PENALTY_OPEN - 2 : FRIENDSHIP_PENALTY_BREAK);

		moveVillagerTo(claim.villager(), pos);

		String blockName = getBlockName(state);
		int friendship = com.mobmind.state.MobMindState.friendship(claim.villager(), pid);
		if (isJob) {
			// 判断工作方块是否属于该村民的职业（如酿造台只属于牧师）
			boolean isOwnJob = isJobBlockForProfession(claim.villager(), state);
			if (isOwnJob) {
				MobAiService.scoldJobBlockDestroyer(player, claim.villager(), blockName, offenses, friendship);
			} else {
				MobAiService.scoldOthersJobBlockDestroyer(player, claim.villager(), blockName, offenses, friendship);
			}
		} else if (isCrop) {
			MobAiService.scoldHousebreaker(player, claim.villager(), isContainer, isHouse, true, blockName, offenses, friendship);
		} else if (isContainer) {
			MobAiService.scoldHousebreaker(player, claim.villager(), true, isHouse, false, blockName, offenses, friendship);
		} else if (isVillageProp) {
			// 村庄公共设施（水井/路灯/道路/干草捆/钟等）→ 村庄破坏提示
			MobAiService.scoldVillageVandal(player, claim.villager(), blockName, offenses, friendship);
		} else {
			// 村庄内的普通建筑方块（圆石/木板/石头/楼梯/台阶等房屋结构）→ 拆房子骂你
			MobAiService.scoldHousebreaker(player, claim.villager(), false, true, false, blockName, offenses, friendship);
		}
	}

	/** 村民喝止：开容器（自然战利品箱/村庄公共箱子/工作方块：附近任何村民都能感知） */
	private static void checkVillagerContainerSnoop(ServerPlayer player, ServerLevel level,
												   BlockPos pos, BlockState state) {
		// 容器是明显行为：anyNearby=true，附近16格内能感知到（视线或近距离）的村民都会过来
		boolean anyNearby = true;
		// 如果 findClaimant 找不到（结构检测失败且村民 HOME 不在附近），直接选最近能看到的村民
		VillagerClaim claim = findClaimant(level, pos, player, anyNearby);
		if (claim == null) {
			// fallback：16格内最近的活着的村民（不要求 HOME/JOB claim，只要在附近就能看到你开箱子）
			AABB box = new AABB(pos).inflate(16.0);
			List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box, Entity::isAlive);
			Villager nearest = null;
			double minD = Double.MAX_VALUE;
			for (Villager v : nearby) {
				if (!PersonaRegistry.supports(v)) continue;
				if (v.getTarget() != null) continue;
				double d = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				if (d < minD && (v.hasLineOfSight(player) || d < 64.0)) {
					nearest = v;
					minD = d;
				}
			}
			if (nearest == null) return;
			claim = new VillagerClaim(nearest, minD);
		}

		long now = level.getLevelData().getGameTime();
		Long last = LAST_SCOLD.get(claim.villager().getUUID());
		if (last != null && now - last < SCOLD_COOLDOWN) return;

		LAST_SCOLD.put(claim.villager().getUUID(), now);
		UUID pid = player.getUUID();
		int offenses = OFFENSE_COUNT.getOrDefault(pid, 0) + 1;
		OFFENSE_COUNT.put(pid, Math.min(offenses, 10));

		com.mobmind.state.MobMindState.adjustFriendship(claim.villager(), pid, FRIENDSHIP_PENALTY_OPEN);

		moveVillagerTo(claim.villager(), pos);

		String blockName = getContainerName(state);
		int friendship = com.mobmind.state.MobMindState.friendship(claim.villager(), pid);
		MobAiService.scoldContainerSnooper(player, claim.villager(), blockName, offenses, friendship);
	}

	/** 检查村民是否看到玩家使用他们的工作方块 */
	private static void checkVillagerJobBlockUse(ServerPlayer player, ServerLevel level,
												 BlockPos pos, BlockState state) {
		// 工作方块是明显行为：anyNearby=true + fallback 直接找最近村民
		VillagerClaim claim = findClaimant(level, pos, player, true);
		if (claim == null) {
			AABB box = new AABB(pos).inflate(16.0);
			List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box, Entity::isAlive);
			Villager nearest = null;
			double minD = Double.MAX_VALUE;
			for (Villager v : nearby) {
				if (!PersonaRegistry.supports(v)) continue;
				if (v.getTarget() != null) continue;
				double d = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				if (d < minD && (v.hasLineOfSight(player) || d < 64.0)) {
					nearest = v;
					minD = d;
				}
			}
			if (nearest == null) return;
			claim = new VillagerClaim(nearest, minD);
		}

		long now = level.getLevelData().getGameTime();
		Long last = LAST_JOB_USE_SCOLD.get(claim.villager().getUUID());
		if (last != null && now - last < SCOLD_COOLDOWN) return;
		LAST_JOB_USE_SCOLD.put(claim.villager().getUUID(), now);

		String blockName = state.getBlock().getDescriptionId();
		// 去掉 "block." 前缀
		if (blockName.startsWith("block.")) blockName = blockName.substring(6);
		UUID pid = player.getUUID();
		int friendship = com.mobmind.state.MobMindState.friendship(claim.villager(), pid);
		// 判断是否是该村民自己的工作方块（如酿造台只属于牧师）
		boolean isOwnJob = isJobBlockForProfession(claim.villager(), state);
		if (isOwnJob) {
			MobAiService.scoldJobBlockUser(player, claim.villager(), blockName, friendship);
		} else {
			MobAiService.scoldOthersJobBlockUser(player, claim.villager(), blockName, friendship);
		}
	}

	/** 玩家破坏村庄公共财产（钟/水井/道路/干草捆等）→ 村民过来喝止，钟的反应特别强烈 */
	private static void checkVillagerVillagePropBreak(ServerPlayer player, ServerLevel level,
													  BlockPos pos, BlockState state, boolean inVillage) {
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		boolean isBell = blockId.equals("bell");

		// anyNearby=true 放宽查找，公共设施任何人看到都能管
		VillagerClaim claim = findClaimant(level, pos, player, true);
		if (claim == null) {
			// fallback：16格内最近的村民，和开箱子/用工作方块一致
			AABB box = new AABB(pos).inflate(16.0);
			List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box, Entity::isAlive);
			Villager nearest = null;
			double minD = Double.MAX_VALUE;
			for (Villager v : nearby) {
				if (!PersonaRegistry.supports(v)) continue;
				if (v.getTarget() != null) continue;
				double d = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				if (d < minD && (v.hasLineOfSight(player) || d < 64.0)) {
					nearest = v;
					minD = d;
				}
			}
			if (nearest == null) return;
			claim = new VillagerClaim(nearest, minD);
		}

		long now = level.getLevelData().getGameTime();
		Long last = LAST_SCOLD.get(claim.villager().getUUID());
		if (last != null && now - last < SCOLD_COOLDOWN) return;

		LAST_SCOLD.put(claim.villager().getUUID(), now);
		UUID pid = player.getUUID();
		int offenses = OFFENSE_COUNT.getOrDefault(pid, 0) + 1;
		OFFENSE_COUNT.put(pid, Math.min(offenses, 10));

		// 钟是村庄最重要的公共设施（集会点、警报器），惩罚更重
		int penalty = isBell ? FRIENDSHIP_PENALTY_BREAK - 3 : FRIENDSHIP_PENALTY_BREAK;
		com.mobmind.state.MobMindState.adjustFriendship(claim.villager(), pid, penalty);

		moveVillagerTo(claim.villager(), pos);

		String blockName = getBlockName(state);
		int friendship = com.mobmind.state.MobMindState.friendship(claim.villager(), pid);

		if (isBell) {
			// 挖钟——极度愤怒！这是整个村庄的警报器/集会点
			MobAiService.scoldVillageBellDestroyer(player, claim.villager(), blockName, offenses, friendship);
		} else {
			MobAiService.scoldVillageVandal(player, claim.villager(), blockName, offenses, friendship);
		}
	}

	/** 让村民走到事发地点 */
	private static void moveVillagerTo(Villager villager, BlockPos target) {
		PathNavigation nav = villager.getNavigation();
		nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.55);
		villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
	}

	/**
	 * 玩家破坏了村民的床——这是村民最重要的私人财产！
	 * 优先找HOME在床位置的村民（床的主人），独立冷却，感知范围更广（24格内都能听到）。
	 */
	private static void checkVillagerBedBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		UUID pid = player.getUUID();
		long now = level.getLevelData().getGameTime();
		String blockName = getBlockName(state);

		// 先找床的主人（HOME位置就在床上/床边2格内的村民）
		AABB searchBox = new AABB(pos).inflate(24.0);
		List<Villager> villagers = level.getEntitiesOfClass(Villager.class, searchBox, Entity::isAlive);
		Villager bedOwner = null;
		Villager nearest = null;
		double minDist = Double.MAX_VALUE;

		for (Villager v : villagers) {
			if (!PersonaRegistry.supports(v)) continue;
			if (v.getTarget() != null) continue;

			// 优先找HOME在床附近的村民（真正的主人）
			Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
			boolean isOwner = false;
			if (home.isPresent()) {
				double homeDist = Math.sqrt(home.get().pos().distSqr(pos));
				if (homeDist <= 3.0) {
					isOwner = true;
					if (bedOwner == null) {
						bedOwner = v;
					}
				}
			}

			// 同时记录最近的村民作为fallback
			double distSqr = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			if (distSqr < minDist && (v.hasLineOfSight(player) || distSqr < 576.0)) { // 24格内都能听到
				minDist = distSqr;
				nearest = v;
			}
		}

		// 优先用床的主人，没有主人用最近的村民
		Villager claimant = bedOwner != null ? bedOwner : nearest;
		if (claimant == null) return;

		// 床用独立冷却，和普通方块破坏分开
		Long lastBed = LAST_BED_SCOLD.get(claimant.getUUID());
		if (lastBed != null && now - lastBed < BED_SCOLD_COOLDOWN) return;
		LAST_BED_SCOLD.put(claimant.getUUID(), now);
		// 同时更新普通冷却，防止立即重复
		LAST_SCOLD.put(claimant.getUUID(), now);

		int offenses = OFFENSE_COUNT.getOrDefault(pid, 0) + 1;
		OFFENSE_COUNT.put(pid, Math.min(offenses, 10));

		com.mobmind.state.MobMindState.adjustFriendship(claimant, pid, -10); // 拆床是大罪！-10好感
		int friendship = com.mobmind.state.MobMindState.friendship(claimant, pid);

		moveVillagerTo(claimant, pos);
		// 拆床是最愤怒的——isHouse=true，表示这是在拆家/毁床
		MobAiService.scoldHousebreaker(player, claimant, false, true, false, blockName, offenses, friendship);
	}

	/**
	 * 村庄铁傀儡守卫：玩家在村庄内搞破坏/翻箱子时，附近 16 格内的铁傀儡会沉重地走过来询问/警告。
	 * 多次作案（offenses>=5）或好感度<20（不友好）→ 铁傀儡愤怒咆哮并攻击玩家。
	 */
	private static void triggerVillageGolemGuard(ServerPlayer player, ServerLevel level, BlockPos pos,
												BlockState state, boolean isContainer, boolean isJob, boolean isVillageProp) {
		AABB box = new AABB(pos).inflate(GOLEM_SENSE_RANGE);
		List<Mob> golems = new ArrayList<>();
		try {
			@SuppressWarnings("unchecked")
			Class<? extends Mob> ironGolemClass =
					(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.animal.IronGolem");
			golems.addAll(level.getEntitiesOfClass(ironGolemClass, box, Entity::isAlive));
		} catch (Exception ignored) {}
		if (golems.isEmpty()) return;

		UUID pid = player.getUUID();
		int offenses = OFFENSE_COUNT.getOrDefault(pid, 0);
		String blockName = isContainer ? getContainerName(state) : getBlockName(state);
		long now = level.getLevelData().getGameTime();

		for (Mob golem : golems) {
			if (!PersonaRegistry.supports(golem)) continue;
			if (golem.getTarget() != null) continue; // 已在战斗中

			// 感知范围：16 格内能听到破坏声，无视线时缩小到 8 格
			double distSqr = golem.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			if (distSqr > GOLEM_SENSE_RANGE * GOLEM_SENSE_RANGE) continue;
			if (!golem.hasLineOfSight(player) && distSqr > 64.0) continue;

			// 冷却：同一铁傀儡 30 秒内不重复触发
			Long last = LAST_GOLEM_INVESTIGATE.get(golem.getUUID());
			if (last != null && now - last < GOLEM_COOLDOWN) continue;
			LAST_GOLEM_INVESTIGATE.put(golem.getUUID(), now);

			// 铁傀儡沉重地走过去看情况
			PathNavigation nav = golem.getNavigation();
			nav.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6);
			golem.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

			int golemFriendship = com.mobmind.state.MobMindState.friendship(golem, pid);
			MobAiService.onVillageGolemInvestigate(golem, player, blockName,
					isContainer, isJob, isVillageProp, offenses, golemFriendship);
		}
	}

	/** 获取方块的可读名称（用于AI提示） */
	private static String getBlockName(BlockState state) {
		var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		String path = key.getPath();
		return path.replace('_', ' ');
	}

	/** 获取容器的可读名称 */
	private static String getContainerName(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof ChestBlock || block instanceof TrappedChestBlock) return "chest";
		if (block instanceof BarrelBlock) return "barrel";
		if (block instanceof ShulkerBoxBlock) return "shulker box";
		if (block instanceof DispenserBlock) return "dispenser";
		if (block instanceof HopperBlock) return "hopper";
		return "container";
	}

	// ---------- 通用结构守卫 ----------

	/**
	 * 触发结构守卫：找到范围内的对应生物并激怒。
	 * @param guardType 1=林地府邸(卫道士/唤魔者), 2=女巫小屋(女巫), 3=沉船/海底废墟(溺尸)
	 * @param eventType 1=破坏方块, 2=开容器
	 */
	private static void triggerStructureGuards(ServerPlayer player, ServerLevel level, BlockPos pos,
											   int guardType, int eventType, String targetName) {
		AABB box = new AABB(pos).inflate(STRUCTURE_GUARD_RANGE);
		List<Mob> guards = new ArrayList<>();

		if (guardType == 1) {
			// 林地府邸：卫道士 + 唤魔者
			try {
				guards.addAll(level.getEntitiesOfClass(
						(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.monster.Vindicator"),
						box, Entity::isAlive));
			} catch (Exception ignored) {}
			try {
				guards.addAll(level.getEntitiesOfClass(
						(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.monster.Evoker"),
						box, Entity::isAlive));
			} catch (Exception ignored) {}
		} else if (guardType == 2) {
			// 女巫小屋：女巫
			try {
				guards.addAll(level.getEntitiesOfClass(
						(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.monster.Witch"),
						box, Entity::isAlive));
			} catch (Exception ignored) {}
		} else if (guardType == 3) {
			// 沉船/海底废墟：溺尸
			try {
				guards.addAll(level.getEntitiesOfClass(
						(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.monster.Drowned"),
						box, Entity::isAlive));
			} catch (Exception ignored) {}
		}

		if (guards.isEmpty()) return;

		for (Mob guard : guards) {
			if (!PersonaRegistry.supports(guard)) continue;
			// 感知范围：结构守卫（女巫/卫道士/溺尸等）16格内能听到破坏声
			if (!guard.hasLineOfSight(player) && guard.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 256.0) continue;

			MobAiService.onStructureGuardTrigger(guard, player, guardType, eventType, targetName);
		}
	}

	// ---------- 猪灵守卫 ----------

	/** 猪灵触发冷却：mob uuid -> 上次触发时间(ms) */
	private static final Map<UUID, Long> PIGLIN_LAST_TRIGGER = new ConcurrentHashMap<>();
	private static final double PIGLIN_RANGE = 16.0;
	private static final long PIGLIN_COOLDOWN_MS = 6000;

	/** 检查方块是否是金块/金矿石类（会激怒猪灵） */
	public static boolean isGoldBlock(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		return id.equals("minecraft:gold_block")
				|| id.equals("minecraft:gold_ore")
				|| id.equals("minecraft:deepslate_gold_ore")
				|| id.equals("minecraft:nether_gold_ore")
				|| id.equals("minecraft:gilded_blackstone");
	}

	private static boolean isNaturalGoldBlock(ServerLevel level, BlockPos pos, BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (id.equals("gold_ore") || id.equals("deepslate_gold_ore") || id.equals("nether_gold_ore")) {
			return true;
		}
		return isInsideBastion(level, pos);
	}

	/** 检查位置是否在堡垒遗迹结构内 */
	private static boolean isInsideBastion(ServerLevel level, BlockPos pos) {
		String structId = getStructureIdAt(level, pos);
		return structId != null && structId.toLowerCase().contains("bastion");
	}

	/** 检查位置是否在自然生成的村庄结构内（平原/沙漠/热带草原/雪地/针叶林村庄都算） */
	private static boolean isInsideVillage(ServerLevel level, BlockPos pos) {
		String structId = getStructureIdAt(level, pos);
		return structId != null && structId.toLowerCase().contains("village");
	}

	/** 检查位置是否在"自然战利品结构"内（下界传送门遗迹、地牢、沙漠神殿、丛林神庙等）——这些箱子村民不管 */
	private static boolean isInNaturalLootStructure(ServerLevel level, BlockPos pos) {
		return isInNaturalLootStructureById(getStructureIdAt(level, pos));
	}

	/** 基于结构ID判断是否是自然战利品结构 */
	private static boolean isInNaturalLootStructureById(String structId) {
		if (structId == null) return false;
		String id = structId.toLowerCase();
		return id.contains("ruined_portal")      // 下界传送门遗迹
				|| id.contains("dungeon")         // 地牢
				|| id.contains("desert_pyramid")  // 沙漠神殿
				|| id.contains("jungle_temple")   // 丛林神庙
				|| id.contains("igloo")           // 雪屋
				|| id.contains("pillager_outpost") // 掠夺者前哨站
				|| id.contains("ancient_city")    // 远古城市
				|| id.contains("trial_chambers")  // 试炼密室
				|| id.contains("end_city")        // 末地城
				|| id.contains("bastion")         // 堡垒遗迹
				|| id.contains("fortress")        // 下界要塞
				|| id.contains("nether_fossil")   // 下界化石
				|| id.contains("mineshaft");      // 废弃矿井
	}

	/** 下界结构守卫：僵尸猪灵（下界传送门遗迹、下界要塞） */
	private static void triggerNetherGuards(ServerPlayer player, ServerLevel level, BlockPos pos, String targetName) {
		AABB box = new AABB(pos).inflate(STRUCTURE_GUARD_RANGE);
		List<Mob> guards = new ArrayList<>();
		// Zombified Piglin
		try {
			guards.addAll(level.getEntitiesOfClass(
					(Class<? extends Mob>) Class.forName("net.minecraft.world.entity.monster.ZombifiedPiglin"),
					box, Entity::isAlive));
		} catch (Exception ignored) {}
		// 也包括 Piglin / PiglinBrute（下界要塞和堡垒里可能有）
		try {
			guards.addAll(level.getEntitiesOfClass(Piglin.class, box, Entity::isAlive));
		} catch (Exception ignored) {}
		try {
			guards.addAll(level.getEntitiesOfClass(PiglinBrute.class, box, Entity::isAlive));
		} catch (Exception ignored) {}
		triggerGuardsAI(player, pos, guards, 4, targetName);
	}

	/** 地牢/神殿守卫：附近的怪物（僵尸、骷髅、蜘蛛、苦力怕等） */
	private static void triggerMonsterGuards(ServerPlayer player, ServerLevel level, BlockPos pos, String targetName) {
		AABB box = new AABB(pos).inflate(12.0);
		List<Mob> guards = new ArrayList<>();
		guards.addAll(level.getEntitiesOfClass(Monster.class, box, Entity::isAlive));
		triggerGuardsAI(player, pos, guards, 5, targetName);
	}

	/** 通用守卫AI触发 */
	private static void triggerGuardsAI(ServerPlayer player, BlockPos pos, List<Mob> guards,
										int guardType, String targetName) {
		if (guards.isEmpty()) return;
		for (Mob guard : guards) {
			if (!PersonaRegistry.supports(guard)) continue;
			if (!guard.hasLineOfSight(player) && guard.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 36.0) continue;

			// 怪物冲向玩家
			PathNavigation nav = guard.getNavigation();
			nav.moveTo(player, 0.8);
			guard.setTarget(player);

			MobAiService.onStructureGuardTrigger(guard, player, guardType, 1, targetName);
		}
	}

	/** 获取位置所在结构的 ID（如 minecraft:village, minecraft:mansion, minecraft:bastion_remnant 等） */
	private static String getStructureIdAt(ServerLevel level, BlockPos pos) {
		try {
			var structureManager = level.structureManager();
			var structuresMap = structureManager.getAllStructuresAt(pos);
			if (structuresMap == null || structuresMap.isEmpty()) return null;
			for (Object structureObj : structuresMap.keySet()) {
				String structId = getStructureId(structureObj, level);
				if (structId != null) return structId;
			}
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] Structure detection failed: {}", e.getMessage());
		}
		return null;
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
			MobMindMod.LOGGER.warn("[MobMind] Failed to get structure ID: {}", e.getMessage());
		}
		return null;
	}

	/** 检查容器是否是自然生成的战利品箱（有LootTable） */
	private static boolean isNaturalContainer(ServerLevel level, BlockPos pos) {
		var blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) return false;
		long key = posKey(pos);

		try {
			Class<?> clazz = blockEntity.getClass();
			while (clazz != null) {
				try {
					var field = clazz.getDeclaredField("lootTable");
					field.setAccessible(true);
					Object lootTable = field.get(blockEntity);
					if (lootTable != null) {
						OPENED_NATURAL_CHESTS.add(key);
						return true;
					}
					break;
				} catch (NoSuchFieldException e) {
					clazz = clazz.getSuperclass();
				}
			}
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] Container LootTable detection failed", e);
		}
		return OPENED_NATURAL_CHESTS.contains(key);
	}

	/** 获取金块的中文/英文名称 */
	private static String getGoldBlockName(BlockState state, boolean english) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return switch (id) {
			case "gold_block" -> english ? "gold block" : "金块";
			case "gold_ore" -> english ? "gold ore" : "金矿石";
			case "deepslate_gold_ore" -> english ? "deepslate gold ore" : "深层金矿石";
			case "nether_gold_ore" -> english ? "nether gold ore" : "下界金矿石";
			case "gilded_blackstone" -> english ? "gilded blackstone" : "镶金黑石";
			default -> english ? "gold" : "金子";
		};
	}

	/** 玩家破坏了方块：检查是否是金块并激怒附近猪灵 */
	public static void onBlockBreakForPiglins(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		if (player.isSpectator() || player.isCreative()) return;
		if (!isGoldBlock(state)) return;
		if (!isNaturalGoldBlock(level, pos, state)) return;

		List<Mob> piglins = new ArrayList<>();
		AABB box = new AABB(pos).inflate(PIGLIN_RANGE);
		piglins.addAll(level.getEntitiesOfClass(Piglin.class, box, Entity::isAlive));
		piglins.addAll(level.getEntitiesOfClass(PiglinBrute.class, box, Entity::isAlive));
		if (piglins.isEmpty()) return;

		long now = System.currentTimeMillis();
		for (Mob piglin : piglins) {
			if (!PersonaRegistry.supports(piglin)) continue;
			if (!piglin.hasLineOfSight(player) && piglin.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 25.0) continue;

			Long last = PIGLIN_LAST_TRIGGER.get(piglin.getUUID());
			if (last != null && now - last < PIGLIN_COOLDOWN_MS) continue;
			PIGLIN_LAST_TRIGGER.put(piglin.getUUID(), now);

			String blockName = getGoldBlockName(state, com.mobmind.state.MobMindState.isPlayerEnglish(player.getUUID()));
			MobAiService.onPiglinGoldMined(piglin, player, blockName);
		}
	}

	/** 玩家打开容器：检查附近是否有猪灵，有就激怒它们 */
	public static void onUseBlockForPiglins(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (player.isSpectator()) return;
		BlockState state = level.getBlockState(pos);
		if (!isContainerBlock(state)) return;

		long key = posKey(pos);
		boolean isNatural = isNaturalContainer(level, pos);
		if (isNatural) {
			OPENED_NATURAL_CHESTS.add(key);
		} else {
			if (!OPENED_NATURAL_CHESTS.contains(key)) return;
		}

		List<Mob> piglins = new ArrayList<>();
		AABB box = new AABB(pos).inflate(PIGLIN_RANGE);
		piglins.addAll(level.getEntitiesOfClass(Piglin.class, box, Entity::isAlive));
		piglins.addAll(level.getEntitiesOfClass(PiglinBrute.class, box, Entity::isAlive));
		if (piglins.isEmpty()) return;

		long now = System.currentTimeMillis();
		for (Mob piglin : piglins) {
			if (!PersonaRegistry.supports(piglin)) continue;
			if (!piglin.hasLineOfSight(player) && piglin.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 25.0) continue;

			Long last = PIGLIN_LAST_TRIGGER.get(piglin.getUUID());
			if (last != null && now - last < PIGLIN_COOLDOWN_MS) continue;
			PIGLIN_LAST_TRIGGER.put(piglin.getUUID(), now);

			String containerName = getContainerName(state);
			MobAiService.onPiglinContainerOpened(piglin, player, containerName);
		}
	}

	/** tick清理过期的作案计数（每5秒调用一次） */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		if (now - lastDecayTick < 6000) return;
		lastDecayTick = now;
		OFFENSE_COUNT.replaceAll((k, v) -> Math.max(0, v - 1));
	}
}
