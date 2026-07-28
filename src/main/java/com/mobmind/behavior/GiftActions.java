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
	private static final long COOLDOWN_MS = 3000;

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive()) continue;
			ServerLevel level = (ServerLevel) player.level();
			if (level.isClientSide()) continue;
			long gameTime = level.getLevelData().getGameTime();

			// 扫描玩家周围3格内的掉落物（必须贴着丢才捡）
			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3.0))) {
				// 只处理该玩家扔出的掉落物（getOwner 返回投掷该物品的实体）
				if (!(ie.getOwner() instanceof ServerPlayer owner) || owner != player) continue;
				if (ie.getItem().isEmpty()) continue;
				// 这些物品正在履行以物易物约定，不要当礼物处理
				if (BarterActions.isBarterItemForPlayer(player, ie.getItem().getItem())) continue;

				// 物品必须非常靠近某个生物才可能被捡
				ItemStack stack = ie.getItem();
				long now = System.currentTimeMillis();

				// 1. 箭：增加远程武器弹药，拾取门槛与武器相同（只要不是激怒状态都允许捡）
				if (isAmmo(stack)) {
					Mob ammoMob = findNearbyWeaponRecipient(level, ie, player, gameTime);
					if (ammoMob != null && !MobMindState.hasActiveBarterDeal(ammoMob, player.getUUID())) {
						// 玩家也必须靠近生物（蹭在一起）
						if (player.distanceTo(ammoMob) > 2.0) continue;
						Long last = LAST_GIFT_REACT.get(ammoMob.getUUID());
						if (last == null || now - last >= COOLDOWN_MS) {
							if (tryGiveAmmo(ammoMob, player, stack, ie)) {
								LAST_GIFT_REACT.put(ammoMob.getUUID(), now);
								continue;
							}
						}
					}
					continue; // 弹药不会被当作普通礼物
				}

				// 2. 不死图腾：拾取并存储，濒死时自动复活（门槛低）
				if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
					Mob totemMob = findNearbyWeaponRecipient(level, ie, player, gameTime);
					if (totemMob != null && !MobMindState.hasActiveBarterDeal(totemMob, player.getUUID())) {
						if (player.distanceTo(totemMob) > 2.0) continue;
						Long last = LAST_GIFT_REACT.get(totemMob.getUUID());
						if (last == null || now - last >= COOLDOWN_MS) {
							if (tryGiveTotem(totemMob, player, stack, ie)) {
								LAST_GIFT_REACT.put(totemMob.getUUID(), now);
								continue;
							}
						}
					}
					continue; // 图腾不当普通礼物
				}

				// 3. 武器/盾牌/盔甲：拾取门槛低（只要不是激怒状态都允许捡，玩家主动送装备表示善意）
				boolean isEquip = WeaponAttackGoal.isWeapon(stack) || isShield(stack) || isArmor(stack);
				if (isEquip) {
					Mob equipMob = findNearbyWeaponRecipient(level, ie, player, gameTime);
					if (equipMob != null && !MobMindState.hasActiveBarterDeal(equipMob, player.getUUID())) {
						if (player.distanceTo(equipMob) > 2.0) continue;
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
					continue; // 装备即使没捡成，也不当作普通礼物处理
				}

				// 4. 其他物品（食物、普通礼物）：需要友好/安抚/非敌对生物才捡
				Mob mob = findNearbyGiftRecipient(level, ie, player, gameTime);
				if (mob == null) continue;
				if (player.distanceTo(mob) > 2.0) continue;
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) continue;

				Long last = LAST_GIFT_REACT.get(mob.getUUID());
				if (last != null && now - last < COOLDOWN_MS) continue;

				// 尝试吃食物
				if (tryEatFood(mob, player, stack, ie)) {
					LAST_GIFT_REACT.put(mob.getUUID(), now);
					continue;
				}
				// 否则当作普通礼物收下并感谢
				acceptGift(mob, player, stack, ie);
				LAST_GIFT_REACT.put(mob.getUUID(), now);
			}
		}
	}

	private static Mob findNearbyGiftRecipient(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> {
					if (!m.isAlive()) return false;
					if (!PersonaRegistry.supports(m)) return false; // 没有设定文件的生物（牛猪鸡等）完全不收礼物
					if (!m.canHoldItem(ie.getItem())) return false;
					if (MobMindState.isProvokedTowards(m, player.getUUID(), gameTime)) return false;
					// 敌对生物也可以收礼物（只要没被激怒），方便玩家通过送礼积累好感
					return true;
				});
		if (nearby.isEmpty()) {
			com.mobmind.MobMindMod.LOGGER.debug("[MobMind] gift 附近无合适生物: item={}", ie.getItem());
			return null;
		}
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	/**
	 * 找附近愿意接收武器的生物。
	 * 武器捡取门槛比普通礼物低——敌对生物（僵尸、苦力怕等）即使在激怒状态也允许拾取武器
	 * （玩家主动给武器，意味着善意或装备升级需求）。
	 * 中立/被动生物仍然需要非激怒状态。
	 * 注意：只有PersonaRegistry中注册的生物才能接收武器，没有设定文件的生物（牛猪鸡等）完全不收。
	 */
	private static Mob findNearbyWeaponRecipient(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5),
				m -> {
					if (!m.isAlive()) return false;
					if (!PersonaRegistry.supports(m)) return false; // 没有设定文件的生物完全不收武器/装备
					if (!m.canHoldItem(ie.getItem())) return false;
					PersonalityGenerator.Category cat = MobMindState.categoryOf(m);
					// 敌对生物（HOSTILE）即使在激怒状态也允许拾取武器
					if (cat == PersonalityGenerator.Category.HOSTILE) return true;
					// 中立/被动生物需要非激怒状态
					return !MobMindState.isProvokedTowards(m, player.getUUID(), gameTime);
				});
		if (nearby.isEmpty()) {
			com.mobmind.MobMindMod.LOGGER.debug("[MobMind] 武器附近无合适生物: item={}", ie.getItem());
			return null;
		}
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	private static boolean tryEquipArmor(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable == null) return false;
		EquipmentSlot slot = equippable.slot();
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] tryEquipArmor: mob={} item={} slot={} type={}",
				mob.getType().getDescription().getString(), stack.getItem(), slot, slot.getType());
		if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;
		// 南瓜/雕刻南瓜/南瓜灯只做交易/礼物，不要自动戴头上
		if (stack.is(net.minecraft.world.item.Items.PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.CARVED_PUMPKIN)
				|| stack.is(net.minecraft.world.item.Items.JACK_O_LANTERN)) {
			return false;
		}
		if (mob.getItemBySlot(slot).getItem() == stack.getItem()) return false;

		// 先保存物品名（消耗前），避免 shrinkOrRemove 后变成空气
		String armorName = stack.getHoverName().getString();
		ItemStack old = mob.getItemBySlot(slot);
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
		// 主手已有同款武器则跳过，避免重复拾取
		if (mob.getItemBySlot(EquipmentSlot.MAINHAND).getItem() == stack.getItem()) return false;

		// 先保存武器名（消耗前），避免 shrinkOrRemove 后变成空气
		String weaponName = stack.getHoverName().getString();
		ItemStack old = mob.getItemBySlot(EquipmentSlot.MAINHAND);
		mob.setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
		mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		// 旧主手物品丢出来，不直接消失
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] tryEquipWeapon: mob={} item={} oldItem={}",
				mob.getType().getDescription().getString(), stack.getItem(), old.getItem());
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
		// 副手已有同款盾牌则跳过，避免重复拾取
		if (mob.getItemBySlot(EquipmentSlot.OFFHAND).getItem() == stack.getItem()) return false;

		// 先保存盾牌名（消耗前），避免 shrinkOrRemove 后变成空气
		String shieldName = stack.getHoverName().getString();
		ItemStack old = mob.getItemBySlot(EquipmentSlot.OFFHAND);
		mob.setItemSlot(EquipmentSlot.OFFHAND, stack.copyWithCount(1));
		mob.setGuaranteedDrop(EquipmentSlot.OFFHAND);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		// 旧副手物品丢出来，不直接消失
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] tryEquipShield: mob={} item={} oldItem={}",
				mob.getType().getDescription().getString(), stack.getItem(), old.getItem());
		com.mobmind.state.MobMindState.markPlayerGivenWeapon(mob);
		if (PersonaRegistry.supports(mob)) {
			MobMindState.adjustFriendship(mob, player.getUUID(), 8);
			MobAiService.onShieldGiven(mob, player, shieldName);
		}
		return true;
	}

	private static boolean tryEatFood(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null) return false;
		if (mob.getHealth() >= mob.getMaxHealth()) return false;
		float heal = FoodValues.healFor(stack.getItem());
		if (heal <= 0) return false;

		// 先保存食物名（消耗前），避免 shrinkOrRemove 后变成空气
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
}
