package com.mobmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BehaviorActions;
import com.mobmind.behavior.ShieldBlockGoal;
import com.mobmind.behavior.WeaponAttackGoal;
import com.mobmind.behavior.WeaponRangedAttackGoal;
import com.mobmind.config.MobMindConfig;
import com.mobmind.net.MobPackets;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.Personality;
import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import com.mobmind.util.EnvironmentSense;
import com.mobmind.util.ItemCatalog;
import com.mobmind.util.MobMindExecutor;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 AI 编排：构建提示词、调用 API、解析回复、应用行为、广播结果。
 * 全部 API 调用异步执行并限流，不阻塞游戏线程。
 */
public final class MobAiService {
	/** 对话历史保留多少条（user+assistant 各算一条，即10轮对话） */
	private static final int MEMORY_LIMIT = 20;
	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HURT_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HELP_CRY = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_POTION_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_GREET = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_TAUNT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_GOSSIP = new ConcurrentHashMap<>();

	private MobAiService() {}

	/** 已提示过"离线模式"的玩家（每次进服提示一次） */
	private static final java.util.Set<UUID> OFFLINE_NOTIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// ---------- 入口：玩家说话 ----------

	public static void handleChatMessage(ServerPlayer player, String rawText) {
		if (rawText == null || rawText.isBlank()) return;
		if (rawText.startsWith("/")) return; // 命令不触发
		String text = rawText.trim();
		java.util.UUID playerId = player.getUUID();
		int radius = MobMindConfig.get().interactRadius + 10;
		AABB box = player.getBoundingBox().inflate(radius);
		List<Mob> nearby = player.level().getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && PersonaRegistry.supports(m) && withinTalkRange(m, player));
		if (nearby.isEmpty()) return;
		// 优先对最近的 1-3 只生物回应，防止刷屏
		nearby.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(player)));
		int count = Math.min(3, nearby.size());
		String langHint = isEnglish(text) ? t("请用英文回复。", "Please reply in English.", playerId) : "";
		String heard = isEnglishUi(playerId)
				? "(You hear player " + player.getGameProfile().name() + " say: \"" + text + "\". You are nearby. Respond in character. " + langHint + ")"
				: "（你听到玩家" + player.getGameProfile().name() + "说：\"" + text + "\"，你在附近，按你的性格回应一句。" + langHint + "）";
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

	/** 判断玩家界面语言是否为英文（优先使用客户端同步的语言设置，更准确） */
	private static boolean isEnglishUi(java.util.UUID playerId) {
		return com.mobmind.state.MobMindState.isPlayerEnglish(playerId);
	}

	/** 无玩家参数时的兜底检测（用于无法确定玩家的场景，准确性较低） */
	private static boolean isEnglishUi() {
		try {
			String title = net.minecraft.locale.Language.getInstance().getOrDefault("gui.mobmind.config.title", "");
			return title.endsWith("Settings");
		} catch (Exception e) {
			return false;
		}
	}

	/** 根据玩家界面语言返回中英双语字符串之一 */
	private static String t(String zh, String en, java.util.UUID playerId) {
		return isEnglishUi(playerId) ? en : zh;
	}

	/** 无玩家参数时的兜底（用于无法确定玩家的场景） */
	private static String t(String zh, String en) {
		return isEnglishUi() ? en : zh;
	}

	/** 将中文alignment标签翻译成英文 */
	private static String translateAlignment(String zhAlignment) {
		if (zhAlignment == null) return "";
		if (zhAlignment.contains("善良")) return "This individual was rolled as [Good/Friendly] on first spawn. This result is permanent and must strictly govern how they behave - they are generally friendly and communicative.";
		if (zhAlignment.contains("中立")) return "This individual was rolled as [Neutral/Balanced] on first spawn. This result is permanent - they are pragmatic and judge based on the situation.";
		if (zhAlignment.contains("邪恶")) return "This individual was rolled as [Evil/Hostile] on first spawn. This result is permanent - they are aggressive and hostile towards players.";
		if (zhAlignment.contains("狡诈")) return "This individual was rolled as [Cunning/Deceitful] on first spawn. This result is permanent - they are crafty and may trick or barter shrewdly.";
		if (zhAlignment.contains("暴躁")) return "This individual was rolled as [Short-tempered/Aggressive] on first spawn. This result is permanent - they get angry easily and may attack.";
		return "This individual has a fixed personality alignment that must govern their behavior.";
	}

	/** 获取生物类型的英文名称（用于英文模式显示） */
	public static String getEnglishMobName(Mob mob) {
		var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
		if (key == null) return "Mob";
		String path = key.getPath();
		// copper_golem -> Copper Golem, iron_golem -> Iron Golem, wandering_trader -> Wandering Trader
		String[] words = path.split("_");
		StringBuilder sb = new StringBuilder();
		for (String word : words) {
			if (!word.isEmpty()) {
				if (!sb.isEmpty()) sb.append(" ");
				sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
	}

	/** 获取物品的英文名称（用于英文模式下的商品列表和物品引用） */
	public static String getEnglishItemName(net.minecraft.world.item.ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "empty";
		var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (key == null) return stack.getHoverName().getString();
		String path = key.getPath();
		String[] words = path.split("_");
		StringBuilder sb = new StringBuilder();
		for (String word : words) {
			if (!word.isEmpty()) {
				if (!sb.isEmpty()) sb.append(" ");
				sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
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

		java.util.UUID playerId = player.getUUID();
		String text = rawText.trim();
		// 跟随邀请：按好感度与外向程度掷骰，决定是否答应跟玩家去看看
		if (FOLLOW_INVITE.matcher(text).find()) {
			Personality p = MobMindState.personalityOf(mob);
			int friendship = MobMindState.friendship(mob, playerId);
			int chance = 20 + friendship / 2 + p.sociability / 4;
			if (mob.getRandom().nextInt(100) < chance) {
				text += t("（你内心决定跟他去看看，回复的 action 必须是 follow）",
						"(You decide to follow him and look around; action must be follow)", playerId);
			} else {
				text += t("（你内心不打算跟他去，用符合你性格的方式婉拒，action 为 none）",
						"(You don't plan to follow; politely refuse in your own style; action is none)", playerId);
			}
		}
		// 视觉：玩家展示建筑求点评，扫描四周人工方块生成所见描述
		if (SHOW_INTENT.matcher(text).find()) {
			String seen = EnvironmentSense.scanBuild(player, isEnglishUi(playerId));
			text += isEnglishUi(playerId)
					? "(You look around and see: " + seen + ". Comment on it according to your personality, taste and stance; praise or roast as you like)"
					: "（你环顾四周，看到：" + seen + "。请根据你的性格、审美和立场点评它，可以真诚夸赞也可以毒舌吐槽）";
			if (SHOW_ALL.matcher(rawText).find()) {
				notifyCrowdOpinion(player, mob, seen);
			}
			// 看完热闹就散了，不再继续跟随
			MobMindState.clearOrder(mob);
		}

		// 主动解除跟随：玩家说"别跟着我/不跟了"
		if (UNFOLLOW.matcher(text).find()) {
			MobMindState.Order order = MobMindState.orderFor(mob, mob.level().getLevelData().getGameTime());
			if (order != null && order.type() == MobMindState.OrderType.FOLLOW && order.playerId().equals(playerId)) {
				MobMindState.clearOrder(mob);
				text += t("（玩家让你别再跟着他，你决定停下。回复用你自己的风格说一声，action 为 none）",
						"(The player told you to stop following; you decide to stay. Respond in your own style; action is none)", playerId);
			}
		}

		if (isEnglish(text)) {
			text += t("（请用英文回复）", "(Please reply in English)", playerId);
		}
		respond(player, mob, text, true);
	}

	/** "给大家看"：附近最多 3 只其他生物也凑过来看热闹，各自按性格点评一句 */
	private static void notifyCrowdOpinion(ServerPlayer player, Mob speaker, String seen) {
		java.util.UUID playerId = player.getUUID();
		AABB box = player.getBoundingBox().inflate(16.0);
		List<Mob> crowd = player.level().getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && m != speaker && PersonaRegistry.supports(m) && withinTalkRange(m, player));
		int limit = Math.min(3, crowd.size());
		for (int i = 0; i < limit; i++) {
			respond(player, crowd.get(i), isEnglishUi(playerId)
					? "(Player " + player.getGameProfile().name() + " is showing everyone what they built. You look over and see: " + seen + ". Comment in character)"
					: "（玩家" + player.getGameProfile().name() + "向大家展示他建的东西。你凑过去看到：" + seen + "。按你的性格点评一句）", false);
		}
	}

	/** 跟随邀请话术（中英） */
	private static final java.util.regex.Pattern FOLLOW_INVITE = java.util.regex.Pattern
			.compile("(跟我来|跟我走|跟着我|跟我去|跟我过|一起来|一起走|带你去|带你看|带你去看|过来看看|来看一下|来这边|陪我去|陪我走|陪我看看|follow me|come with me|come here|let's go|go with me)");
	/** 展示作品求点评话术（中英） */
	private static final java.util.regex.Pattern SHOW_INTENT = java.util.regex.Pattern
			.compile("(看我[建盖造搭做]的|看看我[建盖造搭做]的|我[建盖造搭做]了|给大家看看|给大家看|点评一下|评价一下|看看这个|欣赏一下|我的作品|我的建筑|好看吗|漂亮吗|look at what i built|check out my build|rate my build|what do you think of my)");
	/** 向围观群众展示（中英） */
	private static final java.util.regex.Pattern SHOW_ALL = java.util.regex.Pattern
			.compile("(大家|所有人|大伙|各位|everyone|everybody|all of you|guys)");
	/** 主动解除跟随话术（中英） */
	private static final java.util.regex.Pattern UNFOLLOW = java.util.regex.Pattern
			.compile("(别跟|不要跟|不用跟|不跟了|别跟着我|别跟过来|走开|你回去|待着别动|停下|不用你|散了吧|自己去玩|stop following|don't follow|go away|stay here|wait here)");

	// ---------- 入口：生物被打反应 ----------

	public static void onHurtByPlayer(Mob mob, ServerPlayer player) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();

		// 还手逻辑（激怒 + 锁定目标）不受冷却限制，每次被打都执行，保证生物不会傻站着被连击
		MobMindState.adjustFriendship(mob, playerId, -12);
		long gameTime = mob.level().getLevelData().getGameTime();
		MobMindState.clearCalm(mob, playerId); // 动手即撕毁和解
		com.mobmind.persona.PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
		mob.setLastHurtByMob(player);

		if (cat == com.mobmind.persona.PersonalityGenerator.Category.PASSIVE) {
			// 被动生物（村民等）：被打后害怕，逃跑2分钟
			MobMindState.setOrder(mob, MobMindState.OrderType.FLEE, playerId, gameTime + 2400);
			mob.setTarget(null); // 被动生物不设攻击目标
		} else {
			// 能战斗的生物（HOSTILE/NEUTRAL）：激怒5分钟，锁定玩家
			MobMindState.provoke(mob, playerId, gameTime + 6000);
			if (mob instanceof NeutralMob) {
				mob.setTarget(player);
			}
			mob.setTarget(player);
		}

		// AI 对话反应（20秒冷却，避免被连击时刷屏）
		long now = System.currentTimeMillis();
		Long last = LAST_HURT_REACT.get(mob.getUUID());
		if (last != null && now - last < 20000) return;
		LAST_HURT_REACT.put(mob.getUUID(), now);
		respond(player, mob, t(
				"（玩家" + player.getGameProfile().name() + "突然攻击了你，你受伤了但还活着。用符合你性格的方式反应：愤怒、恐惧、或质问）",
				"(Player " + player.getGameProfile().name() + " suddenly attacked you. You are hurt but still alive. React in character: anger, fear, or demand an explanation)",
				playerId), true);
		spreadGossip(player, mob);
	}

	/** 群体关系网络：受害者向附近同族传播流言，同族相信后降低对玩家的好感度、被激怒并可能议论 */
	private static void spreadGossip(ServerPlayer player, Mob victim) {
		java.util.UUID playerId = player.getUUID();
		MobMindConfig cfg = MobMindConfig.get();
		if (!cfg.gossipEnabled) return;
		long nowMs = System.currentTimeMillis();
		Long last = LAST_GOSSIP.get(victim.getUUID());
		if (last != null && nowMs - last < 30000) return; // 30秒冷却
		LAST_GOSSIP.put(victim.getUUID(), nowMs);

		AABB box = victim.getBoundingBox().inflate(cfg.gossipRadius);
		List<Mob> peers = victim.level().getEntitiesOfClass(Mob.class, box,
				m -> m != victim && m.getType() == victim.getType()
						&& PersonaRegistry.supports(m) && m.isAlive());
		if (peers.isEmpty()) return;
		Collections.shuffle(peers);
		int limit = Math.min(3, peers.size());
		String victimName = victim.getType().getDescription().getString();
		long gameTime = victim.level().getLevelData().getGameTime();

		// 同时通知附近的铁傀儡（如果受害者是村民/流浪商人），铁傀儡会守护村民
		String victimId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(victim.getType()).toString();
		boolean isVillagerType = "minecraft:villager".equals(victimId)
				|| "minecraft:wandering_trader".equals(victimId);
		if (isVillagerType) {
			AABB golemBox = victim.getBoundingBox().inflate(cfg.gossipRadius);
			List<Mob> golems = victim.level().getEntitiesOfClass(Mob.class, golemBox,
					g -> g.isAlive() && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
							.getKey(g.getType()).toString().equals("minecraft:iron_golem"));
			for (Mob golem : golems) {
				MobMindState.adjustFriendship(golem, playerId, -cfg.gossipPenalty);
				MobMindState.provoke(golem, playerId, gameTime + 4800); // 激怒4分钟
				golem.setLastHurtByMob(player);
				golem.setTarget(player);
			}
		}

		for (int i = 0; i < limit; i++) {
			Mob peer = peers.get(i);
			if (peer.getRandom().nextInt(100) >= cfg.gossipChance) continue;
			MobMindState.adjustFriendship(peer, playerId, -cfg.gossipPenalty);
			com.mobmind.persona.PersonalityGenerator.Category cat = MobMindState.categoryOf(peer);
			peer.setLastHurtByMob(player);
			if (cat == com.mobmind.persona.PersonalityGenerator.Category.HOSTILE
					|| cat == com.mobmind.persona.PersonalityGenerator.Category.NEUTRAL) {
				// 能战斗的生物：激怒3分钟，能看到玩家则立刻锁定目标
				MobMindState.provoke(peer, playerId, gameTime + 3600);
				// NeutralMob 需要设置原生愤怒状态以保证追击
				if (peer instanceof NeutralMob neutral) {
					neutral.setTarget(player);
					// 调用NeutralMob原生方法设置愤怒（不同版本方法名可能不同，直接用setTarget+setLastHurtByMob覆盖）
					peer.setLastHurtByMob(player);
				}
				if (peer.getSensing().hasLineOfSight(player)) {
					peer.setTarget(player);
				}
			} else if (cat == com.mobmind.persona.PersonalityGenerator.Category.PASSIVE) {
				// 被动生物（村民等）：听到同族被打，害怕并逃跑1分钟
				MobMindState.setOrder(peer, MobMindState.OrderType.FLEE, playerId, gameTime + 1200);
				peer.setLastHurtByMob(player);
				// 村民看到玩家会逃跑
				if (peer.getSensing().hasLineOfSight(player)) {
					peer.setTarget(null); // 被动生物不设攻击目标
				}
			}
			MobMindMod.LOGGER.info("[MobMind] 流言传播: {} 听说玩家 {} 打了 {}, 好感度-{}",
					peer.getType().getDescription().getString(), player.getGameProfile().name(), victimName, cfg.gossipPenalty);
			if (peer.getRandom().nextInt(100) < cfg.gossipReactChance) {
				boolean killed = !victim.isAlive();
				String prompt;
				if (isEnglishUi(playerId)) {
					if (killed) {
						prompt = "(A player named " + player.getGameProfile().name() + " just KILLED another " + victimName
								+ " — a creature of your same species, NOT a human like the player. You are horrified and furious. Your opinion of this player drops sharply. Cry out in grief and anger to nearby peers of your kind)";
					} else {
						prompt = "(A player named " + player.getGameProfile().name() + " is attacking another " + victimName
								+ " — a creature of your same species, NOT a human like the player. " + victimName + " is hurt but still alive. Your opinion of this player worsens. Warn your peers of your kind or shout at the player in character)";
					}
				} else {
					if (killed) {
						prompt = "（一个叫" + player.getGameProfile().name() + "的人类玩家刚刚杀了另一只" + victimName
								+ "——它和你一样是" + victimName + "，而玩家是人类，不是你们的同类。你既震惊又愤怒，对这个玩家的好感大幅下降。向旁边的同伴哭喊怒骂）";
					} else {
						prompt = "（一个叫" + player.getGameProfile().name() + "的人类玩家正在攻击另一只" + victimName
								+ "——它和你一样是" + victimName + "，而玩家是人类，不是你们的同类。" + victimName + "受伤了但还活着。你对这个玩家的印象变差了。向旁边的同伴喊话警告，或者冲着玩家叫骂）";
					}
				}
				respond(player, peer, prompt, false);
			}
		}
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

		java.util.UUID bestPlayerId = bestSp.getUUID();
		String attackerName = attacker instanceof net.minecraft.world.entity.player.Player
				? attacker.getDisplayName().getString()
				: attacker.getType().getDescription().getString();
		respond(bestSp, mob, isEnglishUi(bestPlayerId)
				? "(You are being attacked by " + attackerName + ", the situation is dire! Cry for help to " + bestSp.getGameProfile().name() + " and ask him to come protect you quickly. You can be panicked, angry or defiant, but make the call for help clear)"
				: "（你正被" + attackerName + "攻击，情况危急！向" + bestSp.getGameProfile().name() + "求救，请求他快来保护你。你可以惊慌、愤怒或逞强，但要明确呼救）", false);
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

		java.util.UUID bestPlayerId = bestSp.getUUID();
		String attackerName = attacker.getType().getDescription().getString();
		respond(bestSp, villager, isEnglishUi(bestPlayerId)
				? "(" + attackerName + " is chasing you; you are running fast but it's still behind! Cry for help to " + bestSp.getGameProfile().name() + " and ask him to come protect you)"
				: "（" + attackerName + "正在追你，你跑得很快但它还在后面！向" + bestSp.getGameProfile().name() + "求救，让他快来保护你）", false);
	}

	// ---------- 入口：被玩家施加药水效果 ----------

	public static void onPotionAffected(Mob mob, ServerPlayer player, net.minecraft.world.effect.MobEffectInstance effect) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_POTION_REACT.get(mob.getUUID());
		if (last != null && now - last < 20000) return; // 20秒冷却
		LAST_POTION_REACT.put(mob.getUUID(), now);

		String name = effect.getEffect().value().getDisplayName().getString();
		boolean harmful = !effect.getEffect().value().isBeneficial();
		String desc = harmful
				? (isEnglishUi(playerId)
					? "(Player " + player.getGameProfile().name() + " applied " + name + " to you; you feel awful. Complain or get angry in character)"
					: "（玩家" + player.getGameProfile().name() + "向你施加了" + name + "，你感觉很不好，用符合性格的方式抱怨或发怒）")
				: (isEnglishUi(playerId)
					? "(Player " + player.getGameProfile().name() + " applied " + name + " to you; you may feel curious, comfortable or wary. Respond in character)"
					: "（玩家" + player.getGameProfile().name() + "向你施加了" + name + "，你可能会感到好奇、舒服或警惕，用符合性格的方式回应）");
		respond(player, mob, desc, false);
	}

	// ---------- 入口：铜傀儡被玩家除锈/上蜡 ----------

	private static final Map<UUID, Long> LAST_COPPER_MAINTAIN = new ConcurrentHashMap<>();

	public static void onCopperGolemMaintained(Mob mob, ServerPlayer player, boolean waxed) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_COPPER_MAINTAIN.get(mob.getUUID());
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_COPPER_MAINTAIN.put(mob.getUUID(), now);

		String action = waxed
				? t("用蜂蜡保护你不再氧化", "protected you with wax from oxidizing", playerId)
				: t("帮你除锈", "removed your rust", playerId);
		MobMindState.adjustFriendship(mob, playerId, 8);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " just " + action + ". You feel much better. Express thanks in short mechanical phrases; you may mention tasks, buttons or rust)"
				: "（玩家" + player.getGameProfile().name() + "刚刚" + action + "，你感到很受用。用机械短句风格表达感谢，可以提到任务、按钮或铜锈）", false);
	}

	// ---------- 入口：铁傀儡被玩家用铁锭修复 ----------

	private static final Map<UUID, Long> LAST_IRON_GOLEM_REPAIR = new ConcurrentHashMap<>();

	public static void onIronGolemRepaired(Mob mob, ServerPlayer player, float healed) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_IRON_GOLEM_REPAIR.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却（连续修复时只说一次）
		LAST_IRON_GOLEM_REPAIR.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 6);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " just repaired you with an iron ingot, restoring " + (int)healed + " health. You are a sturdy guardian. Express deep gratitude in your deep, rumbling voice; you may offer a poppy as thanks, or reaffirm your duty to protect the village)"
				: "（玩家" + player.getGameProfile().name() + "用铁锭修复了你，恢复了" + (int)healed + "点生命值。你是一个坚实的守护者。用低沉浑厚的声音表达诚挚的感谢，可以赠送虞美人花表示谢意，或重申守护村庄的职责）", false);
	}

	// ---------- 入口：玩家挡路，生物叫玩家让开 ----------

	private static final Map<UUID, Long> LAST_MOVE_PLEASE = new ConcurrentHashMap<>();

	public static void onPlayerBlockingPath(Mob mob, ServerPlayer player) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_MOVE_PLEASE.get(mob.getUUID());
		if (last != null && now - last < 15000) return; // 15秒冷却
		LAST_MOVE_PLEASE.put(mob.getUUID(), now);

		String relation;
		int f = MobMindState.friendship(mob, playerId);
		if (f < 20) relation = t("你对这个玩家没什么好感", "you don't like this player much", playerId);
		else if (f < 40) relation = t("你不太认识这个玩家", "you don't know this player well", playerId);
		else if (f < 60) relation = t("你和这个玩家认识", "you know this player", playerId);
		else if (f < 80) relation = t("你和这个玩家是朋友", "you are friends with this player", playerId);
		else relation = t("你和这个玩家是挚友", "you are best friends with this player", playerId);

		respond(player, mob, isEnglishUi(playerId)
				? "(You are trying to go somewhere but " + player.getGameProfile().name() + " is blocking your path. " + relation + ". Ask them to move out of the way in character—be polite if friendly, gruff if hostile, or just grunt and gesture depending on your personality. Keep it short.)"
				: "（你正想去某个地方，但玩家" + player.getGameProfile().name() + "挡在你前面。" + relation + "。用符合你性格的方式叫他让开——友好的客气点说，敌对的凶一点，或者根据性格嘟囔、比划一下。语气简短。）", false);
	}

	// ---------- 入口：生物卡住/掉坑里/走投无路，向熟悉的玩家求救 ----------

	private static final Map<UUID, Long> LAST_STUCK_CRY = new ConcurrentHashMap<>();

	/**
	 * 生物被卡住/掉坑/被困时向附近最熟悉的玩家求救。
	 * @param stuckType 0=卡住不动, 1=掉坑里, 2=撞墙/被阻挡
	 */
	public static void onStuck(Mob mob, int stuckType) {
		if (!PersonaRegistry.supports(mob)) return;
		Level level = mob.level();
		if (level.isClientSide()) return;

		long gameTime = level.getLevelData().getGameTime();
		Long last = LAST_STUCK_CRY.get(mob.getUUID());
		if (last != null && gameTime - last < 4000) return; // 3分多钟冷却

		// 战斗中不喊卡住（被追/打人时没空管卡住）
		if (mob.getTarget() != null) return;

		// 找附近熟悉的玩家求救（好感度≥25，距离48格内）
		ServerPlayer bestSp = null;
		int bestFriendship = -1;
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (p.level() != level || !p.isAlive()) continue;
			if (p.distanceTo(mob) > 48) continue;
			int f = MobMindState.friendship(mob, p.getUUID());
			if (f > bestFriendship && f >= 25) {
				bestFriendship = f;
				bestSp = p;
			}
		}
		if (bestSp == null) return;
		LAST_STUCK_CRY.put(mob.getUUID(), gameTime);

		java.util.UUID bestPlayerId = bestSp.getUUID();
		String situation = switch (stuckType) {
			case 1 -> isEnglishUi(bestPlayerId)
					? "You fell into a deep hole and can't climb out! You're trapped and need help."
					: "你掉进了一个深坑，爬不出来了！你被困住了，需要帮助。";
			case 2 -> isEnglishUi(bestPlayerId)
					? "You keep bumping into walls/blocks and can't find a way forward."
					: "你一直在撞墙/方块，找不到前进的路。";
			default -> isEnglishUi(bestPlayerId)
					? "You're stuck and can't move, no matter how hard you try!"
					: "你被卡住了，怎么动都动不了！";
		};

		respond(bestSp, mob, isEnglishUi(bestPlayerId)
				? "(" + situation + " Call out to " + bestSp.getGameProfile().name() + " for help—describe your predicament and ask them to come get you out. Sound distressed or annoyed depending on your personality.)"
				: "（" + situation + " 向" + bestSp.getGameProfile().name() + "求救——描述你的困境，叫他来救你出去。根据你的性格表现出着急或不爽。）", false);
	}

	// ---------- 入口：生物主动给玩家送礼物 ----------

	private static final Map<UUID, Long> LAST_SPONTANEOUS_GIFT = new ConcurrentHashMap<>();

	/** 生物主动给附近熟悉的玩家丢一个礼物（好感度≥55才有概率） */
	public static void onSpontaneousGift(Mob mob, ServerPlayer player, String giftName) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long gameTime = mob.level().getLevelData().getGameTime();
		Long last = LAST_SPONTANEOUS_GIFT.get(mob.getUUID());
		if (last != null && gameTime - last < 12000) return; // 10分钟冷却（同一生物不频繁送礼）
		LAST_SPONTANEOUS_GIFT.put(mob.getUUID(), gameTime);

		int f = MobMindState.friendship(mob, playerId);
		String relation;
		if (f >= 80) relation = isEnglishUi(playerId)
				? "You are best friends with " + player.getGameProfile().name() + "."
				: "你和" + player.getGameProfile().name() + "是挚友。";
		else if (f >= 60) relation = isEnglishUi(playerId)
				? "You are good friends with " + player.getGameProfile().name() + "."
				: "你和" + player.getGameProfile().name() + "是好朋友。";
		else relation = isEnglishUi(playerId)
				? "You know " + player.getGameProfile().name() + " and feel friendly towards them."
				: "你认识" + player.getGameProfile().name() + "，对他有好感。";

		respond(player, mob, isEnglishUi(playerId)
				? "(You just found/dug up/had a " + giftName + " and decided to give it to " + player.getGameProfile().name() + " as a spontaneous gift. " + relation + " Offer the gift warmly in character—say something nice or casual as you drop it for them. Keep it short.)"
				: "（你刚找到/挖到/身上带着一个" + giftName + "，决定主动送给" + player.getGameProfile().name() + "作为礼物。" + relation + "用符合你性格的方式热情地送上礼物——在丢给他的时候说句好听的或随意的话。语气简短。）", false);
	}

	// ---------- 入口：玩家送盔甲 ----------

	private static final Map<UUID, Long> LAST_ARMOR_REACT = new ConcurrentHashMap<>();

	public static void onArmorGiven(Mob mob, ServerPlayer player, String itemName,
									net.minecraft.world.entity.EquipmentSlot slot) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_ARMOR_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_ARMOR_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 12);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you a " + itemName + " and you have put it on. Respond to the gift in character)"
				: "（玩家" + player.getGameProfile().name() + "送了你一件" + itemName + "，你已经穿上了。用符合你性格的方式回应这份礼物）", false);
	}

	// ---------- 入口：玩家送武器 ----------

	private static final Map<UUID, Long> LAST_WEAPON_REACT = new ConcurrentHashMap<>();

	/** 玩家右键/丢武器给生物：生物装备武器到主手，触发 AI 感谢反应 */
	public static void onWeaponGiven(Mob mob, ServerPlayer player, String weaponName) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_WEAPON_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_WEAPON_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 12);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you a " + weaponName + " and you are now holding it. You can use it to attack nearby enemies. Respond to the gift in character)"
				: "（玩家" + player.getGameProfile().name() + "送了你一把" + weaponName + "，你已经拿在手里了。你可以用它攻击附近的敌人。用符合你性格的方式回应这份礼物）", false);
	}

	// ---------- 入口：玩家送盾牌 ----------

	private static final Map<UUID, Long> LAST_SHIELD_REACT = new ConcurrentHashMap<>();

	/** 玩家右键/丢盾牌给生物：生物装备盾牌到副手，触发 AI 感谢反应 */
	public static void onShieldGiven(Mob mob, ServerPlayer player, String shieldName) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_SHIELD_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_SHIELD_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 12);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you a " + shieldName + " and you are now holding it in your off hand. You can use it to block incoming attacks. Respond to the gift in character)"
				: "（玩家" + player.getGameProfile().name() + "送了你一面" + shieldName + "，你已经拿在副手了。你可以用它格挡即将到来的攻击。用符合你性格的方式回应这份礼物）", false);
	}

	// ---------- 入口：玩家送箭 ----------

	private static final Map<UUID, Long> LAST_AMMO_REACT = new ConcurrentHashMap<>();

	/** 玩家右键/丢箭给生物：增加远程武器弹药，触发 AI 感谢反应 */
	public static void onAmmoGiven(Mob mob, ServerPlayer player, String ammoName, int count) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_AMMO_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_AMMO_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 4);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you " + count + " " + ammoName + ". You can use them as ammunition for your bow or crossbow. Respond to the gift in character)"
				: "（玩家" + player.getGameProfile().name() + "送了你" + count + "支" + ammoName + "，你可以用它们作为弓或弩的弹药。用符合你性格的方式回应这份礼物）", false);
	}

	// ---------- 入口：玩家赠送不死图腾 ----------

	private static final Map<UUID, Long> LAST_TOTEM_REACT = new ConcurrentHashMap<>();

	public static void onTotemGiven(Mob mob, ServerPlayer player, String totemName, int count) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_TOTEM_REACT.get(mob.getUUID());
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_TOTEM_REACT.put(mob.getUUID(), now);

		MobMindState.adjustFriendship(mob, playerId, 10);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you " + count + " " + totemName + ". This Totem of Undying will save you from death once. Thank them in character.)"
				: "（玩家" + player.getGameProfile().name() + "送了你" + count + "个" + totemName + "，这个不死图腾会在你濒死时救你一命。用符合你性格的方式感谢。）", false);
	}

	// ---------- 入口：僵尸村民被玩家救治 ----------

	private static final Map<UUID, Long> LAST_CURE_REACT = new ConcurrentHashMap<>();

	public static void onZombieVillagerCureStarted(net.minecraft.world.entity.monster.zombie.ZombieVillager zv,
											   ServerPlayer player) {
		if (!PersonaRegistry.supports(zv)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_CURE_REACT.get(zv.getUUID());
		if (last != null && now - last < 120000) return; // 2分钟冷却
		LAST_CURE_REACT.put(zv.getUUID(), now);

		long gameTime = zv.level().getLevelData().getGameTime();
		// 标记治疗中：持续约 5 分钟（覆盖完整转化时间），并随机决定忠诚倾向
		MobMindState.markCuringZombieVillager(zv, playerId, gameTime + 6000);
		MobMindState.adjustFriendship(zv, playerId, 25);
		respond(player, zv, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " is curing you with Weakness potion and a golden apple; you will soon turn back into a normal villager. Say something grateful, as if you have survived a disaster)"
				: "（玩家" + player.getGameProfile().name() + "正在用虚弱药水和金苹果救你，你很快就能变回普通村民。用劫后余生的感激语气对他说点什么）", false);
	}

	// ---------- 入口：玩家喂食物 ----------

	private static final Map<UUID, Long> LAST_FOOD_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_FOOD_REQUEST = new ConcurrentHashMap<>();

	public static void onFoodFed(Mob mob, ServerPlayer player, String foodName, float healed) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_FOOD_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_FOOD_REACT.put(mob.getUUID(), now);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " fed you " + foodName + ". You feel much better. Respond in character)"
				: "（玩家" + player.getGameProfile().name() + "喂你吃了" + foodName + "，你感觉好多了。用符合你性格的方式回应）", false);
	}

	/** 玩家满血时丢食物给生物，生物存起来 */
	public static void onFoodStored(Mob mob, ServerPlayer player, String foodName, int count) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_FOOD_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return;
		LAST_FOOD_REACT.put(mob.getUUID(), now);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you " + count + " " + foodName + " for later. You'll save it for when you're hurt. Respond in character, briefly thanking them)"
				: "（玩家" + player.getGameProfile().name() + "给了你" + count + "个" + foodName + "存着。你打算留着受伤的时候吃。用符合你性格的方式简短道谢）", false);
	}

	/** 服务端 tick：低血量的友好生物自动吃存储的食物 */
	private static final Map<UUID, Long> LAST_AUTO_EAT = new ConcurrentHashMap<>();

	public static void tickAutoEatFood(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			java.util.Set<UUID> seen = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
			for (ServerPlayer player : level.players()) {
				AABB box = player.getBoundingBox().inflate(64.0);
				for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
					if (!seen.add(mob.getUUID())) continue;
					if (!mob.isAlive()) continue;
					if (!PersonaRegistry.supports(mob)) continue;
					if (mob.getHealth() >= mob.getMaxHealth() * 0.7f) continue; // 血量>70%不吃
					if (MobMindState.getStoredFoodCount(mob) <= 0) continue;

					long now = level.getLevelData().getGameTime();
					Long last = LAST_AUTO_EAT.get(mob.getUUID());
					if (last != null && now - last < 60) continue; // 3秒冷却
					LAST_AUTO_EAT.put(mob.getUUID(), now);

					if (MobMindState.consumeStoredFood(mob)) {
						mob.heal(4.0f); // 每份食物回4点血（统一值，不区分食物类型）
						mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
								net.minecraft.sounds.SoundEvents.GENERIC_EAT,
								net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
						MobMindMod.LOGGER.info("[MobMind] 自动吃食物: {} HP={}/{}",
								mob.getType().getDescription().getString(),
								String.format("%.0f", mob.getHealth()),
								String.format("%.0f", mob.getMaxHealth()));
					}
				}
			}
		}
	}

	// ---------- 入口：玩家扔礼物给生物 ----------

	private static final Map<UUID, Long> LAST_GIFT_REACT = new ConcurrentHashMap<>();

	public static void onGiftReceived(Mob mob, ServerPlayer player, String itemName, int count) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_GIFT_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_GIFT_REACT.put(mob.getUUID(), now);
		respond(player, mob, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " gave you " + count + " " + itemName + "(s). You accepted it. Express thanks or happiness in character)"
				: "（玩家" + player.getGameProfile().name() + "送给你" + count + "个" + itemName + "，你收下了。用符合你性格的方式表达感谢或开心）", false);
	}

	private static final Map<UUID, Long> LAST_TNT_PLEA = new ConcurrentHashMap<>();

	/** 村民发现家里有 TNT，请求玩家拆除 */
	public static void sendScaredTntPlea(Villager villager, ServerPlayer player, BlockPos tntPos) {
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		UUID vid = villager.getUUID();
		Long last = LAST_TNT_PLEA.get(vid);
		if (last != null && now - last < 10000) return;
		LAST_TNT_PLEA.put(vid, now);
		respond(player, villager, isEnglishUi(playerId)
				? "(There is TNT in your house at " + tntPos.getX() + ", " + tntPos.getY() + ", " + tntPos.getZ() + "! You are terrified. Beg " + player.getGameProfile().name() + " to remove it quickly, or you cannot live here safely)"
				: "（你家" + tntPos.getX() + "," + tntPos.getY() + "," + tntPos.getZ() + "位置放着TNT！你很害怕，恳求" + player.getGameProfile().name() + "快拆掉它，不然你没法安心住在这里）", false);
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
		respond(player, piglin, isEnglishUi(playerId)
				? "(You just accepted a gold ingot from player " + player.getGameProfile().name() + " and gave back the traded item. Respond to this trade in character)"
				: "（你刚刚收下玩家" + player.getGameProfile().name() + "的金锭，并把交易得到的物品回赠给他。用符合你性格的方式回应这次交易）", false);
	}

	// ---------- 入口：猪灵因玩家挖金块/开宝箱发怒 ----------

	private static final Map<UUID, Long> LAST_PIGLIN_LOOT_ANGER = new ConcurrentHashMap<>();

	public static void onPiglinAngeredByLooting(net.minecraft.world.entity.monster.piglin.Piglin piglin, ServerPlayer player) {
		if (!PersonaRegistry.supports(piglin)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_PIGLIN_LOOT_ANGER.get(piglin.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_PIGLIN_LOOT_ANGER.put(piglin.getUUID(), now);

		MobMindState.adjustFriendship(piglin, playerId, -8);
		long gameTime = piglin.level().getLevelData().getGameTime();
		MobMindState.provoke(piglin, playerId, gameTime + 6000); // 激怒5分钟
		respond(player, piglin, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " is mining your gold blocks or rummaging through your chests! You are furious. Shout, threaten or roar in character)"
				: "（玩家" + player.getGameProfile().name() + "正在挖你们的金块或者翻你们的宝箱！你被激怒了。用符合你性格的方式呵斥、威胁或怒吼）", false);
	}

	// ---------- 入口：玩家破坏末地水晶，末影龙发怒 ----------

	private static final Map<UUID, Long> LAST_CRYSTAL_REACT = new ConcurrentHashMap<>();

	public static void onEndCrystalAttacked(net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon, ServerPlayer player) {
		if (!PersonaRegistry.supports(dragon)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_CRYSTAL_REACT.get(dragon.getUUID());
		if (last != null && now - last < 15000) return; // 15秒冷却
		LAST_CRYSTAL_REACT.put(dragon.getUUID(), now);

		MobMindState.adjustFriendship(dragon, playerId, -10);
		long gameTime = dragon.level().getLevelData().getGameTime();
		MobMindState.provoke(dragon, playerId, gameTime + 6000); // 激怒5分钟
		respond(player, dragon, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " is destroying the End Crystal that heals you! You feel rage and agony. Roar or threaten him to stop in character)"
				: "（玩家" + player.getGameProfile().name() + "正在破坏给你回血的末地水晶！你感到愤怒和痛苦，用符合你性格的方式怒吼或威胁他停下）", false);
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
		java.util.UUID nearestId = nearest.getUUID();
		respond(nearest, dragon, isEnglishUi(nearestId)
				? "(You have just been resurrected by an End Crystal and returned to the End. Player " + nearest.getGameProfile().name() + " is nearby. Announce your return or warn the player in character)"
				: "（你刚刚被末地水晶复活，重新降临末地。玩家" + nearest.getGameProfile().name() + "就在附近。用符合你性格的方式宣告你的归来或警告玩家）", false);
	}

	// ---------- 入口：末影人被箭射中后瞬移，嘲讽射箭玩家 ----------

	private static final Map<UUID, Long> LAST_ENDERMAN_ARROW_TELEPORT = new ConcurrentHashMap<>();

	public static void onEndermanHitByArrowTeleport(Mob enderman, ServerPlayer archer) {
		if (!PersonaRegistry.supports(enderman)) return;
		java.util.UUID playerId = archer.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_ENDERMAN_ARROW_TELEPORT.get(enderman.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_ENDERMAN_ARROW_TELEPORT.put(enderman.getUUID(), now);

		respond(archer, enderman, isEnglishUi(playerId)
				? "(You were struck by an arrow shot by " + archer.getGameProfile().name() + " and teleported away to dodge. You find this annoying and amusing. Taunt the player in your disjointed, echoing voice—mock their aim, boast about your teleportation, or threaten them telepathically. Keep it short and eerie.)"
				: "（你被玩家" + archer.getGameProfile().name() + "射出的箭击中，瞬移躲开了。你觉得这既烦人又可笑。用你断续、回声般的声音嘲讽玩家——嘲笑他们的准头，吹嘘你的瞬移能力，或者用心灵感应威胁他们。语气简短而诡异。）", false);
	}

	// ---------- 入口：朋友关系且低血量时主动向玩家要食物 ----------

	public static void tryFoodRequest(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive()) continue;
			java.util.UUID playerId = player.getUUID();
			Level level = player.level();
			if (level.isClientSide()) continue;
			long gameTime = level.getLevelData().getGameTime();
			AABB box = player.getBoundingBox().inflate(16.0);
			// 血量阈值从40%放宽到70%：掉了三分之一血就开始要食物，用户能感知到
			// 只在存储食物吃完后才找玩家要
			List<Mob> hungry = level.getEntitiesOfClass(Mob.class, box, m ->
					m.isAlive()
							&& PersonaRegistry.supports(m)
							&& MobMindState.isFriendlyTo(m, playerId)
							&& m.hasLineOfSight(player)
							&& m.getHealth() < m.getMaxHealth() * 0.7f
							&& MobMindState.getStoredFoodCount(m) <= 0
							&& !isInCombat(m));
			if (hungry.isEmpty()) continue;
			// 选血量比例最低的一只
			hungry.sort(java.util.Comparator.comparingDouble(
					m -> m.getHealth() / m.getMaxHealth()));
			Mob mob = hungry.get(0);
			Long last = LAST_FOOD_REQUEST.get(mob.getUUID());
			if (last != null && gameTime - last < 2400) continue; // 2分钟冷却
			LAST_FOOD_REQUEST.put(mob.getUUID(), gameTime);
			float hpPct = mob.getHealth() / mob.getMaxHealth();
			MobMindMod.LOGGER.info("[MobMind] 求食物: {} HP={}/{}({}%) 玩家={}",
					mob.getType().getDescription().getString(),
					String.format("%.0f", mob.getHealth()),
					String.format("%.0f", mob.getMaxHealth()),
					String.format("%.0f%%", hpPct * 100),
					player.getGameProfile().name());
			respond(player, mob, isEnglishUi(playerId)
					? "(You are injured and low on health (" + String.format("%.0f%%", hpPct * 100) + "). You really hope player " + player.getGameProfile().name() + " can give you some food to heal. Act cute, complain about your wounds, or ask directly in character for something to eat.)"
					: "（你受伤了，血量只剩" + String.format("%.0f%%", hpPct * 100) + "，你真心希望玩家" + player.getGameProfile().name() + "能给你点吃的补补血。用符合你性格的方式撒个娇、抱怨伤势、或者直接向玩家要吃的）", false);
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
		java.util.UUID playerId = player.getUUID();
		Level level = attacker.level();
		long gameTime = level.getLevelData().getGameTime();
		Long last = LAST_MOB_CONFLICT.get(attacker.getUUID());
		if (last != null && gameTime - last < 6000) return; // 5分钟冷却
		LAST_MOB_CONFLICT.put(attacker.getUUID(), gameTime);

		// 找附近对玩家友好、且不是当前攻击者的生物
		AABB box = attacker.getBoundingBox().inflate(16.0);
		List<Mob> allies = level.getEntitiesOfClass(Mob.class, box,
				m -> m != attacker && m.isAlive() && PersonaRegistry.supports(m)
						&& MobMindState.isFriendlyTo(m, playerId));
		if (allies.isEmpty()) return;

		String attackerName = attacker.getType().getDescription().getString();
		String playerName = player.getGameProfile().name();
		int limit = Math.min(3, allies.size());
		for (int i = 0; i < limit; i++) {
			Mob ally = allies.get(i);
			Personality p = MobMindState.personalityOf(ally);
			respond(player, ally, isEnglishUi(playerId)
					? "(You see " + attackerName + " attacking " + playerName + " and you have a good impression of this player. Shout at, stop or mock the attacker in character)"
					: "（你看见" + attackerName + "正在攻击" + playerName + "，而你对这个玩家印象不错。用符合你性格的方式呵斥、阻止或嘲笑攻击者）", false);
			// 持武器/盾牌的友方必出手；其他友方按暴躁程度概率出手
			boolean willFight = WeaponAttackGoal.isHoldingMeleeWeapon(ally)
					|| WeaponRangedAttackGoal.isHoldingRangedWeapon(ally)
					|| ShieldBlockGoal.isHoldingShield(ally)
					|| ally.getRandom().nextInt(100) < p.temper;
			if (willFight) {
				ally.setTarget(attacker);
			}
		}
	}

	// ---------- 入口：随机打招呼 ----------

	public static void tryRandomGreeting(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			java.util.UUID playerId = player.getUUID();
			AABB box = player.getBoundingBox().inflate(8.0);
			List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m) && m.hasLineOfSight(player));
			if (mobs.isEmpty()) continue;
			Mob mob = mobs.get(player.getRandom().nextInt(mobs.size()));

			long gameTime = mob.level().getLevelData().getGameTime();
			Long last = LAST_GREET.get(mob.getUUID());
			if (last != null && gameTime - last < 12000) continue; // 10分钟冷却

			Personality p = MobMindState.personalityOf(mob);
			int friendship = MobMindState.friendship(mob, playerId);
			// 社交倾向越高、好感越高越容易主动搭话
			if (friendship < 25) continue;
			if (player.getRandom().nextInt(100) >= p.sociability / 2) continue;

			LAST_GREET.put(mob.getUUID(), gameTime);
			respond(player, mob, t("（玩家路过你身边，请主动打个招呼）", "(A player is passing by. Greet them proactively)", playerId), false);
			return; // 每轮最多一只生物搭话
		}
	}

	// ---------- 入口：10%敌对生物嘲讽创造模式玩家 ----------

	/** 附近创造模式玩家会被"求战型"怪物嘲讽/激将换生存模式。返回是否触发了一只 */
	public static boolean tryCreativeTaunt(MinecraftServer server) {
		if (!MobMindConfig.get().creativeTauntEnabled) return false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isCreative()) continue;
			java.util.UUID playerId = player.getUUID();
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m) && m.hasLineOfSight(player));
			long gameTime = player.level().getLevelData().getGameTime();
			for (Mob mob : mobs) {
				Personality p = MobMindState.personalityOf(mob);
				if (!Boolean.TRUE.equals(p.creativeTaunt)) continue;
				if (MobMindState.isFriendlyTo(mob, playerId)) continue; // 朋友不嘲讽
				if (isPiglin(mob) && !isPiglinBrute(mob) && hasAnyGoldArmor(player)) continue; // 普通猪灵对穿金甲玩家保持中立
				Long last = LAST_TAUNT.get(mob.getUUID());
				if (last != null && gameTime - last < 3600) continue; // 3分钟冷却
				LAST_TAUNT.put(mob.getUUID(), gameTime);
				respond(player, mob, t("（你发现这个玩家开着创造模式，你根本伤不到他。用你自己的风格激他、嘲讽他，让他换成生存模式和你真正打一场）",
					"(You notice this player is in Creative mode and you cannot hurt them. Taunt and provoke them in your own style, daring them to switch to Survival and fight you for real)", playerId), false);
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
		java.util.UUID playerId = player.getUUID();
		respond(player, mob, isEnglishUi(playerId)
				? "(The player threw " + giveDesc + " to you as agreed, and you handed " + takeDesc + " to the player. The trade is complete. Respond in your own style)"
				: "（玩家按约定把 " + giveDesc + " 扔给了你，你把 " + takeDesc + " 交给了玩家。交易完成，用你自己的风格回应一句）", false);
	}

	public static void notifyBarterDealMade(ServerPlayer player, Mob mob, String giveDesc, String takeDesc) {
		java.util.UUID playerId = player.getUUID();
		respond(player, mob, isEnglishUi(playerId)
				? "(You and the player agreed: they give you " + giveDesc + ", and you give back " + takeDesc + ". Now wait for the player to throw the agreed items to you; do not change the deal. Just confirm with one line)"
				: "（你和玩家约定：他给你 " + giveDesc + "，你回赠 " + takeDesc + "。现在等待玩家把约定物品扔给你，不要改口。回复一句确认即可）", false);
	}

	// ---------- 入口：村民护床 ----------

	/** 玩家睡了村民的床：村民喝止（会赶人的放狠话，不赶的抱怨） */
	public static void scoldBedThief(ServerPlayer player, Mob villager, boolean willKick) {
		java.util.UUID playerId = player.getUUID();
		respond(player, villager, willKick
				? t("（这个玩家占了你的床睡觉！用你自己的风格喝止他，让他立刻滚下床，警告他再不起来你就直接掀他下去）",
					"(This player is sleeping in your bed! Stop them in your own style; tell them to get out right now, or warn them you'll throw them out)", playerId)
				: t("（这个玩家占了你的床睡觉。用你自己的风格抱怨两句，让他知道你很不爽，但你今晚懒得跟他计较）",
					"(This player is sleeping in your bed. Complain a bit in your own style; let them know you're annoyed, but you can't be bothered tonight)", playerId), false);
	}

	/** 裁决结果：赶下床后放狠话，或忍了嘟囔一句 */
	public static void bedKickResolved(ServerPlayer player, Mob villager, boolean kicked) {
		java.util.UUID playerId = player.getUUID();
		respond(player, villager, kicked
				? t("（他赖着不走，你直接把他掀下了床，自己躺回去。用你的风格撂一句狠话）",
					"(He refused to get up, so you threw him out of the bed and lay back down. Drop a tough line in your style)", playerId)
				: t("（他还是没起床，你忍了，自己另外找地方将就一晚。用你的风格嘟囔一句）",
					"(He still didn't get up, so you endured it and found somewhere else to spend the night. Mutter something in your style)", playerId), false);
	}

	/** 玩家在以物易物中欺骗生物：给错物品/给错药水类型，生物发怒攻击 */
	public static void onPlayerCheatedBarter(Mob mob, ServerPlayer player, String expectedDesc, String actualDesc) {
		java.util.UUID playerId = player.getUUID();
		MobMindState.adjustFriendship(mob, playerId, -25);
		long gameTime = mob.level().getLevelData().getGameTime();
		MobMindState.provoke(mob, playerId, gameTime + 12000); // 激怒10分钟
		MobMindState.clearOrder(mob);
		MobMindState.clearBarterDeal(mob.getUUID());
		MobMindState.clearGiftPromise(mob.getUUID());
		respond(player, mob, isEnglishUi(playerId)
				? "(You agreed to trade with player " + player.getGameProfile().name() + ". You expected " + expectedDesc + " but they gave you " + actualDesc + " instead! They tried to CHEAT you! You are furious, enraged. Roar in anger, threaten them, and attack them immediately)"
				: "（你和玩家" + player.getGameProfile().name() + "约定好以物易物，你要的是" + expectedDesc + "，结果他扔给你的是" + actualDesc + "！他竟然敢骗你！你怒不可遏，破口大骂，立刻攻击他）", false);
		if (mob.getTarget() == null) {
			mob.setTarget(player);
		}
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
			bargain = extractBargainFromText(lastUserText(mob.getUUID(), player.getUUID()), reply.say(), av, player.getUUID());
		}
		if (bargain != null && mob instanceof AbstractVillager villager) {
			MobMindMod.LOGGER.info("[MobMind] 砍价: {} 对 {} 商品「{}」 agree={}",
					player.getGameProfile().name(), mob.getType().getDescription().getString(),
					bargain.item(), bargain.agree());
			BarterActions.applyBargain(villager, player, persona, bargain.item(), bargain.agree());
		}
		// 砍价场景（bargain不为null）是修改交易界面价格，不应创建以物易物约定或记录赠送承诺
		// （砍价对话中常出现"X换Y"和"给你"等字眼，容易被误识别为交易/赠送）
		Barter barter = reply.barter();
		if (barter == null && applyActions && bargain == null) { // 模型漏输出 barter 字段时，从对话文本兜底识别（砍价时不兜底）
			barter = extractBarterFromText(lastUserText(mob.getUUID(), player.getUUID()), reply.say(), player.getUUID());
		}
		if (barter != null && bargain == null) { // 砍价场景不创建以物易物约定
			BarterActions.createDeal(mob, player, barter.gives(), barter.takes());
		}

		// 信守承诺：从生物台词中提取它答应给玩家的物品，记录为2分钟内有效的承诺
		// 免费赠送（"送你XX"/"给你XX"）不需要玩家给东西，直接给；
		// 砍价场景（bargain不为null）或交易场景（玩家说"X换Y"）不记录任何承诺，回赠由barter系统处理
		String lastUserMsg = lastUserText(mob.getUUID(), player.getUUID());
		if (applyActions && bargain == null) {
			PromisedItems promisedResult = extractPromisedItems(reply.say(), lastUserMsg, isEnglishUi(player.getUUID()));
			List<ItemCatalog.MatchedItem> promised = promisedResult.items;

			// 安全网：如果存在正式以物易物约定，过滤掉承诺物品中属于交易的物品
			// （如"15个煤炭换1个绿宝石，给你绿宝石"不应被当成免费赠送提前发放，回赠由barter系统统一处理）
			if (barter != null && !promised.isEmpty()) {
				java.util.Set<net.minecraft.world.item.Item> barterItems = new java.util.HashSet<>();
				for (ItemCatalog.MatchedItem g : barter.gives()) {
					if (g != null && g.item() != null) barterItems.add(g.item());
				}
				for (ItemCatalog.MatchedItem t : barter.takes()) {
					if (t != null && t.item() != null) barterItems.add(t.item());
				}
				promised = promised.stream()
						.filter(m -> m != null && m.item() != null && !barterItems.contains(m.item()))
						.collect(java.util.stream.Collectors.toList());
			}

			if (!promised.isEmpty()) {
				List<MobMindState.BarterDeal.ItemRequirement> promisedReqs = new ArrayList<>();
				for (ItemCatalog.MatchedItem m : promised) {
					if (m != null && m.item() != null && m.item() != net.minecraft.world.item.Items.AIR) {
						promisedReqs.add(new MobMindState.BarterDeal.ItemRequirement(m.item(),
								Math.max(1, Math.min(64, m.count())), null));
					}
				}
				if (!promisedReqs.isEmpty()) {
					MobMindState.setGiftPromise(mob, player.getUUID(), promisedReqs, !promisedResult.isFreeGift);
					MobMindMod.LOGGER.info("[MobMind] 记录承诺: {} 答应给 {}: {} (免费={})",
							mob.getType().getDescription().getString(),
							player.getGameProfile().name(), describe(promised), promisedResult.isFreeGift);
				}
			}
		}
		remember(mob.getUUID(), player.getUUID(), "user", lastUserText(mob.getUUID(), player.getUUID()));
		remember(mob.getUUID(), player.getUUID(), "assistant", reply.say());

		String mobName;
		String englishMobType = getEnglishMobName(mob);
		if (isEnglishUi(player.getUUID())) {
			String englishNickname = PersonalityGenerator.generateEnglishName(mob.getUUID());
			mobName = englishNickname + " (" + englishMobType + ")";
		} else {
			mobName = persona.name + "（" + mob.getType().getDescription().getString() + "）";
		}
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
		java.util.UUID playerId = player.getUUID();
		int friendship = MobMindState.friendship(mob, playerId);
		Level level = mob.level();
		boolean english = isEnglishUi(playerId);
		long dayTime = level.getOverworldClockTime() % 24000;
		String timeDesc = (dayTime >= 13000 && dayTime <= 23000)
				? t("夜晚", "night", playerId) : t("白天", "day", playerId);
		String weather = level.isThundering() ? t("雷暴", "thunderstorm", playerId)
				: level.isRaining() ? t("下雨", "rainy", playerId) : t("晴朗", "clear", playerId);
		String hand = player.getMainHandItem().isEmpty()
				|| player.getMainHandItem().getItem() == net.minecraft.world.item.Items.AIR
				? t("空手", "empty hand", playerId)
				: (english ? getEnglishItemName(player.getMainHandItem()) : player.getMainHandItem().getHoverName().getString());
		boolean targetingPlayer = mob.getTarget() == player;
		String relation = friendship < 20 ? t("死敌", "mortal enemy", playerId)
				: friendship < 40 ? t("陌生", "stranger", playerId)
				: friendship < 60 ? t("认识", "acquaintance", playerId)
				: friendship < 80 ? t("朋友", "friend", playerId) : t("挚友", "best friend", playerId);
		String gameMode = player.isCreative()
				? t("创造模式（你伤不到他）", "Creative (you cannot hurt him)", playerId)
				: t("生存模式", "Survival", playerId);
		boolean piglinNeutralGold = isPiglin(mob) && !isPiglinBrute(mob) && hasAnyGoldArmor(player);
		boolean isFriend = friendship >= 60;
		String tauntTrait = MobMindConfig.get().creativeTauntEnabled
				&& Boolean.TRUE.equals(persona.creativeTaunt) && !piglinNeutralGold && player.isCreative() && !isFriend
				? t("\n- 你极度渴望和玩家公平决斗：只要他还在创造模式，你就忍不住三句不离让他换成生存模式再来面对你。",
						"\n- You crave a fair duel with the player: as long as they are in Creative mode, you can't stop taunting them to switch to Survival and face you.", playerId)
				: "";
		String environment = EnvironmentSense.describe(mob, english);

		PersonaRegistry.Persona spec = PersonaRegistry.forMob(mob);
		String personaText = spec != null ? spec.text()
				: t("（无专属设定，按该生物的原版习性扮演）", "(No unique settings; roleplay according to the mob's vanilla behavior)", playerId);
		String alignmentDesc;
		String displayName;
		if (english) {
			displayName = PersonalityGenerator.generateEnglishName(mob.getUUID());
			alignmentDesc = persona.alignment != null
					? translateAlignment(persona.alignment)
					: "";
		} else {
			displayName = persona.name;
			alignmentDesc = persona.alignment != null
					? "该个体在首次生成时被抽取为「" + persona.alignment + "」，此结果永久固定、不会重抽，必须严格遵守设定中该类型立场的表现方式。"
					: "";
		}

		// 村民/流浪商人：注入在售商品列表供砍价参考
		StringBuilder offersSection = new StringBuilder();
		if (mob instanceof AbstractVillager villager && !villager.getOffers().isEmpty()) {
			offersSection.append(english ? "[Items You Are Selling]\n" : "【你在售的商品】\n");
			var offers = villager.getOffers();
			for (int i = 0; i < Math.min(offers.size(), 12); i++) {
				var o = offers.get(i);
				String costA = english ? getEnglishItemName(o.getCostA()) : o.getCostA().getHoverName().getString();
				String cost = costA + "×" + o.getCostA().getCount();
				if (!o.getCostB().isEmpty()) {
					String costB = english ? getEnglishItemName(o.getCostB()) : o.getCostB().getHoverName().getString();
					cost += " + " + costB + "×" + o.getCostB().getCount();
				}
				String result = english ? getEnglishItemName(o.getResult()) : o.getResult().getHoverName().getString();
				offersSection.append(i + 1).append(". ").append(cost).append(" → ")
						.append(result).append("×").append(o.getResult().getCount()).append("\n");
			}
		}

		String system;
		if (english) {
			system = """
					You are roleplaying a mob in Minecraft. Stay fully in character and never mention that you are an AI.
					[Mob Settings (highest priority)]
					%s
					[Personality Roll for This Individual]
					%s
					- Your name: %s
					- Sociability: %d/100, Temper: %d/100, Humor: %d/100
					[Relationship with Player]
					- Player name: %s, Friendship: %d/100 (Relation: %s)
					- Player game mode: %s%s
					[Current Situation]
					- Health %.0f/%.0f, %s, weather: %s
					- Player holding: %s
					- You are attacking this player: %s
					- Your current situation: %s
					%s[Reply Rules]
					1. Output exactly one line of JSON: {"say":"...","mood":"...","action":"...","friendship":number,"bargain":null,"barter":null}
					2. say: first-person spoken style, strictly follow your settings, personality stance and speaking style, max 60 characters. LANGUAGE RULE (CRITICAL, MUST FOLLOW): The player's game client is set to English. You MUST reply in ENGLISH ONLY, no matter what language the player speaks to you. Even if the player types Chinese or another language, you must respond entirely in English. Do NOT mix languages. Do NOT speak Chinese.
					3. mood: an emotion word in English, e.g. happy/angry/scared/curious/calm
					4. action must be one of none|calm|follow|stay|flee|gift|attack:
					   - Player asks you to follow/go with them and you agree → follow
					   - Player asks you to make peace/stop attacking and you agree → calm
					   - Player tells you to stay/wait → stay
					   - You are scared or want to flee → flee
					   - You want to give the player a small gift → gift
					   - You are enraged and want to attack the player (hostile mobs only, must fit your personality stance) → attack
					   - Otherwise → none
					5. friendship: change in friendship from this conversation, integer -10 to 10. Friendly words +1~5, insults/threats -3~10, based on your temper sensitivity
					6. When friendship is low act hostile or wary; when high act warm and enthusiastic; but your stance and behavioral bottom line are always determined by your personality roll
					7. [Bargain - villagers/wandering traders only] When player haggles over a listed item: bargain={"item":"item name in English","agree":"yes or no"}, otherwise null. Decision based on your personality stance, relationship with player and player's rhetoric; each item can only be bargained down once, repeated haggling should be refused (system will handle price increases).
					8. [Barter] When a deal is reached set barter={"gives":[{"name":"item name in English","count":number},...],"takes":[{"name":"item name in English","count":number},...]}. gives = items player gives you, takes = items you give back. Example: player "3 apples for 5 rotten flesh" → {"gives":[{"name":"apple","count":3}],"takes":[{"name":"rotten flesh","count":5}]}; "8 emeralds and 1 cake for iron chestplate" → {"gives":[{"name":"emerald","count":8},{"name":"cake","count":1}],"takes":[{"name":"iron chestplate","count":1}]}. Before player throws all gives items at your feet, do NOT throw takes items and do NOT action:gift. Verbal agreement without barter means refusal. Return items should fit your identity (zombie gives rotten flesh, skeleton gives bones, etc.), 1-16 count. All item names must be in English.
					""".formatted(
					personaText, alignmentDesc, displayName,
					persona.sociability, persona.temper, persona.humor,
					player.getGameProfile().name(), friendship, relation,
					gameMode, tauntTrait,
					mob.getHealth(), mob.getMaxHealth(), timeDesc, weather, hand,
					targetingPlayer ? "yes" : "no", environment, offersSection.toString());
		} else {
			system = """
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
					2. say：用第一人称口语，严格符合你的设定、性格立场与说话风格，不超过60字。语言规则（必须遵守）：玩家游戏客户端语言为中文，你必须始终用中文回复，无论玩家用什么语言和你说话。即使玩家说英文或其他语言，你也必须全部用中文回答。严禁混用语言或切换到英文。
					3. mood：一个中文情绪词，如 开心/生气/害怕/好奇/平静
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
					8.【以物易物】谈妥时设 barter={"gives":[{"name":"玩家应给你的物品名","count":数量},...],"takes":[{"name":"你回赠的物品名","count":数量},...]}。gives=玩家要给你的列表，takes=你给玩家的列表。例：玩家"3个苹果换5个腐肉"→{"gives":[{"name":"苹果","count":3}],"takes":[{"name":"腐肉","count":5}]}；"8个绿宝石加1个蛋糕换铁胸甲"→{"gives":[{"name":"绿宝石","count":8},{"name":"蛋糕","count":1}],"takes":[{"name":"铁胸甲","count":1}]}。玩家把所有 gives 物品扔到你脚边前，不准把 takes 物品丢出来，也不要 action:gift。口头答应但没 barter 等于拒绝。回赠物品要符合身份（僵尸给腐肉、骷髅给骨头等），1-16个。所有物品名必须用中文。
					""".formatted(
					personaText, alignmentDesc, displayName,
					persona.sociability, persona.temper, persona.humor,
					player.getGameProfile().name(), friendship, relation,
					gameMode, tauntTrait,
					mob.getHealth(), mob.getMaxHealth(), timeDesc, weather, hand,
					targetingPlayer ? "是" : "否", environment, offersSection.toString());
		}

		List<OpenAiClient.ChatMessage> messages = new ArrayList<>();
		messages.add(new OpenAiClient.ChatMessage("system", system));
		// 从 MobMindState 读取持久化的对话历史（重启游戏后生物仍记得之前的对话）
		List<MobMindState.ConversationEntry> history = MobMindState.getRecentConversationHistory(
				mob.getUUID(), player.getUUID(), MEMORY_LIMIT);
		if (history != null) {
			for (MobMindState.ConversationEntry entry : history) {
				messages.add(new OpenAiClient.ChatMessage(entry.role(), entry.content()));
			}
		}
		messages.add(new OpenAiClient.ChatMessage("user", userText));
		// 暂存当前用户消息，finish() 后再持久化（连同 assistant 回复一起写入）
		PENDING.put(mob.getUUID() + ":" + player.getUUID(), userText);
		return messages;
	}

	/** 把一轮对话（user+assistant）持久化到 MobMindState，重启游戏后仍可读取 */
	private static void remember(UUID entityId, UUID playerId, String role, String content) {
		if ("__pending__".equals(role)) {
			PENDING.put(entityId + ":" + playerId, content);
			return;
		}
		MobMindState.recordConversation(entityId, playerId, role, content);
		MobMindState.trimConversationHistory(entityId, playerId, MEMORY_LIMIT);
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
				net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> givePotion = ItemCatalog.potionForName(give);
				net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> takePotion = ItemCatalog.potionForName(take);
				gives = List.of(new ItemCatalog.MatchedItem(giveItem, give, giveCount, givePotion));
				takes = List.of(new ItemCatalog.MatchedItem(takeItem, take, takeCount, takePotion));
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
		// 药水类物品需要精确匹配药水效果（喷溅型虚弱药水 vs 喷溅型治疗药水）
		net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion = ItemCatalog.potionForName(name);
		return new ItemCatalog.MatchedItem(item, name, Math.max(1, count), potion);
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

	// 玩家砍价意图（中英）
	private static final java.util.regex.Pattern HAGGLE_INTENT = java.util.regex.Pattern
			.compile("(便宜|砍价|优惠|降价|打折|少[点一儿]|太贵|贵死|cheaper|discount|lower the price|too expensive|overpriced|haggle|bargain)");

	/**
	 * 砍价兜底：玩家明确讨价还价且提到某个在售商品，生物台词接受/拒绝 → 按结果处理。
	 */
	private static Bargain extractBargainFromText(String userText, String say, AbstractVillager villager, java.util.UUID playerId) {
		if (userText == null || say == null || userText.startsWith("（") || userText.startsWith("(")) return null;
		boolean english = isEnglishUi(playerId);
		if (!HAGGLE_INTENT.matcher(userText).find()) return null;
		ItemCatalog.MatchedItem wanted = ItemCatalog.findInText(userText, false, english);
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

	// 生物台词中的明确拒绝（需要更精确匹配，避免误判"不能给太多"等）
	private static final java.util.regex.Pattern REFUSE_PATTERN = java.util.regex.Pattern
			.compile("(不换|不行啊|不行！|不行。|不行，|不要你的|拒绝|免谈|没兴趣|别烦我|不考虑|凭什么|想得美|做梦|滚|no way|nope|never|refuse|reject|not happening|i won't trade|i can't trade|forget it)");
	// 生物台词中的接受成交（中英）
	private static final java.util.regex.Pattern ACCEPT_PATTERN = java.util.regex.Pattern
			.compile("(可以|成交|换吧|接受|同意|没问题|一言为定|行[，。！,!.]|好[，。！,!.]|给你|拿好|拿着|deal|okay|ok|sure|accepted|agreed|yes|alright|fine|let's do it|done|here you go|take this)");

	/**
	 * 模型漏输出 barter 字段时的兜底：玩家文本含"A换B"/"A for B"/"trade A for B"/"give A get B"等且生物台词未明确拒绝 → 成立约定。
	 * 支持多个支付/回赠物品（如"8个绿宝石加1个蛋糕换铁胸甲"）。
	 * 仅用于玩家真实对话（非系统触发），用户文本以（或(开头的是系统注入，跳过。
	 */
	private static Barter extractBarterFromText(String userText, String say, java.util.UUID playerId) {
		if (userText == null || say == null) return null;
		boolean english = isEnglishUi(playerId);
		if (userText.startsWith("（") || userText.startsWith("(")) return null;
		if (REFUSE_PATTERN.matcher(say).find()) return null;
		// 放宽接受判断：只要台词没拒绝就算接受，提高约定成功率
		boolean explicitAccept = ACCEPT_PATTERN.matcher(say).find();
		if (!explicitAccept) {
			MobMindMod.LOGGER.info("[MobMind] 以物易物兜底：生物未明确接受，但尝试识别约定");
		}

		int sep = -1;
		if (!english) {
			sep = userText.indexOf('换');
			if (sep < 0) {
				// 兼容"A换B"、"用A换B"、"拿A换B"之外的表达
				int gei = userText.indexOf('给');
				int yao = userText.indexOf('要');
				int huan = userText.indexOf('换');
				if (gei >= 0 && yao >= 0 && yao > gei) sep = yao;
				else if (gei >= 0 && huan > gei) sep = huan;
			}
		} else {
			String lower = userText.toLowerCase();
			int forIdx = lower.indexOf(" for ");
			int giveIdx = lower.indexOf("give ");
			int getIdx = lower.indexOf("get ");
			int bringIdx = lower.indexOf("bring ");
			int tradeFor = lower.indexOf("trade ");
			if (tradeFor >= 0 && forIdx > tradeFor) {
				sep = forIdx;
			} else if (forIdx >= 0) {
				sep = forIdx;
			} else if (giveIdx >= 0 && getIdx > giveIdx) {
				sep = getIdx;
			} else if (bringIdx >= 0 && getIdx > bringIdx) {
				sep = getIdx;
			}
		}
		if (sep < 0) return null;

		String left = userText.substring(0, sep);
		String right = userText.substring(sep + 1);
		if (english) {
			// 跳过 "for " / "trade " / "get " / "bring " 前缀
			String lowerRight = right.toLowerCase();
			if (lowerRight.startsWith("for ")) right = right.substring(4);
			if (lowerRight.startsWith("get ")) right = right.substring(4);
			String lowerLeft = left.toLowerCase();
			if (lowerLeft.endsWith("trade ")) left = left.substring(0, left.length() - 6);
			if (lowerLeft.endsWith("give ")) left = left.substring(0, left.length() - 5);
			if (lowerLeft.endsWith("bring ")) left = left.substring(0, left.length() - 6);
		}
		List<ItemCatalog.MatchedItem> gives = ItemCatalog.findAllInText(left, english);
		List<ItemCatalog.MatchedItem> takes = ItemCatalog.findAllInText(right, english);
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

	/** 承诺提取结果：items=承诺给的物品列表，isFreeGift=true表示免费赠送（不需要玩家给东西） */
	private record PromisedItems(List<ItemCatalog.MatchedItem> items, boolean isFreeGift) {}

	/**
	 * 从生物台词中提取它答应给玩家的物品（信守承诺：说了给就必须给）。
	 * 匹配"给你XX"、"拿着XX"、"送你XX"、"here you go, XX"、"take this XX"等（免费赠送）。
	 * 注意：交易语境下（玩家说"X换Y"等）不提取任何物品，回赠完全由barter系统统一处理。
	 */
	private static PromisedItems extractPromisedItems(String say, String userText, boolean english) {
		if (say == null || say.isEmpty()) return new PromisedItems(List.of(), false);
		if (REFUSE_PATTERN.matcher(say).find()) return new PromisedItems(List.of(), false);

		// 交易语境检测：检查玩家消息或生物回复中是否有交易关键词
		// 交易场景下回赠由BarterDeal统一处理，"给你绿宝石"是交易回赠不是免费赠送
		String checkUserText = userText != null ? userText : "";
		boolean isTradeContext = (english
				? (say.toLowerCase().contains(" for ") || say.toLowerCase().contains("trade")
				   || say.toLowerCase().contains("exchange") || say.toLowerCase().contains(" swap ")
				   || checkUserText.toLowerCase().contains(" for ") || checkUserText.toLowerCase().contains("trade")
				   || checkUserText.toLowerCase().contains("exchange") || checkUserText.toLowerCase().contains(" swap ")
				   || checkUserText.toLowerCase().contains(" in return"))
				: (say.contains("换") || say.contains("交换")
				   || checkUserText.contains("换") || checkUserText.contains("交换") || checkUserText.contains("换给")));
		if (isTradeContext) {
			return new PromisedItems(List.of(), false);
		}

		List<ItemCatalog.MatchedItem> result = new ArrayList<>();
		boolean isFreeGift = false;

		// 先尝试找"给你"、"送你"、"拿着"后面的物品（这些是免费赠送）
		String[] freePrefixes = english
				? new String[]{"here you go", "take this", "take these", "for you", "have this", "have a ", "i'll give you"}
				: new String[]{"送你", "给你", "拿着", "拿好", "尝尝", "收下"};

		String searchText = say;
		for (String prefix : freePrefixes) {
			int idx = english ? searchText.toLowerCase().indexOf(prefix.toLowerCase()) : searchText.indexOf(prefix);
			while (idx >= 0) {
				isFreeGift = true; // 检测到免费赠送关键词
				int after = idx + prefix.length();
				if (after < searchText.length()) {
					String afterText = searchText.substring(after);
					// 截断在标点符号或另一个动词前
					int end = afterText.length();
					for (char sep : new char[]{'，', '。', '！', '？', '、', '；', ',', '.', '!', '?', ';', '吧', '啊', '呢', '哦', '哈', '呀'}) {
						int sepIdx = afterText.indexOf(sep);
						if (sepIdx >= 0 && sepIdx < end) end = sepIdx;
					}
					String itemText = afterText.substring(0, Math.min(end, 30));
					List<ItemCatalog.MatchedItem> found = ItemCatalog.findAllInText(itemText, english);
					result.addAll(found);
				}
				idx = english ? searchText.toLowerCase().indexOf(prefix.toLowerCase(), idx + 1)
						: searchText.indexOf(prefix, idx + 1);
			}
		}

		// 如果没找到带前缀的，尝试兜底提取（AI可能说"好，附魔金苹果"→有条件承诺）
		// 但这只适用于非交易场景的友好回应
		if (result.isEmpty() && ACCEPT_PATTERN.matcher(say).find()) {
			List<ItemCatalog.MatchedItem> all = ItemCatalog.findAllInText(say, english);
			result.addAll(all);
			isFreeGift = false;
		}

		return new PromisedItems(result, isFreeGift && !result.isEmpty());
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
		boolean english = isEnglishUi(player.getUUID());
		String say;
		String mood = english ? "calm" : "平静";
		String action = "none";
		int delta = 0;

		if (t.contains("攻击") || t.contains("打你") || (english && (t.toLowerCase().contains("attack") || t.toLowerCase().contains("hit you")))) {
			say = english
					? (persona.temper > 50 ? "How dare you hit me?!" : "Ouch... why did you hit me...")
					: (persona.temper > 50 ? "你竟敢动手？！" : "呜……为什么打我……");
			mood = english ? (persona.temper > 50 ? "angry" : "scared") : (persona.temper > 50 ? "生气" : "害怕");
			action = persona.temper > 50 ? "attack" : "flee";
			delta = -5;
		} else if (t.contains("别打") || t.contains("和解") || t.contains("朋友") || t.contains("和平")
				|| (english && (t.toLowerCase().contains("stop") || t.toLowerCase().contains("peace") || t.toLowerCase().contains("friend")))) {
			say = english
					? (persona.temper > 70 ? "Hmph, your attitude isn't bad, so I'll let you off." : "Sure, then we're friends!")
					: (persona.temper > 70 ? "哼，看在态度还行的份上，先放过你。" : "好呀，那我们就是朋友啦！");
			mood = english ? "relieved" : "缓和";
			action = "calm";
			delta = 5;
		} else if (t.contains("跟") || t.contains("走") || t.contains("一起")
				|| (english && (t.toLowerCase().contains("follow") || t.toLowerCase().contains("come") || t.toLowerCase().contains("with me")))) {
			say = english
					? (persona.sociability > 50 ? "Alright, I'll follow you!" : "...Fine, I'll walk with you for a while.")
					: (persona.sociability > 50 ? "好嘞，跟着你走！" : "……那就陪你走一段吧。");
			mood = english ? "happy" : "开心";
			action = "follow";
			delta = 2;
		} else if (t.contains("待着") || t.contains("停下") || t.contains("别动")
				|| (english && (t.toLowerCase().contains("stay") || t.toLowerCase().contains("wait") || t.toLowerCase().contains("stop")))) {
			say = english ? "Okay, I'll stay right here." : "行，我就在这儿待着。";
			action = "stay";
		} else if (t.contains("你好") || t.contains("hi") || t.contains("嗨") || t.contains("（玩家路过")
				|| (english && (t.toLowerCase().contains("hello") || t.toLowerCase().contains("hi ") || t.toLowerCase().contains("hey")))) {
			say = english
					? switch (persona.name.length() % 3) {
						case 0 -> "Hello there, " + player.getGameProfile().name() + "!";
						case 1 -> "Oh, it's you.";
						default -> "Hmm? Did you need something?";
					}
					: switch (persona.name.length() % 3) {
						case 0 -> "你好呀，" + player.getGameProfile().name() + "！";
						case 1 -> "哟，是你啊。";
						default -> "嗯？找我有什么事吗？";
					};
			mood = english ? "curious" : "好奇";
			delta = 1;
		} else if (t.contains("吃") || t.contains("食物") || (english && (t.toLowerCase().contains("food") || t.toLowerCase().contains("eat")))) {
			say = english ? "Talking about food really perks me up!" : "说到吃的，我可就来精神了！";
			mood = english ? "happy" : "开心";
			delta = 1;
		} else {
			say = english
					? switch ((int) (Math.floorMod(mob.getUUID().hashCode(), 4))) {
						case 0 -> "Oh? Go on, I'm listening.";
						case 1 -> "Mm-hmm, and then?";
						case 2 -> "Well, let me think about that.";
						default -> "Haha, interesting.";
					}
					: switch ((int) (Math.floorMod(mob.getUUID().hashCode(), 4))) {
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
