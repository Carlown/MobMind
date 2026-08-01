package com.mobmind.behavior;

import com.mobmind.MobMindMod;
import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好感度影响村民交易价格：
 * - 死敌(friendship<20): 涨价30%
 * - 陌生(20-39): 原价
 * - 认识(40-59): 降价5%
 * - 朋友(60-79): 降价15%
 * - 挚友(80+): 降价25%
 *
 * 实现方式：打开交易界面时通过 MerchantOffer.addToSpecialPriceDiff() 调整价格，
 * 关闭界面时还原，避免永久修改交易数据（砍价系统的永久折扣保持不变，可叠加）。
 */
public class VillagerTradePricing {
	private VillagerTradePricing() {}

	/** 记录每个 (村民,玩家,商品index) 已应用的好感度价格调整值，关闭界面时还原 */
	private static final Map<String, Integer> APPLIED_ADJUSTMENTS = new ConcurrentHashMap<>();
	/** 记录每个村民当前正在交易的玩家UUID，关闭时(null)需要知道还原谁的价格 */
	private static final Map<UUID, UUID> CURRENT_TRADER = new ConcurrentHashMap<>();

	/**
	 * 玩家打开村民交易界面时调用：根据好感度调整所有商品价格。
	 */
	public static void onOpenTrade(AbstractVillager villager, Player player) {
		if (villager.level().isClientSide()) return;
		UUID vId = villager.getUUID();
		UUID pId = player.getUUID();
		CURRENT_TRADER.put(vId, pId);

		// 先还原之前可能残留的调整
		resetFor(villager, pId);

		int friendship = MobMindState.friendship(villager, pId);
		if (friendship >= 20 && friendship < 40) return; // 陌生：原价，无需调整

		double multiplier = priceMultiplier(friendship);
		MerchantOffers offers = villager.getOffers();

		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer offer = offers.get(i);
			if (offer.isOutOfStock()) continue;

			int baseCostA = offer.getCostA().getCount();
			if (baseCostA <= 0) continue;

			// multiplier < 1 = 降价(specialPriceDiff为负), > 1 = 涨价(为正)
			int adjustment = (int) Math.round(baseCostA * (multiplier - 1.0));
			// 好感度非陌生时，即使舍入为0也至少调整1
			if (adjustment == 0 && multiplier != 1.0) {
				adjustment = multiplier > 1.0 ? 1 : -1;
			}
			if (adjustment == 0) continue;

			// 降价时不能低于1
			if (adjustment < 0) {
				int maxDiscount = -(baseCostA - 1); // 最多降到1
				if (adjustment < maxDiscount) adjustment = maxDiscount;
			}

			offer.addToSpecialPriceDiff(adjustment);
			String key = vId + ":" + pId + ":" + i;
			APPLIED_ADJUSTMENTS.put(key, adjustment);
		}

		MobMindMod.LOGGER.info("[MobMind] Friendship pricing: {} with {} friendship={}, price multiplier={}",
				player.getGameProfile().name(), villager.getType().getDescription().getString(), friendship, multiplier);
	}

	/**
	 * 玩家关闭交易界面时调用：还原好感度价格调整。
	 */
	public static void onCloseTrade(AbstractVillager villager) {
		if (villager.level().isClientSide()) return;
		UUID vId = villager.getUUID();
		UUID pId = CURRENT_TRADER.remove(vId);
		if (pId == null) return;
		resetFor(villager, pId);
	}

	private static void resetFor(AbstractVillager villager, UUID pId) {
		UUID vId = villager.getUUID();
		MerchantOffers offers = villager.getOffers();
		for (int i = 0; i < offers.size(); i++) {
			String key = vId + ":" + pId + ":" + i;
			Integer adj = APPLIED_ADJUSTMENTS.remove(key);
			if (adj != null && adj != 0) {
				offers.get(i).addToSpecialPriceDiff(-adj);
			}
		}
	}

	private static double priceMultiplier(int friendship) {
		if (friendship < 20) return 1.30;   // 死敌：涨价30%
		if (friendship < 40) return 1.00;   // 陌生：原价
		if (friendship < 60) return 0.95;   // 认识：95折
		if (friendship < 80) return 0.85;   // 朋友：85折
		return 0.75;                        // 挚友：75折
	}
}
