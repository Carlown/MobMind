package com.mobmind.behavior;

import com.mobmind.MobMindMod;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 以物易物行为：与村民/生物约定物品交换，玩家扔物品到生物脚边，生物捡起后回赠物品。
 * 砍价(bargain)：村民交易界面价格修改。
 *
 * 关键设计：
 * - 玩家必须把物品扔给生物，生物实际捡起（进入手持/背包）才算支付完成
 * - 地面上的物品不算支付，不会被直接消耗（避免物品凭空消失）
 * - 只有当生物手持+背包中有足够约定物品时，才执行交易
 * - 交易从生物手持/背包扣除玩家支付的物品，然后给玩家回赠物品
 */
public class BarterActions {

	private BarterActions() {}

	/** 把砍价结果写回村民/流浪商人商品：同商品只能砍一次；砍太狠会被拒绝 */
	public static void applyBargain(net.minecraft.world.item.trading.Merchant merchant, ServerPlayer player,
								   Personality persona, String itemName, boolean agree) {
		if (merchant.getOffers().isEmpty()) return;
		if (!agree) return;
		String itemLower = itemName.toLowerCase();
		net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) merchant;

		MerchantOffers offers = merchant.getOffers();
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer offer = offers.get(i);
			String rName = offer.getResult().getHoverName().getString().toLowerCase();
			if (!rName.contains(itemLower)) continue;

			int origCostA = offer.getBaseCostA().getCount();
			int curCostA = offer.getCostA().getCount();
			if (curCostA < origCostA) {
				entity.level().playSound(null, entity.blockPosition(),
						SoundEvents.VILLAGER_NO, entity.getSoundSource(), 1.0F, 1.0F);
				return;
			}

			boolean cruelOrEvil = persona.temper > 70 || "邪恶".equals(persona.alignment)
					|| "暴躁".equals(persona.alignment) || "狡诈".equals(persona.alignment);
			double successRate = cruelOrEvil ? 0.35 : 0.7;
			int friendship = MobMindState.friendship((net.minecraft.world.entity.Mob) entity, player.getUUID());
			if (friendship > 50) successRate += 0.2;
			if (friendship < 0) successRate -= 0.2;
			if (player.getRandom().nextDouble() > Math.max(0.1, successRate)) {
				entity.level().playSound(null, entity.blockPosition(),
						SoundEvents.VILLAGER_NO, entity.getSoundSource(), 1.0F, 1.0F);
				return;
			}

			int newCostA = Math.max(1, curCostA - Math.max(1, curCostA / 5));
			ItemStack newCostStack = offer.getCostA().copy();
			newCostStack.setCount(newCostA);
			offer.addToSpecialPriceDiff(newCostA - curCostA);
			offer.setSpecialPriceDiff(newCostA - origCostA);

			entity.level().playSound(null, entity.blockPosition(),
					SoundEvents.VILLAGER_YES, entity.getSoundSource(), 1.0F, 1.0F);

