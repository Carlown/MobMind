package com.mobmind.behavior;

import com.mobmind.MobMindMod;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HayBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民低血量自动觅食：
 * 优先级：箱子食物 > 干草捆→面包 > 收作物 > 杀牲畜
 * - 每3秒检查一次，每村民独立冷却
 * - 只对 Villager 生效，血量<70%maxHealth 且无存储食物时触发
 * - 觅食到的食物：立即吃1份回血(4.0)，剩余存入存储食物系统（后续由 tickAutoEatFood 处理）
 */
public final class VillagerForage {
	private VillagerForage() {}

	private static final long COOLDOWN = 60;          // 3秒冷却（与 tickAutoEatFood 一致）
	private static final int BLOCK_RADIUS = 10;        // 方块搜索半径（菜地和家可能隔得远，扩大范围）
	private static final double LIVESTOCK_RADIUS = 8.0; // 牲畜搜索半径
	private static final float HEAL_PER_FOOD = 4.0f;  // 每份食物回血量（与 tickAutoEatFood 一致）

	private static final Map<UUID, Long> LAST_FORAGE = new ConcurrentHashMap<>();

	/** 村民自动放置的公共粮仓箱子位置（开这个箱子时村民会质问） */
	private static final Set<BlockPos> VILLAGE_COMMON_CHESTS = Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** 新放置但还未"投入使用"的箱子（放置后约3秒内村民不往里存东西，保持 lootTable 未开封状态让 Jade 显示"里面会是什么？"） */
	private static final Map<BlockPos, Long> FRESH_CHESTS = new ConcurrentHashMap<>();

	/** 判断一个箱子位置是否是村民自动放置的公共粮仓 */
	public static boolean isVillageCommonChest(BlockPos pos) {
		return VILLAGE_COMMON_CHESTS.contains(pos);
	}

