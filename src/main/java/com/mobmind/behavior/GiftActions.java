package com.mobmind.behavior;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
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

			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8.0))) {
				// 只处理该玩家扔出的掉落物
				if (!(ie.getOwner() instanceof ServerPlayer owner) || owner != player) continue;
				if (ie.getItem().isEmpty()) continue;
				// 这些物品正在履行以物易物约定，不要当礼物吃掉
				if (BarterActions.isBarterItemForPlayer(player, ie.getItem().getItem())) continue;

				Mob mob = findNearbyFriendlyMob(level, ie, player, gameTime);
				if (mob == null) continue;
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) continue;

				long now = System.currentTimeMillis();
				Long last = LAST_GIFT_REACT.get(mob.getUUID());
				if (last != null && now - last < COOLDOWN_MS) continue;

				ItemStack stack = ie.getItem();
				if (tryEquipArmor(mob, player, stack, ie)) {
					LAST_GIFT_REACT.put(mob.getUUID(), now);
					continue;
				}
				if (tryEatFood(mob, player, stack, ie)) {
					LAST_GIFT_REACT.put(mob.getUUID(), now);
					continue;
				}
				// 普通礼物：收下整组并感谢
				acceptGift(mob, player, stack, ie);
				LAST_GIFT_REACT.put(mob.getUUID(), now);
			}
		}
	}

	private static Mob findNearbyFriendlyMob(ServerLevel level, ItemEntity ie, ServerPlayer player, long gameTime) {
		List<Mob> nearby = level.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(2.0),
				m -> m.isAlive()
						&& PersonaRegistry.supports(m)
						&& (MobMindState.isFriendlyTo(m, player.getUUID())
								|| MobMindState.isCalmedTowards(m, player.getUUID(), gameTime)));
		if (nearby.isEmpty()) return null;
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(ie)));
		return nearby.get(0);
	}

	private static boolean tryEquipArmor(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable == null) return false;
		EquipmentSlot slot = equippable.slot();
		if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;
		if (mob.getItemBySlot(slot).getItem() == stack.getItem()) return false;

		ItemStack old = mob.getItemBySlot(slot);
		mob.setItemSlot(slot, stack.copyWithCount(1));
		mob.setGuaranteedDrop(slot);
		mob.setPersistenceRequired();
		shrinkOrRemove(ie, 1);
		if (!old.isEmpty()) {
			ItemEntity drop = new ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
			mob.level().addFreshEntity(drop);
		}
		MobMindState.adjustFriendship(mob, player.getUUID(), 8);
		MobAiService.onArmorGiven(mob, player, stack.getHoverName().getString(), slot);
		return true;
	}

	private static boolean tryEatFood(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null) return false;
		if (mob.getHealth() >= mob.getMaxHealth()) return false;
		float heal = FoodValues.healFor(stack.getItem());
		if (heal <= 0) return false;

		mob.heal(heal);
		mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
				SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0f, 1.0f);
		shrinkOrRemove(ie, 1);
		MobMindState.adjustFriendship(mob, player.getUUID(), 3);
		MobAiService.onFoodFed(mob, player, stack.getHoverName().getString(), heal);
		return true;
	}

	private static void acceptGift(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		ie.discard();
		MobMindState.adjustFriendship(mob, player.getUUID(), 5);
		MobAiService.onGiftReceived(mob, player, stack.getHoverName().getString(), stack.getCount());
	}

	private static void shrinkOrRemove(ItemEntity ie, int count) {
		ItemStack stack = ie.getItem();
		stack.shrink(count);
		if (stack.isEmpty()) ie.discard();
	}
}