			MobMindMod.LOGGER.info("[MobMind] Bargain success: {} item original {}→new {}",
					entity.getType().getDescription().getString(), origCostA, newCostA);
			return;
		}
	}

	// ---------- 以物易物 ----------

	/** 生物刚捡起一个掉落物时，立即尝试完成以物易物约定 */
	public static void onMobPickedUp(Mob mob, ItemEntity itemEntity) {
		try {
			if (!(itemEntity.getOwner() instanceof ServerPlayer player)) return;
			UUID entityId = mob.getUUID();
			ServerLevel level = (ServerLevel) mob.level();
			long now = level.getLevelData().getGameTime();
			UUID playerId = player.getUUID();

			// 只处理正式的以物易物约定（双向交易）
			// GiftPromise（承诺）不在捡到物品时处理：
			//   - 免费赠送不需要捡东西触发，在tick中自动发放
			//   - 有条件承诺因不记录玩家需给的物品，无法验证支付，不应在捡拾时触发
			MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
			if (deal != null && deal.playerId().equals(playerId) && mob.isAlive()) {
				if (now <= deal.expireGameTime() && player.distanceTo(mob) <= 24) {
					// 只需检查玩家是否已交付约定物品（生物已接收并在手持/背包中）
					// 回赠物品（takes）由 rewardStack 凭空生成，不需要生物身上预先持有
					boolean playerDelivered = hasEnoughInMob(mob, deal.gives());
					MobMindMod.LOGGER.info("[MobMind] onMobPickedUp(deal) playerDelivered={} gives={} takes={}",
							playerDelivered, describe(deal.gives()), describe(deal.takes()));
					if (playerDelivered) {
						// 扣除玩家给的物品（生物收下了），回赠物品凭空生成给玩家
						consumeFromMob(mob, deal.gives());
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
						MobMindMod.LOGGER.info("[MobMind] Barter delivery (pickup triggered): {} ↔ {}", giveDesc, takeDesc);
						MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
					}
				} else {
					MobMindState.clearBarterDeal(entityId);
				}
			}
		} catch (Exception ex) {
			MobMindMod.LOGGER.warn("[MobMind] onMobPickedUp exception", ex);
		}
	}

	/** 在pickUpItem HEAD时检测欺骗行为（此时物品还在ItemEntity中未被捡起） */
	public static void checkCheatOnPickup(Mob mob, ItemEntity itemEntity) {
		try {
			if (!(itemEntity.getOwner() instanceof ServerPlayer player)) return;
			UUID entityId = mob.getUUID();
			UUID playerId = player.getUUID();
			MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
			if (deal == null || !deal.playerId().equals(playerId)) return;

			ServerLevel level = (ServerLevel) mob.level();
			long now = level.getLevelData().getGameTime();
			if (now > deal.expireGameTime()) {
				MobMindState.clearBarterDeal(entityId);
				return;
			}
			if (player.distanceTo(mob) > 24) return;

			ItemStack picked = itemEntity.getItem();
			if (picked.isEmpty()) return;

			CheatCheck cheat = detectCheat(picked, deal.gives());
			if (cheat.isCheat) {
				MobMindMod.LOGGER.info("[MobMind] Player cheated! Agreed: {} actually threw: {}", describe(deal.gives()), cheat.actualDesc());
				MobAiService.onPlayerCheatedBarter(mob, player, describe(deal.gives()), cheat.actualDesc());
			}
		} catch (Exception ex) {
			MobMindMod.LOGGER.warn("[MobMind] checkCheatOnPickup exception", ex);
		}
	}

	/** 有活跃交易约定时，主动帮生物接收匹配的掉落物（模拟捡起），并检查交易是否完成。
	 *  解决女巫等没有原版拾取AI的生物无法捡起交易物品的问题。 */
	public static boolean tryAcceptBarterItem(Mob mob, ServerPlayer player, ItemStack stack, ItemEntity ie) {
		try {
			UUID entityId = mob.getUUID();
			UUID playerId = player.getUUID();
			MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
			if (deal == null || !deal.playerId().equals(playerId) || !mob.isAlive()) return false;

			long gameTime = mob.level().getLevelData().getGameTime();
			if (gameTime > deal.expireGameTime() || player.distanceTo(mob) > 24) {
				MobMindState.clearBarterDeal(entityId);
				return false;
			}

			// 检查掉落物是否匹配 gives 中的任意一项
			boolean matches = false;
			for (MobMindState.BarterDeal.ItemRequirement req : deal.gives()) {
				boolean needPotionCheck = req.potion() != null
						&& (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION);
				if (matchesRequirement(stack, req, needPotionCheck)) {
					matches = true;
					break;
				}
			}
			if (!matches) return false;

			// 将物品放入生物手持栏（模拟捡起）
			ItemStack mainHand = mob.getItemInHand(InteractionHand.MAIN_HAND);
			if (mainHand.isEmpty()) {
				mob.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
			} else if (mainHand.is(stack.getItem()) && mainHand.getCount() + stack.getCount() <= mainHand.getMaxStackSize()) {
				mainHand.grow(stack.getCount());
			} else {
				ItemStack offHand = mob.getItemInHand(InteractionHand.OFF_HAND);
				if (offHand.isEmpty()) {
					mob.setItemInHand(InteractionHand.OFF_HAND, stack.copy());
				} else if (offHand.is(stack.getItem()) && offHand.getCount() + stack.getCount() <= offHand.getMaxStackSize()) {
					offHand.grow(stack.getCount());
				} else {
					return false; // 两只手都满了且无法堆叠
				}
			}

			mob.setPersistenceRequired();
			ie.discard();
			MobMindMod.LOGGER.info("[MobMind] Barter item auto-accepted: {} received {}×{}",
				mob.getType().getDescription().getString(), stack.getHoverName().getString(), stack.getCount());

			tryCompleteBarter(mob, player, gameTime);
			return true;
		} catch (Exception ex) {
			MobMindMod.LOGGER.warn("[MobMind] tryAcceptBarterItem exception", ex);
			return false;
		}
	}

	/** 检查交易约定是否可以完成，如果可以则执行交付 */
	private static boolean tryCompleteBarter(Mob mob, ServerPlayer player, long gameTime) {
		UUID entityId = mob.getUUID();
		UUID playerId = player.getUUID();
		MobMindState.BarterDeal deal = MobMindState.getBarterDeal(entityId);
		if (deal == null || !deal.playerId().equals(playerId) || !mob.isAlive()) return false;
		if (gameTime > deal.expireGameTime() || player.distanceTo(mob) > 24) {
			MobMindState.clearBarterDeal(entityId);
			return false;
		}

		// 只需检查玩家是否已交付约定物品（生物已接收并在手持/背包中）
		// 回赠物品（takes）由 rewardStack 凭空生成，不需要生物身上预先持有
		boolean playerDelivered = hasEnoughInMob(mob, deal.gives());
		MobMindMod.LOGGER.info("[MobMind] tryCompleteBarter playerDelivered={}", playerDelivered);
		if (!playerDelivered) return false;

		ServerLevel level = (ServerLevel) mob.level();
		// 扣除玩家给的物品（生物收下了），回赠物品凭空生成给玩家
		consumeFromMob(mob, deal.gives());
		MobMindState.clearBarterDeal(entityId);
		MobMindState.clearGiftPromise(entityId);
		MobMindState.clearOrder(mob);
		MobMindState.adjustFriendship(mob, playerId, 20);
		MobMindState.calm(mob, playerId, gameTime + 12000);
		for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
			ItemStack reward = rewardStack(take);
			dropRewardToPlayer(level, player, mob, reward);
		}
		String giveDesc = describe(deal.gives());
		String takeDesc = describe(deal.takes());
		MobMindMod.LOGGER.info("[MobMind] Barter delivery: {} ↔ {}", giveDesc, takeDesc);
		MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
		return true;
	}

	/** 提供给GiftActions判断物品是否正在以物易物中（避免当礼物误处理） */
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
		MobMindMod.LOGGER.info("[MobMind] createDeal raw: gives={} takes={} -> converted gives={} takes={}",
				describeMatched(gives), describeMatched(takes), describe(giveReqs), describe(takeReqs));
		if (giveReqs.isEmpty() || takeReqs.isEmpty()) {
			MobMindMod.LOGGER.warn("[MobMind] createDeal empty deal, skipping");
			return;
		}
		long now = ((ServerLevel) mob.level()).getLevelData().getGameTime();
		MobMindState.setBarterDeal(mob, new MobMindState.BarterDeal(
				player.getUUID(), giveReqs, takeReqs, now + 12000));
		String giveDesc = describe(giveReqs);
		String takeDesc = describe(takeReqs);
		MobAiService.notifyBarterDealMade(player, mob, giveDesc, takeDesc);
	}

	private static List<MobMindState.BarterDeal.ItemRequirement> toRequirements(List<ItemCatalog.MatchedItem> list) {
		List<MobMindState.BarterDeal.ItemRequirement> reqs = new ArrayList<>();
		for (ItemCatalog.MatchedItem m : list) {
			if (m != null && m.item() != null && m.item() != Items.AIR) {
				reqs.add(new MobMindState.BarterDeal.ItemRequirement(m.item(),
						Math.max(1, Math.min(64, m.count())), m.potion()));
			}
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

	private static String describeMatched(List<ItemCatalog.MatchedItem> list) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) sb.append("+");
			ItemCatalog.MatchedItem m = list.get(i);
			sb.append(m.name()).append("×").append(m.count());
		}
		return sb.toString();
	}

	private static ItemStack rewardStack(MobMindState.BarterDeal.ItemRequirement req) {
		ItemStack stack = new ItemStack(req.item(), req.count());
		if (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION) {
			try {
				net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion = req.potion();
				if (potion == null) {
					potion = Potions.WATER;
					MobMindMod.LOGGER.warn("[MobMind] rewardStack: unknown potion type, will give water bottle");
				}
				stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] Failed to set potion NBT: {}", e.getMessage());
			}
		}
		return stack;
	}

	/** 每20tick扫描：检查生物是否已收集足够约定物品（手持+背包中），是则完成交易 */
	public static void tickDeals(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();

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

					boolean playerDelivered = hasEnoughInMob(mob, deal.gives());
					if (!playerDelivered) continue;

					// 扣除玩家给的物品（生物收下了），回赠物品凭空生成给玩家
					consumeFromMob(mob, deal.gives());
					it.remove();
					MobMindState.clearGiftPromise(e.getKey());
					MobMindState.clearOrder(mob);
					MobMindState.adjustFriendship(mob, playerId, 20);
					MobMindState.calm(mob, playerId, now + 12000);
					for (MobMindState.BarterDeal.ItemRequirement take : deal.takes()) {
						ItemStack reward = rewardStack(take);
						dropRewardToPlayer(level, player, mob, reward);
					}

					String giveDesc = describe(deal.gives());
					String takeDesc = describe(deal.takes());
					MobMindMod.LOGGER.info("[MobMind] Barter delivery (tick): {} ↔ {}", giveDesc, takeDesc);
					MobAiService.notifyBarterCompleted(player, mob, giveDesc, takeDesc);
				} catch (Exception ex) {
					MobMindMod.LOGGER.warn("[MobMind] tickDeals exception", ex);
				}
			}
		}

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

				if (!promise.requiresPayment() && mob.getTarget() == null
						&& MobMindState.getBarterDeal(pe.getKey()) == null) {
					promiseIt.remove();
					MobMindState.adjustFriendship(mob, playerId, 10);
					for (MobMindState.BarterDeal.ItemRequirement promised : promise.promisedItems()) {
						ItemStack reward = rewardStack(promised);
						dropRewardToPlayer(level, player, mob, reward);
						MobMindMod.LOGGER.info("[MobMind] Free gift (tick): {} gave {} {}",
							mob.getType().getDescription().getString(),
							player.getGameProfile().name(),
							reward.getHoverName().getString());
					}
					MobAiService.notifyBarterCompleted(player, mob, "免费", describe(promise.promisedItems()));
					continue;
				}
			} catch (Exception ex) {
				MobMindMod.LOGGER.warn("[MobMind] tickPromises exception", ex);
			}
		}
	}

	private static boolean matchesRequirement(ItemStack stack, MobMindState.BarterDeal.ItemRequirement req, boolean needPotionCheck) {
		if (stack.isEmpty() || !stack.is(req.item())) return false;
		if (!needPotionCheck) return true;
		PotionContents actual = stack.get(DataComponents.POTION_CONTENTS);
		if (actual == null || !actual.potion().isPresent()) return false;
		var expectedKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(req.potion().value());
		var actualKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(actual.potion().get().value());
		return expectedKey != null && expectedKey.equals(actualKey);
	}

	private static boolean hasEnoughInMob(Mob mob, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int count = 0;
			boolean needPotionCheck = req.potion() != null
					&& (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION);
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = mob.getItemInHand(hand);
				if (matchesRequirement(stack, req, needPotionCheck)) count += stack.getCount();
			}
			if (mob instanceof InventoryCarrier carrier) {
				SimpleContainer inv = carrier.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) {
					ItemStack stack = inv.getItem(i);
					if (matchesRequirement(stack, req, needPotionCheck)) count += stack.getCount();
				}
			}
			if (count < req.count()) return false;
		}
		return true;
	}

	private static void consumeFromMob(Mob mob, List<MobMindState.BarterDeal.ItemRequirement> reqs) {
		for (MobMindState.BarterDeal.ItemRequirement req : reqs) {
			int remaining = req.count();
			boolean needPotionCheck = req.potion() != null
					&& (req.item() == Items.POTION || req.item() == Items.SPLASH_POTION || req.item() == Items.LINGERING_POTION);

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

			if (mob instanceof InventoryCarrier carrier) {
				SimpleContainer inv = carrier.getInventory();
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

	private static void dropRewardToPlayer(ServerLevel level, ServerPlayer player, Mob mob, ItemStack reward) {
		if (!player.addItem(reward.copy())) {
			// 背包满了才丢地上：不设thrower（避免MobMixin当作玩家丢的物品触发距离限制），
			// pickUpDelay=0让玩家立刻能捡，markRewardItem保护60秒防止生物捡回
			ItemEntity drop = new ItemEntity(level, player.getX(), player.getY() + 0.3, player.getZ(), reward);
			drop.setPickUpDelay(0);
			long gameTime = level.getLevelData().getGameTime();
			MobMindState.markRewardItem(drop.getUUID(), gameTime);
			level.addFreshEntity(drop);
		}
	}

	private static Mob findMob(MinecraftServer server, UUID entityId) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(entityId) instanceof Mob mob) return mob;
		}
		return null;
	}

	public record CheatCheck(boolean isCheat, String actualDesc) {}

	public static CheatCheck detectCheat(ItemStack given, List<MobMindState.BarterDeal.ItemRequirement> expected) {
		String actualName = given.getHoverName().getString();
		boolean typeMatched = false;
		boolean potionMismatch = false;

		for (MobMindState.BarterDeal.ItemRequirement req : expected) {
			if (!given.is(req.item())) continue;
			typeMatched = true;

			if (req.item() != Items.POTION && req.item() != Items.SPLASH_POTION && req.item() != Items.LINGERING_POTION) {
				return new CheatCheck(false, actualName);
			}

			PotionContents actualPotion = given.get(DataComponents.POTION_CONTENTS);
			if (req.potion() == null) {
				return new CheatCheck(false, actualName);
			}
			if (actualPotion != null && actualPotion.potion().isPresent()) {
				var expectedKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(req.potion().value());
				var actualKey = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(actualPotion.potion().get().value());
				if (expectedKey != null && expectedKey.equals(actualKey)) {
					return new CheatCheck(false, actualName);
				}
				potionMismatch = true;
			} else {
				potionMismatch = true;
			}
		}

		if (!typeMatched) {
			MobMindMod.LOGGER.info("[MobMind] Cheat detection: item type mismatch, gave {}", actualName);
			return new CheatCheck(true, actualName);
		}

		if (potionMismatch) {
			MobMindMod.LOGGER.info("[MobMind] Cheat detection: potion effect mismatch, gave {}", actualName);
			return new CheatCheck(true, actualName);
		}

		return new CheatCheck(false, actualName);
	}
}
