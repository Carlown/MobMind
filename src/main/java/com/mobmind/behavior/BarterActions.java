package com.mobmind.behavior;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.Personality;
import com.mobmind.state.MobMindState;
import com.mobmind.util.ItemCatalog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交易行为：
 * 1) 村民砍价——对某个在售商品降价；同一商品重复砍价会涨价（性情好的村民不涨价）。
 * 2) 以物易物——谈好约定后，玩家把约定物品扔给生物，生物回赠约定物品。支持多个支付/回赠物品。
 */
public final class BarterActions {
	private BarterActions() {}

	// ---------- 村民砍价 ----------

	public static void applyBargain(AbstractVillager villager, ServerPlayer player,
									Personality persona, String itemName, boolean agree) {
		MerchantOffers offers = villager.getOffers();
		int idx = -1;
		for (int i = 0; i < offers.size(); i++) {
			String resultName = offers.get(i).getResult().getHoverName().getString();
			if (!itemName.isEmpty() && (resultName.contains(itemName) || itemName.contains(resultName))) {
				idx = i;
				break;
			}
		}
		if (idx < 0) return;
		MerchantOffer offer = offers.get(idx);
		UUID vid = villager.getUUID();
		int times = MobMindState.bargainCount(vid, idx);
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] 处理砍价: item={} agree={} times={}", itemName, agree, times);
		if (times == 0) {
			if (agree) {
				int cut = Math.max(1, offer.getBaseCostA().getCount() / 4);
				offer.addToSpecialPriceDiff(-cut); // 首次砍价成功：降价约1/4
				MobMindState.markBargained(vid, idx);
				villager.playSound(SoundEvents.VILLAGER_YES, 1.0f, 1.0f);
				awardBargainAdvancement(player);
			} else {
				villager.playSound(SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
				// 拒绝时不再记录砍价次数，下次仍有机会成功
			}
		} else {
			// 同一商品重复砍价：性情好的只是拒绝，其余坐地起价
			boolean goodTempered = persona.alignment != null && persona.alignmentGood;
			if (agree && !goodTempered) {
				int inc = Math.max(1, offer.getBaseCostA().getCount() / 5);
				offer.addToSpecialPriceDiff(inc);
			}
			MobMindState.markBargained(vid, idx);
			villager.playSound(SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
			// 因为旧版本可能把失败次数也记进去了，所以只要玩家成功砍价就尝试补发成就
			if (agree) awardBargainAdvancement(player);
		}
	}

	/** 砍价成功授予成就 */
	private static void awardBargainAdvancement(ServerPlayer player) {
		var server = player.level().getServer();
		if (server == null) return;
		var holder = server.getAdvancements().get(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("mobmind", "bargain_success"));
		if (holder == null) {
			com.mobmind.MobMindMod.LOGGER.warn("[MobMind] 未找到砍价高手成就定义");
			return;
		}
		boolean granted = player.getAdvancements().award(holder, "bargain_success");
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] 砍价高手成就授予结果: {}", granted);
	}

	// ---------- 以物易物 ----------

