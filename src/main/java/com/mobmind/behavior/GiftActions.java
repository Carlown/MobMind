package com.mobmind.behavior;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import com.mobmind.util.FoodValues;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家向友好/已安抚生物扔物品送礼：
 * - 盔甲 → 自动穿上
 * - 食物 → 血量不满时吃掉回血
 * - 其它 → 收下并感谢
 */
public final class GiftActions {
	private GiftActions() {}

	private static final Map<UUID, Long> LAST_GIFT_REACT = new ConcurrentHashMap<>();
	private static final long COOLDOWN_MS = 1000;

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive()) continue;
			ServerLevel level = (ServerLevel) player.level();
			if (level.isClientSide()) continue;
			long gameTime = level.getLevelData().getGameTime();

			// 扫描玩家周围3格内的掉落物（必须贴脸/靠在一起才接收）
			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3.0))) {
				if (!(ie.getOwner() instanceof ServerPlayer owner) || owner != player) continue;
				if (ie.getItem().isEmpty()) continue;
				// 交易物品不作为礼物处理，但如果有活跃交易约定的生物在附近，主动帮它接收（解决女巫等无拾取AI生物的问题）
				if (BarterActions.isBarterItemForPlayer(player, ie.getItem().getItem())) {
					Mob barterMob = findNearbyBarterMob(level, ie, player);
					if (barterMob != null && player.distanceTo(barterMob) <= 2.0) {
						BarterActions.tryAcceptBarterItem(barterMob, player, ie.getItem(), ie);
					}
					continue;
				}

				ItemStack stack = ie.getItem();
				long now = System.currentTimeMillis();

				// 所有物品都需要玩家和生物靠在一起（1.5格内贴脸）才捡，远了不收
				// 铁傀儡/雪傀儡/铜傀儡完全不拦截掉落物，交给它们自己的mod/AI处理
				//   - 铜傀儡：自己有整理箱子搬运功能
				//   - 铁傀儡/雪傀儡：通过右键喂食/修复，扔地上不处理

				// 1. 箭：增加远程武器弹药（贴脸）
			if (isAmmo(stack)) {
				Mob ammoMob = findNearbyAmmoRecipient(level, ie, player, gameTime);
				if (ammoMob != null && player.distanceTo(ammoMob) <= 1.5
						&& !MobMindState.hasActiveBarterDeal(ammoMob, player.getUUID())) {
						Long last = LAST_GIFT_REACT.get(ammoMob.getUUID());
						if (last == null || now - last >= COOLDOWN_MS) {
							if (tryGiveAmmo(ammoMob, player, stack, ie)) {
								LAST_GIFT_REACT.put(ammoMob.getUUID(), now);
								continue;
							}
						}
					}
					continue;
				}

				// 2. 不死图腾（贴脸）
				if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
					Mob totemMob = findNearbyWeaponRecipient(level, ie, player, gameTime);
					if (totemMob != null && player.distanceTo(totemMob) <= 1.5
							&& !MobMindState.hasActiveBarterDeal(totemMob, player.getUUID())) {
						Long last = LAST_GIFT_REACT.get(totemMob.getUUID());
						if (last == null || now - last >= COOLDOWN_MS) {
							if (tryGiveTotem(totemMob, player, stack, ie)) {
								LAST_GIFT_REACT.put(totemMob.getUUID(), now);
								continue;
							}
						}
					}
					continue;
				}

				// 3. 武器/盾牌/盔甲（贴脸）
				boolean isEquip = WeaponAttackGoal.isWeapon(stack) || isShield(stack) || isArmor(stack);
				if (isEquip) {
					Mob equipMob = findNearbyWeaponRecipient(level, ie, player, gameTime);
					if (equipMob != null && player.distanceTo(equipMob) <= 1.5
							&& !MobMindState.hasActiveBarterDeal(equipMob, player.getUUID())) {
						Long last = LAST_GIFT_REACT.get(equipMob.getUUID());
						if (last == null || now - last >= COOLDOWN_MS) {
							boolean applied = false;
							if (isShield(stack)) {
								applied = tryEquipShield(equipMob, player, stack, ie);
							} else if (WeaponAttackGoal.isWeapon(stack)) {
								applied = tryEquipWeapon(equipMob, player, stack, ie);
							} else if (isArmor(stack)) {
								applied = tryEquipArmor(equipMob, player, stack, ie);
							}
							if (applied) {
								LAST_GIFT_REACT.put(equipMob.getUUID(), now);
								continue;
							}
						}
					}
					continue;
				}

				// 4. 食物/普通礼物（贴脸1.5格内）
				Mob mob = findNearbyGiftRecipient(level, ie, player, gameTime);
				if (mob == null) continue;
				if (player.distanceTo(mob) > 1.5) continue;
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) continue;

				Long last = LAST_GIFT_REACT.get(mob.getUUID());
				if (last != null && now - last < COOLDOWN_MS) continue;

				if (tryEatFood(mob, player, stack, ie)) {
					LAST_GIFT_REACT.put(mob.getUUID(), now);
					continue;
				}
				acceptGift(mob, player, stack, ie);
				LAST_GIFT_REACT.put(mob.getUUID(), now);
			}
		}
	}

	private static Mob findNearbyGiftRecipient(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> {
					if (!m.isAlive()) return false;
					if (!shouldInterceptDrops(m)) return false; // 铁傀儡/雪傀儡/铜傀儡不拦截掉落物
					if (!m.canHoldItem(ie.getItem())) return false;
					// 未注册persona的生物也可以接收礼物，只要能持物且不被激怒
					if (PersonaRegistry.supports(m) && MobMindState.isProvokedTowards(m, player.getUUID(), gameTime)) return false;
					return true;
				});
		if (nearby.isEmpty()) {
			return null;
		}
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	/** 查找附近有活跃交易约定的生物（用于主动帮无拾取AI的生物接收交易物品） */
	private static Mob findNearbyBarterMob(ServerLevel level, ItemEntity ie, ServerPlayer player) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> m.isAlive() && MobMindState.hasActiveBarterDeal(m, player.getUUID()));
		if (nearby.isEmpty()) return null;
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	/** 查找附近可以接收箭矢的生物：必须持有弓/弩，不检查canHoldItem（箭进入MobMind弹药系统，不是手持物品） */
	private static Mob findNearbyAmmoRecipient(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> {
					if (!m.isAlive()) return false;
					if (!canUseEquipment(m)) return false;
					// 必须持有弓或弩才能接收箭矢
					if (!WeaponRangedAttackGoal.isHoldingRangedWeapon(m)) return false;
					// 未注册persona的生物也可以接收弹药
					PersonalityGenerator.Category cat = PersonaRegistry.supports(m) ? MobMindState.categoryOf(m) : null;
					if (cat == PersonalityGenerator.Category.HOSTILE) return true;
					if (!PersonaRegistry.supports(m)) return true;
					return !MobMindState.isProvokedTowards(m, player.getUUID(), gameTime);
				});
		if (nearby.isEmpty()) {
			return null;
		}
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	private static Mob findNearbyWeaponRecipient(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> {
					if (!m.isAlive()) return false;
					if (!canUseEquipment(m)) return false;
					if (!m.canHoldItem(ie.getItem())) return false;
					// 未注册persona的生物（如焦骸等）也可以接收装备，只要能持物
					PersonalityGenerator.Category cat = PersonaRegistry.supports(m) ? MobMindState.categoryOf(m) : null;
					if (cat == PersonalityGenerator.Category.HOSTILE) return true;
					// 未注册persona的生物默认允许接收装备
					if (!PersonaRegistry.supports(m)) return true;
					return !MobMindState.isProvokedTowards(m, player.getUUID(), gameTime);
				});
		if (nearby.isEmpty()) {
			return null;
		}
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	private static boolean tryEquipArmor(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable == null) return false;
		EquipmentSlot slot = equippable.slot();
		if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;
		// 南瓜/雕刻南瓜/南瓜灯只做交易/礼物，不要自动戴头上
		if (stack.is(net.minecraft.world.item.Items.PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.CARVED_PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.JACK_O_LANTERN)) {
			return false;
		}
		ItemStack old = mob.getItemBySlot(slot);
		if (!isBetterItem(stack, old)) return false;

		String armorName = stack.getHoverName().getString();
		mob.setItemSlot(slot, stack.copyWithCount(1));
		mob.setGuaranteedDrop(slot);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 8);
			MobAiService.onArmorGiven(mob, player, armorName, slot);
		}
		return true;
	}

	/** 丢武器给生物：生物会拾起武器装备到主手，并正确使用攻击 */
	private static boolean tryEquipWeapon(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (!WeaponAttackGoal.isWeapon(stack)) return false;
		ItemStack old = mob.getItemBySlot(EquipmentSlot.MAINHAND);
		if (!isBetterItem(stack, old)) return false;

		String weaponName = stack.getHoverName().getString();
		mob.setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
		mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		com.mobmind.state.MobMindState.markPlayerGivenWeapon(mob);
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 8);
			MobAiService.onWeaponGiven(mob, player, weaponName);
		}
		return true;
	}

	/** 判断物品栈是否是盾牌（MC 26.2 中盾牌通过 BlocksAttacks 组件标记） */
	public static boolean isShield(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		// MC 26.2 中盾牌通过 BlocksAttacks 数据组件标记
		return stack.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS) != null;
	}

	/** 判断物品栈是否是人形盔甲（可装备到盔甲栏，排除南瓜/雕刻南瓜/南瓜灯） */
	private static boolean isArmor(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable == null) return false;
		if (equippable.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;
		// 南瓜类只做交易/礼物，不当盔甲自动穿上
		if (stack.is(net.minecraft.world.item.Items.PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.CARVED_PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.JACK_O_LANTERN)) {
			return false;
		}
		return true;
	}

	/** 判断物品栈是否是远程武器弹药 */
	private static boolean isAmmo(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.is(net.minecraft.world.item.Items.ARROW)
				|| stack.is(net.minecraft.world.item.Items.SPECTRAL_ARROW)
				|| stack.is(net.minecraft.world.item.Items.TIPPED_ARROW);
	}

	/** 丢箭给生物：增加远程武器弹药（支持普通箭/光灵箭/药水箭，特殊箭优先使用） */
	private static boolean tryGiveAmmo(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (!isAmmo(stack)) return false;
		String ammoName = stack.getHoverName().getString();
		int count = stack.getCount();
		String ammoKey = MobMindState.ammoKeyFor(stack);
		if (ammoKey != null) {
			MobMindState.addAmmo(mob, ammoKey, count);
		}
		mob.setPersistenceRequired();
		ie.discard();
		// 如果生物正持弓/弩，标记为玩家给予武器，让自定义远程Goal接管以优先使用特殊箭
		if (WeaponRangedAttackGoal.isHoldingRangedWeapon(mob)) {
			MobMindState.markPlayerGivenWeapon(mob);
		}
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 4);
			MobAiService.onAmmoGiven(mob, player, ammoName, count);
		}
		return true;
	}

	/** 丢不死图腾给生物：存储图腾，濒死时自动复活 */
	private static boolean tryGiveTotem(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (!stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) return false;
		String totemName = stack.getHoverName().getString();
		int count = stack.getCount();
		MobMindState.addTotem(mob, count);
		mob.setPersistenceRequired();
		ie.discard();
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 10);
			MobAiService.onTotemGiven(mob, player, totemName, count);
		}
		return true;
	}

	/** 丢盾牌给生物：生物会拾起盾牌装备到副手，并正确使用格挡 */
	private static boolean tryEquipShield(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (!isShield(stack)) return false;
		ItemStack old = mob.getItemBySlot(EquipmentSlot.OFFHAND);
		if (!isBetterItem(stack, old)) return false;

		String shieldName = stack.getHoverName().getString();
		mob.setItemSlot(EquipmentSlot.OFFHAND, stack.copyWithCount(1));
		mob.setGuaranteedDrop(EquipmentSlot.OFFHAND);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		com.mobmind.state.MobMindState.markPlayerGivenWeapon(mob);
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 8);
			MobAiService.onShieldGiven(mob, player, shieldName);
		}
		return true;
	}

	private static boolean tryEatFood(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null) return false;

		float heal = FoodValues.healFor(stack.getItem());
		if (heal <= 0) return false;

		// 满血时存起来，等没血了自动吃
		if (mob.getHealth() >= mob.getMaxHealth()) {
			int count = stack.getCount();
			String foodName = stack.getHoverName().getString();
			MobMindState.addStoredFood(mob, count);
			mob.setPersistenceRequired();
			ie.discard();
			if (PersonaRegistry.supports(mob)) {
				MobMindState.adjustFriendship(mob, player.getUUID(), 3);
				MobAiService.onFoodStored(mob, player, foodName, count);
			}
			return true;
		}

		// 没满血：立即吃掉回血
		String foodName = stack.getHoverName().getString();
		mob.heal(heal);
		mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
				SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0f, 1.0f);
		shrinkOrRemove(ie, 1);
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 3);
			MobAiService.onFoodFed(mob, player, foodName, heal);
		}
		return true;
	}

	private static void acceptGift(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		// 先保存物品名和数量（消耗前），避免 discard 后变成空气
		String giftName = stack.getHoverName().getString();
		int giftCount = stack.getCount();
		// 村民等拥有背包的生物：把礼物放进背包，这样也能用于后续交易交付
		if (mob instanceof net.minecraft.world.entity.npc.InventoryCarrier carrier) {
			net.minecraft.world.item.ItemStack remaining = carrier.getInventory().addItem(stack.copy());
			if (remaining.isEmpty()) {
				ie.discard();
			} else {
				ie.setItem(remaining);
			}
		} else {
			ie.discard();
		}
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 5);
			MobAiService.onGiftReceived(mob, player, giftName, giftCount);
		}
	}

	private static void shrinkOrRemove(ItemEntity ie, int count) {
		ItemStack stack = ie.getItem();
		stack.shrink(count);
		if (stack.isEmpty()) ie.discard();
	}

	/**
	 * 判断是否应该拦截该生物附近的掉落物（由我们的mod处理送礼/装备）。
	 * 只有铜傀儡不拦截——它有自己的整理箱子搬运功能，掉落物交给它自己的mod处理。
	 * 铁傀儡、雪傀儡等其他所有生物都可以接收物品。
	 */
	private static boolean shouldInterceptDrops(Mob mob) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
		if ("minecraft:copper_golem".equals(id)) {
			return false;
		}
		return true;
	}

	/**
	 * 判断生物是否能使用玩家给的装备（武器/盾牌/盔甲/弹药/图腾）。
	 * 铜傀儡不能装备（有自己的整理功能，装备会被AI丢弃导致复制bug）。
	 */
	private static boolean canUseEquipment(Mob mob) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
		if ("minecraft:copper_golem".equals(id)) {
			return false;
		}
		return true;
	}

	/**
	 * 评估物品的"战力评分"，用于判断是否应该替换旧装备。
	 * 评分越高越好：材质等级 + 附魔加成 + 耐久加成。
	 */
	private static int getItemScore(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		int score = 0;
		String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

		if (itemName.contains("netherite")) score += 500;
		else if (itemName.contains("diamond")) score += 400;
		else if (itemName.contains("iron")) score += 300;
		else if (itemName.contains("stone") || itemName.contains("chainmail")) score += 200;
		else if (itemName.contains("golden")) score += 150;
		else if (itemName.contains("wooden") || itemName.contains("leather")) score += 100;

		var enchantments = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
		if (enchantments != null) {
			for (var entry : enchantments.entrySet()) {
				score += entry.getIntValue() * 20;
			}
		}

		if (stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int damage = stack.getDamageValue();
			if (max > 0) score += (int)((max - damage) * 0.5);
		}

		if (isShield(stack)) score += 200;

		return score;
	}

	/**
	 * 判断新物品是否比旧物品好（应该替换旧物品）。
	 */
	private static boolean isBetterItem(ItemStack newStack, ItemStack oldStack) {
		if (oldStack == null || oldStack.isEmpty()) return true;
		if (newStack == null || newStack.isEmpty()) return false;
		if (newStack.getItem() == oldStack.getItem()) {
			return getItemScore(newStack) > getItemScore(oldStack);
		}
		return getItemScore(newStack) > getItemScore(oldStack);
	}
}
