package com.mobmind.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生物的环境感知：描述自身处境（船上/水中/地下等）与扫描玩家展示的建筑。
 * 生成的文本注入 AI 提示词，让生物"知道"自己在哪、看到了什么。
 */
public final class EnvironmentSense {
	private EnvironmentSense() {}

	// ---------- 自身处境 ----------

	/** 一句话描述生物当前所处环境，如"你正坐在船上，雨水正打在你身上，脚下踩着冰块" */
	public static String describe(Mob mob) {
		List<String> parts = new ArrayList<>();
		Level level = mob.level();
		BlockPos pos = mob.blockPosition();

		// 载具（船/矿车/马等）
		Entity vehicle = mob.getVehicle();
		if (vehicle != null) {
			parts.add("你正乘坐" + vehicle.getType().getDescription().getString());
		}
		// 水
		if (mob.isUnderWater()) {
			parts.add("你整个人浸在水下，有点喘不过气");
		} else if (mob.isInWater()) {
			parts.add("你正在水里扑腾");
		}
		// 空中
		if (mob.isFallFlying()) {
			parts.add("你正在空中滑翔");
		} else if (!mob.onGround() && mob.getVehicle() == null && mob.fallDistance > 3) {
			parts.add("你正在往下掉");
		}
		if (mob.isSleeping()) {
			parts.add("你正躺着睡觉");
		}
		// 淋雨/下雪
		if (level.isRainingAt(pos)) {
			parts.add("雨水正打在你身上");
		}
		// 维度
		if (level.dimension() == Level.NETHER) {
			parts.add("身处下界，四周酷热难耐");
		} else if (level.dimension() == Level.END) {
			parts.add("身处末地，脚下是无尽虚空");
		}
		// 脚下方块
		BlockState below = level.getBlockState(pos.below());
		if (!below.isAir()) {
			parts.add("脚下踩着" + below.getBlock().getName().getString());
		}
		// 地下洞穴 / 高处
		boolean seeSky = level.canSeeSky(pos.above());
		if (!seeSky && pos.getY() < 60) {
			parts.add("身处幽暗的地下");
		} else if (seeSky && pos.getY() > 120) {
			parts.add("站在高处，视野开阔");
		}
		// 着火
		if (mob.isOnFire()) {
			parts.add("你身上着火了");
		}
		return parts.isEmpty() ? "陆地上，一切如常" : String.join("，", parts);
	}

	// ---------- 视觉：扫描玩家展示的建筑 ----------

	/** 常见自然方块：扫描时忽略，避免把山川树木当成玩家的作品 */
	private static final Set<Block> NATURAL = Set.of(
			Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH,
			Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.MUD,
			Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
			Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF, Blocks.CALCITE,
			Blocks.WATER, Blocks.LAVA, Blocks.BEDROCK, Blocks.CLAY, Blocks.ICE, Blocks.PACKED_ICE,
			Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.POWDER_SNOW,
			Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.END_STONE,
			Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
			Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
			Blocks.PALE_OAK_LOG, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
			Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES,
			Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES,
			Blocks.CHERRY_LEAVES, Blocks.PALE_OAK_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
			Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
			Blocks.DANDELION, Blocks.POPPY, Blocks.VINE, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
			Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
			Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSS_BLOCK
	);

	/** 贵重方块：扫描到时特别提及 */
	private static final Set<Block> PRECIOUS = java.util.stream.Stream.of(
			Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.NETHERITE_BLOCK,
			Blocks.BEACON, Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK
	).collect(java.util.stream.Collectors.toUnmodifiableSet());

	/**
	 * 扫描玩家周围的人工建筑，生成一句视觉描述供生物点评。
	 * 范围：以玩家为中心 21×13×21，忽略自然方块。
	 */
	public static String scanBuild(ServerPlayer player) {
		Level level = player.level();
		BlockPos center = player.blockPosition();
		Map<String, Integer> counts = new HashMap<>();
		List<String> precious = new ArrayList<>();
		int total = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -10; dx <= 10; dx++) {
			for (int dy = -4; dy <= 8; dy++) {
				for (int dz = -10; dz <= 10; dz++) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) continue;
					Block block = state.getBlock();
					if (NATURAL.contains(block)) continue;
					total++;
					counts.merge(block.getName().getString(), 1, Integer::sum);
					if (PRECIOUS.contains(block) && !precious.contains(block.getName().getString())) {
						precious.add(block.getName().getString());
					}
					minX = Math.min(minX, dx); maxX = Math.max(maxX, dx);
					minY = Math.min(minY, dy); maxY = Math.max(maxY, dy);
					minZ = Math.min(minZ, dz); maxZ = Math.max(maxZ, dz);
				}
			}
		}

		if (total < 8) {
			return "四周都是自然景物，看不出什么像样的人工建筑";
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder materials = new StringBuilder();
		for (int i = 0; i < Math.min(5, sorted.size()); i++) {
			if (i > 0) materials.append("、");
			materials.append(sorted.get(i).getKey()).append("×").append(sorted.get(i).getValue());
		}
		String size = (maxX - minX + 1) + "×" + (maxY - minY + 1) + "×" + (maxZ - minZ + 1);
		String desc = "一座约 " + size + "（宽×高×深）的建筑，共约 " + total + " 个人工方块，主要材料：" + materials;
		if (!precious.isEmpty()) {
			desc += "，还用到了贵重的" + String.join("、", precious);
		}
		return desc;
	}
}
