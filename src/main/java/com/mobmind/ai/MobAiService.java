package com.mobmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BehaviorActions;
import com.mobmind.config.MobMindConfig;
import com.mobmind.net.MobPackets;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.Personality;
import com.mobmind.state.MobMindState;
import com.mobmind.util.EnvironmentSense;
import com.mobmind.util.ItemCatalog;
import com.mobmind.util.MobMindExecutor;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 AI 编排：构建提示词、调用 API、解析回复、应用行为、广播结果。
 * 全部 API 调用异步执行并限流，不阻塞游戏线程。
 */
public final class MobAiService {
	/** entityUuid -> (playerUuid -> 最近对话) */
	private static final Map<UUID, Map<UUID, Deque<OpenAiClient.ChatMessage>>> MEMORY = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HURT_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HELP_CRY = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_POTION_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_GREET = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_TAUNT = new ConcurrentHashMap<>();

	private MobAiService() {}

	/** 已提示过"离线模式"的玩家（每次进服提示一次） */
	private static final java.util.Set<UUID> OFFLINE_NOTIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// ---------- 入口：玩家说话 ----------

	public static void handleChatMessage(ServerPlayer player, String rawText) {
		if (rawText == null || rawText.isBlank()) return;
		if (rawText.startsWith("/")) return; // 命令不触发
		String text = rawText.trim();
		int radius = MobMindConfig.get().interactRadius + 10;
		AABB box = player.getBoundingBox().inflate(radius);
		List<Mob> nearby = player.level().getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && PersonaRegistry.supports(m) && withinTalkRange(m, player));
		if (nearby.isEmpty()) return;
		// 优先对最近的 1-3 只生物回应，防止刷屏
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(player)));
		int count = Math.min(3, nearby.size());
		String langHint = isEnglish(text) ? "请用英文回复。" : "";
		String heard = "（你听到玩家" + player.getGameProfile().name() + "说：\"" + text + "\"，你在附近，按你的性格回应一句。" + langHint + "）";
		for (int i = 0; i < count; i++) {
			Mob mob = nearby.get(i);
			long now = System.currentTimeMillis();
			Long last = LAST_REQUEST.get(mob.getUUID());
			if (last != null && now - last < 2000) continue;
			LAST_REQUEST.put(mob.getUUID(), now);
			respond(player, mob, heard, true);
		}
	}

	private static boolean isEnglish(String text) {
		if (text == null || text.isBlank()) return false;
		int asciiLetters = 0, totalLetters = 0;
		for (char c : text.toCharArray()) {
			if (Character.isLetter(c)) {
				totalLetters++;
				if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) asciiLetters++;
			}
		}
		return totalLetters > 0 && (double) asciiLetters / totalLetters > 0.65;
	}

	/** 判断当前游戏界面语言是否为英文（用于决定显示名格式） */
	private static boolean isEnglishUi() {
		try {
			String title = net.minecraft.locale.Language.getInstance().getOrDefault("gui.mobmind.config.title", "");
			return title.endsWith("Settings");
		} catch (Exception e) {
			return false;
		}
	}

	/** 判断生物是否在可对话范围内；飞行/空中生物额外放宽垂直距离，末影龙使用大范围水平距离 */
	private static boolean withinTalkRange(Mob mob, ServerPlayer player) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(mob.getType()).toString();
		if (id.equals("minecraft:ender_dragon")) {
			double dx = mob.getX() - player.getX();
			double dz = mob.getZ() - player.getZ();
			return dx * dx + dz * dz <= 128.0 * 128.0;
		}
		int base = MobMindConfig.get().interactRadius;
		boolean flying = !mob.onGround() && isFlyingEntityType(mob);
		int limit = flying ? base + 48 : base + 10;
		return mob.distanceTo(player) <= limit;
	}

	private static boolean isFlyingEntityType(Mob mob) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(mob.getType()).toString();
		return id.equals("minecraft:phantom") || id.equals("minecraft:bat")
				|| id.equals("minecraft:bee") || id.equals("minecraft:allay")
				|| id.equals("minecraft:parrot") || id.equals("minecraft:vex")
				|| id.equals("minecraft:ender_dragon")
				|| id.equals("minecraft:ghast") || id.equals("minecraft:happy_ghast");
	}

	public static void handleSpeak(ServerPlayer player, int entityId, String rawText) {
		if (rawText == null || rawText.isBlank()) return;
		Entity e = player.level().getEntity(entityId);
		if (!(e instanceof Mob mob) || !mob.isAlive()) return;
		if (!PersonaRegistry.supports(mob)) return; // 无专属设定的生物（如普通动物）不启用 AI
		if (!withinTalkRange(mob, player)) return;

		// 每只生物 2 秒内只处理一次请求，防止刷屏
		long now = System.currentTimeMillis();
		Long last = LAST_REQUEST.get(mob.getUUID());
		if (last != null && now - last < 2000) return;
		LAST_REQUEST.put(mob.getUUID(), now);

		String text = rawText.trim();
		// 跟随邀请：按好感度与外向程度掷骰，决定是否答应跟玩家去看看
		if (FOLLOW_INVITE.matcher(text).find()) {
			Personality p = MobMindState.personalityOf(mob);
			int friendship = MobMindState.friendship(mob, player.getUUID());
			int chance = 20 + friendship / 2 + p.sociability / 4;
			if (mob.getRandom().nextInt(100) < chance) {
				text += "（你内心决定跟他去看看，回复的 action 必须是 follow）";
			} else {
				text += "（你内心不打算跟他去，用符合你性格的方式婉拒，action 为 none）";
			}
		}
		// 视觉：玩家展示建筑求点评，扫描四周人工方块生成所见描述
		if (SHOW_INTENT.matcher(text).find()) {
			String seen = EnvironmentSense.scanBuild(player);
			text += "（你环顾四周，看到：" + seen
					+ "。请根据你的性格、审美和立场点评它，可以真诚夸赞也可以毒舌吐槽）";
			if (SHOW_ALL.matcher(rawText).find()) {
				notifyCrowdOpinion(player, mob, seen);
			}
			// 看完热闹就散了，不再继续跟随
			MobMindState.clearOrder(mob);
		}

		// 主动解除跟随：玩家说"别跟着我/不跟了"
		if (UNFOLLOW.matcher(text).find()) {
			MobMindState.Order order = MobMindState.orderFor(mob, mob.level().getLevelData().getGameTime());
			if (order != null && order.type() == MobMindState.OrderType.FOLLOW && order.playerId().equals(player.getUUID())) {
				MobMindState.clearOrder(mob);
				text += "（玩家让你别再跟着他，你决定停下。回复用你自己的风格说一声，action 为 none）";
			}
		}

		if (isEnglish(text)) {
			text += "（请用英文回复）";
		}
		respond(player, mob, text, true);
	}

	/** "给大家看"：附近最多 3 只其他生物也凑过来看热闹，各自按性格点评一句 */
	private static void notifyCrowdOpinion(ServerPlayer player, Mob speaker, String seen) {
		AABB box = player.getBoundingBox().inflate(16.0);
		List<Mob> crowd = player.level().getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && m != speaker && PersonaRegistry.supports(m) && withinTalkRange(m, player));
		int limit = Math.min(3, crowd.size());
		for (int i = 0; i < limit; i++) {
			respond(player, crowd.get(i), "（玩家" + player.getGameProfile().name()
					+ "向大家展示他建的东西。你凑过去看到：" + seen + "。按你的性格点评一句）", false);
		}
	}

	/** 跟随邀请话术 */
	private static final java.util.regex.Pattern FOLLOW_INVITE = java.util.regex.Pattern
			.compile("(跟我来|跟我走|跟着我|跟我去|跟我过|一起来|一起走|带你去|带你看|带你去看|过来看看|来看一下|来这边|陪我去|陪我走|陪我看看)");
	/** 展示作品求点评话术 */
	private static final java.util.regex.Pattern SHOW_INTENT = java.util.regex.Pattern
			.compile("(看我[建盖造搭做]的|看看我[建盖造搭做]的|我[建盖造搭做]了|给大家看看|给大家看|点评一下|评价一下|看看这个|欣赏一下|我的作品|我的建筑|好看吗|漂亮吗)");
	/** 向围观群众展示 */
	private static final java.util.regex.Pattern SHOW_ALL = java.util.regex.Pattern
			.compile("(大家|所有人|大伙|各位)");
	/** 主动解除跟随话术 */
	private static final java.util.regex.Pattern UNFOLLOW = java.util.regex.Pattern
			.compile("(别跟|不要跟|不用跟|不跟了|别跟着我|别跟过来|走开|你回去|待着别动|停下|不用你|散了吧|自己去玩)");

	// ---------- 入口：生物被打反应 ----------

	public static void onHurtByPlayer(Mob mob, ServerPlayer player) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_HURT_REACT.get(mob.getUUID());
		if (last != null && now - last < 20000) return; // 20秒冷却
		LAST_HURT_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, player.getUUID(), -12);
		long gameTime = mob.level().getLevelData().getGameTime();
		MobMindState.clearCalm(mob, player.getUUID()); // 动手即撕毁和解
		MobMindState.provoke(mob, player.getUUID(), gameTime + 6000); // 激怒5分钟，允许还手
		respond(player, mob, "（你突然攻击了它）", true);
	}

	// ---------- 入口：熟人生物被其他人/怪物攻击，向高好感玩家求救 ----------

	public static void onHurtByOther(Mob mob, Entity attacker) {
		if (!PersonaRegistry.supports(mob)) return;
		if (attacker == null || attacker == mob) return;
		Level level = mob.level();
		if (level.isClientSide()) return;

		long gameTime = level.getLevelData().getGameTime();
		Long last = LAST_HELP_CRY.get(mob.getUUID());
		if (last != null && gameTime - last < 6000) return; // 5分钟冷却

		// 找附近最熟悉、且在线的玩家求救
		UUID bestPlayer = null;
		int bestFriendship = -1;
		ServerPlayer bestSp = null;
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (p.level() != level || !p.isAlive()) continue;
			if (p.distanceTo(mob) > 64) continue;
			int f = MobMindState.friendship(mob, p.getUUID());
			if (f > bestFriendship && f >= 30) {
				bestFriendship = f;
				bestPlayer = p.getUUID();
				bestSp = p;
			}
		}
		if (bestSp == null) return;
		LAST_HELP_CRY.put(mob.getUUID(), gameTime);

		String attackerName = attacker instanceof net.minecraft.world.entity.player.Player
				? attacker.getDisplayName().getString()
				: attacker.getType().getDescription().getString();
		respond(bestSp, mob, "（你正被" + attackerName + "攻击，情况危急！向" + bestSp.getGameProfile().name()
				+ "求救，请求他快来保护你。你可以惊慌、愤怒或逞强，但要明确呼救）", false);
	}

	// ---------- 入口：村民被怪物追逐（还没被打到）就向高好感玩家求救 ----------

	private static final Map<UUID, Long> LAST_VILLAGER_CHASE_CRY = new ConcurrentHashMap<>();

	public static void onVillagerChased(net.minecraft.world.entity.npc.villager.Villager villager, Mob attacker) {
		if (!PersonaRegistry.supports(villager)) return;
		if (attacker == null || attacker == villager) return;
		Level level = villager.level();
		if (level.isClientSide()) return;

		long gameTime = level.getLevelData().getGameTime();
		Long last = LAST_VILLAGER_CHASE_CRY.get(villager.getUUID());
		if (last != null && gameTime - last < 6000) return; // 5分钟冷却

		UUID bestPlayer = null;
		int bestFriendship = -1;
		ServerPlayer bestSp = null;
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (p.level() != level || !p.isAlive()) continue;
			if (p.distanceTo(villager) > 64) continue;
			int f = MobMindState.friendship(villager, p.getUUID());
			if (f > bestFriendship && f >= 30) {
				bestFriendship = f;
				bestPlayer = p.getUUID();
				bestSp = p;
			}
		}
		if (bestSp == null) return;
		LAST_VILLAGER_CHASE_CRY.put(villager.getUUID(), gameTime);

		String attackerName = attacker.getType().getDescription().getString();
		respond(bestSp, villager, "（" + attackerName + "正在追你，你跑得很快但它还在后面！向"
				+ bestSp.getGameProfile().name() + "求救，让他快来保护你）", false);
	}

	// ---------- 入口：被玩家施加药水效果 ----------

	public static void onPotionAffected(Mob mob, ServerPlayer player, net.minecraft.world.effect.MobEffectInstance effect) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_POTION_REACT.get(mob.getUUID());
		if (last != null && now - last < 20000) return; // 20秒冷却
		LAST_POTION_REACT.put(mob.getUUID(), now);

		String name = effect.getEffect().value().getDisplayName().getString();
		boolean harmful = !effect.getEffect().value().isBeneficial();
		String desc = harmful
				? "（玩家" + player.getGameProfile().name() + "向你施加了" + name + "，你感觉很不好，用符合性格的方式抱怨或发怒）"
				: "（玩家" + player.getGameProfile().name() + "向你施加了" + name + "，你可能会感到好奇、舒服或警惕，用符合性格的方式回应）";
		respond(player, mob, desc, false);
	}

	// ---------- 入口：铜傀儡被玩家除锈/上蜡 ----------

	private static final Map<UUID, Long> LAST_COPPER_MAINTAIN = new ConcurrentHashMap<>();

	public static void onCopperGolemMaintained(Mob mob, ServerPlayer player, boolean waxed) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_COPPER_MAINTAIN.get(mob.getUUID());
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_COPPER_MAINTAIN.put(mob.getUUID(), now);

		String action = waxed ? "用蜂蜡保护你不再氧化" : "帮你除锈";
		MobMindState.adjustFriendship(mob, player.getUUID(), 8);
		respond(player, mob, "（玩家" + player.getGameProfile().name() + "刚刚" + action
				+ "，你感到很受用。用机械短句风格表达感谢，可以提到任务、按钮或铜锈）", false);
	}

	// ---------- 入口：玩家送盔甲 ----------

	private static final Map<UUID, Long> LAST_ARMOR_REACT = new ConcurrentHashMap<>();

	public static void onArmorGiven(Mob mob, ServerPlayer player, String itemName,
									net.minecraft.world.entity.EquipmentSlot slot) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_ARMOR_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_ARMOR_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, player.getUUID(), 12);
		respond(player, mob, "（玩家" + player.getGameProfile().name() + "送了你一件" + itemName
				+ "，你已经穿上了。用符合你性格的方式回应这份礼物）", false);
	}

	// ---------- 入口：僵尸村民被玩家救治 ----------

	private static final Map<UUID, Long> LAST_CURE_REACT = new ConcurrentHashMap<>();

	public static void onZombieVillagerCureStarted(net.minecraft.world.entity.monster.zombie.ZombieVillager zv,
											   ServerPlayer player) {
		if (!PersonaRegistry.supports(zv)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_CURE_REACT.get(zv.getUUID());
		if (last != null && now - last < 120000) return; // 2分钟冷却
		LAST_CURE_REACT.put(zv.getUUID(), now);

		MobMindState.adjustFriendship(zv, player.getUUID(), 25);
		respond(player, zv, "（玩家" + player.getGameProfile().name()
				+ "正在用虚弱药水和金苹果救你，你很快就能变回普通村民。用劫后余生的感激语气对他说点什么）", false);
	}

	// ---------- 入口：玩家喂食物 ----------

	private static final Map<UUID, Long> LAST_FOOD_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_FOOD_REQUEST = new ConcurrentHashMap<>();

	public static void onFoodFed(Mob mob, ServerPlayer player, String foodName, float healed) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_FOOD_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_FOOD_REACT.put(mob.getUUID(), now);
		respond(player, mob, "（玩家" + player.getGameProfile().name() + "喂你吃了" + foodName
				+ "，你感觉好多了。用符合你性格的方式回应）", false);
	}

	// ---------- 入口：玩家扔礼物给生物 ----------

	private static final Map<UUID, Long> LAST_GIFT_REACT = new ConcurrentHashMap<>();

	public static void onGiftReceived(Mob mob, ServerPlayer player, String itemName, int count) {
		if (!PersonaRegistry.supports(mob)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_GIFT_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_GIFT_REACT.put(mob.getUUID(), now);
		respond(player, mob, "（玩家" + player.getGameProfile().name() + "送给你" + count + "个" + itemName
				+ "，你收下了。用符合你性格的方式表达感谢或开心）", false);
	}

	// ---------- 入口：猪灵原版金锭以物易物 ----------

	/** piglinUuid -> 扔金锭玩家的 UUID */
	private static final Map<UUID, UUID> PIGLIN_GOLD_THROWER = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_PIGLIN_BARTER = new ConcurrentHashMap<>();

	/** 猪灵捡起玩家扔出的金锭：记录交易发起人 */
	public static void onPiglinGoldReceived(Mob piglin, ServerPlayer player) {
		if (!PersonaRegistry.supports(piglin)) return;
		PIGLIN_GOLD_THROWER.put(piglin.getUUID(), player.getUUID());
	}

	/** 猪灵完成回赠（把交易物品扔给玩家）后触发一句对话 */
	public static void onPiglinBarterComplete(Mob piglin) {
		if (!PersonaRegistry.supports(piglin)) return;
		UUID playerId = PIGLIN_GOLD_THROWER.remove(piglin.getUUID());
		if (playerId == null) return;

		long now = System.currentTimeMillis();
		Long last = LAST_PIGLIN_BARTER.get(piglin.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_PIGLIN_BARTER.put(piglin.getUUID(), now);

		if (piglin.level().getServer() == null) return;
		ServerPlayer player = piglin.level().getServer().getPlayerList().getPlayer(playerId);
		if (player == null || !player.isAlive() || player.distanceTo(piglin) > 32) return;

		MobMindState.adjustFriendship(piglin, playerId, 3);
		respond(player, piglin, "（你刚刚收下玩家" + player.getGameProfile().name()
				+ "的金锭，并把交易得到的物品回赠给他。用符合你性格的方式回应这次交易）", false);
	}

	// ---------- 入口：猪灵因玩家挖金块/开宝箱发怒 ----------

	private static final Map<UUID, Long> LAST_PIGLIN_LOOT_ANGER = new ConcurrentHashMap<>();

	public static void onPiglinAngeredByLooting(net.minecraft.world.entity.monster.piglin.Piglin piglin, ServerPlayer player) {
		if (!PersonaRegistry.supports(piglin)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_PIGLIN_LOOT_ANGER.get(piglin.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_PIGLIN_LOOT_ANGER.put(piglin.getUUID(), now);

		MobMindState.adjustFriendship(piglin, player.getUUID(), -8);
		long gameTime = piglin.level().getLevelData().getGameTime();
		MobMindState.provoke(piglin, player.getUUID(), gameTime + 6000); // 激怒5分钟
		respond(player, piglin, "（玩家" + player.getGameProfile().name()
				+ "正在挖你们的金块或者翻你们的宝箱！你被激怒了。用符合你性格的方式呵斥、威胁或怒吼）", false);
	}

	// ---------- 入口：玩家破坏末地水晶，末影龙发怒 ----------

	private static final Map<UUID, Long> LAST_CRYSTAL_REACT = new ConcurrentHashMap<>();

	public static void onEndCrystalAttacked(net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon, ServerPlayer player) {
		if (!PersonaRegistry.supports(dragon)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_CRYSTAL_REACT.get(dragon.getUUID());
		if (last != null && now - last < 15000) return; // 15秒冷却
		LAST_CRYSTAL_REACT.put(dragon.getUUID(), now);

		MobMindState.adjustFriendship(dragon, player.getUUID(), -10);
		long gameTime = dragon.level().getLevelData().getGameTime();
		MobMindState.provoke(dragon, player.getUUID(), gameTime + 6000); // 激怒5分钟
		respond(player, dragon, "（玩家" + player.getGameProfile().name()
				+ "正在破坏给你回血的末地水晶！你感到愤怒和痛苦，用符合你性格的方式怒吼或威胁他停下）", false);
	}

	// ---------- 入口：末影龙被末地水晶复活 ----------

	private static final Map<UUID, Long> LAST_DRAGON_RESPAWN = new ConcurrentHashMap<>();

	public static void onDragonRespawned(net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
		if (!PersonaRegistry.supports(dragon)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_DRAGON_RESPAWN.get(dragon.getUUID());
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_DRAGON_RESPAWN.put(dragon.getUUID(), now);

		if (dragon.level().getServer() == null) return;
		ServerLevel level = (ServerLevel) dragon.level();
		ServerPlayer nearest = null;
		double bestDist = Double.MAX_VALUE;
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (p.level() != level) continue;
			double d = p.distanceTo(dragon);
			if (d < bestDist) {
				bestDist = d;
				nearest = p;
			}
		}
		if (nearest == null) return;
		respond(nearest, dragon, "（你刚刚被末地水晶复活，重新降临末地。玩家" + nearest.getGameProfile().name()
				+ "就在附近。用符合你性格的方式宣告你的归来或警告玩家）", false);
	}

	// ---------- 入口：朋友关系且低血量时主动向玩家要食物 ----------

	public static void tryFoodRequest(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive()) continue;
			Level level = player.level();
			if (level.isClientSide()) continue;
			long gameTime = level.getLevelData().getGameTime();
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Mob> hungry = level.getEntitiesOfClass(Mob.class, box, m ->
					m.isAlive()
							&& PersonaRegistry.supports(m)
							&& MobMindState.isFriendlyTo(m, player.getUUID())
							&& m.getHealth() < m.getMaxHealth() * 0.4f
							&& !isInCombat(m));
			if (hungry.isEmpty()) continue;
			// 选血量比例最低的一只
			hungry.sort(java.util.Comparator.comparingDouble(
					m -> m.getHealth() / m.getMaxHealth()));
			Mob mob = hungry.get(0);
			Long last = LAST_FOOD_REQUEST.get(mob.getUUID());
			if (last != null && gameTime - last < 2400) continue; // 2分钟冷却
			LAST_FOOD_REQUEST.put(mob.getUUID(), gameTime);
			respond(player, mob, "（你肚子饿了，血量也不足，向玩家" + player.getGameProfile().name()
					+ "要点吃的。用符合你性格的方式撒娇、抱怨或直接开口）", false);
			return; // 每轮最多触发一次
		}
	}

	private static boolean isInCombat(Mob mob) {
		return mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100;
	}

	// ---------- 入口：敌对生物攻击玩家，友好生物可能出面阻止 ----------

	private static final Map<UUID, Long> LAST_MOB_CONFLICT = new ConcurrentHashMap<>();

	public static void onMobTargetsPlayer(Mob attacker, ServerPlayer player) {
		if (!PersonaRegistry.supports(attacker)) return;
		Level level = attacker.level();
		long gameTime = level.getLevelData().getGameTime();
		Long last = LAST_MOB_CONFLICT.get(attacker.getUUID());
		if (last != null && gameTime - last < 6000) return; // 5分钟冷却
		LAST_MOB_CONFLICT.put(attacker.getUUID(), gameTime);

		// 找附近对玩家友好、且不是当前攻击者的生物
		AABB box = attacker.getBoundingBox().inflate(16.0);
		List<Mob> allies = level.getEntitiesOfClass(Mob.class, box,
				m -> m != attacker && m.isAlive() && PersonaRegistry.supports(m)
						&& MobMindState.isFriendlyTo(m, player.getUUID()));
		if (allies.isEmpty()) return;

		String attackerName = attacker.getType().getDescription().getString();
		String playerName = player.getGameProfile().name();
		int limit = Math.min(2, allies.size());
		for (int i = 0; i < limit; i++) {
			Mob ally = allies.get(i);
			Personality p = MobMindState.personalityOf(ally);
			respond(player, ally, "（你看见" + attackerName + "正在攻击" + playerName
					+ "，而你对这个玩家印象不错。用符合你性格的方式呵斥、阻止或嘲笑攻击者）", false);
			// 暴躁程度越高，越可能真的动手帮玩家
			if (ally.getRandom().nextInt(100) < p.temper / 2) {
				ally.setTarget(attacker);
			}
		}
	}

	// ---------- 入口：随机打招呼 ----------

	public static void tryRandomGreeting(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			AABB box = player.getBoundingBox().inflate(8.0);
			List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m));
			if (mobs.isEmpty()) continue;
			Mob mob = mobs.get(player.getRandom().nextInt(mobs.size()));

			long gameTime = mob.level().getLevelData().getGameTime();
			Long last = LAST_GREET.get(mob.getUUID());
			if (last != null && gameTime - last < 12000) continue; // 10分钟冷却

			Personality p = MobMindState.personalityOf(mob);
			int friendship = MobMindState.friendship(mob, player.getUUID());
			// 社交倾向越高、好感越高越容易主动搭话
			if (friendship < 25) continue;
			if (player.getRandom().nextInt(100) >= p.sociability / 2) continue;

			LAST_GREET.put(mob.getUUID(), gameTime);
			respond(player, mob, "（玩家路过你身边，请主动打个招呼）", false);
			return; // 每轮最多一只生物搭话
		}
	}

	// ---------- 入口：10%敌对生物嘲讽创造模式玩家 ----------

	/** 附近创造模式玩家会被"求战型"怪物嘲讽/激将换生存模式。返回是否触发了一只 */
	public static boolean tryCreativeTaunt(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isCreative()) continue;
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m));
			long gameTime = player.level().getLevelData().getGameTime();
			for (Mob mob : mobs) {
				Personality p = MobMindState.personalityOf(mob);
				if (!Boolean.TRUE.equals(p.creativeTaunt)) continue;
				if (isPiglin(mob) && !isPiglinBrute(mob) && hasAnyGoldArmor(player)) continue; // 普通猪灵对穿金甲玩家保持中立
				Long last = LAST_TAUNT.get(mob.getUUID());
				if (last != null && gameTime - last < 3600) continue; // 3分钟冷却
				LAST_TAUNT.put(mob.getUUID(), gameTime);
				respond(player, mob, "（你发现这个玩家开着创造模式，你根本伤不到他。用你自己的风格激他、嘲讽他，让他换成生存模式和你真正打一场）", false);
				return true;
			}
		}
		return false;
	}

	private static boolean isPiglin(Mob mob) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(mob.getType()).toString().equals("minecraft:piglin");
	}

	private static boolean isPiglinBrute(Mob mob) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(mob.getType()).toString().equals("minecraft:piglin_brute");
	}

	private static boolean hasAnyGoldArmor(ServerPlayer player) {
		for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
			if (!slot.isArmor()) continue;
			net.minecraft.world.item.ItemStack stack = player.getItemBySlot(slot);
			if (stack.is(net.minecraft.world.item.Items.GOLDEN_HELMET)
					|| stack.is(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE)
					|| stack.is(net.minecraft.world.item.Items.GOLDEN_LEGGINGS)
					|| stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS)) {
				return true;
			}
		}
		return false;
	}

	// ---------- 入口：以物易物交付完成 ----------

	public static void notifyBarterCompleted(ServerPlayer player, Mob mob, String giveDesc, String takeDesc) {
		respond(player, mob, "（玩家按约定把 " + giveDesc + " 扔给了你，你把 " + takeDesc + " 交给了玩家。交易完成，用你自己的风格回应一句）", false);
	}

	public static void notifyBarterDealMade(ServerPlayer player, Mob mob, String giveDesc, String takeDesc) {
		respond(player, mob, "（你和玩家约定：他给你 " + giveDesc + "，你回赠 " + takeDesc
				+ "。现在等待玩家把约定物品扔给你，不要改口。回复一句确认即可）", false);
	}

	// ---------- 入口：村民护床 ----------

	/** 玩家睡了村民的床：村民喝止（会赶人的放狠话，不赶的抱怨） */
	public static void scoldBedThief(ServerPlayer player, Mob villager, boolean willKick) {
		respond(player, villager, willKick
				? "（这个玩家占了你的床睡觉！用你自己的风格喝止他，让他立刻滚下床，警告他再不起来你就直接掀他下去）"
				: "（这个玩家占了你的床睡觉。用你自己的风格抱怨两句，让他知道你很不爽，但你今晚懒得跟他计较）", false);
	}

	/** 裁决结果：赶下床后放狠话，或忍了嘟囔一句 */
	public static void bedKickResolved(ServerPlayer player, Mob villager, boolean kicked) {
		respond(player, villager, kicked
				? "（他赖着不走，你直接把他掀下了床，自己躺回去。用你的风格撂一句狠话）"
				: "（他还是没起床，你忍了，自己另外找地方将就一晚。用你的风格嘟囔一句）", false);
	}

	// ---------- 核心流程 ----------

	/** 砍价：对某个在售商品，agree=是否同意降价 */
	private record Bargain(String item, boolean agree) {}
	/** 以物易物：玩家交付 gives 列表，生物回赠 takes 列表 */
	private record Barter(List<ItemCatalog.MatchedItem> gives, List<ItemCatalog.MatchedItem> takes) {}
	private record ParsedReply(String say, String mood, String action, int friendshipDelta,
							   Bargain bargain, Barter barter) {}

	private static void respond(ServerPlayer player, Mob mob, String userText, boolean applyActions) {
		MobMindConfig cfg = MobMindConfig.get();
		Personality persona = MobMindState.personalityOf(mob);
		MinecraftServer server = player.level().getServer();
		if (server == null) return;

		if (!cfg.isApiReady()) {
			if (cfg.offlineFallback) {
				if (OFFLINE_NOTIFIED.add(player.getUUID())) {
					sendSystem(player, net.minecraft.network.chat.Component.translatable("status.mobmind.api_not_set_fallback"));
					MobMindMod.LOGGER.info("[MobMind] API 未配置，{} 的对话使用离线兜底回复", player.getGameProfile().name());
				}
				ParsedReply fallback = offlineReply(persona, mob, player, userText);
				finish(server, player, mob, persona, fallback, applyActions);
			} else {
				sendSystem(player, net.minecraft.network.chat.Component.translatable("status.mobmind.api_not_set"));
			}
			return;
		}
		if (!MobMindExecutor.tryAcquireApiSlot()) {
			sendSystem(player, net.minecraft.network.chat.Component.translatable("status.mobmind.api_busy"));
			return;
		}

		MobMindExecutor.runAsync(() -> {
			try {
				long t0 = System.currentTimeMillis();
				List<OpenAiClient.ChatMessage> messages = buildMessages(persona, mob, player, userText);
				String raw = OpenAiClient.chat(cfg, messages);
				long aiMs = System.currentTimeMillis() - t0;
				MobMindMod.LOGGER.info("[MobMind] AI 响应 {}ms, 原始回复: {}", aiMs,
						raw.length() > 500 ? raw.substring(0, 500) : raw);
				ParsedReply reply = parse(raw, persona);
				server.execute(() -> finish(server, player, mob, persona, reply, applyActions));
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] AI 调用失败: {}", e.getMessage());
				server.execute(() -> {
					if (MobMindConfig.get().offlineFallback) {
						finish(server, player, mob, persona, offlineReply(persona, mob, player, userText), applyActions);
					} else {
						sendSystem(player, net.minecraft.network.chat.Component.translatable("status.mobmind.api_error", e.getMessage()));
					}
				});
			} finally {
				MobMindExecutor.releaseApiSlot();
			}
		});
	}

	/** 在主线程执行：调整好感度、应用行为、广播回复 */
	private static void finish(MinecraftServer server, ServerPlayer player, Mob mob,
							   Personality persona, ParsedReply reply, boolean applyActions) {
		if (!mob.isAlive()) return;
		int friendship = MobMindState.adjustFriendship(mob, player.getUUID(), reply.friendshipDelta());
		String action = "none";
		if (applyActions) {
			action = BehaviorActions.apply(mob, player, reply.action());
		}
		Bargain bargain = reply.bargain();
		if (bargain == null && applyActions && mob instanceof AbstractVillager av) { // 模型漏输出 bargain 字段时兜底
			bargain = extractBargainFromText(lastUserText(mob.getUUID(), player.getUUID()), reply.say(), av);
		}
		if (bargain != null && mob instanceof AbstractVillager villager) {
			MobMindMod.LOGGER.info("[MobMind] 砍价: {} 对 {} 商品「{}」 agree={}",
					player.getGameProfile().name(), mob.getType().getDescription().getString(),
					bargain.item(), bargain.agree());
			BarterActions.applyBargain(villager, player, persona, bargain.item(), bargain.agree());
		}
		Barter barter = reply.barter();
		if (barter == null && applyActions) { // 模型漏输出 barter 字段时，从对话文本兜底识别
			barter = extractBarterFromText(lastUserText(mob.getUUID(), player.getUUID()), reply.say());
		}
		if (barter != null) { // 所有生物（含村民）都可以谈以物易物
			BarterActions.createDeal(mob, player, barter.gives(), barter.takes());
		}
		remember(mob.getUUID(), player.getUUID(), "user", lastUserText(mob.getUUID(), player.getUUID()));
		remember(mob.getUUID(), player.getUUID(), "assistant", reply.say());

		String mobName = isEnglishUi()
				? mob.getType().getDescription().getString()
				: persona.name + "（" + mob.getType().getDescription().getString() + "）";
		MobPackets.ReplyPayload packet = new MobPackets.ReplyPayload(
				mob.getId(), mobName, reply.say(), reply.mood(), action, friendship,
				player.getGameProfile().name(), persona.voiceId);
		for (ServerPlayer p : PlayerLookup.tracking(mob)) {
			ServerPlayNetworking.send(p, packet);
		}
		if (!PlayerLookup.tracking(mob).contains(player)) {
			ServerPlayNetworking.send(player, packet);
		}

		// 说话时高亮 3 秒，方便玩家从一群生物里找到说话者
		MobMindState.glowFor(mob, mob.level().getLevelData().getGameTime(), 60);
	}

	// ---------- 提示词 ----------

	private static List<OpenAiClient.ChatMessage> buildMessages(Personality persona, Mob mob,
																ServerPlayer player, String userText) {
		int friendship = MobMindState.friendship(mob, player.getUUID());
		Level level = mob.level();
		long dayTime = level.getOverworldClockTime() % 24000;
		String timeDesc = (dayTime >= 13000 && dayTime <= 23000) ? "夜晚" : "白天";
		String weather = level.isThundering() ? "雷暴" : level.isRaining() ? "下雨" : "晴朗";
		String hand = player.getMainHandItem().isEmpty() ? "空手" : player.getMainHandItem().getHoverName().getString();
		boolean targetingPlayer = mob.getTarget() == player;
		String relation = friendship < 20 ? "死敌" : friendship < 40 ? "陌生" : friendship < 60 ? "认识" : friendship < 80 ? "朋友" : "挚友";
		String gameMode = player.isCreative() ? "创造模式（你伤不到他）" : "生存模式";
		boolean piglinNeutralGold = isPiglin(mob) && !isPiglinBrute(mob) && hasAnyGoldArmor(player);
		String tauntTrait = Boolean.TRUE.equals(persona.creativeTaunt) && !piglinNeutralGold
				? "\n- 你极度渴望和玩家公平决斗：只要他还在创造模式，你就忍不住三句不离让他换成生存模式再来面对你。" : "";
		String environment = EnvironmentSense.describe(mob);

		PersonaRegistry.Persona spec = PersonaRegistry.forMob(mob);
		String personaText = spec != null ? spec.text() : "（无专属设定，按该生物的原版习性扮演）";
		String alignmentDesc = persona.alignment != null
				? "该个体在首次生成时被抽取为「" + persona.alignment + "」，此结果永久固定、不会重抽，必须严格遵守设定中该类型立场的表现方式。"
				: "";

		// 村民/流浪商人：注入在售商品列表供砍价参考
		StringBuilder offersSection = new StringBuilder();
		if (mob instanceof AbstractVillager villager && !villager.getOffers().isEmpty()) {
			offersSection.append("【你在售的商品】\n");
			var offers = villager.getOffers();
			for (int i = 0; i < Math.min(offers.size(), 12); i++) {
				var o = offers.get(i);
				String cost = o.getCostA().getHoverName().getString() + "×" + o.getCostA().getCount();
				if (!o.getCostB().isEmpty()) cost += " + " + o.getCostB().getHoverName().getString() + "×" + o.getCostB().getCount();
				offersSection.append(i + 1).append(". ").append(cost).append(" → ")
						.append(o.getResult().getHoverName().getString()).append("×").append(o.getResult().getCount()).append("\n");
			}
		}

		String system = """
				你正在 Minecraft 世界里扮演一只生物，必须完全代入角色，禁止提及你是 AI。
				【生物设定（最高优先级，必须严格遵守）】
				%s
				【本个体的性格抽取结果】
				%s
				- 你的名字: %s
				- 外向程度: %d/100，暴躁程度: %d/100，幽默感: %d/100
				【与玩家的关系】
				- 玩家名: %s，好感度: %d/100（关系: %s）
				- 玩家游戏模式: %s%s
				【当前处境】
				- 生命值 %.0f/%.0f，%s，天气%s
				- 玩家手持: %s
				- 你正在攻击该玩家: %s
				- 你当前的处境: %s
				%s【回复规则】
				1. 只输出一行 JSON：{"say":"...","mood":"...","action":"...","friendship":数字,"bargain":null,"barter":null}
				2. say：用第一人称口语，严格符合你的设定、性格立场与说话风格，不超过60字。语言规则（必须遵守）：玩家使用什么语言，你就必须用完全相同语言回复；中文玩家→中文回复，英文玩家→英文回复，严禁混用或突然切换语言。
				3. mood：一个情绪词，如 开心/生气/害怕/好奇/平静
				4. action 必须是 none|calm|follow|stay|flee|gift|attack 之一：
				   - 玩家请求你跟随/同行且你愿意 → follow
				   - 玩家请求和解/别打他，且你愿意停手 → calm
				   - 玩家让你待在原地/停下 → stay
				   - 你被吓到或想逃走 → flee
				   - 你想送玩家一个小礼物 → gift
				   - 你被激怒想攻击玩家（仅限敌对生物，且需符合你的性格立场） → attack
				   - 其他情况 → none
				5. friendship：本次对话后好感度增减，-10 到 10 的整数。友善话语+1~5，辱骂威胁-3~10，依据你的暴躁程度决定敏感度
				6. 好感度低时应表现得敌对或警惕；好感度高时应亲切热情；但立场与行为底线永远以你的性格抽取结果为准
				7.【砍价-仅村民/流浪商人】玩家对某个在售商品砍价时：bargain={"item":"商品名","agree":"yes或no"}，否则为 null。是否同意由你的性格立场、与玩家的关系和玩家的话术决定；每个商品最多只能砍成一次，重复砍价你应该拒绝（系统会自动处理涨价）。
				8.【以物易物】谈妥时设 barter={"gives":[{"name":"玩家应给你的物品","count":数量},...],"takes":[{"name":"你回赠的物品","count":数量},...]}。gives=玩家要给你的列表，takes=你给玩家的列表。例：玩家"3个苹果换5个腐肉"→{"gives":[{"name":"苹果","count":3}],"takes":[{"name":"腐肉","count":5}]}；"8个绿宝石加1个蛋糕换铁胸甲"→{"gives":[{"name":"绿宝石","count":8},{"name":"蛋糕","count":1}],"takes":[{"name":"铁胸甲","count":1}]}。玩家把所有 gives 物品扔到你脚边前，不准把 takes 物品丢出来，也不要 action:gift。口头答应但没 barter 等于拒绝。回赠物品要符合身份（僵尸给腐肉、骷髅给骨头等），1-16个。
				""".formatted(
				personaText, alignmentDesc, persona.name,
				persona.sociability, persona.temper, persona.humor,
				player.getGameProfile().name(), friendship, relation,
				gameMode, tauntTrait,
				mob.getHealth(), mob.getMaxHealth(), timeDesc, weather, hand,
				targetingPlayer ? "是" : "否", environment, offersSection.toString());

		List<OpenAiClient.ChatMessage> messages = new ArrayList<>();
		messages.add(new OpenAiClient.ChatMessage("system", system));
		Deque<OpenAiClient.ChatMessage> history = MEMORY
				.computeIfAbsent(mob.getUUID(), k -> new ConcurrentHashMap<>())
				.get(player.getUUID());
		if (history != null) messages.addAll(history);
		messages.add(new OpenAiClient.ChatMessage("user", userText));
		remember(mob.getUUID(), player.getUUID(), "__pending__", userText);
		return messages;
	}

	private static void remember(UUID entityId, UUID playerId, String role, String content) {
		if ("__pending__".equals(role)) {
			PENDING.put(entityId + ":" + playerId, content);
			return;
		}
		Deque<OpenAiClient.ChatMessage> history = MEMORY
				.computeIfAbsent(entityId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(playerId, k -> new ArrayDeque<>());
		if ("user".equals(role)) {
			history.addLast(new OpenAiClient.ChatMessage("user", content));
		} else if ("assistant".equals(role)) {
			history.addLast(new OpenAiClient.ChatMessage("assistant", content));
		}
		while (history.size() > 4) history.removeFirst();
	}

	private static final Map<String, String> PENDING = new ConcurrentHashMap<>();

	private static String lastUserText(UUID entityId, UUID playerId) {
		return PENDING.getOrDefault(entityId + ":" + playerId, "...");
	}

	// ---------- 回复解析 ----------

	private static ParsedReply parse(String raw, Personality persona) {
		try {
			int start = raw.indexOf('{');
			int end = raw.lastIndexOf('}');
			if (start >= 0 && end > start) {
				JsonObject o = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
				String say = o.has("say") ? o.get("say").getAsString().trim() : "";
				String mood = o.has("mood") ? o.get("mood").getAsString().trim() : "平静";
				String action = o.has("action") ? o.get("action").getAsString().trim().toLowerCase() : "none";
				int delta = o.has("friendship") ? o.get("friendship").getAsInt() : 0;
				delta = Math.max(-10, Math.min(10, delta));
				if (say.isEmpty()) say = "……";
				return new ParsedReply(say, mood, BehaviorActions.isValid(action) ? action : "none", delta,
						parseBargain(o), parseBarter(o));
			}
		} catch (Exception e) {
			MobMindMod.LOGGER.debug("[MobMind] 回复解析失败，尝试提取台词: {}", e.getMessage());
		}
		// 非标准 JSON 时，尝试只提取 say 字段，避免把 mood/action 也读出来
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\"say\"\\s*:\\s*\"([^\"]{1,300})\"").matcher(raw);
		if (m.find()) {
			return new ParsedReply(m.group(1).trim(), "平静", "none", 0, null, null);
		}
		String cleaned = raw.replaceAll("<\\|[^|]*\\|>", "").trim();
		// 内容仍像 JSON 碎片（含其他字段名），不当作台词
		if (cleaned.contains("\"mood\"") || cleaned.contains("\"action\"") || cleaned.contains("\"friendship\"")) {
			return new ParsedReply("……", "平静", "none", 0, null, null);
		}
		if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
		return new ParsedReply(cleaned.isEmpty() ? "……" : cleaned, "平静", "none", 0, null, null);
	}

	private static Bargain parseBargain(JsonObject o) {
		try {
			if (!o.has("bargain") || !o.get("bargain").isJsonObject()) return null;
			JsonObject b = o.getAsJsonObject("bargain");
			String item = b.has("item") ? b.get("item").getAsString().trim() : "";
			boolean agree = b.has("agree") && "yes".equalsIgnoreCase(b.get("agree").getAsString().trim());
			return item.isEmpty() ? null : new Bargain(item, agree);
		} catch (Exception e) {
			return null;
		}
	}

	private static Barter parseBarter(JsonObject o) {
		try {
			JsonObject b = null;
			if (o.has("barter") && o.get("barter").isJsonObject()) b = o.getAsJsonObject("barter");
			else if (o.has("trade") && o.get("trade").isJsonObject()) b = o.getAsJsonObject("trade"); // 别名容错
			if (b == null) return null;

			List<ItemCatalog.MatchedItem> gives = parseItemsArray(b, "gives");
			List<ItemCatalog.MatchedItem> takes = parseItemsArray(b, "takes");
			if (gives == null || takes == null) {
				// 旧版单物品字段兼容
				String give = b.has("give") ? b.get("give").getAsString().trim() : "";
				String take = b.has("take") ? b.get("take").getAsString().trim() : "";
				int giveCount = parseCount(b, "giveCount");
				int takeCount = parseCount(b, "takeCount");
				if (give.isEmpty() || take.isEmpty()) return null;
				Item giveItem = ItemCatalog.byName(give);
				Item takeItem = ItemCatalog.byName(take);
				if (giveItem == null || takeItem == null) return null;
				gives = List.of(new ItemCatalog.MatchedItem(giveItem, give, giveCount));
				takes = List.of(new ItemCatalog.MatchedItem(takeItem, take, takeCount));
			}
			if (gives.isEmpty() || takes.isEmpty()) return null;
			return new Barter(gives, takes);
		} catch (Exception e) {
			return null;
		}
	}

	/** 解析 barter 中的 gives/takes：支持数组或单个对象 */
	private static List<ItemCatalog.MatchedItem> parseItemsArray(JsonObject b, String key) {
		if (!b.has(key)) return null;
		List<ItemCatalog.MatchedItem> list = new ArrayList<>();
		var el = b.get(key);
		if (el.isJsonArray()) {
			for (var itemEl : el.getAsJsonArray()) {
				if (!itemEl.isJsonObject()) continue;
				ItemCatalog.MatchedItem m = parseItemObject(itemEl.getAsJsonObject());
				if (m != null) list.add(m);
			}
		} else if (el.isJsonObject()) {
			ItemCatalog.MatchedItem m = parseItemObject(el.getAsJsonObject());
			if (m != null) list.add(m);
		} else {
			return null;
		}
		return list;
	}

	private static ItemCatalog.MatchedItem parseItemObject(JsonObject obj) {
		String name = obj.has("name") ? obj.get("name").getAsString().trim() : "";
		if (name.isEmpty() && obj.has("item")) name = obj.get("item").getAsString().trim();
		if (name.isEmpty()) return null;
		Item item = ItemCatalog.byName(name);
		if (item == null) return null;
		int count = 1;
		if (obj.has("count")) {
			try { count = obj.get("count").getAsInt(); } catch (Exception ignored) {}
		}
		return new ItemCatalog.MatchedItem(item, name, Math.max(1, count));
	}

	/** 数量容错解析：支持数字与中文数字（五/十二/二十） */
	private static int parseCount(JsonObject o, String key) {
		if (!o.has(key)) return 1;
		try {
			return o.get(key).getAsInt();
		} catch (Exception ignored) {
			return chineseNumber(o.get(key).getAsString());
		}
	}

	// 玩家砍价意图
	private static final java.util.regex.Pattern HAGGLE_INTENT = java.util.regex.Pattern
			.compile("(便宜|砍价|优惠|降价|打折|少[点一儿]|太贵|贵死)");

	/**
	 * 砍价兜底：玩家明确讨价还价且提到某个在售商品，生物台词接受/拒绝 → 按结果处理。
	 */
	private static Bargain extractBargainFromText(String userText, String say, AbstractVillager villager) {
		if (userText == null || say == null || userText.startsWith("（")) return null;
		if (!HAGGLE_INTENT.matcher(userText).find()) return null;
		ItemCatalog.MatchedItem wanted = ItemCatalog.findInText(userText, false);
		if (wanted == null) return null;
		String offerName = null;
		var offers = villager.getOffers();
		for (int i = 0; i < offers.size(); i++) {
			if (offers.get(i).getResult().is(wanted.item())) {
				offerName = offers.get(i).getResult().getHoverName().getString();
				break;
			}
		}
		if (offerName == null) return null;
		boolean refuse = REFUSE_PATTERN.matcher(say).find();
		boolean accept = !refuse && ACCEPT_PATTERN.matcher(say).find();
		if (!refuse && !accept) return null;
		return new Bargain(offerName, accept);
	}

	// 生物台词中的明确拒绝（出现时不兜底创建约定）
	private static final java.util.regex.Pattern REFUSE_PATTERN = java.util.regex.Pattern
			.compile("(不换|不行|不能|不要|拒绝|免谈|没兴趣|停止询问|别烦|不考虑|凭什么|想得美|做梦|滚)");
	// 生物台词中的接受成交
	private static final java.util.regex.Pattern ACCEPT_PATTERN = java.util.regex.Pattern
			.compile("(可以|成交|换吧|接受|同意|没问题|一言为定|行[，。！,!.]|好[，。！,!.]|给你)");

	/**
	 * 模型漏输出 barter 字段时的兜底：玩家文本含"A换B"且生物台词表示接受 → 成立约定。
	 * 支持多个支付/回赠物品（如"8个绿宝石加1个蛋糕换铁胸甲"）。
	 * 仅用于玩家真实对话（非系统触发），用户文本以（开头的是系统注入，跳过。
	 */
	private static Barter extractBarterFromText(String userText, String say) {
		if (userText == null || say == null) return null;
		if (userText.startsWith("（") || !userText.contains("换")) return null;
		if (REFUSE_PATTERN.matcher(say).find()) return null;
		if (!ACCEPT_PATTERN.matcher(say).find()) return null;

		int sep = userText.indexOf('换');
		String left = userText.substring(0, sep);                    // 玩家给出的
		String right = userText.substring(sep + 1);                  // 玩家想要的
		List<ItemCatalog.MatchedItem> gives = ItemCatalog.findAllInText(left);
		List<ItemCatalog.MatchedItem> takes = ItemCatalog.findAllInText(right);
		if (gives.isEmpty() || takes.isEmpty()) return null;
		// 简单去重：同一物品在两边都出现则跳过
		boolean overlap = gives.stream().anyMatch(g ->
				takes.stream().anyMatch(t -> t.item() == g.item()));
		if (overlap) return null;
		MobMindMod.LOGGER.info("[MobMind] 文本兜底识别约定: 玩家给 {} 换 {}",
				describe(gives), describe(takes));
		return new Barter(gives, takes);
	}

	private static String describe(List<ItemCatalog.MatchedItem> list) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) sb.append("+");
			ItemCatalog.MatchedItem m = list.get(i);
			sb.append(m.name()).append("×").append(m.count());
		}
		return sb.toString();
	}

	private static int chineseNumber(String s) {
		if (s == null) return 1;
		s = s.trim();
		if (s.isEmpty()) return 1;
		String digits = "零一二三四五六七八九";
		int shi = s.indexOf('十');
		if (shi >= 0) {
			int tens = shi > 0 ? digits.indexOf(s.charAt(0)) : 1;
			int ones = shi < s.length() - 1 ? digits.indexOf(s.charAt(shi + 1)) : 0;
			if (tens > 0) return tens * 10 + Math.max(0, ones);
		}
		int v = digits.indexOf(s.charAt(0));
		return v > 0 ? v : 1;
	}

	// ---------- 离线兜底 ----------

	private static ParsedReply offlineReply(Personality persona, Mob mob, ServerPlayer player, String text) {
		String t = text == null ? "" : text;
		String say;
		String mood = "平静";
		String action = "none";
		int delta = 0;

		if (t.contains("攻击") || t.contains("打你")) {
			say = persona.temper > 50 ? "你竟敢动手？！" : "呜……为什么打我……";
			mood = persona.temper > 50 ? "生气" : "害怕";
			action = persona.temper > 50 ? "attack" : "flee";
			delta = -5;
		} else if (t.contains("别打") || t.contains("和解") || t.contains("朋友") || t.contains("和平")) {
			say = persona.temper > 70 ? "哼，看在态度还行的份上，先放过你。" : "好呀，那我们就是朋友啦！";
			mood = "缓和";
			action = "calm";
			delta = 5;
		} else if (t.contains("跟") || t.contains("走") || t.contains("一起")) {
			say = persona.sociability > 50 ? "好嘞，跟着你走！" : "……那就陪你走一段吧。";
			mood = "开心";
			action = "follow";
			delta = 2;
		} else if (t.contains("待着") || t.contains("停下") || t.contains("别动")) {
			say = "行，我就在这儿待着。";
			action = "stay";
		} else if (t.contains("你好") || t.contains("hi") || t.contains("嗨") || t.contains("（玩家路过")) {
			say = switch (persona.name.length() % 3) {
				case 0 -> "你好呀，" + player.getGameProfile().name() + "！";
				case 1 -> "哟，是你啊。";
				default -> "嗯？找我有什么事吗？";
			};
			mood = "好奇";
			delta = 1;
		} else if (t.contains("吃") || t.contains("食物")) {
			say = "说到吃的，我可就来精神了！";
			mood = "开心";
			delta = 1;
		} else {
			say = switch ((int) (Math.floorMod(mob.getUUID().hashCode(), 4))) {
				case 0 -> "哦？继续说，我听着呢。";
				case 1 -> "嗯嗯，然后呢？";
				case 2 -> "这事儿嘛……让我想想。";
				default -> "哈哈，有意思。";
			};
			delta = 1;
		}
		return new ParsedReply(say, mood, action, delta, null, null);
	}

	private static void sendSystem(ServerPlayer player, String msg) {
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7" + msg));
	}

	private static void sendSystem(ServerPlayer player, net.minecraft.network.chat.Component msg) {
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7").append(msg));
	}
}