	public static void tick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			Set<UUID> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
			for (ServerPlayer player : level.players()) {
				AABB box = player.getBoundingBox().inflate(64.0);
				for (Villager villager : level.getEntitiesOfClass(Villager.class, box)) {
					if (!seen.add(villager.getUUID())) continue;
					if (!villager.isAlive()) continue;
					if (!PersonaRegistry.supports(villager)) continue;

					long now = level.getLevelData().getGameTime();
					Long last = LAST_FORAGE.get(villager.getUUID());
					if (last != null && now - last < COOLDOWN) continue;

					boolean isFarmer = isFarmer(villager);
					boolean lowHp = villager.getHealth() < villager.getMaxHealth() * 0.7f;
					if (!lowHp) {
						// 血量满：只有农民做本职工作——收成熟作物存起来（合成面包+干草捆放村庄箱子）
						// 其他职业（铁匠/牧师/图书管理员等）满血时不搞农业
						if (isFarmer && tryHarvestCrop(level, villager)) {
							LAST_FORAGE.put(villager.getUUID(), now);
						}
						continue;
					}
					// 低血量：有存储食物时由 tickAutoEatFood 处理；只在无存储食物时觅食（会吃）
					if (MobMindState.getStoredFoodCount(villager) > 0) continue;

					if (tryForage(level, villager, isFarmer)) {
						LAST_FORAGE.put(villager.getUUID(), now);
					}
				}
			}
		}
	}

	/** 判断村民是否是农民职业（只有农民会种菜收菜管理粮食） */
	private static boolean isFarmer(Villager v) {
		var profHolder = v.getVillagerData().profession();
		return profHolder.unwrapKey()
				.map(k -> k.identifier().getPath().equals("farmer"))
				.orElse(false);
	}

	/** 按优先级尝试觅食，成功任一即返回。isFarmer决定是否可以收割作物 */
	private static boolean tryForage(ServerLevel level, Villager villager, boolean isFarmer) {
		if (tryChestFood(level, villager)) return true;      // 所有村民：从公共箱子拿吃的
		if (tryHayBale(level, villager)) return true;        // 所有村民：拆干草捆吃
		if (isFarmer && tryHarvestCrop(level, villager)) return true; // 只有农民：收割作物
		return tryHuntLivestock(level, villager);            // 所有村民：打猎获取食物
	}

	/** 吃1份回血，剩余优先放村庄箱子（公共储备），放不下才存入虚拟存储 */
	private static void eatAndStore(Villager villager, int total, Item item) {
		villager.heal(HEAL_PER_FOOD);
		villager.level().playSound(null, villager.getX(), villager.getY(), villager.getZ(),
				SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0f, 1.0f);
		int remaining = total - 1;
		if (remaining <= 0) return;
		// 优先放附近村庄箱子（公共储备）
		ServerLevel level = (ServerLevel) villager.level();
		int stored = storeItemInVillageChest(level, villager, item, remaining, true);
		remaining -= stored;
		// 放不下的存虚拟存储
		if (remaining > 0) {
			MobMindState.addStoredFood(villager, remaining);
		}
	}

	/** 把物品放进附近村庄箱子（非玩家放置）；autoCreate=true 时无箱子则在旁边放一个新箱子再存。返回实际放入数量 */
	private static int storeItemInVillageChest(ServerLevel level, Villager villager, Item item, int count, boolean autoCreate) {
		if (count <= 0) return 0;
		int maxStack = new ItemStack(item).getMaxStackSize();
		BlockPos origin = villager.blockPosition();
		int r = BLOCK_RADIUS;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int remaining = count;
		// 清理过期的"新鲜箱子"标记（放置超过3秒/60ticks 或箱子已不存在）
		long gameTime = level.getLevelData().getGameTime();
		FRESH_CHESTS.entrySet().removeIf(e -> {
			BlockPos p = e.getKey();
			BlockEntity be = level.getBlockEntity(p);
			return be == null || !(be instanceof Container) || gameTime - e.getValue() > 60L;
		});
		// 第一轮：找已有村庄箱子存（跳过刚放的"新鲜箱子"，保持未开封状态）
		outer:
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (FRESH_CHESTS.containsKey(cursor)) continue; // 跳过新箱子，保持未打开状态
					BlockEntity be = level.getBlockEntity(cursor);
					if (!(be instanceof Container container)) continue;
					if (HouseGuard.isPlayerPlaced(cursor)) continue;
					boolean changed = false;
					for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
						ItemStack slot = container.getItem(i);
						if (slot.isEmpty()) {
							int put = Math.min(remaining, maxStack);
							container.setItem(i, new ItemStack(item, put));
							remaining -= put;
							changed = true;
						} else if (slot.is(item) && slot.getCount() < slot.getMaxStackSize()) {
							int can = slot.getMaxStackSize() - slot.getCount();
							int put = Math.min(remaining, can);
							ItemStack copy = slot.copy();
							copy.grow(put);
							container.setItem(i, copy);
							remaining -= put;
							changed = true;
						}
					}
					if (changed) container.setChanged();
					if (remaining <= 0) break outer;
				}
			}
		}
		// 还有剩余且允许自动放箱子 → 在附近放一个新箱子（设置 lootTable，不立即存东西，保持"里面会是什么？"状态）
		if (remaining > 0 && autoCreate) {
			BlockPos chestPos = placeChestNearby(level, origin);
			if (chestPos != null) {
				// 标记为新鲜箱子，记录放置时间，3秒后再往里存东西
				FRESH_CHESTS.put(chestPos, gameTime);
				MobMindMod.LOGGER.info("[MobMind] Villager forage: auto-placed village chest at {} (loot table set, fresh)", chestPos);
			}
		}
		return count - remaining;
	}

	/** 简化版（不自动放箱子），供干草捆等方块存放用 */
	private static int storeItemInVillageChest(ServerLevel level, Villager villager, Item item, int count) {
		return storeItemInVillageChest(level, villager, item, count, false);
	}

	/** 判断指定位置是否是稳固的自然地面（草方块/土径/石头/沙等），可以放箱子/干草块 */
	private static boolean isStableGround(BlockState state) {
		if (state.isAir()) return false;
		net.minecraft.world.level.block.Block b = state.getBlock();
		String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
		// 只有干草块上可堆叠
		if (b instanceof HayBlock) return true;
		// 白名单：允许自然地面 + 各类型村庄建筑地面（沙漠/热带草原/针叶林等）
		// 草方块、土径（村庄小路）
		if (id.equals("grass_block") || id.equals("dirt_path")) return true;
		// 沙/沙砾/红沙（沙漠、沙滩）
		if (id.equals("sand") || id.equals("red_sand") || id.equals("gravel")) return true;
		// 裸石头/圆石/深板岩/花岗岩/闪长岩/安山岩/basalt/黑stone（自然生成的地面）
		if (id.equals("stone") || id.equals("cobblestone")
				|| id.equals("deepslate") || id.equals("cobbled_deepslate")
				|| id.equals("granite") || id.equals("diorite") || id.equals("andesite")
				|| id.equals("basalt") || id.equals("blackstone")
				|| id.equals("calcite") || id.equals("tuff")
				|| id.equals("mossy_cobblestone")) return true;
		// 沙漠村庄地面：砂岩家族（普通/切制/平滑/錾制、红砂岩同上）
		if (id.equals("sandstone") || id.equals("cut_sandstone")
				|| id.equals("smooth_sandstone") || id.equals("chiseled_sandstone")
				|| id.equals("red_sandstone") || id.equals("cut_red_sandstone")
				|| id.equals("smooth_red_sandstone") || id.equals("chiseled_red_sandstone")) return true;
		// 陶瓦/彩色陶瓦（terracotta及各种颜色：白色/橙色/品红色/淡蓝色/黄色/黄绿色/粉红色/灰色/淡灰色/青色/紫色/蓝色/棕色/绿色/红色/黑色）
		// 沙漠村庄大量用陶瓦铺地
		if (id.equals("terracotta") || id.endsWith("_terracotta")) return true;
		// 泥土本身不放，但砂土（coarse_dirt）和根土（rooted_dirt）是自然地面；农田/耕地不放
		// 注意：dirt/farmland 仍然不放，避免堆在耕地和普通泥土地上
		return false;
	}

	/**
	 * 检查指定位置正下方（垂直向下）是否有容器方块（箱子/木桶等），
	 * 中间允许穿过干草块——确保容器顶上永远是空气，不能压任何方块（包括干草块）。
	 */
	private static boolean isBlockAboveContainer(ServerLevel level, BlockPos pos) {
		BlockPos cursor = pos.below();
		for (int i = 0; i < 5; i++) { // 最多往下查5格（干草块堆叠上限是4层）
			BlockState state = level.getBlockState(cursor);
			if (state.isAir()) return false;
			if (HouseGuard.isContainerBlock(state)) return true;
			if (!(state.getBlock() instanceof HayBlock)) return false; // 遇到非干草块/非容器就停止
			cursor = cursor.below();
		}
		return false;
	}

	/** 判断放置点是否在农田中间（周围有耕地或作物则不放，避免破坏田地） */
	private static boolean isInFarmland(ServerLevel level, BlockPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockState below = level.getBlockState(pos.offset(dx, -1, dz));
				BlockState at = level.getBlockState(pos.offset(dx, 0, dz));
				String bid = BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath();
				if (bid.equals("farmland")) return true;
				if (below.getBlock() instanceof net.minecraft.world.level.block.CropBlock) return true;
				if (at.getBlock() instanceof net.minecraft.world.level.block.CropBlock) return true;
				if (bid.equals("water") || BuiltInRegistries.BLOCK.getKey(at.getBlock()).getPath().equals("water")) return true;
			}
		}
		return false;
	}

	/**
	 * 从中心 origin 出发，沿水平偏移 hx/hz 往下找稳固地面，返回地面上方的 air 位置。
	 * 若上方不是空气则返回 null。
	 */
	private static BlockPos findPlaceOnGround(ServerLevel level, BlockPos origin, int hx, int hz, boolean needAirAbove) {
		BlockPos cursor = origin.offset(hx, 0, hz);
		// 最多往下找6格（避免掉下悬崖）
		for (int i = 0; i < 6; i++) {
			BlockState s = level.getBlockState(cursor);
			if (isStableGround(s)) {
				BlockPos place = cursor.above();
				BlockState at = level.getBlockState(place);
				if (!at.isAir() && !(at.getBlock() instanceof HayBlock)) return null; // 已有方块且不是干草块
				// 绝对禁止：放在容器正上方（包括干草块压在容器上的情况，箱子顶永远留空气）
				if (isBlockAboveContainer(level, place)) return null;
				// 不在农田中间（周围3x3有耕地/作物/水则不放）
				if (isInFarmland(level, place)) return null;
				if (needAirAbove) {
					BlockState above = level.getBlockState(place.above());
					if (!above.isAir() && !(above.getBlock() instanceof HayBlock)
							&& !(above.getBlock() instanceof net.minecraft.world.level.block.CropBlock)) return null;
				}
				return place;
			}
			if (cursor.getY() <= level.getMinY() + 1) break;
			cursor = cursor.below();
		}
		return null;
	}

	/** 在指定位置附近放一个 chest 方块（放在稳固地面上，避开耕地/作物/水） */
	private static BlockPos placeChestNearby(ServerLevel level, BlockPos origin) {
		// 8 个方向 + 更大半径（1~8格），优先近的位置，确保在田边/村庄地面找到位置
		// 沙漠村庄用砂岩/陶瓦铺地，热带草原用金合欢木地面，都已加入isStableGround白名单
		int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
		for (int r = 1; r <= 8; r++) {
			for (int[] d : dirs) {
				BlockPos place = findPlaceOnGround(level, origin, d[0]*r, d[1]*r, true);
				if (place != null) {
					level.setBlock(place, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
					// 记录为村民公共箱子（开箱子时村民会质问）
					VILLAGE_COMMON_CHESTS.add(place);
					// 给新箱子设置 lootTable，让 Jade/WTHIT 显示"里面会是什么？"（像自然生成的战利品箱）
					setChestLootTable(level, place);
					MobMindMod.LOGGER.info("[MobMind] Villager forage: placing village chest at {} (stable ground, loot table set)", place);
					return place;
				}
			}
		}
		return null;
	}

	/** 给新放置的箱子设置 lootTable（mobmind:chests/village_granary），使其在第一次打开前显示"里面会是什么？" */
	private static void setChestLootTable(ServerLevel level, BlockPos pos) {
		try {
			BlockEntity be = level.getBlockEntity(pos);
			if (be == null) return;
			// RandomizableContainerBlockEntity → 在Yarn中可能叫 LootableContainerBlockEntity 或同名
			if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity lootable) {
				var lootId = Identifier.fromNamespaceAndPath("mobmind", "chests/village_granary");
				var lootKey = net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.LOOT_TABLE, lootId);
				lootable.setLootTable(lootKey, level.getRandom().nextLong());
				be.setChanged();
			}
		} catch (Exception e) {
			// 回退：反射尝试 LootableContainerBlockEntity
			try {
				BlockEntity be = level.getBlockEntity(pos);
				if (be == null) return;
				Class<?> clazz = Class.forName("net.minecraft.world.level.block.entity.LootableContainerBlockEntity");
				if (clazz.isInstance(be)) {
					var lootId = Identifier.fromNamespaceAndPath("mobmind", "chests/village_granary");
					var lootKey = net.minecraft.resources.ResourceKey.create(
							net.minecraft.core.registries.Registries.LOOT_TABLE, lootId);
					var method = clazz.getDeclaredMethod("setLootTable", net.minecraft.resources.ResourceKey.class, long.class);
					method.invoke(be, lootKey, level.getRandom().nextLong());
					be.setChanged();
				}
			} catch (Exception e2) {
				MobMindMod.LOGGER.warn("[MobMind] Failed to set chest lootTable at {}: {}", pos, e2.toString());
			}
		}
	}

	/** 在村民旁边规整放置干草捆方块：找稳固地面，已有干草捆则往上堆叠成柱（最多4层） */
	private static boolean placeHayBlockNearby(ServerLevel level, Villager villager) {
		BlockPos origin = villager.blockPosition();
		int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
		for (int r = 1; r <= 8; r++) {
			for (int[] d : dirs) {
				BlockPos place = findPlaceOnGround(level, origin, d[0]*r, d[1]*r, false);
				if (place == null) continue;
				// 已有干草块则往上堆叠（最多4层）
				for (int stack = 0; stack < 4; stack++) {
					BlockState s = level.getBlockState(place);
					if (s.isAir()) {
						// 双重检查：放置前再次确认不在容器上方（包括堆叠的情况）
						if (isBlockAboveContainer(level, place)) break;
						level.setBlock(place, net.minecraft.world.level.block.Blocks.HAY_BLOCK.defaultBlockState(), 3);
						return true;
					}
					if (s.getBlock() instanceof HayBlock) {
						place = place.above();
						// 堆叠前检查：往上走后也要确保不在容器上方
						if (isBlockAboveContainer(level, place)) break;
						continue;
					}
					break;
				}
			}
		}
		return false;
	}

	/** 让村民看向目标方块 */
	private static void lookAt(Villager villager, BlockPos pos) {
		villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/**
	 * 村民能否触及目标方块（距离<=3格）；太远则走过去并返回 false。
	 * 避免隔墙/隔门隔空取物——村民必须真的走过去才能拿。
	 */
	private static boolean canReachOrMoveTo(Villager villager, BlockPos pos) {
		if (villager.blockPosition().distSqr(pos) > 9.0) {
			villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6);
			villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			return false;
		}
		return true;
	}

	// ---------- 1. 翻箱子找食物 ----------
	private static boolean tryChestFood(ServerLevel level, Villager villager) {
		BlockPos origin = villager.blockPosition();
		int r = BLOCK_RADIUS;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockEntity be = level.getBlockEntity(cursor);
					if (!(be instanceof Container container)) continue;
					// 跳过玩家自己放置的箱子（只翻村庄原有的公共储备，不偷玩家箱子）
					if (HouseGuard.isPlayerPlaced(cursor)) continue;
					// 带 lootTable 的自然战利品箱未开过时 getItem 返回空，会自动跳过
					for (int i = 0; i < container.getContainerSize(); i++) {
						ItemStack stack = container.getItem(i);
						if (stack.isEmpty()) continue;
						if (stack.get(DataComponents.FOOD) == null) continue;
						Item item = stack.getItem();
						if (!canReachOrMoveTo(villager, cursor)) return true; // 太远走过去，下轮再拿
						container.removeItem(i, 1);
						container.setChanged();
						lookAt(villager, cursor);
						eatAndStore(villager, 1, item);
					MobMindMod.LOGGER.info("[MobMind] Villager forage: took 1 {} from chest at {}",
								BuiltInRegistries.ITEM.getKey(item), cursor);
						return true;
					}
				}
			}
		}
		return false;
	}

	// ---------- 2. 拆干草捆 → 合成面包 ----------
	private static boolean tryHayBale(ServerLevel level, Villager villager) {
		BlockPos origin = villager.blockPosition();
		int r = BLOCK_RADIUS;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState state = level.getBlockState(cursor);
					if (!(state.getBlock() instanceof HayBlock)) continue;
					// 1 干草捆 = 9 小麦 = 3 面包；不掉落方块（村民直接获得面包）
				if (!canReachOrMoveTo(villager, cursor)) return true; // 太远走过去，下轮再拆
				BlockPos below = cursor.below();
				level.destroyBlock(cursor, false, villager);
				level.playSound(null, cursor, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8f, 1.0f);
				// 干草块压在土径上会把土径变成泥土；拆完后村民把下方泥土铲回土径
				BlockState belowState = level.getBlockState(below);
				String belowId = BuiltInRegistries.BLOCK.getKey(belowState.getBlock()).getPath();
				if ((belowId.equals("dirt") || belowId.equals("grass_block"))
						&& level.getBlockState(cursor).isAir()) {
					level.setBlock(below, net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(), 3);
					level.playSound(null, below, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 0.6f, 1.0f);
				}
				lookAt(villager, cursor);
				eatAndStore(villager, 3, Items.BREAD);
					MobMindMod.LOGGER.info("[MobMind] Villager forage: harvested hay bale at {} -> 3 bread", cursor);
					return true;
				}
			}
		}
		return false;
	}

	// ---------- 3. 收作物 ----------
	private static boolean tryHarvestCrop(ServerLevel level, Villager villager) {
		BlockPos origin = villager.blockPosition();
		int r = BLOCK_RADIUS;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState state = level.getBlockState(cursor);
					if (!(state.getBlock() instanceof CropBlock crop)) continue;
					if (!crop.isMaxAge(state)) continue;
					Item foodItem = cropFoodItem(crop);
					if (foodItem == null) continue;
				boolean lowHp = villager.getHealth() < villager.getMaxHealth() * 0.7f;
				// 收获作物：重置为幼龄（模拟收获+留种重新播种，作物方块保留继续生长），村民直接获得对应食物
				if (!canReachOrMoveTo(villager, cursor)) return true; // 太远走过去，下轮再收
				level.setBlock(cursor, crop.defaultBlockState(), 3);
				level.playSound(null, cursor, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
				lookAt(villager, cursor);
				// 小麦：合成1面包+1干草捆；其他作物（胡萝卜/土豆/甜菜根）：收1份
			if (foodItem == Items.BREAD) {
				if (lowHp) {
					eatAndStore(villager, 1, Items.BREAD);
				} else {
					// 血量满：面包存村庄箱子，存不了存虚拟存储
				int bpLeft = storeItemInVillageChest(level, villager, Items.BREAD, 1, true);
					if (bpLeft > 0) MobMindState.addStoredFood(villager, bpLeft);
				}
				// 干草捆优先存村庄箱子，存不了则把干草捆方块直接放在地上
				int leftover = storeItemInVillageChest(level, villager, Items.HAY_BLOCK, 1);
				if (leftover > 0) {
					boolean placed = placeHayBlockNearby(level, villager);
					MobMindMod.LOGGER.info("[MobMind] Villager forage: no village chest, {}",
							placed ? "placed hay block on ground" : "no air space to place hay block");
				} else {
					MobMindMod.LOGGER.info("[MobMind] Villager forage: stored 1 hay block in village chest");
				}
			} else {
				// 胡萝卜/土豆/甜菜根：低血量吃1存余（土豆按烤土豆吃），血量满全存村庄箱子
				if (lowHp) {
					eatAndStore(villager, 1, foodItem);
				} else {
					// 血量满：存生作物（土豆存生POTATO），不播放吃声；箱子放不下存虚拟存储
					Item storeItem = cropStoredItem(crop);
					if (storeItem != null) {
						int stored = storeItemInVillageChest(level, villager, storeItem, 1, true);
						if (stored < 1) MobMindState.addStoredFood(villager, 1 - stored);
					}
				}
			}
					MobMindMod.LOGGER.info("[MobMind] Villager forage: harvested crop {} at {} -> {}",
							BuiltInRegistries.BLOCK.getKey(crop), cursor,
							BuiltInRegistries.ITEM.getKey(foodItem));
					return true;
				}
			}
		}
		return false;
	}

	/** 根据作物类型返回对应食物（小麦→面包，土豆→烤土豆模拟烤熟，其他直接吃） */
	private static Item cropFoodItem(CropBlock crop) {
		String path = BuiltInRegistries.BLOCK.getKey(crop).getPath();
		return switch (path) {
			case "wheat" -> Items.BREAD;            // 小麦 → 合成面包吃
			case "carrots" -> Items.CARROT;         // 胡萝卜
			case "potatoes" -> Items.BAKED_POTATO;  // 土豆 → 烤熟（模拟）
			case "beetroots" -> Items.BEETROOT;     // 甜菜根
			default -> null;
		};
	}

	/** 收获作物后存入箱子的物品（血量满时存，土豆存生土豆不是烤土豆） */
	private static Item cropStoredItem(CropBlock crop) {
		String path = BuiltInRegistries.BLOCK.getKey(crop).getPath();
		return switch (path) {
			case "wheat" -> Items.BREAD;            // 小麦 → 面包
			case "carrots" -> Items.CARROT;
			case "potatoes" -> Items.POTATO;        // 生土豆存入箱子
			case "beetroots" -> Items.BEETROOT;
			default -> null;
		};
	}

	// ---------- 4. 杀牲畜 → 拿熟肉 ----------
	private static boolean tryHuntLivestock(ServerLevel level, Villager villager) {
		AABB box = villager.getBoundingBox().inflate(LIVESTOCK_RADIUS);
		List<Animal> livestock = level.getEntitiesOfClass(Animal.class, box, Entity::isAlive);
		// 只保留 cow/sheep/pig（用 entity type ID 过滤，避免误伤马/羊驼等）
		List<Animal> targets = new ArrayList<>();
		for (Animal a : livestock) {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath();
			if (id.equals("cow") || id.equals("sheep") || id.equals("pig")) {
				targets.add(a);
			}
		}
		if (targets.isEmpty()) return false;

		// 选最近的一只
		Animal target = targets.stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(villager)))
				.orElse(null);
		if (target == null) return false;

		String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
		Item cookedMeat = switch (id) {
			case "cow" -> Items.COOKED_BEEF;
			case "pig" -> Items.COOKED_PORKCHOP;
			case "sheep" -> Items.COOKED_MUTTON;
			default -> null;
		};
		if (cookedMeat == null) return false;

		// 太远则走过去，下轮再杀（避免隔空击杀）
		if (target.distanceToSqr(villager) > 9.0) {
			villager.getNavigation().moveTo(target, 0.6);
			villager.getLookControl().setLookAt(target);
			return true;
		}
		// 击杀牲畜：discard 直接移除实体（绝对不触发 die/掉落物，避免牛羊猪掉肉和羊毛），村民获得熟肉作为战利品；播放死亡声音作为反馈
		target.playSound(net.minecraft.sounds.SoundEvents.GENERIC_DEATH, 1.0f, 1.0f);
		target.discard();
		villager.getLookControl().setLookAt(target);
		// 1 头牲畜给2份熟肉：吃1存1
		eatAndStore(villager, 2, cookedMeat);
		MobMindMod.LOGGER.info("[MobMind] Villager forage: killed {} -> 2 {}",
				target.getType().getDescription().getString(),
				BuiltInRegistries.ITEM.getKey(cookedMeat));
		return true;
	}
}
