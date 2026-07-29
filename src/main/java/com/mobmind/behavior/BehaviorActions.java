package com.mobmind.behavior;

import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.ArrayList;

/**
 * 将 AI 输出的动作指令应用到生物行为上。
 */
public final class BehaviorActions {
	private BehaviorActions() {}

	public static final String[] VALID_ACTIONS = {"none", "calm", "follow", "stay", "flee", "gift", "attack"};

	public static boolean isValid(String action) {
		for (String a : VALID_ACTIONS) if (a.equals(action)) return true;
		return false;
	}

	/** 应用动作，返回实际生效的动作名 */
	public static String apply(Mob mob, Player player, String action) {
		if (!isValid(action)) action = "none";
		Level level = mob.level();
		long now = level.getLevelData().getGameTime();

		switch (action) {
			case "calm" -> {
				mob.setTarget(null);
				mob.setLastHurtByMob(null); // 清除仇恨记忆，防止原版AI重新锁定
				if (mob instanceof NeutralMob neutral) neutral.stopBeingAngry();
				MobMindState.calm(mob, player.getUUID(), now + 12000); // 10分钟（同时清除激怒状态）
				MobMindState.clearOrder(mob);
			}
			case "follow" -> MobMindState.setOrder(mob, MobMindState.OrderType.FOLLOW, player.getUUID(), now + 6000);
			case "stay" -> MobMindState.setOrder(mob, MobMindState.OrderType.STAY, player.getUUID(), now + 6000);
			case "flee" -> MobMindState.setOrder(mob, MobMindState.OrderType.FLEE, player.getUUID(), now + 1200);
			case "gift" -> {
				// 如果当前有未完成的以物易物约定，不要直接送礼，等玩家交付后再按约定交换
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) {
					return "none";
				}
				dropGiftFor(mob, player);
			}
			case "attack" -> {
				if (mob instanceof Monster || mob instanceof NeutralMob) {
					MobMindState.clearCalm(mob, player.getUUID()); // 翻脸：安抚作废
					MobMindState.provoke(mob, player.getUUID(), now + 6000); // 5分钟激怒，压过好感
					mob.setLastHurtByMob(player);
					mob.setTarget(player);
				} else {
					return "none"; // 被动生物不会攻击，忽略
				}
			}
			default -> { }
		}
		return action;
	}

	/** 让生物给玩家丢出一个礼物 */
	public static ItemStack dropGiftFor(Mob mob, Player player) {
		Level level = mob.level();
		ItemStack gift = giftFor(mob, mob.getRandom());
		ItemEntity drop = new ItemEntity(level, mob.getX(), mob.getY() + 0.5, mob.getZ(), gift.copy());
		// 给物品一个朝玩家方向的初速度，让礼物"丢"向玩家
		double dx = player.getX() - mob.getX();
		double dz = player.getZ() - mob.getZ();
		double dist = Math.sqrt(dx*dx + dz*dz);
		if (dist > 0) {
			drop.setDeltaMovement(dx / dist * 0.3, 0.2, dz / dist * 0.3);
		}
		drop.setThrower(player);
		if (!level.isClientSide()) {
			long gameTime = level.getLevelData().getGameTime();
			MobMindState.markRewardItem(drop.getUUID(), gameTime);
		}
		level.addFreshEntity(drop);
		return gift;
	}

	/** 让生物在附近丢出一个礼物 */
	public static ItemStack dropGiftNearby(Mob mob) {
		Level level = mob.level();
		ItemStack gift = giftFor(mob, mob.getRandom());
		RandomSource r = mob.getRandom();
		double ox = (r.nextDouble() - 0.5) * 1.5;
		double oz = (r.nextDouble() - 0.5) * 1.5;
		ItemEntity drop = new ItemEntity(level, mob.getX() + ox, mob.getY() + 0.5, mob.getZ() + oz, gift.copy());
		drop.setDeltaMovement(ox * 0.2, 0.15, oz * 0.2);
		level.addFreshEntity(drop);
		return gift;
	}

	private static String getEntityId(Mob mob) {
		Identifier key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
		return key != null ? key.toString() : "";
	}

	private static ItemStack giftFor(Mob mob, RandomSource random) {
		List<ItemStack> candidates = new ArrayList<>();
		String id = getEntityId(mob);

		// --- 根据生物类型ID添加特色礼物 ---
		switch (id) {
			case "minecraft:iron_golem" -> {
				candidates.add(new ItemStack(Items.POPPY));
				candidates.add(new ItemStack(Items.POPPY));
				candidates.add(new ItemStack(Items.IRON_INGOT, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.IRON_BLOCK));
			}
			case "minecraft:villager" -> {
				candidates.add(new ItemStack(Items.EMERALD, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.BREAD, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.WHEAT, random.nextInt(4) + 2));
				candidates.add(new ItemStack(Items.CARROT, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.POTATO, random.nextInt(3) + 1));
				if (random.nextFloat() < 0.1f) candidates.add(new ItemStack(Items.EMERALD_BLOCK));
			}
			case "minecraft:wandering_trader" -> {
				candidates.add(new ItemStack(Items.EMERALD));
				candidates.add(new ItemStack(Items.WHEAT_SEEDS, random.nextInt(4) + 1));
				candidates.add(new ItemStack(Items.PUMPKIN_SEEDS));
				candidates.add(new ItemStack(Items.MELON_SEEDS));
			}
			case "minecraft:mooshroom" -> {
				candidates.add(new ItemStack(Items.RED_MUSHROOM));
				candidates.add(new ItemStack(Items.BROWN_MUSHROOM));
				candidates.add(new ItemStack(Items.MUSHROOM_STEW));
				if (random.nextFloat() < 0.3f) candidates.add(new ItemStack(Items.LEATHER, random.nextInt(2) + 1));
			}
			case "minecraft:cow" -> {
				candidates.add(new ItemStack(Items.LEATHER, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.BEEF));
				candidates.add(new ItemStack(Items.MILK_BUCKET));
			}
			case "minecraft:pig" -> {
				candidates.add(new ItemStack(Items.PORKCHOP));
				if (random.nextFloat() < 0.3f) {
					candidates.add(new ItemStack(Items.CARROT));
					candidates.add(new ItemStack(Items.POTATO));
					candidates.add(new ItemStack(Items.BEETROOT));
				}
			}
			case "minecraft:chicken" -> {
				candidates.add(new ItemStack(Items.FEATHER, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.CHICKEN));
				candidates.add(new ItemStack(Items.EGG, random.nextInt(2) + 1));
			}
			case "minecraft:sheep" -> {
				// 羊送羊肉
				candidates.add(new ItemStack(Items.MUTTON, random.nextInt(2) + 1));
			}
			case "minecraft:rabbit" -> {
				candidates.add(new ItemStack(Items.RABBIT_HIDE));
				candidates.add(new ItemStack(Items.RABBIT));
				candidates.add(new ItemStack(Items.CARROT));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.RABBIT_FOOT));
			}
			case "minecraft:wither_skeleton" -> {
				candidates.add(new ItemStack(Items.BONE, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.COAL, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.WITHER_SKELETON_SKULL));
			}
			case "minecraft:stray" -> {
				candidates.add(new ItemStack(Items.BONE, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.ARROW, random.nextInt(4) + 2));
				candidates.add(new ItemStack(Items.SPECTRAL_ARROW, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.1f) candidates.add(new ItemStack(Items.TIPPED_ARROW));
			}
			case "minecraft:skeleton" -> {
				candidates.add(new ItemStack(Items.BONE, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.ARROW, random.nextInt(4) + 2));
				if (random.nextFloat() < 0.1f) candidates.add(new ItemStack(Items.BOW));
			}
			case "minecraft:drowned" -> {
				candidates.add(new ItemStack(Items.ROTTEN_FLESH, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.COPPER_INGOT, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.NAUTILUS_SHELL));
				if (random.nextFloat() < 0.05f) candidates.add(new ItemStack(Items.TRIDENT));
			}
			case "minecraft:zombified_piglin" -> {
				candidates.add(new ItemStack(Items.ROTTEN_FLESH, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.GOLD_NUGGET, random.nextInt(4) + 2));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.GOLD_INGOT));
			}
			case "minecraft:husk" -> {
				candidates.add(new ItemStack(Items.ROTTEN_FLESH, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.SAND, random.nextInt(3) + 1));
			}
			case "minecraft:zombie" -> {
				candidates.add(new ItemStack(Items.ROTTEN_FLESH, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.25f) candidates.add(new ItemStack(Items.IRON_INGOT));
				if (random.nextFloat() < 0.25f) {
					candidates.add(new ItemStack(Items.CARROT));
					candidates.add(new ItemStack(Items.POTATO));
				}
			}
			case "minecraft:creeper" -> {
				candidates.add(new ItemStack(Items.GUNPOWDER, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.1f) {
					candidates.add(new ItemStack(Items.MUSIC_DISC_11));
					candidates.add(new ItemStack(Items.MUSIC_DISC_13));
				}
			}
			case "minecraft:spider", "minecraft:cave_spider" -> {
				candidates.add(new ItemStack(Items.STRING, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.SPIDER_EYE));
				if (random.nextFloat() < 0.12f) {
					candidates.add(new ItemStack(Items.FERMENTED_SPIDER_EYE));
					candidates.add(new ItemStack(Items.COBWEB));
				}
			}
			case "minecraft:enderman" -> {
				if (random.nextFloat() < 0.5f) candidates.add(new ItemStack(Items.ENDER_PEARL));
				if (random.nextFloat() < 0.1f) candidates.add(new ItemStack(Items.ENDER_EYE));
				if (candidates.isEmpty()) {
					candidates.add(new ItemStack(Items.OBSIDIAN));
					candidates.add(new ItemStack(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.asItem()));
				}
			}
			case "minecraft:witch" -> {
				candidates.add(new ItemStack(Items.GLASS_BOTTLE, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.REDSTONE, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.GLOWSTONE_DUST, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.15f) {
					candidates.add(new ItemStack(Items.SPLASH_POTION));
					candidates.add(new ItemStack(Items.POTION));
				}
			}
			case "minecraft:blaze" -> {
				candidates.add(new ItemStack(Items.BLAZE_ROD));
				candidates.add(new ItemStack(Items.BLAZE_POWDER, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.MAGMA_CREAM, random.nextInt(2) + 1));
			}
			case "minecraft:ghast" -> {
				candidates.add(new ItemStack(Items.GHAST_TEAR));
				candidates.add(new ItemStack(Items.FIRE_CHARGE, random.nextInt(2) + 1));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.GUNPOWDER, random.nextInt(3) + 2));
			}
			case "minecraft:piglin", "minecraft:piglin_brute" -> {
				candidates.add(new ItemStack(Items.GOLD_NUGGET, random.nextInt(5) + 3));
				candidates.add(new ItemStack(Items.GOLD_INGOT));
				if (random.nextFloat() < 0.12f) {
					candidates.add(new ItemStack(Items.GOLDEN_SWORD));
					candidates.add(new ItemStack(Items.GOLDEN_APPLE));
				}
			}
			case "minecraft:hoglin" -> {
				candidates.add(new ItemStack(Items.PORKCHOP, random.nextInt(3) + 2));
				candidates.add(new ItemStack(Items.LEATHER, random.nextInt(2) + 1));
			}
			case "minecraft:wolf" -> {
				candidates.add(new ItemStack(Items.BONE, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.RABBIT));
				candidates.add(new ItemStack(Items.MUTTON));
				if (random.nextFloat() < 0.1f) candidates.add(new ItemStack(Items.LEATHER));
			}
			case "minecraft:cat", "minecraft:ocelot" -> {
				candidates.add(new ItemStack(Items.STRING));
				candidates.add(new ItemStack(Items.RABBIT_HIDE));
				candidates.add(new ItemStack(Items.FEATHER));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.RABBIT_FOOT));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.GOLD_INGOT));
			}
			case "minecraft:fox" -> {
				candidates.add(new ItemStack(Items.RABBIT_HIDE));
				candidates.add(new ItemStack(Items.SWEET_BERRIES, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.WHEAT));
				candidates.add(new ItemStack(Items.FEATHER));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.RABBIT_FOOT));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.EMERALD));
			}
			case "minecraft:llama", "minecraft:trader_llama" -> {
				candidates.add(new ItemStack(Items.LEATHER, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.WHEAT, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.HAY_BLOCK));
			}
			case "minecraft:glow_squid" -> {
				candidates.add(new ItemStack(Items.GLOW_INK_SAC, random.nextInt(2) + 1));
			}
			case "minecraft:squid" -> {
				candidates.add(new ItemStack(Items.INK_SAC, random.nextInt(2) + 1));
			}
			case "minecraft:dolphin" -> {
				candidates.add(new ItemStack(Items.COD, random.nextInt(2) + 1));
				candidates.add(new ItemStack(Items.SALMON));
				if (random.nextFloat() < 0.15f) candidates.add(new ItemStack(Items.NAUTILUS_SHELL));
			}
			case "minecraft:polar_bear" -> {
				candidates.add(new ItemStack(Items.COD, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.SALMON, random.nextInt(2) + 1));
			}
			case "minecraft:panda" -> {
				candidates.add(new ItemStack(Items.BAMBOO, random.nextInt(3) + 1));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.BAMBOO_BLOCK));
			}
			case "minecraft:parrot" -> {
				candidates.add(new ItemStack(Items.FEATHER, random.nextInt(3) + 1));
				candidates.add(new ItemStack(Items.WHEAT_SEEDS));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.NAME_TAG));
			}
			case "minecraft:bee" -> {
				candidates.add(new ItemStack(Items.HONEYCOMB));
				if (random.nextFloat() < 0.25f) candidates.add(new ItemStack(Items.HONEY_BOTTLE));
			}
			case "minecraft:frog" -> {
				candidates.add(new ItemStack(Items.LILY_PAD));
				if (random.nextFloat() < 0.25f) candidates.add(new ItemStack(Items.SLIME_BALL));
			}
			case "minecraft:allay" -> {
				candidates.add(new ItemStack(Items.AMETHYST_SHARD));
				if (random.nextFloat() < 0.25f) candidates.add(new ItemStack(Items.COOKIE));
			}
			case "minecraft:warden" -> {
				if (random.nextFloat() < 0.3f) {
					candidates.add(new ItemStack(Items.SCULK_CATALYST));
					candidates.add(new ItemStack(Items.DISC_FRAGMENT_5));
				} else {
					candidates.add(new ItemStack(Items.SCULK, random.nextInt(3) + 1));
				}
			}
			case "minecraft:cod" -> {
				candidates.add(new ItemStack(Items.COD));
				candidates.add(new ItemStack(Items.BONE_MEAL));
			}
			case "minecraft:salmon" -> {
				candidates.add(new ItemStack(Items.SALMON));
				candidates.add(new ItemStack(Items.BONE_MEAL));
			}
			case "minecraft:pufferfish" -> {
				candidates.add(new ItemStack(Items.PUFFERFISH));
				candidates.add(new ItemStack(Items.BONE_MEAL));
			}
			case "minecraft:tropical_fish" -> {
				candidates.add(new ItemStack(Items.TROPICAL_FISH));
				candidates.add(new ItemStack(Items.BONE_MEAL));
				if (random.nextFloat() < 0.08f) candidates.add(new ItemStack(Items.NAUTILUS_SHELL));
			}
			case "minecraft:turtle" -> {
				candidates.add(new ItemStack(Items.SEAGRASS, random.nextInt(3) + 1));
				if (random.nextFloat() < 0.15f) {
					candidates.add(new ItemStack(Items.TURTLE_HELMET));
					// SCUTE在新版本可能改名，安全起见跳过
				}
			}
			case "minecraft:bat" -> {
				if (random.nextFloat() < 0.25f) candidates.add(new ItemStack(Items.GLOW_BERRIES));
			}
			case "minecraft:zoglin" -> {
				candidates.add(new ItemStack(Items.ROTTEN_FLESH, random.nextInt(2) + 1));
			}
			default -> { /* 不认识的生物，用默认分类 */ }
		}

		// --- 默认按分类回退 ---
		if (candidates.isEmpty()) {
			PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
			switch (cat) {
				case PASSIVE -> {
					candidates.add(new ItemStack(Items.APPLE));
					candidates.add(new ItemStack(Items.WHEAT_SEEDS, random.nextInt(3) + 1));
					candidates.add(new ItemStack(Items.DANDELION));
					candidates.add(new ItemStack(Items.POPPY));
					candidates.add(new ItemStack(Items.BREAD));
				}
				case NEUTRAL -> {
					candidates.add(new ItemStack(Items.EMERALD));
					candidates.add(new ItemStack(Items.IRON_NUGGET, random.nextInt(3) + 1));
					candidates.add(new ItemStack(Items.FLINT));
					candidates.add(new ItemStack(Items.STICK));
				}
				case HOSTILE -> {
					candidates.add(new ItemStack(Items.BONE));
					candidates.add(new ItemStack(Items.ROTTEN_FLESH));
					candidates.add(new ItemStack(Items.GUNPOWDER));
					candidates.add(new ItemStack(Items.STRING));
					candidates.add(new ItemStack(Items.ARROW, random.nextInt(3) + 1));
				}
			}
		}

		if (candidates.isEmpty()) {
			return new ItemStack(Items.STICK);
		}
		// 过滤空物品
		candidates.removeIf(ItemStack::isEmpty);
		if (candidates.isEmpty()) {
			return new ItemStack(Items.APPLE);
		}
		return candidates.get(random.nextInt(candidates.size()));
	}
}