	/** 生物刚捡起一个掉落物时，立即尝试完成以物易物约定 */
	public static void onMobPickedUp(Mob mob, ItemEntity itemEntity) {
		try {
			if (!(itemEntity.getOwner() instanceof ServerPlayer player)) return;
			UUID entityId = mob.getUUID();
			MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] onMobPickedUp: mob={} item={} deal={}",
					mob.getType().getDescription().getString(), itemEntity.getItem(), deal != null);
			if (deal == null || !deal.playerId().equals(player.getUUID())) return;
			if (!mob.isAlive()) return;
			long now = mob.level().getLevelData().getGameTime();
			if (now > deal.expireGameTime()) {
				MobMindState.clearBarterDeal(entityId);
				return;
			}
			if (player.distanceTo(mob) > 16) return;
			ServerLevel level = (ServerLevel) mob.level();
			List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(8.0));
			boolean canFulfill = canFulfillTotal(mob, nearby, deal.gives());
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] onMobPickedUp canFulfill={} gives={}", canFulfill, deal.gives());
			if (!canFulfill) return;

			consumeTotal(mob, nearby, deal.gives());
			MobMindState.clearBarterDeal(entityId);
			MobMindState.clearOrder(mob);
			MobMindState.adjustFriendship(mob, player.getUUID(), 18);
			MobMindState.calm(mob, player.getUUID(), now + 12000);
			for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
				ItemStack reward = rewardStack(take);
				dropRewardToPlayer(level, player, mob, reward);
			}
			String giveDesc = describe(deal.gives());
			String takeDesc = describe(deal.takes());
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物交付(捡起触发): {} ↔ {}", giveDesc, takeDesc);
			MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
			// 以物易物不属于砍价，不触发砍价成就
		} catch (Exception ex) {
			com.mobmind.MobMindMod.LOGGER.warn("[MobMind] onMobPickedUp 异常", ex);
		}
	}

	/** 判断某玩家当前是否有以物易物约定需要该物品 */
	public static boolean isBarterItemForPlayer(ServerPlayer player, Item item) {
		UUID pid = player.getUUID();
		Iterator<Map.Entry<UUID, MobMindState.BarterDeal>> it = MobMindState.barterDealEntries();
		while (it.hasNext()) {
			Map.Entry<UUID, MobMindState.BarterDeal> e = it.next();
			MobMindState.BarterDeal deal = e.getValue();
			if (!deal.playerId().equals(pid)) continue;
			for (MobMindState.BarterDeal.ItemRequirement req : deal.gives()) {
				if (req.item() == item) return true;
			}
		}
		return false;
	}

	/** 记录一笔约定：玩家需交付 gives，生物回赠 takes，5分钟有效 */
	public static void createDeal(Mob mob, ServerPlayer player,
								  List<ItemCatalog.MatchedItem> gives, List<ItemCatalog.MatchedItem> takes) {
		List<MobMindState.BarterDeal.ItemRequirement> giveReqs = toRequirements(gives);
		List<MobMindState.BarterDeal.ItemRequirement> takeReqs = toRequirements(takes);
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] createDeal 原始: gives={} takes={} -> 转换后 gives={} takes={}",
				gives, takes, giveReqs, takeReqs);
		if (giveReqs.isEmpty() || takeReqs.isEmpty()) {
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物约定物品无法识别: gives={} takes={}", gives, takes);
			return;
		}
		long now = mob.level().getLevelData().getGameTime();
		MobMindState.setBarterDeal(mob, new MobMindState.BarterDeal(
				player.getUUID(), giveReqs, takeReqs, now + 6000));
		// 交易期间生物原地待命看着玩家，不乱跑，方便玩家扔物品
		MobMindState.setOrder(mob, MobMindState.OrderType.STAY, player.getUUID(), now + 6000);
		String giveDesc = describe(giveReqs);
		String takeDesc = describe(takeReqs);
		MobAiService.notifyBarterDealMade(player, mob, giveDesc, takeDesc);
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物约定成立: {} 给 {} → 回赠 {}",
				player.getGameProfile().name(), giveDesc, takeDesc);
	}

	private static List<MobMindState.BarterDeal.ItemRequirement> toRequirements(List<ItemCatalog.MatchedItem> list) {
		List<MobMindState.BarterDeal.ItemRequirement> reqs = new ArrayList<>();
		for (ItemCatalog.MatchedItem m : list) {
			if (m == null || m.item() == null || m.item() == Items.AIR) continue;
			reqs.add(new MobMindState.BarterDeal.ItemRequirement(m.item(), Math.max(1, Math.min(64, m.count())),
					ItemCatalog.potionForName(m.name())));
		}
		return reqs;
	}

	private static String describe(List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < reqs.size(); i++) {
			if (i > 0) sb.append("+");
			MobMindState.BarterDeal.ItemRequirement r = reqs.get(i);
			sb.append(rewardStack(r).getHoverName().getString()).append("×").append(r.count());
		}
		return sb.toString();
	}

	/** 构造回赠物品栈；药水类会附加具体药效，避免给出水瓶 */
	private static ItemStack rewardStack(MobMindState.BarterDeal.ItemRequirement req) {
		ItemStack stack = new ItemStack(req.item(), req.count());
		if (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION) {
			try {
				net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion = req.potion() != null ? req.potion() : Potions.HEALING;
				com.mobmind.MobMindMod.LOGGER.info("[MobMind] rewardStack potion: {} -> {}", req.item(), potion);
				stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
			} catch (Exception e) {
				com.mobmind.MobMindMod.LOGGER.warn("[MobMind] 药水 NBT 设置失败: {}", e.getMessage());
			}
		}
		return stack;
	}

	/** 每20tick扫描：玩家是否把约定物品扔到了生物身边 */
	public static void tickDeals(MinecraftServer server) {
		if (MobMindState.barterDealsEmpty()) return;
		long now = server.overworld().getLevelData().getGameTime();
		Iterator<Map.Entry<UUID, MobMindState.BarterDeal>> it = MobMindState.barterDealEntries();
		while (it.hasNext()) {
			try {
				Map.Entry<UUID, MobMindState.BarterDeal> e = it.next();
				MobMindState.BarterDeal deal = e.getValue();
				Mob mob = findMob(server, e.getKey());
				if (mob == null || !mob.isAlive() || now > deal.expireGameTime()) {
					it.remove();
					continue;
				}
				ServerPlayer player = server.getPlayerList().getPlayer(deal.playerId());
				if (player == null || player.distanceTo(mob) > 16) continue;
				ServerLevel level = (ServerLevel) mob.level();

				// 玩家丢到生物附近的掉落物 + 生物自己捡起来的手持/背包物品都算交付
				List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(8.0));
				boolean canFulfill = canFulfillTotal(mob, nearby, deal.gives());
				com.mobmind.MobMindMod.LOGGER.info("[MobMind] tickDeals: mob={} canFulfill={} gives={} nearbyItems={}",
					mob.getType().getDescription().getString(), canFulfill, deal.gives(), nearby.size());
				if (!canFulfill) continue;

				// 交付：先扣地面掉落物，不够再扣生物手持/背包
				consumeTotal(mob, nearby, deal.gives());
				it.remove();
				MobMindState.clearOrder(mob);
				MobMindState.adjustFriendship(mob, player.getUUID(), 18);
				MobMindState.calm(mob, player.getUUID(), now + 12000); // 和解 10 分钟
				for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
					ItemStack reward = rewardStack(take);
					dropRewardToPlayer(level, player, mob, reward);
				}

				String giveDesc = describe(deal.gives());
				String takeDesc = describe(deal.takes());
				com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物交付: {} ↔ {}", giveDesc, takeDesc);
				MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
				// 以物易物不属于砍价，不触发砍价成就
			} catch (Exception ex) {
				com.mobmind.MobMindMod.LOGGER.warn("[MobMind] tickDeals 异常", ex);
			}
		}
	}

	/** 统计生物手持+背包里某物品的数量 */
	private static int countInMob(Mob mob, Item item) {
		int count = 0;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = mob.getItemInHand(hand);
			if (stack.is(item)) count += stack.getCount();
		}
		if (mob instanceof InventoryCarrier carrier) {
			count += carrier.getInventory().countItem(item);
		}
		return count;
	}

	/** 检查地面掉落物 + 生物手持/背包是否能满足所有需求 */
	private static boolean canFulfillTotal(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int have = countInMob(mob, req.item());
			for (ItemEntity ie : items) {
				if (ie.getItem().is(req.item())) have += ie.getItem().getCount();
			}
			if (have < req.count()) return false;
		}
		return true;
	}

	/** 先扣地面掉落物，不够再扣生物手持/背包 */
	private static void consumeTotal(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int remaining = req.count();
			// 1) 地面掉落物
			for (ItemEntity ie : items) {
				ItemStack stack = ie.getItem();
				if (!stack.is(req.item())) continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty()) ie.discard();
				remaining -= take;
				if (remaining <= 0) break;
			}
			if (remaining <= 0) continue;
			// 2) 生物主/副手
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = mob.getItemInHand(hand);
				if (!stack.is(req.item())) continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty()) mob.setItemInHand(hand, ItemStack.EMPTY);
				remaining -= take;
				if (remaining <= 0) break;
			}
			if (remaining <= 0) continue;
			// 3) 生物背包
			if (mob instanceof InventoryCarrier carrier) {
				net.minecraft.world.SimpleContainer inv = carrier.getInventory();
				for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
					ItemStack stack = inv.getItem(i);
					if (!stack.is(req.item())) continue;
					int take = Math.min(remaining, stack.getCount());
					stack.shrink(take);
					if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
					remaining -= take;
				}
			}
		}
	}

	/** 把回赠物品交给玩家；背包满则丢在玩家脚边，并设短延迟防止村民等生物抢回去 */
	private static void dropRewardToPlayer(ServerLevel level, ServerPlayer player, Mob mob, ItemStack reward) {
		if (!player.addItem(reward.copy())) {
			ItemEntity drop = new ItemEntity(level, player.getX(), player.getY() + 0.3, player.getZ(), reward);
			drop.setThrower(mob);
			drop.setPickUpDelay(20);
			level.addFreshEntity(drop);
		}
	}

	private static Mob findMob(MinecraftServer server, UUID entityId) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(entityId) instanceof Mob mob) return mob;
		}
		return null;
	}
}
