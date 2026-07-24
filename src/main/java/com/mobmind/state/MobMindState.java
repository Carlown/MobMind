package com.mobmind.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mobmind.MobMindMod;
import com.mobmind.persona.Personality;
import com.mobmind.persona.PersonalityGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
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

	/** 一条有时效的行为指令 */
	public record Order(OrderType type, UUID playerId, long expireGameTime) {}

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
		if (value >= 60) {
			mob.setPersistenceRequired();
		}
		return value;
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
		public record ItemRequirement(net.minecraft.world.item.Item item, int count) {}
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

	private static Mob findMob(MinecraftServer server, UUID entityId) {
		for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(entityId) instanceof Mob mob) return mob;
		}
		return null;
	}

	// ---------- 行为指令 ----------

	public static void setOrder(Mob mob, OrderType type, UUID playerId, long expireGameTime) {
		ORDERS.put(mob.getUUID(), new Order(type, playerId, expireGameTime));
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
