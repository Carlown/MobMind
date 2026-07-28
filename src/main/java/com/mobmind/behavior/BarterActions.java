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

	/** 生物刚捡起一个掉落物时，立即尝试完成以物易物约定或信守承诺 */
	public static void onMobPickedUp(Mob mob, ItemEntity itemEntity) {
		try {
			if (!(itemEntity.getOwner() instanceof ServerPlayer player)) return;
			UUID entityId = mob.getUUID();
			ServerLevel level = (ServerLevel) mob.level();
			long now = level.getLevelData().getGameTime();
			UUID playerId = player.getUUID();

			// 1. 先检查是否有正式的以物易物约定
			MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
			if (deal != null && deal.playerId().equals(playerId) && mob.isAlive()) {
				if (now <= deal.expireGameTime() && player.distanceTo(mob) <= 24) {
					// 只统计该玩家扔出的掉落物（过滤掉其他玩家/自然掉落的物品）
					List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(10.0),
							ie -> ie.getOwner() instanceof ServerPlayer sp && sp.getUUID().equals(playerId));
					// 统计：地面(玩家扔的) + 手持 + 背包（村民等InventoryCarrier捡起物品直接进背包）
					boolean canFulfill = canFulfillTotal(mob, nearby, deal.gives());
					com.mobmind.MobMindMod.LOGGER.info("[MobMind] onMobPickedUp(约定) canFulfill={} gives={}", canFulfill, deal.gives());
					if (canFulfill) {
						consumeTotal(mob, nearby, deal.gives());
						MobMindState.clearBarterDeal(entityId);
						MobMindState.clearGiftPromise(entityId);
						MobMindState.clearOrder(mob);
						MobMindState.adjustFriendship(mob, playerId, 20);
						MobMindState.calm(mob, playerId, now + 12000);
						for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
							ItemStack reward = rewardStack(take);
							dropRewardToPlayer(level, player, mob, reward);
						}
						String giveDesc = describe(deal.gives());
						String takeDesc = describe(deal.takes());
						com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物交付(捡起触发): {} ↔ {}", giveDesc, takeDesc);
						MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
						return;
					} else {
						// 检查是否在欺骗：玩家扔了不符合约定的物品（错误类型或错误药水）
						ItemStack picked = itemEntity.getItem();
						if (!picked.isEmpty()) {
							CheatCheck cheat = detectCheat(picked, deal.gives());
							if (cheat.isCheat) {
								com.mobmind.MobMindMod.LOGGER.info("[MobMind] 玩家欺骗！约定: {} 实际扔: {}", describe(deal.gives()), picked.getHoverName().getString());
								MobAiService.onPlayerCheatedBarter(mob, player, describe(deal.gives()), cheat.actualDesc);
								return;
							}
						}
					}
				} else {
					MobMindState.clearBarterDeal(entityId);
				}
			}

			// 2. 检查是否有承诺赠送（生物说过要给东西）
			MobMindState.GiftPromise promise = MobMindState.getGiftPromise(entityId);
			if (promise != null && promise.playerId().equals(playerId) && mob.isAlive()) {
				if (now <= promise.expireGameTime() && player.distanceTo(mob) <= 24) {
					// 免费赠送（如"送你一个马铃薯"）：不需要玩家给东西，直接给
					if (!promise.requiresPayment()) {
						MobMindState.clearGiftPromise(entityId);
						MobMindState.adjustFriendship(mob, playerId, 10);
						for (MobMindState.BarterDeal.ItemRequirement promised : promise.promisedItems()) {
							ItemStack reward = rewardStack(promised);
							dropRewardToPlayer(level, player, mob, reward);
							com.mobmind.MobMindMod.LOGGER.info("[MobMind] 免费赠送: {} 给 {} {}",
									mob.getType().getDescription().getString(),
									player.getGameProfile().name(),
									reward.getHoverName().getString());
						}
						MobAiService.notifyBarterCompleted(player, mob, "免费", describe(promise.promisedItems()));
						return;
					}
					// 有条件承诺：只统计该玩家扔出的掉落物
					List<ItemEntity> nearbyPlayerItems = level.getEntitiesOfClass(ItemEntity.class,
							mob.getBoundingBox().inflate(10.0),
							ie -> ie.getOwner() instanceof ServerPlayer sp && sp.getUUID().equals(playerId));
					// 玩家给了东西：地面有物品 或 手持/背包有新物品
					boolean playerGaveSomething = !nearbyPlayerItems.isEmpty()
							|| !mob.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
							|| !mob.getItemInHand(InteractionHand.OFF_HAND).isEmpty();
					if (playerGaveSomething) {
						// 消耗地面物品
						for (ItemEntity ie : nearbyPlayerItems) {
							ie.discard();
						}
						MobMindState.clearGiftPromise(entityId);
						MobMindState.adjustFriendship(mob, playerId, 15);
						MobMindState.calm(mob, playerId, now + 6000);
						for (MobMindState.BarterDeal.ItemRequirement promised : promise.promisedItems()) {
							ItemStack reward = rewardStack(promised);
							dropRewardToPlayer(level, player, mob, reward);
							com.mobmind.MobMindMod.LOGGER.info("[MobMind] 信守承诺: {} 给 {} {}",
									mob.getType().getDescription().getString(),
									player.getGameProfile().name(),
									reward.getHoverName().getString());
						}
						MobAiService.notifyBarterCompleted(player, mob, "物品", describe(promise.promisedItems()));
					}
				} else {
					MobMindState.clearGiftPromise(entityId);
				}
			}
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

	/** 记录一笔约定：玩家需交付 gives，生物回赠 takes，10分钟有效 */
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
				player.getUUID(), giveReqs, takeReqs, now + 12000)); // 10分钟有效
		// 交易期间生物原地待命看着玩家，不乱跑，方便玩家扔物品
		MobMindState.setOrder(mob, MobMindState.OrderType.STAY, player.getUUID(), now + 12000);
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
				net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion = req.potion();
				if (potion == null) {
					// 未知药水类型时给水瓶而非治疗药水，避免错误物品
					com.mobmind.MobMindMod.LOGGER.warn("[MobMind] rewardStack: 未知药水类型，将给水瓶");
					potion = Potions.WATER;
				}
				stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
			} catch (Exception e) {
				com.mobmind.MobMindMod.LOGGER.warn("[MobMind] 药水 NBT 设置失败: {}", e.getMessage());
			}
		}
		return stack;
	}

	/** 每20tick扫描：玩家是否把约定物品扔到了生物身边；同时检查承诺赠送 */
	public static void tickDeals(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();

		// 1. 处理正式的以物易物约定
		if (!MobMindState.barterDealsEmpty()) {
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
					if (player == null || player.distanceTo(mob) > 24) continue;
					ServerLevel level = (ServerLevel) mob.level();
					UUID playerId = player.getUUID();

					// 只统计该玩家扔到生物附近的掉落物（过滤其他玩家/自然掉落）
					List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class,
							mob.getBoundingBox().inflate(10.0),
							ie -> ie.getOwner() instanceof ServerPlayer sp && sp.getUUID().equals(playerId));
					// 统计：地面(玩家扔的) + 手持 + 背包（村民等InventoryCarrier捡起物品进背包）
					boolean canFulfill = canFulfillTotal(mob, nearby, deal.gives());
					com.mobmind.MobMindMod.LOGGER.info("[MobMind] tickDeals: mob={} canFulfill={} gives={} nearbyItems={}",
						mob.getType().getDescription().getString(), canFulfill, deal.gives(), nearby.size());
					if (!canFulfill) continue;

					// 交付：地面→手持→背包，正确扣除所有物品
					consumeTotal(mob, nearby, deal.gives());
					it.remove();
					MobMindState.clearGiftPromise(e.getKey()); // 完成约定同时清除承诺
					MobMindState.clearOrder(mob);
					MobMindState.adjustFriendship(mob, playerId, 20);
					MobMindState.calm(mob, playerId, now + 12000);
					for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
						ItemStack reward = rewardStack(take);
						dropRewardToPlayer(level, player, mob, reward);
					}

					String giveDesc = describe(deal.gives());
					String takeDesc = describe(deal.takes());
					com.mobmind.MobMindMod.LOGGER.info("[MobMind] 以物易物交付: {} ↔ {}", giveDesc, takeDesc);
					MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
				} catch (Exception ex) {
					com.mobmind.MobMindMod.LOGGER.warn("[MobMind] tickDeals 异常", ex);
				}
			}
		}

		// 2. 处理承诺赠送（生物说过要给东西）
		Iterator<Map.Entry<UUID, MobMindState.GiftPromise>> promiseIt = MobMindState.giftPromiseEntries();
		while (promiseIt.hasNext()) {
			try {
				Map.Entry<UUID, MobMindState.GiftPromise> pe = promiseIt.next();
				MobMindState.GiftPromise promise = pe.getValue();
				Mob mob = findMob(server, pe.getKey());
				if (mob == null || !mob.isAlive() || now > promise.expireGameTime()) {
					promiseIt.remove();
					continue;
				}
				ServerPlayer player = server.getPlayerList().getPlayer(promise.playerId());
				if (player == null || player.distanceTo(mob) > 24) continue;
				ServerLevel level = (ServerLevel) mob.level();
				UUID playerId = player.getUUID();

				// 免费赠送（"送你XX"/"给你XX"）：不需要玩家给东西，直接给
				if (!promise.requiresPayment() && mob.getTarget() == null) {
					promiseIt.remove();
					MobMindState.adjustFriendship(mob, playerId, 10);
					for (MobMindState.BarterDeal.ItemRequirement promised : promise.promisedItems()) {
						ItemStack reward = rewardStack(promised);
						dropRewardToPlayer(level, player, mob, reward);
						com.mobmind.MobMindMod.LOGGER.info("[MobMind] 免费赠送(tick): {} 给 {} {}",
								mob.getType().getDescription().getString(),
								player.getGameProfile().name(),
								reward.getHoverName().getString());
					}
					MobAiService.notifyBarterCompleted(player, mob, "免费", describe(promise.promisedItems()));
					continue;
				}

				// 有条件承诺：检查玩家是否扔了东西在附近
				List<ItemEntity> nearbyPlayerItems = level.getEntitiesOfClass(ItemEntity.class,
						mob.getBoundingBox().inflate(10.0),
						ie -> ie.getOwner() instanceof ServerPlayer sp && sp.getUUID().equals(playerId));
				boolean hasItemInHand = !mob.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
						|| !mob.getItemInHand(InteractionHand.OFF_HAND).isEmpty();
				boolean playerGaveSomething = !nearbyPlayerItems.isEmpty() || hasItemInHand;

				// 需要生物在约定成立后收到东西，且不是战斗状态
				if (promise.requiresPayment() && playerGaveSomething && mob.getTarget() == null) {
					// 消耗地面物品
					for (ItemEntity ie : nearbyPlayerItems) {
						ie.discard();
					}
					promiseIt.remove();
					MobMindState.adjustFriendship(mob, playerId, 15);
					MobMindState.calm(mob, playerId, now + 6000);
					for (MobMindState.BarterDeal.ItemRequirement promised : promise.promisedItems()) {
						ItemStack reward = rewardStack(promised);
						dropRewardToPlayer(level, player, mob, reward);
						com.mobmind.MobMindMod.LOGGER.info("[MobMind] 信守承诺(tick): {} 给 {} {}",
								mob.getType().getDescription().getString(),
								player.getGameProfile().name(),
								reward.getHoverName().getString());
					}
					MobAiService.notifyBarterCompleted(player, mob, "物品", describe(promise.promisedItems()));
				}
			} catch (Exception ex) {
				com.mobmind.MobMindMod.LOGGER.warn("[MobMind] tickPromises 异常", ex);
			}
		}
	}

	/** 统计生物手持（刚捡起的）+ 地面掉落物中某物品的数量（不统计背包，避免吞生物原有物品） */
	private static int countInHandsAndGround(Mob mob, List<ItemEntity> items, Item item) {
		int count = 0;
		// 只统计主副手，不统计背包
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = mob.getItemInHand(hand);
			if (stack.is(item)) count += stack.getCount();
		}
		for (ItemEntity ie : items) {
			if (ie.getItem().is(item)) count += ie.getItem().getCount();
		}
		return count;
	}

	/** 检查地面掉落物 + 生物手持是否能满足玩家支付需求 */
	private static boolean canFulfillPlayerPayment(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int have = countInHandsAndGround(mob, items, req.item());
			if (have < req.count()) return false;
		}
		return true;
	}

	/** 先扣地面掉落物，不够再扣生物手持（不碰背包） */
	private static void consumePlayerPayment(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
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
			// 2) 生物主/副手（刚捡起的）
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = mob.getItemInHand(hand);
				if (!stack.is(req.item())) continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty()) mob.setItemInHand(hand, ItemStack.EMPTY);
				remaining -= take;
				if (remaining <= 0) break;
			}
		}
	}

	/** 统计生物手持+背包里匹配需求的物品数量（药水类还要匹配药水效果） */
	private static int countInMob(Mob mob, MobMindState.BarterDeal.ItemRequirement req) {
		int count = 0;
		Item item = req.item();
		net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion = req.potion();
		boolean needPotionCheck = potion != null
				&& (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION);

		// 主副手
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = mob.getItemInHand(hand);
			if (matchesRequirement(stack, req, needPotionCheck)) count += stack.getCount();
		}
		// 背包（村民/猪灵等InventoryCarrier）
		if (mob instanceof InventoryCarrier carrier) {
			net.minecraft.world.SimpleContainer inv = carrier.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (matchesRequirement(stack, req, needPotionCheck)) count += stack.getCount();
			}
		}
		return count;
	}

	/** 判断物品栈是否匹配需求（物品类型+药水效果） */
	private static boolean matchesRequirement(ItemStack stack, MobMindState.BarterDeal.ItemRequirement req, boolean needPotionCheck) {
		if (stack.isEmpty() || !stack.is(req.item())) return false;
		if (!needPotionCheck) return true;
		// 需要检查药水效果
		PotionContents actual = stack.get(DataComponents.POTION_CONTENTS);
		if (actual == null || !actual.potion().isPresent()) return false;
		var expectedKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(req.potion().value());
		var actualKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(actual.potion().get().value());
		return expectedKey != null && expectedKey.equals(actualKey);
	}

	/** 检查地面掉落物 + 生物手持/背包是否能满足所有需求 */
	private static boolean canFulfillTotal(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int have = countInMob(mob, req);
			for (ItemEntity ie : items) {
				if (matchesRequirement(ie.getItem(), req, req.potion() != null
						&& (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION))) {
					have += ie.getItem().getCount();
				}
			}
			if (have < req.count()) return false;
		}
		return true;
	}

	/** 先扣地面掉落物，不够再扣生物手持/背包，药水类精确匹配药水效果 */
	private static void consumeTotal(Mob mob, List<ItemEntity> items, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int remaining = req.count();
			boolean needPotionCheck = req.potion() != null
					&& (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION);

			// 1) 地面掉落物
			for (ItemEntity ie : items) {
				if (remaining <= 0) break;
				ItemStack stack = ie.getItem();
				if (!matchesRequirement(stack, req, needPotionCheck)) continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty()) ie.discard();
				remaining -= take;
			}
			if (remaining <= 0) continue;

			// 2) 生物主/副手
			for (InteractionHand hand : InteractionHand.values()) {
				if (remaining <= 0) break;
				ItemStack stack = mob.getItemInHand(hand);
				if (!matchesRequirement(stack, req, needPotionCheck)) continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty()) mob.setItemInHand(hand, ItemStack.EMPTY);
				remaining -= take;
			}
			if (remaining <= 0) continue;

			// 3) 生物背包（村民/猪灵等InventoryCarrier）
			if (mob instanceof InventoryCarrier carrier) {
				net.minecraft.world.SimpleContainer inv = carrier.getInventory();
				for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
					ItemStack stack = inv.getItem(i);
					if (!matchesRequirement(stack, req, needPotionCheck)) continue;
					int take = Math.min(remaining, stack.getCount());
					stack.shrink(take);
					if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
					remaining -= take;
				}
			}
		}
	}

	/** 把回赠物品交给玩家；背包满则丢在玩家脚边，并标记为奖励物品防止生物自己捡回去 */
	private static void dropRewardToPlayer(ServerLevel level, ServerPlayer player, Mob mob, ItemStack reward) {
		if (!player.addItem(reward.copy())) {
			ItemEntity drop = new ItemEntity(level, player.getX(), player.getY() + 0.3, player.getZ(), reward);
			drop.setThrower(player); // set thrower to player so mob pickup restriction applies
			drop.setPickUpDelay(10);
			long gameTime = level.getLevelData().getGameTime();
			com.mobmind.state.MobMindState.markRewardItem(drop.getUUID(), gameTime);
			level.addFreshEntity(drop);
		}
	}

	private static Mob findMob(MinecraftServer server, UUID entityId) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(entityId) instanceof Mob mob) return mob;
		}
		return null;
	}

	/** 欺骗检测结果 */
	private record CheatCheck(boolean isCheat, String actualDesc) {}

	/**
	 * 检测玩家扔的物品是否是欺骗行为：
	 * - 物品类型不在约定的gives列表中 → 作弊
	 * - 物品类型正确但药水效果不对（如约定伤害药水却给了水瓶/治疗药水）→ 作弊
	 */
	private static CheatCheck detectCheat(ItemStack given, List<MobMindState.BarterDeal.ItemRequirement> expected) {
		String actualName = given.getHoverName().getString();
		boolean typeMatched = false; // 是否有类型匹配的约定
		boolean potionMismatch = false; // 是否类型匹配但药水不匹配

		for (MobMindState.BarterDeal.ItemRequirement req : expected) {
			if (!given.is(req.item())) continue;
			typeMatched = true;

			// 非药水物品：类型匹配即可，不算作弊
			if (req.item() != Items.POTION && req.item() != Items.SPLASH_POTION && req.item() != Items.LINGERING_POTION) {
				return new CheatCheck(false, actualName);
			}

			// 药水类物品：检查药水效果
			PotionContents actualPotion = given.get(DataComponents.POTION_CONTENTS);
			if (req.potion() == null) {
				// 约定不要求特定药水效果 → 类型匹配即可
				return new CheatCheck(false, actualName);
			}
			// 约定要求特定药水效果，比较registry key
			if (actualPotion != null && actualPotion.potion().isPresent()) {
				var expectedKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(req.potion().value());
				var actualKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(actualPotion.potion().get().value());
				if (expectedKey != null && expectedKey.equals(actualKey)) {
					// 药水效果匹配
					return new CheatCheck(false, actualName);
				} else {
					// 类型匹配但药水效果不对
					potionMismatch = true;
				}
			} else {
				// 约定要特定药水但给了无效果物品/水瓶
				potionMismatch = true;
			}
		}

		if (!typeMatched) {
			// 物品类型完全不在约定列表中 → 作弊
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] 欺骗检测：物品类型不匹配，给的是{}", actualName);
			return new CheatCheck(true, actualName);
		}

		if (potionMismatch) {
			// 类型匹配但药水效果不对 → 作弊，直接用物品显示名（已经包含药水类型，如"水瓶"、"治疗药水"）
			com.mobmind.MobMindMod.LOGGER.info("[MobMind] 欺骗检测：药水效果不匹配，给的是{}", actualName);
			return new CheatCheck(true, actualName);
		}

		return new CheatCheck(false, actualName);
	}
}
