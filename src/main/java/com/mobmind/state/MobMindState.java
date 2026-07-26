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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端运行时状态：生物人格、玩家-生物好感度、行为指令、安抚状态。
 * 持久化到存档根目录 mobmind.json。
 */
public final class MobMindState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 行为指令类型 */
	public enum OrderType { FOLLOW, STAY, FLEE }

	/** 一条有时效的行为指令；fleeFrom 仅 FLEE 使用，表示要远离的点 */
	public record Order(OrderType type, UUID playerId, long expireGameTime, net.minecraft.world.phys.Vec3 fleeFrom) {}

	private static final Map<UUID, Personality> PERSONALITIES = new ConcurrentHashMap<>();
	/** entityUuid -> (playerUuid -> 0..100) */
	private static final Map<UUID, Map<UUID, Integer>> FRIENDSHIP = new ConcurrentHashMap<>();
	private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();
	/** entityUuid -> (playerUuid -> 安抚截止 gameTime) */
	private static final Map<UUID, Map<UUID, Long>> CALMED = new ConcurrentHashMap<>();

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

	// ---------- 村民砍价记录 ----------

	/** villagerUuid -> (offerIndex -> 已砍价次数)，持久化 */
	private static final Map<UUID, Map<Integer, Integer>> BARGAINS = new ConcurrentHashMap<>();
	/** entityUuid -> 高亮结束 gameTime */
	private static final Map<UUID, Long> GLOW_UNTIL = new ConcurrentHashMap<>();

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

	/** 服务端 tick 调用：到时间后取消高亮 */
	public static void tickGlow(MinecraftServer server) {
		long now = server.overworld().getLevelData().getGameTime();
		Iterator<Map.Entry<UUID, Long>> it = GLOW_UNTIL.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Long> e = it.next();
			Mob mob = findMob(server, e.getKey());
			if (mob == null || now >= e.getValue()) {
				if (mob != null) mob.setGlowingTag(false);
				it.remove();
			}
		}
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
			MobMindMod.LOGGER.info("[MobMind] 已加载 {} 只生物的人格档案", PERSONALITIES.size());
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
		saveFile = null;
	}
}
