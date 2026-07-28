package com.mobmind.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mobmind.MobMindMod;
import com.mobmind.persona.Personality;
import com.mobmind.persona.PersonalityGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端运行时状态：生物人格、玩家-生物好感度、行为指令、安抚状态、对话历史。
 * 持久化到存档根目录 mobmind.json。
 */
public final class MobMindState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 行为指令类型 */
	public enum OrderType { FOLLOW, STAY, FLEE }

	/** 一条有时效的行为指令；fleeFrom 仅 FLEE 使用，表示要远离的点 */
	public record Order(OrderType type, UUID playerId, long expireGameTime, net.minecraft.world.phys.Vec3 fleeFrom) {}

	/** 一条对话历史记录（role=user/assistant，content=消息内容） */
	public record ConversationEntry(String role, String content) {}

	/** 每只生物对每位玩家最多保留多少条对话历史（user+assistant 各算一条，即10轮对话） */
	private static final int MAX_CONVERSATION_ENTRIES = 20;

	private static final Map<UUID, Personality> PERSONALITIES = new ConcurrentHashMap<>();
	/** entityUuid -> (playerUuid -> 0..100) */
	private static final Map<UUID, Map<UUID, Integer>> FRIENDSHIP = new ConcurrentHashMap<>();
	private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();
	/** entityUuid -> (playerUuid -> 安抚截止 gameTime) */
	private static final Map<UUID, Map<UUID, Long>> CALMED = new ConcurrentHashMap<>();
	/** entityUuid -> (playerUuid -> 对话历史列表)，持久化保存，重启游戏后不丢失 */
	private static final Map<UUID, Map<UUID, List<ConversationEntry>>> CONVERSATION_HISTORY = new ConcurrentHashMap<>();
	/** entityUuid -> (ammoKey -> count)，远程武器弹药库存。
	 *  ammoKey 规则："arrow"=普通箭，"spectral"=光灵箭，"tipped:<potionId>"=药水箭（按药水类型区分）。
	 *  取箭时优先使用特殊箭（光灵/药水），用完再用普通箭。 */
	private static final Map<UUID, Map<String, Integer>> AMMO = new ConcurrentHashMap<>();
	/** 玩家给予过武器的生物 UUID（这些生物使用自定义近战/远程/格挡 Goal，消耗弹药）；
	 *  自然生成带武器的生物（骷髅、流浪者等）不加入，保留原版无限箭 AI。 */
	private static final java.util.Set<UUID> PLAYER_GIVEN_WEAPON = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** playerUuid -> language code (e.g. "en_us", "zh_cn")，客户端同步过来的玩家语言设置 */
	private static final Map<UUID, String> PLAYER_LANGUAGE = new ConcurrentHashMap<>();
	/** 生物刚扔给玩家的奖励物品 entityUuid -> 过期 gameTime，这些物品生物自己不能捡回去（防止女巫等把自己扔给玩家的药水捡回来） */
	private static final Map<UUID, Long> REWARD_ITEMS = new ConcurrentHashMap<>();

	private static Path saveFile;

	private MobMindState() {}

	// ---------- 人格 ----------

	public static Personality personalityOf(Mob mob) {
		Personality p = PERSONALITIES.computeIfAbsent(mob.getUUID(), id -> {
			Personality created = PersonalityGenerator.generate(id, categoryOf(mob));
			created.voiceId = com.mobmind.voice.LocalVoice.assignVoiceId(com.mobmind.config.MobMindConfig.get());
			return created;
		});
		// 有专属设定的生物：首次生成时抽取一次善恶倾向，之后固定（旧档案补齐）
		com.mobmind.persona.PersonaRegistry.Persona persona = com.mobmind.persona.PersonaRegistry.forMob(mob);
		if (persona != null && p.alignment == null) {
			persona.rollAlignment(p, mob.getUUID());
		}
		// 10% 的敌对生物执着于让创造模式玩家换生存模式（一次性抽取，固定）。
		// 猪灵蛮兵必定参与此行为；普通猪灵抽取后仍需在运行时判断玩家是否穿金。
		if (p.creativeTaunt == null) {
			boolean isPiglinBrute = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(mob.getType()).toString().equals("minecraft:piglin_brute");
			p.creativeTaunt = isPiglinBrute || (categoryOf(mob) == PersonalityGenerator.Category.HOSTILE
					&& new java.util.Random(mob.getUUID().hashCode() * 31L + 29).nextInt(100) < 10);
		}
		// 音色池变更（如切换中文音色池）后，修正旧档案中不在池内的音色
		com.mobmind.config.MobMindConfig cfg = com.mobmind.config.MobMindConfig.get();
		int[] pool = com.mobmind.voice.LocalVoice.voicePool(cfg);
		boolean inPool = false;
		for (int id : pool) {
			if (id == p.voiceId) { inPool = true; break; }
		}
		if (!inPool) p.voiceId = com.mobmind.voice.LocalVoice.assignVoiceId(cfg);
		return p;
	}

	public static PersonalityGenerator.Category categoryOf(Entity e) {
		if (e instanceof Animal) return PersonalityGenerator.Category.PASSIVE;
		if (e instanceof NeutralMob) return PersonalityGenerator.Category.NEUTRAL;
		if (e instanceof Monster) return PersonalityGenerator.Category.HOSTILE;
		return PersonalityGenerator.Category.NEUTRAL;
	}

	// ---------- 好感度 ----------

	public static int friendship(Mob mob, UUID playerId) {
		return FRIENDSHIP.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>())
				.computeIfAbsent(playerId, k -> initialFriendshipFor(mob));
	}

	public static int adjustFriendship(Mob mob, UUID playerId, int delta) {
		Map<UUID, Integer> map = FRIENDSHIP.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>());
		int base = map.computeIfAbsent(playerId, k -> initialFriendshipFor(mob));
		int value = Math.max(0, Math.min(100, base + delta));
		map.put(playerId, value);
		// 达到朋友关系后让生物不再因远离玩家而自然消失
		if (isFriendlyTo(mob, playerId)) {
			mob.setPersistenceRequired();
		}
		return value;
	}

	/** 加载存档或实体重新进入世界时，对已有友好关系的生物恢复持久化标记 */
	public static void ensurePersistenceIfFriendly(Mob mob) {
		if (mob == null || mob.isPersistenceRequired()) return;
		Map<UUID, Integer> map = FRIENDSHIP.get(mob.getUUID());
		if (map == null) return;
		for (UUID playerId : map.keySet()) {
			if (isFriendlyTo(mob, playerId)) {
				mob.setPersistenceRequired();
				return;
			}
		}
	}

	// ---------- 玩家语言 ----------

	/** 设置玩家的语言代码（由客户端同步过来） */
	public static void setPlayerLanguage(ServerPlayer player, String languageCode) {
		if (player == null || languageCode == null) return;
		PLAYER_LANGUAGE.put(player.getUUID(), languageCode);
		MobMindMod.LOGGER.info("[MobMind] 玩家 {} 语言设置为 {}", player.getGameProfile().name(), languageCode);
	}

	/** 判断玩家是否使用英文界面（优先使用客户端同步的语言，兜底用服务端 Language 类检测） */
	public static boolean isPlayerEnglish(UUID playerId) {
		String lang = PLAYER_LANGUAGE.get(playerId);
		if (lang != null) {
			return lang.startsWith("en");
		}
		// 兜底：用服务端语言检测（单人游戏时服务端与客户端语言一致）
		try {
			String title = net.minecraft.locale.Language.getInstance().getOrDefault("gui.mobmind.config.title", "");
			return title.endsWith("Settings");
		} catch (Exception e) {
			return false;
		}
	}

	/** 标记一个物品为"生物给玩家的奖励"，生物自己不能捡回去（有效期100tick=5秒） */
	public static void markRewardItem(UUID itemEntityId, long currentGameTime) {
		REWARD_ITEMS.put(itemEntityId, currentGameTime + 100);
	}

	/** 检查该物品是否是生物刚给玩家的奖励（生物不能捡回去）；同时清理过期条目 */
	public static boolean isRewardItemForbiddenForMobs(UUID itemEntityId, long currentGameTime) {
		Long expire = REWARD_ITEMS.get(itemEntityId);
		if (expire == null) return false;
		if (currentGameTime > expire) {
			REWARD_ITEMS.remove(itemEntityId);
			return false;
		}
		return true;
	}

	/** 初始好感度：按设定抽取的善恶倾向决定，善良型更友善 */
	private static int initialFriendshipFor(Mob mob) {
		Personality p = personalityOf(mob);
		if (p.alignment != null) {
			if (p.alignmentGood) return 45;
			return categoryOf(mob) == PersonalityGenerator.Category.HOSTILE ? 10 : 25;
		}
		return PersonalityGenerator.initialFriendship(categoryOf(mob));
	}

	/** 高好感度玩家不会被该生物主动攻击；善良型生物更容易放下敌意 */
	public static boolean isFriendlyTo(Mob mob, UUID playerId) {
		Personality p = personalityOf(mob);
		int threshold = (p.alignment != null && p.alignmentGood) ? 40 : 60;
		return friendship(mob, playerId) >= threshold;
	}

	/**
	 * 判断两个生物是否互为友军（即存在一个共同的玩家，双方都对该玩家友好不攻击）。
	 * 友军之间不会主动互相攻击，也不会互相造成伤害，即使一个是僵尸一个是铁傀儡。
	 * 使用isFriendlyTo阈值（善良型≥40，邪恶型≥60）——只要不攻击玩家就算友军，不需要达到60好友。
	 */
	public static boolean areAllies(Mob a, Mob b) {
		if (a == b) return true;
		Map<UUID, Integer> friendsOfA = FRIENDSHIP.get(a.getUUID());
		if (friendsOfA == null || friendsOfA.isEmpty()) return false;
		for (Map.Entry<UUID, Integer> entry : friendsOfA.entrySet()) {
			UUID pid = entry.getKey();
			// a 对 pid 友好不攻击，检查 b 是否也对 pid 友好不攻击
			if (isFriendlyTo(a, pid) && isFriendlyTo(b, pid)) return true;
		}
		return false;
	}

	/**
	 * 检查攻击者是否不应该攻击目标（因为攻击者是某玩家的好朋友≥60，而目标对该玩家友好不攻击）。
	 * 这是单向保护：攻击者不应该主动出手，但被攻击者自卫时不受限制。
	 */
	public static boolean shouldNotAttack(Mob attacker, Mob target) {
		Map<UUID, Integer> friendsOfAttacker = FRIENDSHIP.get(attacker.getUUID());
		if (friendsOfAttacker == null || friendsOfAttacker.isEmpty()) return false;
		for (Map.Entry<UUID, Integer> entry : friendsOfAttacker.entrySet()) {
			UUID pid = entry.getKey();
			// 攻击者是玩家的好友≥60，且目标对该玩家友好不攻击，就不应该主动打
			if (entry.getValue() >= 60 && isFriendlyTo(target, pid)) {
				return true;
			}
		}
		return false;
	}

	// ---------- 以物易物约定 ----------

	/** 一笔待交付的以物易物约定（玩家扔 gives 列表里的物品，生物回赠 takes 列表） */
	public record BarterDeal(UUID playerId, java.util.List<ItemRequirement> gives,
							 java.util.List<ItemRequirement> takes, long expireGameTime) {
		public record ItemRequirement(net.minecraft.world.item.Item item, int count,
									  net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {}
	}

	private static final Map<UUID, BarterDeal> BARTER_DEALS = new ConcurrentHashMap<>();

	public static void setBarterDeal(Mob mob, BarterDeal deal) {
		BARTER_DEALS.put(mob.getUUID(), deal);
	}

	public static boolean barterDealsEmpty() {
		return BARTER_DEALS.isEmpty();
	}

	public static java.util.Iterator<Map.Entry<UUID, BarterDeal>> barterDealEntries() {
		return BARTER_DEALS.entrySet().iterator();
	}

	public static void clearBarterDeal(UUID entityId) {
		BARTER_DEALS.remove(entityId);
	}

	public static boolean hasActiveBarterDeal(Mob mob, UUID playerId) {
		BarterDeal deal = BARTER_DEALS.get(mob.getUUID());
		return deal != null && deal.playerId().equals(playerId);
	}

	public static BarterDeal getBarterDeal(UUID entityId) {
		return BARTER_DEALS.get(entityId);
	}

	// ---------- 生物承诺赠送（信守承诺机制） ----------

	/** 生物答应给玩家的物品承诺（2分钟内有效）
	 *  requiresPayment=true：需要玩家先给东西才履约（如"你给我XX我给你YY"）
	 *  requiresPayment=false：免费赠送，不需要玩家给任何东西，直接给（如"送你一个马铃薯"）
	 */
	public record GiftPromise(UUID playerId, java.util.List<BarterDeal.ItemRequirement> promisedItems, long expireGameTime, boolean requiresPayment) {}
	private static final Map<UUID, GiftPromise> GIFT_PROMISES = new ConcurrentHashMap<>();

	public static void setGiftPromise(Mob mob, UUID playerId, java.util.List<BarterDeal.ItemRequirement> items, boolean requiresPayment) {
		long now = mob.level().getLevelData().getGameTime();
		GIFT_PROMISES.put(mob.getUUID(), new GiftPromise(playerId, items, now + 2400, requiresPayment)); // 2分钟有效
	}

	public static void setGiftPromise(Mob mob, UUID playerId, java.util.List<BarterDeal.ItemRequirement> items) {
		setGiftPromise(mob, playerId, items, true); // 默认需要玩家给东西
	}

	public static GiftPromise getGiftPromise(UUID entityId) {
		return GIFT_PROMISES.get(entityId);
	}

	public static void clearGiftPromise(UUID entityId) {
		GIFT_PROMISES.remove(entityId);
	}

	public static java.util.Iterator<Map.Entry<UUID, GiftPromise>> giftPromiseEntries() {
		return GIFT_PROMISES.entrySet().iterator();
	}

	// ---------- 村民砍价记录 ----------

	/** villagerUuid -> (offerIndex -> 已砍价次数)，持久化 */
	private static final Map<UUID, Map<Integer, Integer>> BARGAINS = new ConcurrentHashMap<>();
	/** entityUuid -> 高亮结束 gameTime */
	private static final Map<UUID, Long> GLOW_UNTIL = new ConcurrentHashMap<>();
	/** entityUuid -> 持有的不死图腾数量（玩家赠送） */
	private static final Map<UUID, Integer> TOTEMS = new ConcurrentHashMap<>();

	public static int bargainCount(UUID villagerId, int offerIndex) {
		return BARGAINS.getOrDefault(villagerId, Map.of()).getOrDefault(offerIndex, 0);
	}

	public static void markBargained(UUID villagerId, int offerIndex) {
		BARGAINS.computeIfAbsent(villagerId, k -> new ConcurrentHashMap<>())
				.merge(offerIndex, 1, Integer::sum);
	}

	// ---------- 说话高亮 ----------

	/** 让生物高亮显示一段时间，方便玩家定位说话者 */
	public static void glowFor(Mob mob, long gameTime, int ticks) {
		GLOW_UNTIL.put(mob.getUUID(), gameTime + ticks);
		mob.setGlowingTag(true);
	}

	/** 该生物是否有活跃的发光效果（未过期） */
	public static boolean hasActiveGlow(Mob mob) {
		Long until = GLOW_UNTIL.get(mob.getUUID());
		if (until == null) return false;
		long now = mob.level().getLevelData().getGameTime();
		return now < until;
	}

	/** 服务端 tick 调用：到时间后取消高亮 */
	public static void tickGlow(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		Iterator<Map.Entry<UUID, Long>> it = GLOW_UNTIL.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Long> e = it.next();
			Mob mob = findMob(server, e.getKey());
			if (now >= e.getValue()) {
				if (mob != null) mob.setGlowingTag(false);
				it.remove();
			}
			// mob == null 时不删除条目——等区块加载后再关闭发光，避免永久发光 bug
		}
	}

	// ---------- 不死图腾 ----------

	/** 给生物添加不死图腾（玩家赠送） */
	public static void addTotem(Mob mob, int count) {
		TOTEMS.merge(mob.getUUID(), count, Integer::sum);
		mob.setPersistenceRequired();
	}

	/** 生物是否持有不死图腾 */
	public static boolean hasTotem(Mob mob) {
		return TOTEMS.getOrDefault(mob.getUUID(), 0) > 0;
	}

	/**
	 * 尝试消耗一个不死图腾，返回是否成功。
	 * 调用方需要自己处理复活后的效果（回血、状态效果等）。
	 */
	public static boolean consumeTotem(Mob mob) {
		UUID id = mob.getUUID();
		int count = TOTEMS.getOrDefault(id, 0);
		if (count <= 0) return false;
		if (count <= 1) TOTEMS.remove(id);
		else TOTEMS.put(id, count - 1);
		return true;
	}

	/**
	 * 服务端 tick 调用：被安抚的末影龙/凋灵强制停止攻击。
	 * 末影龙会被切回盘旋阶段；凋灵会被清除攻击目标。
	 */
	public static void tickBossCalm(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		for (ServerLevel level : server.getAllLevels()) {
			// 只扫描玩家附近的 Boss，避免全图扫描导致服务器卡顿
			java.util.Set<UUID> seen = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
			for (ServerPlayer player : level.players()) {
				AABB box = player.getBoundingBox().inflate(128.0);
				for (EnderDragon dragon : level.getEntitiesOfClass(EnderDragon.class, box)) {
					if (!seen.add(dragon.getUUID())) continue;
					if (!isCalmedTowards(dragon, now)) continue;
					dragon.setTarget(null);
					if (dragon.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.HOLDING_PATTERN) {
						dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
					}
				}
				for (WitherBoss wither : level.getEntitiesOfClass(WitherBoss.class, box)) {
					if (!seen.add(wither.getUUID())) continue;
					if (!isCalmedTowards(wither, now)) continue;
					wither.setTarget(null);
				}
			}
		}
	}

	/**
	 * 服务端 tick 调用：让治疗中且忠诚的僵尸村民帮助救助者攻击附近敌对怪物。
	 */
	public static void tickCuringZombieVillagers(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		Iterator<Map.Entry<UUID, Long>> it = CURING_ZOMBIE_VILLAGERS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Long> e = it.next();
			if (now > e.getValue()) {
				it.remove();
				CURING_LOYALTY.remove(e.getKey());
				CURING_HEALER.remove(e.getKey());
				continue;
			}
			if (CURING_LOYALTY.getOrDefault(e.getKey(), 0) != 1) continue;
			UUID healerId = CURING_HEALER.get(e.getKey());
			if (healerId == null) continue;
			ServerPlayer healer = server.getPlayerList().getPlayer(healerId);
			if (healer == null || !healer.isAlive()) continue;
			if (!(healer.level().getEntity(e.getKey()) instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager zv)
				|| !zv.isAlive()) continue;
		if (zv.getTarget() instanceof Monster) continue; // 已经在打怪
		AABB box = healer.getBoundingBox().inflate(12.0);
		List<Monster> threats = healer.level().getEntitiesOfClass(Monster.class, box,
				m -> m.isAlive() && m != zv && !(m instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager));
		if (!threats.isEmpty()) {
			Monster target = threats.get(0);
			for (Monster m : threats) {
				if (m.distanceToSqr(healer) < target.distanceToSqr(healer)) {
					target = m;
				}
			}
			zv.setTarget(target);
		}
	}
	}

	private static boolean isCalmedTowards(Mob mob, long gameTime) {
		Map<UUID, Long> map = CALMED.get(mob.getUUID());
		if (map == null) return false;
		for (Long until : map.values()) {
			if (until != null && gameTime <= until) return true;
		}
		return false;
	}

	private static Mob findMob(MinecraftServer server, UUID entityId) {
		for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(entityId) instanceof Mob mob) return mob;
		}
		return null;
	}

	// ---------- 行为指令 ----------

	public static void setOrder(Mob mob, OrderType type, UUID playerId, long expireGameTime) {
		ORDERS.put(mob.getUUID(), new Order(type, playerId, expireGameTime, null));
	}

	public static void setFleeOrder(Mob mob, net.minecraft.world.phys.Vec3 fleeFrom, long expireGameTime) {
		ORDERS.put(mob.getUUID(), new Order(OrderType.FLEE, null, expireGameTime, fleeFrom));
	}

	public static Order orderFor(Mob mob, long gameTime) {
		Order o = ORDERS.get(mob.getUUID());
		if (o == null) return null;
		if (gameTime > o.expireGameTime()) {
			ORDERS.remove(mob.getUUID());
			return null;
		}
		return o;
	}

	public static void clearOrder(Mob mob) {
		ORDERS.remove(mob.getUUID());
	}

	// ---------- 安抚 ----------

	public static void calm(Mob mob, UUID playerId, long untilGameTime) {
		CALMED.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>()).put(playerId, untilGameTime);
		// 和解后清除激怒状态
		Map<UUID, Long> provoked = PROVOKED.get(mob.getUUID());
		if (provoked != null) provoked.remove(playerId);
	}

	public static boolean isCalmedTowards(Mob mob, UUID playerId, long gameTime) {
		Map<UUID, Long> map = CALMED.get(mob.getUUID());
		if (map == null) return false;
		Long until = map.get(playerId);
		return until != null && gameTime <= until;
	}

	public static void clearCalm(Mob mob, UUID playerId) {
		Map<UUID, Long> map = CALMED.get(mob.getUUID());
		if (map != null) map.remove(playerId);
	}

	// ---------- 远程弹药 ----------

	/** 根据箭 ItemStack 生成 ammoKey；返回 null 表示不是箭 */
	public static String ammoKeyFor(net.minecraft.world.item.ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		net.minecraft.world.item.Item item = stack.getItem();
		if (item == net.minecraft.world.item.Items.ARROW) return "arrow";
		if (item == net.minecraft.world.item.Items.SPECTRAL_ARROW) return "spectral";
		if (item == net.minecraft.world.item.Items.TIPPED_ARROW) {
			// 药水箭：按药水类型区分 key
			var potionContents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
			if (potionContents != null && potionContents.potion().isPresent()) {
				return "tipped:" + net.minecraft.core.registries.BuiltInRegistries.POTION
						.getKey(potionContents.potion().get().value());
			}
			return "tipped:minecraft:awkward"; // 无药水效果的药水箭兜底
		}
		return null;
	}

	/** 根据 ammoKey 生成对应的箭 ItemStack（1支） */
	public static net.minecraft.world.item.ItemStack createArrowFor(String key) {
		if (key == null) return net.minecraft.world.item.ItemStack.EMPTY;
		if (key.equals("arrow")) return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW);
		if (key.equals("spectral")) return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW);
		if (key.startsWith("tipped:")) {
			String potionId = key.substring(7);
			net.minecraft.world.item.ItemStack tipped = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW);
			var potionReg = net.minecraft.core.registries.BuiltInRegistries.POTION;
			var potionHolder = potionReg.get(net.minecraft.resources.Identifier.parse(potionId));
			if (potionHolder.isPresent()) {
				tipped.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
						new net.minecraft.world.item.alchemy.PotionContents(potionHolder.get()));
			}
			return tipped;
		}
		return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW);
	}

	/** 获取该生物弹药总量（所有箭类型合计） */
	public static int getAmmo(Mob mob) {
		Map<String, Integer> inv = AMMO.get(mob.getUUID());
		if (inv == null) return 0;
		int total = 0;
		for (int c : inv.values()) total += c;
		return total;
	}

	/** 添加指定类型的箭 */
	public static int addAmmo(Mob mob, String ammoKey, int amount) {
		if (ammoKey == null || amount <= 0) return getAmmo(mob);
		Map<String, Integer> inv = AMMO.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>());
		int cur = inv.getOrDefault(ammoKey, 0);
		int value = Math.max(0, cur + amount);
		if (value == 0) inv.remove(ammoKey);
		else inv.put(ammoKey, value);
		return getAmmo(mob);
	}

	/** 兼容旧接口：默认添加普通箭 */
	public static int addAmmo(Mob mob, int amount) {
		return addAmmo(mob, "arrow", amount);
	}

	/** 消耗一支箭：优先消耗特殊箭（光灵/药水），用完再用普通箭。
	 *  返回被消耗的箭的 ammoKey；无弹药返回 null。 */
	public static String consumeAmmoArrow(Mob mob) {
		Map<String, Integer> inv = AMMO.get(mob.getUUID());
		if (inv == null || inv.isEmpty()) return null;

		// 优先级：1) 光灵箭  2) 药水箭（任意类型）  3) 普通箭
		// 药水箭内部按 key 字典序（先到先得），每种独立
		String chosen = null;

		if (inv.getOrDefault("spectral", 0) > 0) {
			chosen = "spectral";
		} else {
			String bestTipped = null;
			int bestTippedCount = 0;
			for (Map.Entry<String, Integer> e : inv.entrySet()) {
				String k = e.getKey();
				if (k.startsWith("tipped:") && e.getValue() > 0) {
					if (bestTipped == null || e.getValue() > bestTippedCount
							|| (e.getValue() == bestTippedCount && k.compareTo(bestTipped) < 0)) {
						bestTipped = k;
						bestTippedCount = e.getValue();
					}
				}
			}
			if (bestTipped != null) {
				chosen = bestTipped;
			} else if (inv.getOrDefault("arrow", 0) > 0) {
				chosen = "arrow";
			}
		}

		if (chosen == null) return null;
		int left = inv.get(chosen) - 1;
		if (left <= 0) inv.remove(chosen);
		else inv.put(chosen, left);
		return chosen;
	}

	/** 兼容旧接口：消耗一支箭（自动选特殊箭优先），返回是否成功 */
	public static boolean consumeAmmo(Mob mob) {
		return consumeAmmoArrow(mob) != null;
	}

	/** 取一支箭但不消耗（用于弩装填时查看下一支箭类型），返回 ammoKey；无弹药返回 null */
	public static String peekAmmo(Mob mob) {
		Map<String, Integer> inv = AMMO.get(mob.getUUID());
		if (inv == null || inv.isEmpty()) return null;
		if (inv.getOrDefault("spectral", 0) > 0) return "spectral";
		for (Map.Entry<String, Integer> e : inv.entrySet()) {
			if (e.getKey().startsWith("tipped:") && e.getValue() > 0) return e.getKey();
		}
		if (inv.getOrDefault("arrow", 0) > 0) return "arrow";
		return null;
	}

	// ---------- 玩家给予武器标记（用于区分自然生成带武器的生物） ----------

	/** 标记该生物收到过玩家给予的武器/盾牌，自定义近战/远程/格挡 Goal 将对其生效 */
	public static void markPlayerGivenWeapon(Mob mob) {
		PLAYER_GIVEN_WEAPON.add(mob.getUUID());
	}

	/** 该生物是否收到过玩家给予的武器/盾牌（自然生成带武器的骷髅等返回 false，保留原版 AI） */
	public static boolean hasPlayerGivenWeapon(Mob mob) {
		return PLAYER_GIVEN_WEAPON.contains(mob.getUUID());
	}

	// ---------- 对话历史（持久化） ----------

	/**
	 * 记录一条对话历史（user=玩家说的，assistant=生物回复的）。
	 * 每只生物对每位玩家最多保留 {@link #MAX_CONVERSATION_ENTRIES} 条，超出则丢弃最早的。
	 * 数据会持久化到 mobmind.json，重启游戏后生物仍记得之前的对话。
	 */
	public static void recordConversation(UUID entityId, UUID playerId, String role, String content) {
		if (role == null || content == null || role.isBlank()) return;
		CONVERSATION_HISTORY
				.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(playerId, k -> new ArrayList<>())
				.add(new ConversationEntry(role, content));
	}

	/**
	 * 获取某只生物对某位玩家的对话历史列表（只读视图）。
	 * 列表按时间顺序排列，最早的在前，最新的在后。
	 * 返回 null 表示无历史记录。
	 */
	public static List<ConversationEntry> getConversationHistory(UUID entityId, UUID playerId) {
		Map<UUID, List<ConversationEntry>> map = CONVERSATION_HISTORY.get(entityId);
		if (map == null) return null;
		return map.get(playerId);
	}

	/** 获取某只生物对某位玩家的对话历史，并裁剪到最近 N 条（用于构建 prompt） */
	public static List<ConversationEntry> getRecentConversationHistory(UUID entityId, UUID playerId, int maxEntries) {
		List<ConversationEntry> full = getConversationHistory(entityId, playerId);
		if (full == null || full.isEmpty()) return List.of();
		int size = full.size();
		if (size <= maxEntries) return new ArrayList<>(full);
		return new ArrayList<>(full.subList(size - maxEntries, size));
	}

	/** 修剪对话历史到指定长度，丢弃最早的条目 */
	public static void trimConversationHistory(UUID entityId, UUID playerId, int maxEntries) {
		Map<UUID, List<ConversationEntry>> map = CONVERSATION_HISTORY.get(entityId);
		if (map == null) return;
		List<ConversationEntry> list = map.get(playerId);
		if (list == null || list.size() <= maxEntries) return;
		synchronized (list) {
			if (list.size() > maxEntries) {
				list.subList(0, list.size() - maxEntries).clear();
			}
		}
	}

	/** 默认裁剪到 MAX_CONVERSATION_ENTRIES 条 */
	public static void trimConversationHistory(UUID entityId, UUID playerId) {
		trimConversationHistory(entityId, playerId, MAX_CONVERSATION_ENTRIES);
	}

	/** 判断玩家是否与该生物对话过（即"认识"） */
	public static boolean hasMet(UUID entityId, UUID playerId) {
		Map<UUID, List<ConversationEntry>> map = CONVERSATION_HISTORY.get(entityId);
		if (map == null) return false;
		List<ConversationEntry> history = map.get(playerId);
		return history != null && !history.isEmpty();
	}

	// ---------- 激怒（被攻击/翻脸后暂时敌对，压过好感与安抚） ----------

	/** entityUuid -> (playerUuid -> 激怒截止 gameTime) */
	private static final Map<UUID, Map<UUID, Long>> PROVOKED = new ConcurrentHashMap<>();

	public static void provoke(Mob mob, UUID playerId, long untilGameTime) {
		PROVOKED.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>()).put(playerId, untilGameTime);
	}

	public static boolean isProvokedTowards(Mob mob, UUID playerId, long gameTime) {
		Map<UUID, Long> map = PROVOKED.get(mob.getUUID());
		if (map == null) return false;
		Long until = map.get(playerId);
		return until != null && gameTime <= until;
	}

	// ---------- 僵尸村民治疗中 ----------

	/** 治疗中的僵尸村民：entityUuid -> 治疗截止 gameTime */
	private static final Map<UUID, Long> CURING_ZOMBIE_VILLAGERS = new ConcurrentHashMap<>();
	/** 治疗中的僵尸村民对救助者的忠诚倾向：-1=仍敌对, 0=中立不攻击, 1=帮助救助者打怪 */
	private static final Map<UUID, Integer> CURING_LOYALTY = new ConcurrentHashMap<>();
	/** 治疗中的僵尸村民对应的救助者 */
	private static final Map<UUID, UUID> CURING_HEALER = new ConcurrentHashMap<>();

	public static void markCuringZombieVillager(Mob mob, UUID healerId, long untilGameTime) {
		CURING_ZOMBIE_VILLAGERS.put(mob.getUUID(), untilGameTime);
		CURING_HEALER.put(mob.getUUID(), healerId);
		// 大部分感恩（60% 帮助，30% 中立不攻击），10% 仍敌对
		double roll = Math.random();
		int loyalty = roll < 0.6 ? 1 : (roll < 0.9 ? 0 : -1);
		CURING_LOYALTY.put(mob.getUUID(), loyalty);
	}

	public static boolean isCuringZombieVillager(Mob mob, long gameTime) {
		Long until = CURING_ZOMBIE_VILLAGERS.get(mob.getUUID());
		return until != null && gameTime <= until;
	}

	public static int curingLoyalty(Mob mob) {
		return CURING_LOYALTY.getOrDefault(mob.getUUID(), 0);
	}

	public static UUID curingHealer(Mob mob) {
		return CURING_HEALER.get(mob.getUUID());
	}

	public static void clearCuringZombieVillager(Mob mob) {
		CURING_ZOMBIE_VILLAGERS.remove(mob.getUUID());
		CURING_LOYALTY.remove(mob.getUUID());
		CURING_HEALER.remove(mob.getUUID());
	}

	/** 供外部调用：当僵尸村民完成转化后清除状态 */
	public static void onZombieVillagerCured(Mob mob) {
		clearCuringZombieVillager(mob);
	}

	// ---------- 持久化 ----------

	public static void load(MinecraftServer server) {
		clear();
		saveFile = server.getWorldPath(LevelResource.ROOT).resolve("mobmind.json");
		if (!Files.exists(saveFile)) return;
		try (Reader r = Files.newBufferedReader(saveFile)) {
			com.google.gson.JsonObject root = GSON.fromJson(r, com.google.gson.JsonObject.class);
			if (root == null) return;
			if (root.has("personalities")) {
				Map<String, Personality> p = GSON.fromJson(root.get("personalities"),
						new TypeToken<Map<String, Personality>>() {}.getType());
				if (p != null) p.forEach((k, v) -> {
					try { PERSONALITIES.put(UUID.fromString(k), v); } catch (IllegalArgumentException ignored) {}
				});
			}
			if (root.has("friendship")) {
				Map<String, Map<String, Integer>> f = GSON.fromJson(root.get("friendship"),
						new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
				if (f != null) f.forEach((ek, pv) -> {
					try {
						UUID entityId = UUID.fromString(ek);
						Map<UUID, Integer> map = FRIENDSHIP.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>());
						pv.forEach((pk, v) -> {
							try { map.put(UUID.fromString(pk), v); } catch (IllegalArgumentException ignored) {}
						});
					} catch (IllegalArgumentException ignored) {}
				});
			}
			if (root.has("bargains")) {
				Map<String, Map<String, Integer>> b = GSON.fromJson(root.get("bargains"),
						new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
				if (b != null) b.forEach((ek, ov) -> {
					try {
						UUID villagerId = UUID.fromString(ek);
						Map<Integer, Integer> map = BARGAINS.computeIfAbsent(villagerId, k -> new ConcurrentHashMap<>());
						ov.forEach((ok, v) -> map.put(Integer.parseInt(ok), v));
					} catch (IllegalArgumentException ignored) {}
				});
			}
			if (root.has("conversations")) {
			Map<String, Map<String, List<ConversationEntry>>> c = GSON.fromJson(root.get("conversations"),
					new TypeToken<Map<String, Map<String, List<ConversationEntry>>>>() {}.getType());
			if (c != null) c.forEach((ek, pv) -> {
				try {
					UUID entityId = UUID.fromString(ek);
					Map<UUID, List<ConversationEntry>> map = CONVERSATION_HISTORY.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>());
					pv.forEach((pk, v) -> {
						try { map.put(UUID.fromString(pk), v); } catch (IllegalArgumentException ignored) {}
					});
				} catch (IllegalArgumentException ignored) {}
			});
		}
		if (root.has("ammo")) {
			com.google.gson.JsonElement ammoEl = root.get("ammo");
			if (ammoEl.isJsonObject()) {
				com.google.gson.JsonObject aObj = ammoEl.getAsJsonObject();
				// 新格式：{ "<uuid>": { "arrow": 5, "spectral": 2, "tipped:xxx": 3 } }
				// 旧格式兼容：{ "<uuid>": 5 }（纯整数当作普通箭数量）
				for (Map.Entry<String, com.google.gson.JsonElement> entry : aObj.entrySet()) {
					try {
						UUID entityId = UUID.fromString(entry.getKey());
						com.google.gson.JsonElement val = entry.getValue();
						Map<String, Integer> inv = new ConcurrentHashMap<>();
						if (val.isJsonObject()) {
							val.getAsJsonObject().entrySet().forEach(ie -> {
								try { inv.put(ie.getKey(), ie.getValue().getAsInt()); } catch (Exception ignored) {}
							});
						} else if (val.isJsonPrimitive()) {
							int n = val.getAsInt();
							if (n > 0) inv.put("arrow", n);
						}
						if (!inv.isEmpty()) AMMO.put(entityId, inv);
					} catch (IllegalArgumentException ignored) {}
				}
			}
		}
		if (root.has("totems")) {
			com.google.gson.JsonElement tEl = root.get("totems");
			if (tEl.isJsonObject()) {
				for (Map.Entry<String, com.google.gson.JsonElement> entry : tEl.getAsJsonObject().entrySet()) {
					try {
						UUID entityId = UUID.fromString(entry.getKey());
						int count = entry.getValue().getAsInt();
						if (count > 0) TOTEMS.put(entityId, count);
					} catch (IllegalArgumentException ignored) {}
				}
			}
		}
		MobMindMod.LOGGER.info("[MobMind] 已加载 {} 只生物的人格档案，{} 只生物的对话历史", PERSONALITIES.size(), CONVERSATION_HISTORY.size());
		} catch (Exception e) {
			MobMindMod.LOGGER.warn("[MobMind] 存档数据读取失败", e);
		}
	}

	public static void save(MinecraftServer server) {
		if (saveFile == null) return;
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		com.google.gson.JsonObject p = new com.google.gson.JsonObject();
		PERSONALITIES.forEach((k, v) -> p.add(k.toString(), GSON.toJsonTree(v)));
		root.add("personalities", p);
		com.google.gson.JsonObject f = new com.google.gson.JsonObject();
		FRIENDSHIP.forEach((ek, pv) -> {
			com.google.gson.JsonObject inner = new com.google.gson.JsonObject();
			pv.forEach((pk, v) -> inner.addProperty(pk.toString(), v));
			f.add(ek.toString(), inner);
		});
		root.add("friendship", f);
		com.google.gson.JsonObject b = new com.google.gson.JsonObject();
		BARGAINS.forEach((ek, ov) -> {
			com.google.gson.JsonObject inner = new com.google.gson.JsonObject();
			ov.forEach((ok, v) -> inner.addProperty(String.valueOf(ok), v));
			b.add(ek.toString(), inner);
		});
		root.add("bargains", b);
		com.google.gson.JsonObject c = new com.google.gson.JsonObject();
		CONVERSATION_HISTORY.forEach((ek, pv) -> {
			com.google.gson.JsonObject inner = new com.google.gson.JsonObject();
			pv.forEach((pk, v) -> inner.add(pk.toString(), GSON.toJsonTree(v)));
			c.add(ek.toString(), inner);
		});
		root.add("conversations", c);
		com.google.gson.JsonObject a = new com.google.gson.JsonObject();
		AMMO.forEach((ek, inv) -> {
			com.google.gson.JsonObject inner = new com.google.gson.JsonObject();
			inv.forEach((k, v) -> inner.addProperty(k, v));
			a.add(ek.toString(), inner);
		});
		root.add("ammo", a);
		com.google.gson.JsonObject t = new com.google.gson.JsonObject();
		TOTEMS.forEach((uuid, count) -> t.addProperty(uuid.toString(), count));
		root.add("totems", t);
		try {
			Path tmp = saveFile.resolveSibling("mobmind.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp)) {
				GSON.toJson(root, w);
			}
			Files.move(tmp, saveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MobMindMod.LOGGER.warn("[MobMind] 存档数据保存失败", e);
		}
	}

	public static void clear() {
		PERSONALITIES.clear();
		FRIENDSHIP.clear();
		ORDERS.clear();
		CALMED.clear();
		PROVOKED.clear();
		BARTER_DEALS.clear();
		BARGAINS.clear();
		CONVERSATION_HISTORY.clear();
		AMMO.clear();
		PLAYER_GIVEN_WEAPON.clear();
		TOTEMS.clear();
		GLOW_UNTIL.clear();
		saveFile = null;
	}

	// ---------- 变形数据迁移 ----------

	/** 获取所有好感度数据（用于变形迁移） */
	public static Map<UUID, Integer> getAllFriendship(UUID entityId) {
		Map<UUID, Integer> map = FRIENDSHIP.get(entityId);
		return map != null ? new HashMap<>(map) : null;
	}

	/** 设置单个玩家的好感度 */
	public static void setFriendship(UUID entityId, UUID playerId, int level) {
		FRIENDSHIP.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>()).put(playerId, level);
	}

	/** 获取所有激怒数据（用于变形迁移） */
	public static Map<UUID, Long> getAllProvoked(UUID entityId) {
		Map<UUID, Long> map = PROVOKED.get(entityId);
		return map != null ? new HashMap<>(map) : null;
	}

	/** 设置单个玩家的激怒标记 */
	public static void setProvoked(UUID entityId, UUID playerId, long untilGameTime) {
		PROVOKED.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>()).put(playerId, untilGameTime);
	}

	/** 获取所有安抚数据（用于变形迁移） */
	public static Map<UUID, Long> getAllCalmed(UUID entityId) {
		Map<UUID, Long> map = CALMED.get(entityId);
		return map != null ? new HashMap<>(map) : null;
	}

	/** 设置单个玩家的安抚标记 */
	public static void setCalmed(UUID entityId, UUID playerId, long untilGameTime) {
		CALMED.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>()).put(playerId, untilGameTime);
	}

	/** 获取人格数据（用于变形迁移） */
	public static Personality getPersonalityData(UUID entityId) {
		return PERSONALITIES.get(entityId);
	}

	/** 设置人格数据 */
	public static void setPersonalityData(UUID entityId, Object personality) {
		if (personality instanceof Personality p) {
			PERSONALITIES.put(entityId, p);
		}
	}

	/** 获取对话历史数据（用于变形迁移） */
	public static Map<UUID, List<ConversationEntry>> getConversationHistoryData(UUID entityId) {
		Map<UUID, List<ConversationEntry>> map = CONVERSATION_HISTORY.get(entityId);
		return map != null ? new HashMap<>(map) : null;
	}

	/** 设置对话历史数据 */
	public static void setConversationHistoryData(UUID entityId, Object data) {
		if (data instanceof Map<?, ?> map) {
			Map<UUID, List<ConversationEntry>> newMap = new ConcurrentHashMap<>();
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (e.getKey() instanceof UUID uid && e.getValue() instanceof List<?> list) {
					try {
						@SuppressWarnings("unchecked")
						List<ConversationEntry> entries = (List<ConversationEntry>) list;
						newMap.put(uid, new ArrayList<>(entries));
					} catch (Exception ignored) {}
				}
			}
			if (!newMap.isEmpty()) CONVERSATION_HISTORY.put(entityId, newMap);
		}
	}

	/** 获取所有弹药数据（用于变形迁移） */
	public static Map<String, Integer> getAllAmmo(UUID entityId) {
		Map<String, Integer> map = AMMO.get(entityId);
		return map != null ? new HashMap<>(map) : null;
	}

	/** 设置弹药数据 */
	public static void setAmmo(UUID entityId, String ammoKey, int count) {
		AMMO.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>()).put(ammoKey, count);
	}

	/** 获取图腾数量（用于变形迁移） */
	public static int getTotemCount(UUID entityId) {
		return TOTEMS.getOrDefault(entityId, 0);
	}

	/** 设置图腾数量 */
	public static void setTotemCount(UUID entityId, int count) {
		if (count > 0) TOTEMS.put(entityId, count);
	}

	/** 获取僵尸村民治愈数据（用于变形迁移） */
	public static Object getCuringData(UUID entityId) {
		if (CURING_ZOMBIE_VILLAGERS.containsKey(entityId)) {
			java.util.Map<String, Object> data = new java.util.HashMap<>();
			data.put("until", CURING_ZOMBIE_VILLAGERS.get(entityId));
			data.put("loyalty", CURING_LOYALTY.getOrDefault(entityId, 0));
			data.put("healer", CURING_HEALER.get(entityId));
			return data;
		}
		return null;
	}

	/** 设置僵尸村民治愈数据 */
	@SuppressWarnings("unchecked")
	public static void setCuringData(UUID entityId, Object data) {
		if (data instanceof java.util.Map<?, ?> map) {
			Long until = (Long) map.get("until");
			Integer loyalty = (Integer) map.get("loyalty");
			UUID healer = (UUID) map.get("healer");
			if (until != null) CURING_ZOMBIE_VILLAGERS.put(entityId, until);
			if (loyalty != null) CURING_LOYALTY.put(entityId, loyalty);
			if (healer != null) CURING_HEALER.put(entityId, healer);
		}
	}

	/** 清除指定实体的所有 MobMind 数据（变形或死亡后调用） */
	public static void clearEntityData(UUID entityId) {
		PERSONALITIES.remove(entityId);
		FRIENDSHIP.remove(entityId);
		ORDERS.remove(entityId);
		CALMED.remove(entityId);
		PROVOKED.remove(entityId);
		BARGAINS.remove(entityId);
		CONVERSATION_HISTORY.remove(entityId);
		AMMO.remove(entityId);
		PLAYER_GIVEN_WEAPON.remove(entityId);
		TOTEMS.remove(entityId);
		GLOW_UNTIL.remove(entityId);
		clearCuringZombieVillager(entityId);
	}

	/** 重载：标记玩家给武器（通过 UUID） */
	public static void markPlayerGivenWeapon(UUID entityId) {
		PLAYER_GIVEN_WEAPON.add(entityId);
	}

	/** 重载：标记僵尸村民治愈（通过 UUID） */
	private static void clearCuringZombieVillager(UUID entityId) {
		CURING_ZOMBIE_VILLAGERS.remove(entityId);
		CURING_LOYALTY.remove(entityId);
		CURING_HEALER.remove(entityId);
	}
}
