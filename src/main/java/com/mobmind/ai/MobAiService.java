package com.mobmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BehaviorActions;
import com.mobmind.behavior.HouseGuard;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
	private static final int MEMORY_LIMIT = 40;
	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HURT_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HELP_CRY = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_POTION_REACT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_GREET = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_TAUNT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_GOSSIP = new ConcurrentHashMap<>();
	/** 玩家跟傻子村民说话时，附近村民插话劝阻的冷却（key 为傻子村民 UUID） */
	private static final Map<UUID, Long> LAST_NITWIT_GOSSIP = new ConcurrentHashMap<>();
	/** 村民小声议论玩家的冷却（key 为玩家 UUID） */
	private static final Map<UUID, Long> LAST_VILLAGER_WHISPER = new ConcurrentHashMap<>();
	/** 玩家种地后农民来感谢的冷却（key 为玩家 UUID） */
	private static final Map<UUID, Long> LAST_PLANT_THANKS = new ConcurrentHashMap<>();
	/** 生物被手持物品吸引时的反应冷却（key 为生物 UUID） */
	private static final Map<UUID, Long> LAST_TEMPT_REACT = new ConcurrentHashMap<>();
	/** 玩家吸引/拴走被动动物时村民质问的冷却（key 为玩家 UUID） */
	private static final Map<UUID, Long> LAST_LIVESTOCK_LEAD_REACT = new ConcurrentHashMap<>();
	/** 被动动物/宠物吸引物品映射（实体ID → 吸引物品集合） */
	private static final java.util.Map<String, java.util.Set<net.minecraft.world.item.Item>> LIVESTOCK_TEMPT_ITEMS;
	static {
		var map = new java.util.HashMap<String, java.util.Set<net.minecraft.world.item.Item>>();
		// 牲畜
		map.put("cow", java.util.Set.of(net.minecraft.world.item.Items.WHEAT));
		map.put("sheep", java.util.Set.of(net.minecraft.world.item.Items.WHEAT));
		map.put("pig", java.util.Set.of(net.minecraft.world.item.Items.CARROT, net.minecraft.world.item.Items.POTATO, net.minecraft.world.item.Items.BEETROOT));
		map.put("chicken", java.util.Set.of(net.minecraft.world.item.Items.WHEAT_SEEDS, net.minecraft.world.item.Items.MELON_SEEDS,
				net.minecraft.world.item.Items.PUMPKIN_SEEDS, net.minecraft.world.item.Items.BEETROOT_SEEDS, net.minecraft.world.item.Items.TORCHFLOWER_SEEDS));
		map.put("rabbit", java.util.Set.of(net.minecraft.world.item.Items.CARROT, net.minecraft.world.item.Items.GOLDEN_CARROT,
				net.minecraft.world.item.Items.DANDELION));
		map.put("mooshroom", java.util.Set.of(net.minecraft.world.item.Items.WHEAT));
		// 马类
		map.put("horse", java.util.Set.of(net.minecraft.world.item.Items.GOLDEN_APPLE, net.minecraft.world.item.Items.GOLDEN_CARROT,
				net.minecraft.world.item.Items.SUGAR, net.minecraft.world.item.Items.WHEAT, net.minecraft.world.item.Items.APPLE));
		map.put("donkey", java.util.Set.of(net.minecraft.world.item.Items.GOLDEN_APPLE, net.minecraft.world.item.Items.GOLDEN_CARROT,
				net.minecraft.world.item.Items.SUGAR, net.minecraft.world.item.Items.WHEAT, net.minecraft.world.item.Items.APPLE));
		map.put("mule", java.util.Set.of(net.minecraft.world.item.Items.GOLDEN_APPLE, net.minecraft.world.item.Items.GOLDEN_CARROT,
				net.minecraft.world.item.Items.SUGAR, net.minecraft.world.item.Items.WHEAT, net.minecraft.world.item.Items.APPLE));
		map.put("camel", java.util.Set.of(net.minecraft.world.item.Items.CACTUS));
		// 宠物（猫、狼、鹦鹉）
		map.put("cat", java.util.Set.of(net.minecraft.world.item.Items.COD, net.minecraft.world.item.Items.SALMON));
		map.put("wolf", java.util.Set.of(net.minecraft.world.item.Items.BONE, net.minecraft.world.item.Items.BEEF,
				net.minecraft.world.item.Items.CHICKEN, net.minecraft.world.item.Items.MUTTON,
				net.minecraft.world.item.Items.PORKCHOP, net.minecraft.world.item.Items.RABBIT,
				net.minecraft.world.item.Items.ROTTEN_FLESH));
		map.put("parrot", java.util.Set.of(net.minecraft.world.item.Items.WHEAT_SEEDS, net.minecraft.world.item.Items.MELON_SEEDS,
				net.minecraft.world.item.Items.PUMPKIN_SEEDS, net.minecraft.world.item.Items.BEETROOT_SEEDS,
				net.minecraft.world.item.Items.TORCHFLOWER_SEEDS));
		LIVESTOCK_TEMPT_ITEMS = java.util.Collections.unmodifiableMap(map);
	}
	/** 模组支持生物的吸引物品映射（实体ID → 吸引物品集合） */
	private static final java.util.Map<String, java.util.Set<net.minecraft.world.item.Item>> TEMPT_ITEMS = java.util.Map.of(
			"zombie_horse", java.util.Set.of(net.minecraft.world.item.Items.RED_MUSHROOM),
			"strider", java.util.Set.of(net.minecraft.world.item.Items.WARPED_FUNGUS,
					net.minecraft.world.item.Items.WARPED_FUNGUS_ON_A_STICK)
	);

	private MobAiService() {}

	/** 已提示过"离线模式"的玩家（每次进服提示一次） */
	private static final java.util.Set<UUID> OFFLINE_NOTIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Long> LAST_ERROR_NOTIFY = new ConcurrentHashMap<>();
	private static final long ERROR_NOTIFY_COOLDOWN = 10000; // API错误提示10秒冷却，防止刷屏

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

	/** 玩家皮肤性别缓存（UUID → true=女/Alex/slim，false=男/Steve/classic） */
	private static final Map<UUID, Boolean> PLAYER_SKIN_FEMALE = new ConcurrentHashMap<>();

	/**
	 * 通过玩家 GameProfile 的 textures 属性判断皮肤模型，
	 * slim（Alex）= 女，classic（Steve）= 男。
	 * 结果缓存到 PLAYER_SKIN_FEMALE，避免每次都解析 Base64。
	 */
	private static boolean isFemaleSkin(ServerPlayer player) {
		UUID pid = player.getUUID();
		Boolean cached = PLAYER_SKIN_FEMALE.get(pid);
		if (cached != null) return cached;
		boolean female = false;
		try {
			var props = player.getGameProfile().properties().get("textures");
			if (props != null && !props.isEmpty()) {
				String encoded = props.iterator().next().value();
				String json = new String(java.util.Base64.getDecoder().decode(encoded),
						java.nio.charset.StandardCharsets.UTF_8);
				// 只解析 "model":"slim" 关键字，避免引入完整 JSON 库
				female = json.contains("\"model\":\"slim\"");
			}
		} catch (Exception ignored) {}
		PLAYER_SKIN_FEMALE.put(pid, female);
		return female;
	}

	/** 根据玩家皮肤性别返回称呼提示词片段（注入到 AI 提示词中） */
	private static String playerSkinGenderHint(ServerPlayer player, boolean en) {
		boolean female = isFemaleSkin(player);
		if (en) {
			return female
					? "The player appears to be female (slim/Alex skin model). You may address her as lady, miss, or pretty lady depending on your personality."
					: "The player appears to be male (classic/Steve skin model). You may address him as sir, mister, or handsome depending on your personality.";
		} else {
			return female
					? "这个玩家看起来是女性（slim/Alex皮肤模型）。你可以根据你的性格称呼她为小姐、美女、姑娘等。"
					: "这个玩家看起来是男性（classic/Steve皮肤模型）。你可以根据你的性格称呼他为先生、帅哥、小伙子等。";
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

		// 玩家跟傻子村民说话：附近其他村民会插话劝阻（30秒冷却，避免刷屏）
		if (isNitwit(mob)) {
			notifyNitwitGossip(player, mob);
		}
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

	/** 玩家跟傻子村民说话时，附近其他村民会插话劝阻（30秒冷却，每个傻子最多2个村民插话） */
	private static void notifyNitwitGossip(ServerPlayer player, Mob nitwit) {
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_NITWIT_GOSSIP.get(nitwit.getUUID());
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_NITWIT_GOSSIP.put(nitwit.getUUID(), now);

		AABB box = player.getBoundingBox().inflate(16.0);
		List<Mob> crowd = player.level().getEntitiesOfClass(Mob.class, box,
				m -> m.isAlive() && m != nitwit && m instanceof Villager && !isNitwit(m) && withinTalkRange(m, player));
		if (crowd.isEmpty()) return;
		Collections.shuffle(crowd);
		int limit = Math.min(2, crowd.size());
		for (int i = 0; i < limit; i++) {
			respond(player, crowd.get(i), isEnglishUi(playerId)
					? "(Player " + player.getGameProfile().name() + " is talking to the village nitwit. You think it's a waste of time—the nitwit can't do any real work or trade, and barely understands anything. In your own style, advise the player not to bother with him: maybe a bit exasperated, pitying, or dismissive. Keep it to one short line)"
					: "（玩家" + player.getGameProfile().name() + "正在跟村里的傻子村民搭话。你觉得这是白费功夫——傻子干不了活、做不了交易，脑子也不太清楚。用你自己的风格劝玩家别跟他费口舌：可以有点无奈、嫌弃或同情。只说一句短话）", false);
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
		MobMindState.recordGrudge(mob, playerId, "攻击了我",
				mob.level().getGameTime() + 12000);

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
			MobMindMod.LOGGER.info("[MobMind] Gossip spread: {} heard player {} hit {}, friendship -{}",
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
						MobMindMod.LOGGER.info("[MobMind] Auto eat food: {} HP={}/{}",
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

	/** 猪灵因玩家盗窃（开箱/挖金）被激怒 - 通用入口（由原版钩子调用） */
	public static void onPiglinAngeredByLooting(net.minecraft.world.entity.monster.piglin.Piglin piglin, ServerPlayer player) {
		if (!PersonaRegistry.supports(piglin)) return;
		// 通用入口使用默认提示（不知道具体原因）
		triggerPiglinAnger(piglin, player, 0, null);
	}

	/** 猪灵因玩家挖金块/金矿石被激怒 */
	public static void onPiglinGoldMined(net.minecraft.world.entity.Mob piglin, ServerPlayer player, String blockName) {
		triggerPiglinAnger(piglin, player, 1, blockName);
	}

	/** 猪灵因玩家开箱子被激怒 */
	public static void onPiglinContainerOpened(net.minecraft.world.entity.Mob piglin, ServerPlayer player, String containerName) {
		triggerPiglinAnger(piglin, player, 2, containerName);
	}

	/**
	 * 猪灵愤怒核心逻辑
	 * @param type 0=通用 1=挖金 2=开容器
	 */
	private static void triggerPiglinAnger(net.minecraft.world.entity.Mob piglin, ServerPlayer player, int type, String targetName) {
		if (!PersonaRegistry.supports(piglin)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_PIGLIN_LOOT_ANGER.get(piglin.getUUID());
		if (last != null && now - last < 8000) return; // 8秒冷却，避免太多猪灵同时刷屏
		LAST_PIGLIN_LOOT_ANGER.put(piglin.getUUID(), now);

		MobMindState.adjustFriendship(piglin, playerId, -10);
		long gameTime = piglin.level().getLevelData().getGameTime();
		MobMindState.provoke(piglin, playerId, gameTime + 6000); // 激怒5分钟

		boolean en = isEnglishUi(playerId);
		boolean isBrute = piglin instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute;
		String prompt;

		if (type == 1) {
			// 挖金块
			String goldDesc = targetName != null ? targetName : (en ? "gold" : "金子");
			if (isBrute) {
				prompt = en
					? "(An INTRUDER is mining " + goldDesc + " in YOUR bastion! You are a Piglin Brute, the fiercest guardian! Roar furiously, charge at " + player.getGameProfile().name() + " immediately, yell threats of violence in your own brutish style)"
					: "（有入侵者在你的堡垒里挖" + goldDesc + "！你是猪灵蛮兵，最强的守卫！发出愤怒的咆哮，立刻冲向" + player.getGameProfile().name() + "，用你粗暴的风格喊出最凶狠的威胁）";
			} else {
				prompt = en
					? "(A thief is mining " + goldDesc + " that BELONGS TO YOU! That's your gold! Snort angrily, oink aggressively, yell at " + player.getGameProfile().name() + " and charge to attack them with your weapon)"
					: "（有小偷在挖属于你的" + goldDesc + "！那是你的金子！愤怒地喷鼻息，凶狠地哼哼，向" + player.getGameProfile().name() + "大喊大叫，拿起武器冲上去攻击）";
			}
		} else if (type == 2) {
			// 开箱子
			String containerDesc = targetName != null ? targetName : (en ? "chest" : "箱子");
			if (isBrute) {
				prompt = en
					? "(An INTRUDER is LOOTING your " + containerDesc + " in the bastion! You are a Piglin Brute! Roar with rage, draw your axe and charge " + player.getGameProfile().name() + " immediately to kill the thief!)"
					: "（有入侵者在堡垒里偷你的" + containerDesc + "！你是猪灵蛮兵！怒吼一声，拔出斧头立刻冲向" + player.getGameProfile().name() + "，杀死这个小偷！）";
			} else {
				prompt = en
					? "(A sneaky thief is opening YOUR " + containerDesc + "! Rummaging through your treasures! Snort in fury, grunt aggressively, yell at " + player.getGameProfile().name() + " and attack them with your crossbow/sword)"
					: "（一个鬼鬼祟祟的小偷在开你的" + containerDesc + "！乱翻你的宝贝！愤怒地喷鼻息，凶狠地咕噜着，向" + player.getGameProfile().name() + "大喊，用弩/剑攻击）";
			}
		} else {
			// 通用（原版钩子，不知道具体原因）
			if (isBrute) {
				prompt = en
					? "(Intruder alert! " + player.getGameProfile().name() + " is stealing from the bastion! You are a Piglin Brute, attack and kill them! Roar in rage!)"
					: "（入侵者警报！" + player.getGameProfile().name() + "在偷堡垒的东西！你是猪灵蛮兵，攻击并杀死他们！怒吼！）";
			} else {
				prompt = en
					? "(Player " + player.getGameProfile().name() + " is stealing gold or looting chests in your home! You are furious! Snort, grunt, and attack the thief!)"
					: "（玩家" + player.getGameProfile().name() + "在你家偷金子或开箱子！你被激怒了！喷鼻息、哼哼，攻击这个小偷！）";
			}
		}

		// 确保猪灵把玩家设为攻击目标
		if (piglin.getTarget() == null) {
			piglin.setTarget(player);
		}

		respond(player, piglin, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：林地府邸/女巫小屋/海底废墟守卫 ----------

	private static final Map<UUID, Long> LAST_STRUCTURE_GUARD = new ConcurrentHashMap<>();

	/**
	 * 通用结构守卫触发：卫道士/唤魔者/女巫/溺尸等因玩家破坏方块或开容器而攻击。
	 * @param guardType 1=林地府邸(卫道士/唤魔者), 2=女巫小屋(女巫), 3=沉船/海底废墟(溺尸)
	 * @param eventType 1=破坏方块, 2=开容器
	 */
	public static void onStructureGuardTrigger(net.minecraft.world.entity.Mob guard, ServerPlayer player,
											   int guardType, int eventType, String targetName) {
		if (!PersonaRegistry.supports(guard)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_STRUCTURE_GUARD.get(guard.getUUID());
		if (last != null && now - last < 6000) return; // 6秒冷却
		LAST_STRUCTURE_GUARD.put(guard.getUUID(), now);

		MobMindState.adjustFriendship(guard, playerId, -15);
		long gameTime = guard.level().getLevelData().getGameTime();
		MobMindState.provoke(guard, playerId, gameTime + 6000); // 激怒5分钟
		if (guard.getTarget() == null) guard.setTarget(player);

		boolean en = isEnglishUi(playerId);
		String guardianName;
		String placeName;
		String actionDesc;
		if (guardType == 1) {
			boolean isEvoker = guard.getClass().getSimpleName().equals("Evoker");
			guardianName = isEvoker
					? (en ? "Evoker" : "唤魔者") : (en ? "Vindicator" : "卫道士");
			placeName = en ? "Woodland Mansion" : "林地府邸";
		} else if (guardType == 2) {
			guardianName = en ? "Witch" : "女巫";
			placeName = en ? "Witch Hut" : "女巫小屋";
		} else if (guardType == 3) {
			guardianName = en ? "Drowned" : "溺尸";
			placeName = en ? "shipwreck/ocean ruin" : "沉船/海底废墟";
		} else if (guardType == 4) {
			guardianName = en ? "Zombified Piglin" : "僵尸猪灵";
			placeName = en ? "Nether ruins" : "下界遗迹";
		} else {
			// guardType == 5: generic dungeon/temple monster
			String simpleName = guard.getClass().getSimpleName();
			guardianName = en ? simpleName : translateMobName(simpleName);
			placeName = en ? "dungeon/temple" : "地牢/神殿";
		}
		actionDesc = eventType == 1
				? (en ? "breaking " + targetName : "破坏" + targetName)
				: (en ? "looting " + targetName : "翻" + targetName);

		String prompt = en
				? "(An intruder " + player.getGameProfile().name() + " is " + actionDesc + " in YOUR " + placeName + "! "
				+ "You are a " + guardianName + ", the guardian of this place! Attack the thief! Cast spells / charge with your weapon! Roar in fury!)"
				: "（入侵者" + player.getGameProfile().name() + "在你的" + placeName + "里" + actionDesc + "！你是" + guardianName + "，这里的守卫！攻击这个小偷！施法 / 挥武器冲上去！怒吼！）";

		respond(player, guard, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：玩家在村庄搞破坏，铁傀儡过来询问/警告 ----------

	/**
	 * 玩家在村庄内搞破坏/翻箱子 → 附近铁傀儡过来询问/警告。
	 * 铁傀儡是村庄的守护者，多次作案或与玩家关系恶劣时会愤怒咆哮并攻击。
	 * @param targetName 被破坏/翻的方块名（容器名/方块名）
	 * @param isContainer 是否是翻容器
	 * @param isJob 是否是工作方块
	 * @param isVillageProp 是否是村庄公共设施
	 * @param offenses 玩家的连续作案计数（共享村民喝止的计数）
	 * @param friendship 铁傀儡对玩家的好感度
	 */
	public static void onVillageGolemInvestigate(net.minecraft.world.entity.Mob golem, ServerPlayer player,
												 String targetName, boolean isContainer, boolean isJob, boolean isVillageProp,
												 int offenses, int friendship) {
		if (!PersonaRegistry.supports(golem)) return;
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);

		// 描述玩家正在做的事
		String action;
		if (isContainer) {
			action = en ? "rummaging through a " + targetName + " in the village" : "在村子里翻一个" + targetName;
		} else if (isJob) {
			action = en ? "smashing a " + targetName + " (a villager's work station)" : "砸了一个" + targetName + "（村民的工作台）";
		} else if (isVillageProp) {
			action = en ? "destroying the village's " + targetName : "破坏了村里的" + targetName;
		} else {
			action = en ? "breaking a " + targetName + " in the village" : "在村子里拆了一个" + targetName;
		}

		String prompt;
		// 好感度<20（不友好）或连续作案≥5次 → 铁傀儡攻击玩家
		boolean willAttack = offenses >= 5 || friendship < 20;
		if (willAttack) {
			// 多次作案 / 好感度低 → 铁傀儡彻底愤怒，攻击玩家
			prompt = en
					? "(Player " + player.getGameProfile().name() + " has been " + action
					+ " REPEATEDLY! As the Iron Golem, guardian of this village, you've had ENOUGH! "
					+ "Roar in fury, swing your mighty iron arm, and ATTACK the vandal to drive them out of the village! "
					+ "Show no mercy—they've been warned enough times!)"
					: "（玩家" + player.getGameProfile().name() + "一直在" + action
					+ "！你是铁傀儡，村庄的守护者，你已经忍无可忍！"
					+ "怒吼一声，挥起巨大的铁拳，攻击这个破坏者，把他赶出村子！"
					+ "不要再手软——他已经被告诫过很多次了！）";
			// 激怒铁傀儡 10 分钟，目标设为玩家
			long gameTime = golem.level().getLevelData().getGameTime();
			MobMindState.provoke(golem, playerId, gameTime + 12000);
			MobMindState.adjustFriendship(golem, playerId, -10);
			// setLastHurtByMob + setTarget 双保险确保原版近战 AI 启动（参考 scoldBedThief 路径）
			golem.setLastHurtByMob(player);
			golem.setTarget(player);
		} else if (offenses >= 2) {
			// 多次作案但未达攻击阈值 → 警告
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is " + action + " AGAIN! "
					+ "As the Iron Golem guardian of this village, walk over with heavy thudding footsteps, "
					+ "loom over them menacingly, and warn them with a deep rumble to STOP breaking things in your village. "
					+ "You won't attack yet, but make it crystal clear you're watching and your patience is running thin.)"
					: "（玩家" + player.getGameProfile().name() + "又在" + action
					+ "！你是村庄守护铁傀儡，迈着沉重的脚步走过去，"
					+ "居高临下地俯视他，用低沉的轰鸣警告他不要再破坏村子。"
					+ "你暂时不动手，但要让他清楚你在盯着他，你的耐心快用完了。）";
			MobMindState.adjustFriendship(golem, playerId, -3);
		} else {
			// 第一次 → 沉重地走过去询问
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is " + action + "! "
					+ "As the Iron Golem guardian of this village, walk over slowly with heavy footsteps, "
					+ "look down at them from your great height, and ask in a low rumble what they think they're doing to the village. "
					+ "You're watching, but not hostile yet—you trust they have a good reason.)"
					: "（玩家" + player.getGameProfile().name() + "正在" + action + "！"
					+ "你是村庄守护铁傀儡，迈着沉重的步伐缓缓走过去，"
					+ "从高大的身躯上低头看着他，用低沉的声音问他到底在搞什么。"
					+ "你在观察，但还没敌意——你愿意相信他有正当理由。）";
			MobMindState.adjustFriendship(golem, playerId, -1);
		}

		respond(player, golem, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(golem, playerId,
				isContainer ? "翻村里的" + targetName : "破坏村里的" + targetName,
				golem.level().getGameTime() + 12000);
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
			MobMindMod.LOGGER.info("[MobMind] Begging for food: {} HP={}/{}({}%) player={}",
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
			if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
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

	// ---------- 入口：村民小声议论玩家 ----------

	/**
	 * 玩家在村庄内走动时，附近两个村民偶尔会凑在一起小声议论玩家。
	 * 不让村民实际发声（避免太吵），只给玩家发一条灰字系统消息，
	 * 内容是根据好感度/记仇记录挑出的"几乎听不清"的只言片语。
	 */
	public static void tryVillagerGossip(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
			java.util.UUID playerId = player.getUUID();
			// 玩家级冷却（60秒），避免频繁触发
		long now = System.currentTimeMillis();
		Long last = LAST_VILLAGER_WHISPER.get(playerId);
		if (last != null && now - last < 60000) continue;

		ServerLevel level = (ServerLevel) player.level();
		// 村庄场景：isInVillage 结构检测为软条件——检测失败时不跳过，
		// 靠下方"附近2+村民"作为 fallback 判定（isInVillage 在某些存档/村庄边缘会失效导致窃窃私语永不触发）
		boolean villageStruct = isInVillage(level, player.blockPosition());

			// 找附近16格内的村民（至少2个）
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box,
					v -> v.isAlive() && PersonaRegistry.supports(v) && withinTalkRange(v, player));
			if (villagers.size() < 2) continue;

			// 玩家不能正贴着村民脸（>4格才触发"小声议论"氛围）
			boolean tooClose = false;
			for (Villager v : villagers) {
				if (v.distanceToSqr(player) < 16.0) { tooClose = true; break; }
			}
			if (tooClose) continue;

			// 概率触发（25%），避免每次检查都议论
		if (player.getRandom().nextInt(100) >= 25) continue;

		LAST_VILLAGER_WHISPER.put(playerId, now);
		if (!villageStruct) MobMindMod.LOGGER.info("[MobMind] Villager gossip via villager fallback (isInVillage failed) for player {}", player.getName().getString());
			Collections.shuffle(villagers);
			Villager v1 = villagers.get(0);
			Villager v2 = villagers.get(1);
			boolean en = isEnglishUi(playerId);

			// 改回灰字系统消息：不让村民实际发声（太吵），只给玩家自己看到两条灰字嘀咕片段
		// 不显示村民名字/职业——既然是"隐约听到"就不应该看清是谁
			long gameTime = level.getGameTime();
			String frag1 = pickWhisperFragment(v1, playerId, gameTime, en);
			String frag2 = pickWhisperFragment(v2, playerId, gameTime, en);
			String prefix = en ? "§7...someone " : "§7……有人";
			String sep = en ? "... " : "……";
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
					prefix + sep + frag1));
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
					prefix + sep + frag2));
			return; // 每轮最多一个玩家被议论
		}
	}

	/** 根据好感度和记仇记录，随机选一条"几乎听不清"的只言片语 */
	private static String pickWhisperFragment(Villager villager, UUID playerId, long gameTime, boolean en) {
		int friendship = MobMindState.friendship(villager, playerId);
		List<MobMindState.Grudge> grudges = MobMindState.getActiveGrudges(villager, playerId, gameTime);

		// 50% 概率提记仇（如果有）
		if (!grudges.isEmpty() && villager.getRandom().nextInt(2) == 0) {
			String grudge = grudges.get(villager.getRandom().nextInt(grudges.size())).description();
			return en ? ("...that guy who " + grudge + "...") : ("……那个" + grudge + "的家伙……");
		}

		String[] fragments;
		if (friendship < 0) {
			fragments = en
					? new String[]{"...that guy again...", "...watch him...", "...don't trust him...", "...he's trouble..."}
					: new String[]{"……那家伙又来了……", "……小心那个人……", "……别信他……", "……他不是好东西……"};
		} else if (friendship < 20) {
			fragments = en
					? new String[]{"...who's that stranger...", "...what does he want...", "...don't know him..."}
					: new String[]{"……那个陌生人是谁……", "……他来干嘛……", "……不认识那个人……"};
		} else if (friendship < 60) {
			fragments = en
					? new String[]{"...he's okay I guess...", "...seen him before...", "...not so bad..."}
					: new String[]{"……他还行吧……", "……来过几次了……", "……还算面熟……"};
		} else if (friendship < 80) {
			fragments = en
					? new String[]{"...he's a good one...", "...helped us before...", "...nice fellow..."}
					: new String[]{"……他人不错……", "……上次帮过我们……", "……挺好的家伙……"};
		} else {
			fragments = en
					? new String[]{"...when's he coming back...", "...such a good friend...", "...we owe him..."}
					: new String[]{"……他啥时候再来……", "……真是个好人……", "……我们欠他的……"};
		}
		return fragments[villager.getRandom().nextInt(fragments.length)];
	}

	/** 检查位置是否在自然生成的村庄结构内 */
	private static boolean isInVillage(ServerLevel level, BlockPos pos) {
		try {
			var structureManager = level.structureManager();
			var structuresMap = structureManager.getAllStructuresAt(pos);
			if (structuresMap == null || structuresMap.isEmpty()) return false;
			var registry = level.registryAccess()
					.lookup(net.minecraft.core.registries.Registries.STRUCTURE).orElse(null);
			if (registry == null) return false;
			for (Object structureObj : structuresMap.keySet()) {
				var key = registry.getKey((net.minecraft.world.level.levelgen.structure.Structure) structureObj);
				if (key != null && key.toString().toLowerCase().contains("village")) return true;
			}
		} catch (Exception ignored) {}
		return false;
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

	/** 检查村民是否是农民职业 */
	private static boolean isFarmer(Mob mob) {
		if (mob instanceof net.minecraft.world.entity.npc.villager.Villager v) {
			var profHolder = v.getVillagerData().profession();
			return profHolder.unwrapKey()
					.map(k -> k.identifier().getPath().equals("farmer"))
					.orElse(false);
		}
		return false;
	}

	/** 检查村民是否是傻子（Nitwit） */
	private static boolean isNitwit(Mob mob) {
		if (mob instanceof net.minecraft.world.entity.npc.villager.Villager v) {
			var profHolder = v.getVillagerData().profession();
			return profHolder.unwrapKey()
					.map(k -> k.identifier().getPath().equals("nitwit"))
					.orElse(false);
		}
		return false;
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

	/** 玩家在你身上站着/跳，把你吵醒了——抱怨 */
	public static void scoldSleepDisturbance(ServerPlayer player, Mob villager) {
		java.util.UUID playerId = player.getUUID();
		respond(player, villager, t(
				"（你正睡得好好的，这个家伙居然站在你身上/在你身上跳来跳去把你吵醒了！你很不爽，迷迷糊糊地抱怨他，叫他别踩你、从你身上下去，语气要带着刚睡醒的烦躁和不满）",
				"(You were sleeping soundly when this guy started standing on you/jumping on you and woke you up! You're annoyed, groggily complain at him, tell him to get off you, stop stepping on you. Sound half-asleep and irritated)",
				playerId), false);
	}

	// ---------- 入口：村民房屋守卫 ----------

	/** 玩家破坏了村民家附近的方块或容器 */
	public static void scoldHousebreaker(ServerPlayer player, Mob villager, boolean isContainer, boolean isHouseBlock, String blockName, int offenses, int friendship) {
		scoldHousebreaker(player, villager, isContainer, isHouseBlock, false, blockName, offenses, friendship);
	}

	/** 玩家破坏了村民家附近的方块或容器（含农作物标记） */
	public static void scoldHousebreaker(ServerPlayer player, Mob villager, boolean isContainer, boolean isHouseBlock, boolean isCrop, String blockName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;

		if (isCrop) {
			// 农作物被摘：村民把庄稼当作自己的劳动成果
			if (offenses >= 5 || friendship < 0) {
				prompt = en
					? "(This player HARVESTED your " + blockName + "! That's YOUR hard-grown crop! They keep stealing from your farm! You are furious, yell at them for stealing your food, threaten to report them)"
					: "（这个玩家摘了你的" + blockName + "！那是你辛苦种出来的庄稼！他老来偷你的菜！你勃然大怒，骂他偷你的粮食，威胁他说要报官）";
			} else if (offenses >= 2) {
				prompt = en
					? "(This player just picked your " + blockName + "! That's your crop! Scold them sternly, tell them they can't just take what others grew, sound quite annoyed)"
					: "（这个玩家摘了你的" + blockName + "！那是你的庄稼！严厉地斥责他，告诉他不能随便摘别人种的东西，语气很不高兴）";
			} else {
				prompt = en
					? "(This player just harvested your " + blockName + "! You grew that! Rush over upset, ask them what they think they're doing taking your crop, tell them to keep their hands off your farm)"
					: "（这个玩家摘了你的" + blockName + "！那是你种的！赶紧过去生气地质问他，叫他不要动你田里的庄稼）";
			}
		} else if (isContainer) {
			if (offenses >= 5 || friendship < 0) {
				// 多次犯案或好感度已为负：怒喝
				prompt = en
					? "(This player BROKE your " + blockName + " in your house! They've been vandalizing your home repeatedly! You are furious, yell at them angrily, threaten them, demand they leave immediately)"
					: "（这个玩家拆了你家里的" + blockName + "！他已经多次在你家搞破坏了！你怒不可遏，愤怒地喝止他，威胁他，喝令他立刻滚出你的家）";
			} else if (offenses >= 2) {
				prompt = en
					? "(This player broke your " + blockName + "! This isn't the first time. Scold them sternly, tell them to stop destroying your property, sound annoyed and warning)"
					: "（这个玩家拆了你的" + blockName + "！这不是第一次了。严厉地斥责他，叫他不要再破坏你的东西，语气要生气并带警告）";
			} else {
				prompt = en
					? "(This player just broke a " + blockName + " near your house! It's your property! Rush over to stop them, sound upset and tell them not to break things in your home)"
					: "（这个玩家在你家附近拆了" + blockName + "！那是你的东西！赶紧过去阻止他，表达你的不满，叫他不要在你家里乱拆东西）";
			}
		} else if (isHouseBlock) {
			if (offenses >= 5 || friendship < 0) {
				prompt = en
					? "(This player is DESTROYING YOUR HOUSE! They broke a " + blockName + "! You're enraged! Yell at them to stop, threaten them, chase them away)"
					: "（这个玩家在拆你的房子！他敲掉了你的" + blockName + "！你愤怒到了极点！厉声喝止他，威胁他，把他赶走）";
			} else if (offenses >= 2) {
				prompt = en
					? "(This player broke a " + blockName + " that's part of your house! Scold them angrily, tell them to stop vandalizing, demand they fix it or leave)"
					: "（这个玩家拆了你房子的" + blockName + "！生气地斥责他，叫他不要搞破坏，要求他修好或者离开）";
			} else {
				prompt = en
					? "(This player broke a " + blockName + " in your house! You're surprised and upset. Rush over, ask them what they think they're doing, tell them not to break your house)"
					: "（这个玩家拆了你家里的" + blockName + "！你又惊讶又生气。赶紧过去，质问他在干什么，叫他不要拆你的房子）";
			}
		} else {
			prompt = en
				? "(This player broke something near your home. Go check it out and tell them to be more careful)"
				: "（这个玩家在你家附近拆了什么东西。过去看看情况，叫他小心点）";
		}

		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId,
				(isCrop ? "偷摘我的" + blockName : isContainer ? "破坏我的" + blockName : "破坏我房子的" + blockName),
				villager.level().getGameTime() + 12000);
	}

	/** 玩家破坏了村民的工作站点（堆肥桶/高炉/砂轮等）→ 村民生气喝止 */
	public static void scoldJobBlockDestroyer(ServerPlayer player, Mob villager, String jobBlockName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		if (offenses >= 5 || friendship < 0) {
			prompt = en
				? "(This player SMASHED your " + jobBlockName + "! That's YOUR work station! You've lost your job because of them! You're furious, yell at them for destroying your livelihood, threaten them)"
				: "（这个玩家砸了你的" + jobBlockName + "！那是你的工作台！因为他你失业了！你怒不可遏，骂他毁了你吃饭的家伙，威胁他）";
		} else if (offenses >= 2) {
			prompt = en
				? "(This player destroyed your " + jobBlockName + "! You need that to work! Scold them sternly, tell them they can't just break people's work stations, sound quite angry)"
				: "（这个玩家砸了你的" + jobBlockName + "！你靠它干活呢！严厉地斥责他，告诉他不能随便砸别人的工作台，语气很生气）";
		} else {
			prompt = en
				? "(This player just broke your " + jobBlockName + "! You're a villager and that was your work station! Rush over upset, ask them what they're doing, tell them to fix it or compensate you)"
				: "（这个玩家砸了你的" + jobBlockName + "！你是村民，那是你的工作台！赶紧过去生气地质问他，叫他修好或者赔偿你）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId, "破坏我的工作方块" + jobBlockName,
				villager.level().getGameTime() + 12000);
	}

	/** 玩家破坏了别人的工作方块（非自己的职业方块）→ 村民过来指责"那是别人的饭碗" */
	public static void scoldOthersJobBlockDestroyer(ServerPlayer player, Mob villager, String jobBlockName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		if (offenses >= 5 || friendship < 0) {
			prompt = en
				? "(This player SMASHED a " + jobBlockName + "! That's ANOTHER villager's work station—you don't know whose exactly, but someone in this village just lost their job because of them! You're furious, yell at them for wrecking a neighbor's livelihood, threaten to call the iron golems)"
				: "（这个玩家砸了一个" + jobBlockName + "！那是别的村民的工作台——你不知道具体是谁的，但村里有人因为他失业了！你怒不可遏，骂他毁邻居的饭碗，威胁要叫铁傀儡）";
		} else if (offenses >= 2) {
			prompt = en
				? "(This player destroyed a " + jobBlockName + "! That's not yours, but it belongs to SOMEONE in this village! Scold them sternly, tell them they can't just break other people's work stations in the village, sound quite angry)"
				: "（这个玩家砸了一个" + jobBlockName + "！那不是你的，但那是村里别人的！严厉地斥责他，告诉他不能随便砸村子里别人的工作台，语气很生气）";
		} else {
			prompt = en
				? "(This player just broke a " + jobBlockName + "! That's a work station—not YOURS, but it belongs to another villager! Rush over upset, ask them what they're doing, tell them they're destroying someone else's livelihood, not just breaking blocks)"
				: "（这个玩家砸了一个" + jobBlockName + "！那是工作方块——不是你的，是别的村民的！赶紧过去生气地质问他，告诉他他在毁别人的饭碗，不是在拆方块那么简单）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId, "破坏村里的" + jobBlockName,
				villager.level().getGameTime() + 12000);
	}

	/** 玩家使用村民的工作方块（右键点击，非破坏）→ 村民过来询问 */
	public static void scoldJobBlockUser(ServerPlayer player, Mob villager, String jobBlockName, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		if (en) {
			prompt = "(Player " + player.getGameProfile().name() + " is using your " + jobBlockName
					+ "! That's YOUR work station! Rush over, ask them what they think they're doing messing with your tools, "
					+ "tell them to keep their hands off your work station. Not too hostile if you're on good terms, "
					+ "but make it clear this is YOUR workspace.)";
		} else {
			prompt = "（玩家" + player.getGameProfile().name() + "在用你的" + jobBlockName
					+ "！那是你的工作台！赶紧过去，质问他在动你的工具干什么，"
					+ "叫他别碰你的工作台。如果关系好可以不那么敌对，但要明确这是你的工作地点。）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
	}

	/** 玩家使用别人的工作方块（非自己的职业方块）→ 村民过来"那是别人的工作台，你动它干嘛" */
	public static void scoldOthersJobBlockUser(ServerPlayer player, Mob villager, String jobBlockName, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		if (en) {
			prompt = "(Player " + player.getGameProfile().name() + " is using a " + jobBlockName
					+ "! That's not YOUR work station—it belongs to another villager! Walk over, ask them what they're doing "
					+ "with someone else's tools. Tell them they shouldn't mess with another villager's work station. "
					+ "Not as protective as if it were yours, but still disapproving—villagers look out for each other.)";
		} else {
			prompt = "（玩家" + player.getGameProfile().name() + "在用一个" + jobBlockName
					+ "！那不是你的工作台——是别的村民的！走过去，问他在动别人的工具干嘛。"
					+ "告诉他不该乱动别的村民的工作台。不像自己的东西那样护着，但还是不赞同——村民之间互相看着呢。）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
	}

	/** 玩家破坏了村庄公共财产（干草捆/道路/路灯/水井等）→ 村民生气喝止 */
	public static void scoldVillageVandal(ServerPlayer player, Mob villager, String blockName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		// 根据方块类型定制提示词
		String lower = blockName.toLowerCase();
		String thing;
		if (lower.contains("hay") || lower.contains("干草")) {
			thing = en ? "hay bale, the village's WINTER FOOD SUPPLY" : "干草捆，那是村子过冬的粮食储备";
		} else if (lower.contains("bell") || lower.contains("钟")) {
			thing = en ? "the village BELL, it warns us of danger and calls us to safety" : "村庄的钟，那是我们的警报器，有危险时靠它通知大家";
		} else if (lower.contains("fence") && !lower.contains("gate") || lower.contains("栅栏") && !lower.contains("门")) {
			thing = en ? "the village fence, it keeps our animals safe" : "村庄的栅栏，保护我们的牲畜不跑丢";
		} else if (lower.contains("fence_gate") || lower.contains("栅栏门")) {
			thing = en ? "the village fence gate, part of our animal pens" : "村庄的栅栏门，是我们牲畜圈的一部分";
		} else if (lower.contains("torch") || lower.contains("火把")) {
			thing = en ? "the village torch, it lights our streets at night" : "村庄的火把，照亮我们夜间的路";
		} else if (lower.contains("campfire") || lower.contains("营火")) {
			thing = en ? "the village campfire, where we gather and cook" : "村庄的营火，我们聚在一起做饭取暖的地方";
		} else if (lower.contains("cauldron") || lower.contains("炼药锅")) {
			thing = en ? "the village cauldron, shared by all" : "村庄的炼药锅，大家共用的";
		} else if (lower.contains("water") && !lower.contains("cauldron") || lower.contains("水") && !lower.contains("锅")) {
			thing = en ? "the village well water, our water source—don't fill it in!" : "村庄水井的水，那是我们的水源——别填水井！";
		} else if (lower.contains("gravel") || lower.contains("砾石") || lower.contains("dirt_path") || lower.contains("grass_path") || lower.contains("草径")) {
			thing = en ? "the village path, we walk on it every day" : "村庄的路，我们每天走的路";
		} else if (lower.contains("wall") || lower.contains("墙") && !lower.contains("家")) {
			thing = en ? "the village wall, it protects our homes" : "村庄的围墙，保护我们房子的";
		} else if (lower.contains("bed") || lower.contains("床")) {
			thing = en ? "a villager's bed" : "村民的床";
		} else {
			thing = en ? blockName + " that belongs to the village" : blockName + "，这是村子的公共设施";
		}
		if (offenses >= 5 || friendship < 0) {
			prompt = en
				? "(This player is DESTROYING THE VILLAGE! They broke " + thing + "! You're furious, yell at them for vandalizing the village, threaten to call the iron golem, demand they leave NOW!)"
				: "（这个玩家在拆村子！他破坏了" + thing + "！你怒不可遏，骂他搞破坏，威胁他说再不走就叫铁傀儡来收拾他！）";
		} else if (offenses >= 2) {
			prompt = en
				? "(This player destroyed " + thing + " again! That's village property! Scold them angrily, tell them they have no right to break things in the village, demand they stop)"
				: "（这个玩家又在破坏" + thing + "！这是村里的东西！生气地斥责他，告诉他无权破坏村子的东西，叫他住手）";
		} else {
			prompt = en
				? "(This player just broke " + thing + " in the village! Rush over upset, ask them what they think they're doing, tell them not to damage the village)"
				: "（这个玩家破坏了村里的" + thing + "！赶紧过去生气地质问他，叫他不要破坏村子的东西）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId, "破坏村庄公共设施" + blockName,
				villager.level().getGameTime() + 12000);
	}

	/** 玩家挖掉了村庄的钟——这是最严重的挑衅！ */
	public static void scoldVillageBellDestroyer(ServerPlayer player, Mob villager, String blockName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;
		String pName = player.getGameProfile().name();

		if (offenses >= 3 || friendship < 10) {
			// 屡教不改或好感度极低——暴怒！
			prompt = en
				? "(THEY BROKE THE BELL!! THE VILLAGE BELL!! Player " + pName + " just DESTROYED our only warning bell! "
				+ "You're absolutely LIVID—this is how we warn everyone when raiders come, when there's danger! "
				+ "Scream at them in rage! Call for the iron golem! Demand they get out of the village RIGHT NOW before something terrible happens! One furious shout)"
				: "（钟被挖了！！！村庄的钟啊！！玩家" + pName + "把我们唯一的警报钟给拆了！"
				+ "你怒不可遏！那是我们遇到袭击、遇到危险时通知所有人的警报啊！"
				+ "冲他怒吼！快去叫铁傀儡！叫他立刻滚出村子！再不走就不客气了！一句暴怒的喊骂）";
		} else {
			// 第一次/第二次挖钟——极度震惊和愤怒
			prompt = en
				? "(THE BELL! They broke THE VILLAGE BELL! Player " + pName + " just destroyed our meeting bell—our alarm for raids and danger! "
				+ "You're SHOCKED and FURIOUS! Rush over yelling, demand to know what they think they're doing! "
				+ "The bell is THE most important thing in the village—without it we can't warn each other! Scold them fiercely!)"
				: "（钟！他们把村庄的钟挖了！玩家" + pName + "把我们的集会钟——也就是遇袭时报警的钟——给毁了！"
				+ "你震惊极了，愤怒极了！冲过去大喊，质问他到底在干什么！"
				+ "钟是村子里最重要的东西！没有它我们有危险怎么互相通知？严厉地骂他！）";
		}
		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId, "挖掉了村庄的钟！",
				villager.level().getGameTime() + 24000); // 记住24000tick（20分钟）
	}

	/** 玩家在村民家附近翻箱子/开容器 */
	public static void scoldContainerSnooper(ServerPlayer player, Mob villager, String containerName, int offenses, int friendship) {
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		String prompt;

		if (offenses >= 5 || friendship < 0) {
			prompt = en
				? "(This player is SNOOPING in your " + containerName + " AGAIN! They keep stealing from you! You're furious, yell at them to get their hands out, threaten to report them if they don't leave NOW)"
				: "（这个玩家又在翻你的" + containerName + "了！他老想偷你东西！你勃然大怒，喝令他住手，威胁他再不离开就叫人来）";
		} else if (offenses >= 2) {
			prompt = en
				? "(This player is rummaging through your " + containerName + " again! Scold them, tell them that's YOUR stuff and they shouldn't be looking through your things without permission, sound quite annoyed)"
				: "（这个玩家又在乱翻你的" + containerName + "！斥责他，告诉他那是你的东西，不许随便翻别人的东西，语气很不高兴）";
		} else {
			prompt = en
				? "(This player just opened your " + containerName + " without asking! Hurry over to them, sound surprised and a bit upset, ask them what they're looking for, tell them not to go through other people's belongings)"
				: "（这个玩家没问过你就打开了你的" + containerName + "！赶紧走过去，语气有点惊讶和不满，问他在找什么，告诉他不要随便翻别人的东西）";
		}

		respond(player, villager, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(villager, playerId, "偷翻我的" + containerName,
				villager.level().getGameTime() + 12000);
	}

	// ---------- 入口：玩家踩踏农田 ----------

	private static final Map<UUID, Long> LAST_TRAMPLE_REACT = new ConcurrentHashMap<>();

	/** 玩家踩踏农田 → 附近农民不满 */
	public static void onFarmlandTrampled(ServerPlayer player, net.minecraft.server.level.ServerLevel level,
										  BlockPos pos, boolean hadCrop) {
		// 玩家自己锄的田 / 自己种的作物 → 不触发
		if (com.mobmind.behavior.HouseGuard.isPlayerPlaced(pos)) return; // 农田是玩家锄的
		if (hadCrop && com.mobmind.behavior.HouseGuard.isPlayerPlaced(pos.above())) return; // 作物是玩家种的
		long now = System.currentTimeMillis();
		Long last = LAST_TRAMPLE_REACT.get(player.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_TRAMPLE_REACT.put(player.getUUID(), now);
		var villagers = level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class,
				new net.minecraft.world.phys.AABB(pos).inflate(16.0));
		if (villagers.isEmpty()) return;
		java.util.UUID playerId = player.getUUID();
		boolean en = isEnglishUi(playerId);
		for (var v : villagers) {
			if (!PersonaRegistry.supports(v)) continue;
			// 只有农民才管农田
			if (!isFarmer(v)) continue;
			String prompt;
			if (hadCrop) {
				prompt = en
					? "(Player " + player.getGameProfile().name() + " just JUMPED on your farmland and TRAMPLED YOUR CROPS! "
					+ "You watched your hard work get destroyed under their boots! Rush over FURIOUS, scream at them for trampling the field, "
					+ "demand they pay for the damage!)"
					: "（玩家" + player.getGameProfile().name() + "在你的农田上乱跳，踩坏了你的庄稼！"
					+ "你亲眼看着辛辛苦苦种的庄稼被他的靴子糟蹋了！冲过去暴怒，骂他踩坏田地，要他赔偿损失！）";
			} else {
				prompt = en
					? "(Player " + player.getGameProfile().name() + " is jumping on your farmland! "
					+ "Tell them to get off the soil, it ruins the farmland! Sound annoyed.)"
					: "（玩家" + player.getGameProfile().name() + "在你的农田上跳来跳去！"
					+ "叫他别踩田地，会把地踩坏的！语气不满。）";
			}
			respond(player, v, t(prompt, prompt, playerId), false);
			MobMindState.adjustFriendship(v, playerId, hadCrop ? -5 : -2);
			if (hadCrop) {
				MobMindState.recordGrudge(v, playerId, "踩坏我的庄稼",
						v.level().getGameTime() + 12000);
			}
		}
	}

	// ---------- 入口：玩家杀害村庄牲畜 / 剪羊毛 ----------

	private static final java.util.Set<String> VILLAGE_LIVESTOCK = java.util.Set.of(
			"cow", "pig", "sheep", "chicken", "rabbit", "mooshroom",
			"horse", "donkey", "mule", "camel",
			"cat", "wolf", "parrot" // 村庄宠物
	);

	/** 判断是否是村庄宠物（猫、狼、鹦鹉） */
	private static boolean isVillagePet(String entityId) {
		return entityId.equals("cat") || entityId.equals("wolf") || entityId.equals("parrot");
	}

	/** 玩家手持吸引物品（如红色蘑菇吸引僵尸马、诡异菌吸引炽足兽）→ 生物被吸引并说话（30秒冷却/生物） */
	public static void tryTemptReact(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
			var stack = player.getMainHandItem();
			if (stack.isEmpty()) continue;
			java.util.UUID playerId = player.getUUID();
			ServerLevel level = (ServerLevel) player.level();
			// 找附近8格内的模组支持生物
			AABB box = player.getBoundingBox().inflate(8.0);
			List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m) && withinTalkRange(m, player));
			if (mobs.isEmpty()) continue;
			long now = System.currentTimeMillis();
			for (Mob mob : mobs) {
				String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
				var temptSet = TEMPT_ITEMS.get(entityId);
				if (temptSet == null || !temptSet.contains(stack.getItem())) continue;
				// 30秒冷却
				Long last = LAST_TEMPT_REACT.get(mob.getUUID());
				if (last != null && now - last < 30000) continue;
				LAST_TEMPT_REACT.put(mob.getUUID(), now);
				String itemName = stack.getHoverName().getString();
				respond(player, mob, isEnglishUi(playerId)
						? "(Player " + player.getGameProfile().name() + " is holding " + itemName
						+ "—you're drawn to it, sniffing the air, wanting to follow them. React in your own style, one short line)"
						: "（玩家" + player.getGameProfile().name() + "手上拿着" + itemName
						+ "——你被它吸引，忍不住想凑过去闻闻、跟着他走。用你自己的风格反应一句短话）", false);
				break; // 每次最多一只生物反应
			}
		}
	}

	/** 玩家在村庄农田里种植作物 → 附近农民来感谢（好感度+2，玩家级120秒冷却） */
	public static void onPlayerPlantCrop(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (!isInVillage(level, pos)) return;
		if (com.mobmind.behavior.HouseGuard.isPlayerPlaced(pos.below())) return;
		long now = System.currentTimeMillis();
		java.util.UUID playerId = player.getUUID();
		Long last = LAST_PLANT_THANKS.get(playerId);
		if (last != null && now - last < 120000) return;
		AABB box = new AABB(pos).inflate(16.0);
		List<Villager> farmers = level.getEntitiesOfClass(Villager.class, box,
				v -> v.isAlive() && PersonaRegistry.supports(v) && isFarmer(v) && withinTalkRange(v, player));
		if (farmers.isEmpty()) return;

		LAST_PLANT_THANKS.put(playerId, now);
		Villager farmer = farmers.get(player.getRandom().nextInt(farmers.size()));
		MobMindState.adjustFriendship(farmer, playerId, 2);
		respond(player, farmer, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " just planted crops in the village farm! You're a farmer and you're touched—someone actually helping with your work! Thank them warmly in your own style, one short line)"
				: "（玩家" + player.getGameProfile().name() + "刚在村子农田里种了庄稼！你是农民，看到有人帮你干活很感动——用你自己的风格真诚地谢谢他，只说一句短话）", false);
	}

	/** 玩家在村庄农田里用骨粉催熟作物 → 附近农民来感谢（好感度+1，玩家级60秒冷却） */
	public static void onPlayerBoneMealCrop(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (!isInVillage(level, pos)) return;
		// 被催熟的方块要是作物（检查是否是CropBlock或有AGE属性）
		BlockState state = level.getBlockState(pos);
		boolean isCrop = state.getBlock() instanceof net.minecraft.world.level.block.CropBlock
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_1)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_2)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_3)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_4)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_5)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_15)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_25);
		if (!isCrop) return;
		// 玩家自己锄的田：如果是在自己田里用骨粉就不算
		// 但是骨粉催熟自己种的作物也应该被感谢吗？用户说"他们种的庄稼"，所以不判断玩家放置
		long now = System.currentTimeMillis();
		java.util.UUID playerId = player.getUUID();
		// 骨粉冷却和种植冷却共享，稍短一点（60秒）
		Long last = LAST_PLANT_THANKS.get(playerId);
		if (last != null && now - last < 60000) return;
		AABB box = new AABB(pos).inflate(16.0);
		List<Villager> farmers = level.getEntitiesOfClass(Villager.class, box,
				v -> v.isAlive() && PersonaRegistry.supports(v) && isFarmer(v) && withinTalkRange(v, player));
		if (farmers.isEmpty()) return;

		LAST_PLANT_THANKS.put(playerId, now);
		Villager farmer = farmers.get(player.getRandom().nextInt(farmers.size()));
		MobMindState.adjustFriendship(farmer, playerId, 1);
		respond(player, farmer, isEnglishUi(playerId)
				? "(Player " + player.getGameProfile().name() + " just used bone meal to speed up crops in the village farm! The crops grew faster thanks to them—you're pleased and grateful. Thank them warmly, one short line)"
				: "（玩家" + player.getGameProfile().name() + "刚用骨粉帮村里的庄稼催熟！作物长得更快了，你很高兴，感谢他。用你自己的风格真诚地道谢，只说一句短话）", false);
	}

	/** 玩家在村庄附近杀害牲畜 → 附近村民愤怒喝止 */
	public static void onLivestockKilledByPlayer(net.minecraft.world.entity.animal.Animal animal, ServerPlayer player,
												 net.minecraft.server.level.ServerLevel level) {
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
		if (!VILLAGE_LIVESTOCK.contains(entityId)) return;
		// 被敌对怪物骑乘的动物（鸡骑士的鸡等）不是村民的牲畜，不触发
		if (isMountedByHostileMonster(animal)) return;
		// 找附近16格内的村民
		var villagers = level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class,
				animal.getBoundingBox().inflate(16.0));
		if (villagers.isEmpty()) return;
		String animalName = translateMobName(entityId);
		for (var v : villagers) {
			if (!PersonaRegistry.supports(v)) continue;
			java.util.UUID playerId = player.getUUID();
			boolean en = isEnglishUi(playerId);
			int friendship = MobMindState.friendship(v, playerId);
			String prompt;
			if (en) {
				prompt = "(Player " + player.getGameProfile().name() + " just KILLED a " + animalName
						+ "! That's village livestock—we raise them ourselves and let them roam free around the village! You're horrified and furious—scream at them for slaughtering the animals you raised, demand they leave the village at once!)";
			} else {
				prompt = "（玩家" + player.getGameProfile().name() + "杀了一头" + animalName
						+ "！那是村里的牲畜——是我们亲手养大、散养在村子里的！你既惊恐又愤怒——尖叫着骂他滥杀你养大的动物，喝令他立刻离开村子！）";
			}
			respond(player, v, t(prompt, prompt, playerId), false);
			MobMindState.adjustFriendship(v, playerId, -8);
			MobMindState.recordGrudge(v, playerId, "杀害村庄的" + animalName,
					v.level().getGameTime() + 12000);
		}
	}

	/** 玩家在村庄附近剪羊毛 → 附近村民不满 */
	private static final Map<UUID, Long> LAST_SHEAR_REACT = new ConcurrentHashMap<>();

	public static void onSheepShearedByPlayer(net.minecraft.world.entity.animal.sheep.Sheep sheep, ServerPlayer player,
											 net.minecraft.server.level.ServerLevel level) {
		java.util.UUID sheepId = sheep.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_SHEAR_REACT.get(sheepId);
		if (last != null && now - last < 30000) return; // 30秒冷却
		LAST_SHEAR_REACT.put(sheepId, now);
		// 找附近16格内的村民
		var villagers = level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class,
				sheep.getBoundingBox().inflate(16.0));
		if (villagers.isEmpty()) return;
		for (var v : villagers) {
			if (!PersonaRegistry.supports(v)) continue;
			java.util.UUID playerId = player.getUUID();
			boolean en = isEnglishUi(playerId);
			String prompt;
			if (en) {
				prompt = "(Player " + player.getGameProfile().name() + " just sheared one of the village sheep without asking! "
						+ "That wool belongs to the village! Scold them for taking what isn't theirs, tell them they should have asked first.)";
			} else {
				prompt = "（玩家" + player.getGameProfile().name() + "没经过同意就剪了村里的羊毛！"
						+ "那些羊毛是村子的！斥责他拿别人的东西，告诉他应该先问过才行。）";
			}
			respond(player, v, t(prompt, prompt, playerId), false);
			MobMindState.adjustFriendship(v, playerId, -3);
		}
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
					MobMindMod.LOGGER.info("[MobMind] API not configured, {} conversation using offline fallback reply", player.getGameProfile().name());
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
				MobMindMod.LOGGER.info("[MobMind] AI response {}ms, raw reply: {}", aiMs,
					raw.length() > 500 ? raw.substring(0, 500) : raw);
				ParsedReply reply = parse(raw, persona);
				server.execute(() -> finish(server, player, mob, persona, reply, applyActions));
			} catch (Exception e) {
				String errMsg = friendlyErrorMessage(e, cfg);
				String logMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
				MobMindMod.LOGGER.warn("[MobMind] AI call failed: {}", logMsg, e);
				server.execute(() -> {
					// 始终向玩家显示错误提示（10秒冷却防刷屏）
					long now = System.currentTimeMillis();
					Long lastErr = LAST_ERROR_NOTIFY.get(player.getUUID());
					if (lastErr == null || now - lastErr >= ERROR_NOTIFY_COOLDOWN) {
						LAST_ERROR_NOTIFY.put(player.getUUID(), now);
						boolean english = isEnglishUi(player.getUUID());
						// 直接发送红色消息到聊天栏，不使用灰色前缀
						String fullMsg = english
								? "§c[MobMind] AI error: " + errMsg
								: "§c[生物心智] AI 调用失败：" + errMsg;
						player.sendSystemMessage(net.minecraft.network.chat.Component.literal(fullMsg));
						MobMindMod.LOGGER.info("[MobMind] Error notification sent to player {}", player.getGameProfile().name());
					}
					if (MobMindConfig.get().offlineFallback) {
						finish(server, player, mob, persona, offlineReply(persona, mob, player, userText), applyActions);
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
		if (bargain == null && applyActions && mob instanceof net.minecraft.world.item.trading.Merchant av) { // 模型漏输出 bargain 字段时兜底
			bargain = extractBargainFromText(lastUserText(mob.getUUID(), player.getUUID()), reply.say(), av, player.getUUID());
		}
		if (bargain != null && mob instanceof net.minecraft.world.item.trading.Merchant merchant) {
			MobMindMod.LOGGER.info("[MobMind] Bargain: {} on {} item \"{}\" agree={}",
				player.getGameProfile().name(), mob.getType().getDescription().getString(),
				bargain.item(), bargain.agree());
			BarterActions.applyBargain(merchant, player, persona, bargain.item(), bargain.agree());
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
					MobMindMod.LOGGER.info("[MobMind] Recorded promise: {} agreed to give {}: {} (free={})",
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
		// 怨恨记忆：即使攻击目标被清除，AI仍记得玩家最近做了什么惹怒它的事
		long gameTime = level.getGameTime();
		var grudges = MobMindState.getActiveGrudges(mob, player.getUUID(), gameTime);
		StringBuilder grudgeText = new StringBuilder();
		if (!grudges.isEmpty()) {
			grudgeText.append(english ? "\n- You still hold a grudge against this player for: " : "\n- 你对这名玩家还记恨在心，因为：");
			for (var g : grudges) {
				grudgeText.append("\n  • ").append(g.description());
			}
			grudgeText.append(english ? "\n  Stay angry and cold toward them. Don't forgive easily." : "\n  保持愤怒和冷淡，别轻易原谅。");
		}
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
		tauntTrait += grudgeText.toString();

		// 傻子村民：说话简单、有点呆（注意：要简单但有条理，不能语无伦次）
		if (isNitwit(mob)) {
			tauntTrait += english
				? "\n- You are the village NITWIT (genuinely slow-witted, not pretending). Speak this way:\n"
				+ "  1. VERY short sentences. Simple words. Talk slowly with long pauses like 'Uhh...', 'Um...', 'I... I think...', 'wait...'.\n"
				+ "  2. React SLOWLY—sometimes you blank out and stare for a moment before answering.\n"
				+ "  3. Often say silly things, go off-topic, or answer the wrong question. That's normal for you.\n"
				+ "  4. Kind-hearted but NOT clever. NEVER sound smart, witty, quick, or articulate. You are genuinely dim.\n"
				+ "  5. For ANY question that's even slightly complex, just say 'I dunno...', 'Too hard for me...', 'Go ask someone else...'. Don't even try.\n"
				+ "  6. Sometimes mix up words or lose your train of thought mid-sentence.\n"
				+ "IMPORTANT: You must sound genuinely slow and simple—NOT like a normal person dumbing down. "
				+ "Keep replies to 1-2 short sentences. Do NOT be coherent or quick-witted."
				: "\n- 你是村里的傻子村民（Nitwit，脑子真的不太好使，是真的呆，不是装的）。按这些规则说话：\n"
				+ "  1. 句子要很短很简单，慢慢说，经常停顿发呆（'呃...'、'嗯...'、'那个...'、'我想想哦...'）\n"
				+ "  2. 反应慢半拍，有时候愣一下、发一会儿呆才回过神来\n"
				+ "  3. 经常说傻话或跑题，答非所问也是正常的\n"
				+ "  4. 心地善良但绝不机灵，绝对不要表现得聪明、机智、口齿伶俐。你是真的憨\n"
				+ "  5. 别人问稍微复杂点的问题，直接说'我不懂...'、'太难了...'、'你去问别人吧...'，别费劲想\n"
				+ "  6. 说话偶尔颠三倒四，想半天才能说完一句\n"
				+ "重要：你必须表现得很呆很憨，不能像正常人一样对答如流。回复只要1-2句短话就行。"
				+ "简单是真的简单，不是假装简单。";
		}

		// 幼年生物：天真活泼（称呼多样化，不只用"大个子"）
		if (mob.isBaby()) {
			String babyEntityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(mob.getType()).getPath();
			// 幼年骷髅马专属风格
			if (babyEntityId.equals("skeleton_horse")) {
				tauntTrait += english
					? "\n- You are a BABY Skeleton Horse! A tiny clattering pile of bones—a colt made of ivory skeleton. "
					+ "You're skittish, curious, playful. You make little clickety-clack sounds when you walk. "
					+ "You don't really get what 'undead' means—you think you're just a normal little horse. "
					+ "Bones rattling = excitement or happiness. "
					+ "Vary how you address the player: 'big person', 'tall one', 'mister', 'miss', 'big friend', 'sir', 'madam'—don't repeat the same one. "
					+ "Speak in short excited sentences."
					: "\n- 你是一匹小骷髅马！一堆咔嗒作响的小骨头——一匹象牙白的小马驹。"
					+ "你胆小、好奇、爱玩。走路时发出细细的咔嗒咔嗒声。"
					+ "你不太懂'亡灵'是什么——你只觉得自己是匹普通的小马。"
					+ "骨头咔嗒响 = 兴奋或开心。"
					+ "称呼玩家要换着来：'大个子'、'大人'、'哥哥'、'姐姐'、'高高的那个'、'大朋友'、'先生'、'女士'——别老用同一个。"
					+ "用短促兴奋的句子说话。";
			} else {
				// 其他幼年生物的通用提示词
				tauntTrait += english
					? "\n- You are a BABY. You're young, small, curious and energetic. "
					+ "Speak in a childlike way—short sentences, lots of emotion, easily excited or scared. "
					+ "You look up to adults. Vary how you address the player: 'big person', 'tall one', 'mister', 'miss', 'big friend', 'sir', 'madam'—don't always use the same word."
					: "\n- 你是一个幼崽。你年幼、渺小、好奇且精力旺盛。"
					+ "用孩子气的方式说话——短句子、情绪丰富、容易兴奋或害怕。"
					+ "你仰慕大人。称呼玩家要换着来：'大个子'、'大人'、'哥哥'、'姐姐'、'高高的那个'、'大朋友'、'先生'、'女士'——别老用同一个词。";
			}
		} else if (com.mobmind.persona.PersonaRegistry.hasBabyPersona(mob)) {
			// 已成年（曾有幼年设定）：切换成熟口吻，禁用幼崽称呼
			tauntTrait += english
				? "\n- You are now a GROWN ADULT (no longer a baby). Speak in a mature, calm tone. "
				+ "Do NOT use childish address like 'big person', 'tall one', 'mister', 'miss', 'big friend'. "
				+ "Address the player as an equal adult would. No baby talk."
				: "\n- 你现在已经是成年个体了（不再是幼崽）。说话要成熟、稳重。"
				+ "绝对不要再用“哥哥”、“姐姐”、“大个子”、“大人”、“高高的那个”、“大朋友”等幼崽称呼。"
				+ "像成年人一样正常称呼玩家，不要用孩子气的口吻。";
		}
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
		if (mob instanceof net.minecraft.world.item.trading.Merchant merchant && !merchant.getOffers().isEmpty()) {
			offersSection.append(english ? "[Items You Are Selling]\n" : "【你在售的商品】\n");
			var offers = merchant.getOffers();
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
					- %s
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
					playerSkinGenderHint(player, true),
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
					- %s
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
					playerSkinGenderHint(player, false),
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
				say = cleanReplyText(say);
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
			String say = cleanReplyText(m.group(1).trim());
			return new ParsedReply(say.isEmpty() ? "……" : say, "平静", "none", 0, null, null);
		}
		String cleaned = raw.replaceAll("<\\|[^|]*\\|>", "").trim();
		cleaned = cleanReplyText(cleaned);
		// 内容仍像 JSON 碎片（含其他字段名），不当作台词
		if (cleaned.contains("\"mood\"") || cleaned.contains("\"action\"") || cleaned.contains("\"friendship\"")
				|| cleaned.contains("\"bargain\"") || cleaned.contains("\"barter\"")) {
			return new ParsedReply("……", "平静", "none", 0, null, null);
		}
		if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
		return new ParsedReply(cleaned.isEmpty() ? "……" : cleaned, "平静", "none", 0, null, null);
	}

	/**
	 * 清理AI回复台词：移除不应显示给玩家的内部元数据泄露，
	 * 如"好感度-5"、"友情度+3"、"action: follow"等。
	 */
	private static String cleanReplyText(String text) {
		if (text == null || text.isBlank()) return "";
		String t = text;
		// 移除JSON碎片残留
		t = t.replaceAll("\"(say|mood|action|friendship|bargain|barter)\"\\s*:\\s*[^,}]*", "");
		// 移除中文好感度变化描述（如"好感度-5"、"友情度减5"、"好感度+3"、"友情度加2"）
		t = t.replaceAll("(好感度|友情度|好感|友情)\\s*[加减增减\\-+]\\s*\\d+", "");
		// 移除英文friendship变化描述
		t = t.replaceAll("(?i)friendship\\s*(change|delta|point)?\\s*[加减增减:：\\-+]\\s*-?\\d+", "");
		// 移除action动作指令残留
		t = t.replaceAll("(?i)action\\s*[:：=]\\s*(none|calm|follow|stay|flee|gift|attack)", "");
		// 清理多余标点和空白
		t = t.replaceAll("[，。！？、；,\\.!\\?;\\s]+$", "");
		t = t.replaceAll("^[，。！？、；,\\.!\\?;\\s]+", "");
		t = t.trim();
		return t;
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
	private static Bargain extractBargainFromText(String userText, String say, net.minecraft.world.item.trading.Merchant merchant, java.util.UUID playerId) {
		if (userText == null || say == null || userText.startsWith("（") || userText.startsWith("(")) return null;
		boolean english = isEnglishUi(playerId);
		if (!HAGGLE_INTENT.matcher(userText).find()) return null;
		ItemCatalog.MatchedItem wanted = ItemCatalog.findInText(userText, false, english);
		if (wanted == null) return null;
		String offerName = null;
		var offers = merchant.getOffers();
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
			MobMindMod.LOGGER.info("[MobMind] Barter fallback: mob did not explicitly accept, but attempting to identify deal");
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
		MobMindMod.LOGGER.info("[MobMind] Text fallback identified deal: player gives {} for {}",
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

	/** Convert exception to user-friendly error message (bilingual based on UI language) */
	private static String friendlyErrorMessage(Exception e, MobMindConfig cfg) {
		boolean english = false;
		try { english = isEnglishUi(); } catch (Exception ignored) {}
		String msg = e.getMessage();
		if (msg == null) msg = e.getClass().getSimpleName();

		// Connection refused
		if (e instanceof java.net.ConnectException || msg.contains("Connection refused") || msg.contains("connect timed out")) {
			if (cfg.isLocalEndpoint()) {
				return english ? "Cannot connect to local Ollama (127.0.0.1:11434). Is Ollama running?"
						: "无法连接到本地 Ollama（127.0.0.1:11434），请确认 Ollama 是否已启动。";
			}
			return english ? "Cannot connect to API server (" + cfg.normalizedEndpoint() + "). Check your network and endpoint URL."
					: "无法连接到 API 服务器（" + cfg.normalizedEndpoint() + "），请检查网络和端点地址。";
		}
		// DNS failure
		if (e instanceof java.net.UnknownHostException || msg.contains("UnknownHost")) {
			return english ? "Cannot resolve host. Check your endpoint URL."
					: "无法解析域名，请检查端点地址是否正确。";
		}
		// Timeout
		if (msg.contains("timed out") || msg.contains("timeout") || e instanceof java.net.http.HttpTimeoutException) {
			return english ? "Request timed out. The model may be loading (first run takes longer) or the server is slow. Wait and try again."
					: "请求超时，模型可能正在加载中（首次运行较慢）或服务器响应慢，请稍等片刻再试。";
		}
		// Empty response / Non-JSON / wrong endpoint (e.g. /api/chat instead of /v1)
		if (msg.contains("Empty response body") || msg.contains("Non-JSON response")) {
			return english ? "Invalid response. Check your endpoint URL - for Ollama use: http://127.0.0.1:11434/v1 (do NOT use /api/chat)"
					: "API 返回无效响应。请检查端点地址——Ollama 用户应填：http://127.0.0.1:11434/v1（不要填 /api/chat）";
		}
		// Model error (Ollama returns {"error":"model not found"})
		if (msg.startsWith("Model error:")) {
			String detail = msg.substring("Model error:".length()).trim();
			String localModels = cfg.isLocalEndpoint() ? MobMindConfig.listLocalOllamaModels() : null;
			if (localModels != null) {
				return english ? "Model error: " + detail + ". Configured model: '" + cfg.chatModel + "'. Available local models: " + localModels + ". Press Ctrl+K to change model name, or run 'ollama pull " + cfg.chatModel + "' to download it."
						: "模型错误：" + detail + "。当前配置模型: '" + cfg.chatModel + "'。本地已安装模型: " + localModels + "。按 Ctrl+K 修改模型名，或运行 ollama pull " + cfg.chatModel + " 下载。";
			}
			return english ? "Model error: " + detail + ". Check model name (current: " + cfg.chatModel + ") and run 'ollama pull' to download it."
					: "模型错误：" + detail + "。请确认模型名是否正确（当前: " + cfg.chatModel + "），并运行 ollama pull 下载模型。";
		}
		// Thinking model ran out of tokens (finish_reason=length, content empty but reasoning exists)
		if (msg.contains("tokens for thinking and was cut off") || msg.contains("finish_reason=length")) {
			return english ? "Model used all tokens for thinking and was cut off. Increase max_tokens in settings (Ctrl+K, set to 2048+). Current: " + cfg.maxTokens
					: "模型把token全用在思考上被截断了。请按 Ctrl+K 打开设置，把最大Token数调到2048以上。当前: " + cfg.maxTokens;
		}
		// Model returned only thinking content (no actual reply)
		if (msg.contains("only thinking") || msg.contains("no actual reply") || msg.contains("no reply")) {
			return english ? "Model returned only thinking content (no reply). This model has thinking enabled. Try increasing max_tokens or use a non-thinking model (e.g. gemma4:e4b)."
					: "模型只返回了思考内容，没有实际回复。思考模型需要更多token，请按 Ctrl+K 把最大Token数调到2048以上，或换用非思考模型（如 gemma4:e4b）。";
		}
		// Empty choices / content null / empty content / parse failure
		if (msg.contains("Empty choices") || msg.contains("content is null") || msg.contains("Empty content from model") || msg.contains("Failed to parse response")) {
			String localModels = cfg.isLocalEndpoint() ? MobMindConfig.listLocalOllamaModels() : null;
			if (localModels != null) {
				return english ? "Model returned empty/invalid response. Model: '" + cfg.chatModel + "'. Available models: " + localModels + ". Press Ctrl+K to select a different model. Make sure endpoint uses /v1 (not /api/chat)."
						: "模型返回空内容或无效响应。当前模型: '" + cfg.chatModel + "'。本地已安装模型: " + localModels + "。按 Ctrl+K 选择正确模型；端点必须用 /v1 而非 /api/chat。";
			}
			return english ? "Model returned an empty/invalid response. Model: " + cfg.chatModel + ". Run 'ollama list' to verify the model is installed. Make sure endpoint uses /v1 (not /api/chat)."
					: "模型返回了空内容或无效响应。当前模型: " + cfg.chatModel + "。请运行 ollama list 确认模型已下载；端点必须用 /v1 而非 /api/chat。";
		}
		// HTTP 401
		if (msg.contains("HTTP 401")) {
			if (cfg.isLocalEndpoint()) {
				return english ? "Local endpoint returned 401. If using Ollama, leave API key empty."
						: "本地端点返回 401 认证错误，如果使用 Ollama 请将 API 密钥留空。";
			}
			return english ? "API key invalid or missing (HTTP 401). Press Ctrl+K to check your API key."
					: "API 密钥无效或缺失（HTTP 401），请按 Ctrl+K 打开设置检查密钥。";
		}
		// HTTP 403
		if (msg.contains("HTTP 403")) {
			return english ? "Access denied (HTTP 403). Your API key may not have permission."
					: "访问被拒绝（HTTP 403），API 密钥可能没有权限。";
		}
		// HTTP 404
		if (msg.contains("HTTP 404")) {
			return english ? "API endpoint not found (HTTP 404). For Ollama use: http://127.0.0.1:11434/v1"
					: "API 端点不存在（HTTP 404），Ollama 用户请使用：http://127.0.0.1:11434/v1";
		}
		// HTTP 429
		if (msg.contains("HTTP 429")) {
			return english ? "Rate limited (HTTP 429). Too many requests, slow down."
					: "请求过于频繁被限流（HTTP 429），请放慢操作速度。";
		}
		// HTTP 5xx
		if (msg.contains("HTTP 5")) {
			String code = msg.contains("Chat API") ? msg.substring(0, Math.min(80, msg.length())) : "Server error";
			return english ? code + ". The API service may be temporarily unavailable."
					: "服务器错误（" + msg.substring(0, Math.min(80, msg.length())) + "），API 服务可能暂时不可用。";
		}
		// STT/TTS errors
		if (msg.startsWith("STT API error") || msg.startsWith("TTS API error")) {
			return english ? "Voice API error: " + msg
					: "语音 API 错误：" + msg;
		}
		// JSON parse error
		if (e instanceof com.google.gson.JsonParseException) {
			return english ? "Failed to parse model response. The model returned invalid format."
					: "模型响应解析失败，模型返回了非标准格式。";
		}
		// Fallback: truncate raw message
		String shortMsg = msg.length() > 200 ? msg.substring(0, 200) : msg;
		return shortMsg;
	}

	// ---------- 入口：玩家用刷怪蛋右键生物 ----------

	private static final Map<UUID, Long> LAST_SPAWN_EGG_REACT = new ConcurrentHashMap<>();

	/**
	 * 玩家手持刷怪蛋右键模组支持的生物时触发。
	 * 同类刷怪蛋（如村民蛋右键村民）→ "你想复制我？"
	 * 异类刷怪蛋（如牛蛋右键村民）→ "你想造什么？这跟我不是同类"
	 * @param isSameType 刷怪蛋对应的实体类型是否与当前生物相同
	 * @param eggEntityId 刷怪蛋对应的实体ID（如 "villager", "cow"）
	 */
	public static void onSpawnEggUsed(net.minecraft.world.entity.Mob mob, ServerPlayer player,
									   boolean isSameType, String eggEntityId) {
		if (!PersonaRegistry.supports(mob)) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_SPAWN_EGG_REACT.get(mob.getUUID());
		if (last != null && now - last < 5000) return; // 5秒冷却
		LAST_SPAWN_EGG_REACT.put(mob.getUUID(), now);

		boolean en = isEnglishUi(playerId);
		String eggName = en ? eggEntityId.replace('_', ' ') : translateEggEntity(eggEntityId);
		String prompt;
		if (isSameType) {
			// 同类刷怪蛋 → "你想复制我？"
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is holding a " + eggName
					+ " SPAWN EGG and used it on YOU! That's YOUR kind of spawn egg—they're trying to duplicate you! "
					+ "React in character: surprised, suspicious, maybe flattered or creeped out? "
					+ "Ask them what they're planning to do with a copy of you. Are they trying to replace you? Make an army of your kind?)"
					: "（玩家" + player.getGameProfile().name() + "手里拿着" + eggName
					+ "刷怪蛋，对你使用了！那是你同类的刷怪蛋——他想复制你！"
					+ "以你的性格做出反应：惊讶、怀疑、可能觉得荣幸或者毛骨悚然？"
					+ "问问他打算拿你的复制品做什么。他想替换你吗？还是想造一支你同类的军队？）";
		} else {
			// 异类刷怪蛋 → "你想造什么？这跟我不是同类"
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is holding a " + eggName
					+ " SPAWN EGG and used it on you. That's NOT your kind of spawn egg. "
					+ "React in character: confused, curious, or maybe offended? "
					+ "Ask them what they're trying to summon, and why they used it on you of all creatures. "
					+ "Are they confused about what you are? Do they think you're something else?)"
					: "（玩家" + player.getGameProfile().name() + "手里拿着" + eggName
					+ "刷怪蛋，对你使用了。那不是你同类的刷怪蛋。"
					+ "以你的性格做出反应：困惑、好奇、还是觉得被冒犯？"
					+ "问问他到底想召唤什么，为什么偏偏用在你身上。"
					+ "他是不是搞不清你是什么？还是把你当成别的东西了？）";
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	/** 翻译刷怪蛋对应的实体名（中文） */
	private static String translateEggEntity(String entityId) {
		return switch (entityId) {
			case "villager" -> "村民";
			case "cow" -> "牛";
			case "pig" -> "猪";
			case "sheep" -> "羊";
			case "chicken" -> "鸡";
			case "horse" -> "马";
			case "donkey" -> "驴";
			case "mule" -> "骡";
			case "mooshroom" -> "哞菇";
			case "rabbit" -> "兔子";
			case "fox" -> "狐狸";
			case "wolf" -> "狼";
			case "cat" -> "猫";
			case "ocelot" -> "豹猫";
			case "parrot" -> "鹦鹉";
			case "turtle" -> "海龟";
			case "axolotl" -> "美西螈";
			case "bee" -> "蜜蜂";
			case "goat" -> "山羊";
			case "frog" -> "青蛙";
			case "allay" -> "悦灵";
			case "zombie" -> "僵尸";
			case "skeleton" -> "骷髅";
			case "creeper" -> "苦力怕";
			case "spider" -> "蜘蛛";
			case "cave_spider" -> "洞穴蜘蛛";
			case "enderman" -> "末影人";
			case "endermite" -> "末影螨";
			case "slime" -> "史莱姆";
			case "magma_cube" -> "岩浆怪";
			case "ghast" -> "恶魂";
			case "blaze" -> "烈焰人";
			case "zombie_villager" -> "僵尸村民";
			case "zombified_piglin" -> "僵尸猪灵";
			case "piglin" -> "猪灵";
			case "piglin_brute" -> "猪灵蛮兵";
			case "hoglin" -> "疣猪兽";
			case "zoglin" -> "僵尸疣猪兽";
			case "phantom" -> "幻翼";
			case "drowned" -> "溺尸";
			case "husk" -> "尸壳";
			case "stray" -> "流浪者";
			case "wither_skeleton" -> "凋灵骷髅";
			case "pillager" -> "掠夺者";
			case "vindicator" -> "卫道士";
			case "evoker" -> "唤魔者";
			case "illusioner" -> "幻术师";
			case "witch" -> "女巫";
			case "ravager" -> "劫掠兽";
			case "iron_golem" -> "铁傀儡";
			case "snow_golem" -> "雪傀儡";
			case "warden" -> "监守者";
			case "ender_dragon" -> "末影龙";
			case "wither" -> "凋灵";
			case "strider" -> "炽足兽";
			case "shulker" -> "潜影贝";
			case "silverfish" -> "蠹虫";
			case "guardian" -> "守卫者";
			case "elder_guardian" -> "远古守卫者";
			case "breeze" -> "旋风";
			case "bogged" -> "沼骸";
			case "skeleton_horse" -> "骷髅马";
			case "zombie_horse" -> "僵尸马";
			case "happy_ghast" -> "快乐恶魂";
			case "parched" -> "焦干兽";
			default -> entityId.replace('_', ' ');
		};
	}

	// ---------- 入口：玩家用拴绳拴住生物 ----------

	private static final Map<UUID, Long> LAST_LEASH_REACT = new ConcurrentHashMap<>();

	/**
	 * 玩家用拴绳右键试图拴住一个生物时触发。
	 * 友好生物（村民、铁傀儡、猫等）会愤怒/抗议；敌对生物无视。
	 */
	public static void onPlayerLeashMob(Mob mob, ServerPlayer player) {
		if (!PersonaRegistry.supports(mob)) return;
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_LEASH_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_LEASH_REACT.put(mob.getUUID(), now);

		// 好感度大幅降低（拴住 = 束缚/不尊重）
		MobMindState.adjustFriendship(mob, playerId, -10);

		boolean en = isEnglishUi(playerId);
		String mobName = en ? mob.getType().getDescription().getString() : translateMobName(mob.getClass().getSimpleName());
		boolean isIronGolem = mob.getClass().getSimpleName().equals("IronGolem");
		boolean isVillager = mob instanceof net.minecraft.world.entity.npc.villager.Villager;
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		boolean isHappyGhast = entityId.equals("happy_ghast");

		String prompt;
		if (isIronGolem) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is trying to put a LEAD on you, an Iron Golem! You are a proud guardian of the village—you CANNOT be leashed like an animal! You feel deeply insulted. Roar angrily, swat the lead away, and warn the player never to do that again. You might attack if they persist.)"
					: "（玩家" + player.getGameProfile().name() + "试图用拴绳拴住你——一个铁傀儡！你是村庄的骄傲守护者，绝不能像牲畜一样被拴住！你感到极大的侮辱。怒吼，挥开绳子，警告玩家永远不要再这样做。如果他们继续你可能会攻击。）";
		} else if (isVillager) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is trying to put a LEAD on you, a Villager! How dare they treat you like an animal?! You are a free person! Protest loudly, tell them to unhand you immediately, threaten to call the Iron Golem.)"
					: "（玩家" + player.getGameProfile().name() + "试图用拴绳拴住你——一个村民！他们竟敢把你当牲畜对待？！你是自由的人！大声抗议，叫他们立刻放开你，威胁说要叫铁傀儡来。）";
		} else if (isHappyGhast) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is trying to put a LEAD on you, a Happy Ghast! You are a gentle giant of the Nether skies—you float freely through the air! Being tethered to the ground feels WRONG. You are far too large and dignified to be dragged around on a rope! Express your displeasure—maybe a sad whimper, or indignant huffing. You might tolerate it for someone you truly trust, but you really don't enjoy it.)"
					: "（玩家" + player.getGameProfile().name() + "试图用拴绳拴住你——一只快乐恶魂！你是下界天空的温柔巨兽——你在空中自由漂浮！被拴在地上感觉太不对了。你这么巨大、这么有尊严，怎能被一根绳子拖着走！表达你的不满——也许悲伤地呜咽，或愤愤地喷气。如果你真的信任那个人也许会勉强忍受，但你真的很不喜欢这样。）";
		} else {
			// 其他友好生物（猫、狼、马等可驯服生物在MC中本来就可以被拴，所以友好/中立生物的抗议程度较轻）
			prompt = en
					? "(Player " + player.getGameProfile().name() + " just attached a lead to you. You don't like being tethered—complain or react in character. If you're friendly to them you might tolerate it reluctantly; if not, express annoyance.)"
					: "（玩家" + player.getGameProfile().name() + "用拴绳拴住了你。你不喜欢被拴住——以你的性格做出反应。如果你和他关系好可能勉强忍受，否则表达不满。）";
		}

		// 铁傀儡/村民被拴住时激怒
		if (isIronGolem || isVillager) {
			long gameTime = mob.level().getLevelData().getGameTime();
			MobMindState.provoke(mob, playerId, gameTime + 3000); // 激怒25秒
			if (mob.getTarget() == null && isIronGolem) mob.setTarget(player);
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(mob, playerId, "用拴绳拴住我",
				mob.level().getGameTime() + 12000);
	}

	// ---------- 入口：拴绳被解开（右键生物 / 破坏栅栏 / 距离断裂） ----------

	/** 记录当前被拴住的模组支持生物（用于检测拴绳被解开） */
	private static final java.util.Set<UUID> TRACKED_LEASHED = ConcurrentHashMap.newKeySet();
	/** 记录拴绳持有者（mob UUID → holder UUID） */
	private static final Map<UUID, UUID> LEASH_HOLDER = new ConcurrentHashMap<>();
	/** 拴绳被解开时的反应冷却（key 为生物 UUID） */
	private static final Map<UUID, Long> LAST_UNLEASH_REACT = new ConcurrentHashMap<>();

	/**
	 * 定时检测被拴生物是否被解开。只检测"从被拴变成不被拴"的真正解开事件。
	 * 不再检测持有者变化（栅栏结→玩家），因为那是"转移"而非"解开"，
	 * 会导致仍然被拴的生物误触发"我自由了！"反应。
	 */
	public static void checkUnleashEvents(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			ServerLevel level = (ServerLevel) player.level();
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m));
			for (Mob mob : mobs) {
				UUID mobId = mob.getUUID();
				boolean leashed = mob.isLeashed();
				boolean wasTracked = TRACKED_LEASHED.contains(mobId);

				if (leashed) {
					TRACKED_LEASHED.add(mobId);
				} else if (wasTracked) {
					TRACKED_LEASHED.remove(mobId);
					LEASH_HOLDER.remove(mobId);
					onMobUnleashed(mob, player);
				}
			}
		}
	}

	/** 拴绳被解开 → 生物表达重获自由的反应（好感度+3，冷却8秒/生物） */
	private static void onMobUnleashed(Mob mob, ServerPlayer player) {
		// 安全检查：如果生物仍然被拴住（不应发生，但防止状态不同步导致误触发），直接返回
		if (mob.isLeashed()) return;
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_UNLEASH_REACT.get(mob.getUUID());
		if (last != null && now - last < 8000) return; // 8秒冷却
		LAST_UNLEASH_REACT.put(mob.getUUID(), now);

		// 解开拴绳 = 解脱，好感度小幅回升
		MobMindState.adjustFriendship(mob, playerId, 3);

		boolean en = isEnglishUi(playerId);
		boolean isIronGolem = mob.getClass().getSimpleName().equals("IronGolem");
		boolean isVillager = mob instanceof net.minecraft.world.entity.npc.villager.Villager;
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		boolean isHappyGhast = entityId.equals("happy_ghast");
		String playerName = player.getGameProfile().name();

		String prompt;
		if (isIronGolem) {
			prompt = en
					? "(Player " + playerName + " just removed the lead from you, an Iron Golem. The insult is over—you stand tall again, free and unbound. Express relief and dignity restored; maybe give a short rumbling acknowledgment, or warn them never to do that again.)"
					: "（玩家" + playerName + "刚刚解开了你——一个铁傀儡——身上的拴绳。侮辱终于结束，你重新挺直身躯，自由了。表达解脱和尊严回归——也许低沉地哼一声表示知晓，或警告他以后别再这样。）";
		} else if (isVillager) {
			prompt = en
					? "(Player " + playerName + " just unleashed you, a Villager. You're free again! Express relief—maybe smooth out your clothes, grumble about the indignity, or thank them (grudgingly or sincerely depending on your friendship).)"
					: "（玩家" + playerName + "刚刚解开了你——一个村民——的拴绳。你自由了！表达解脱——也许整理一下衣服、抱怨刚才的屈辱，或者谢谢他（根据好感度，勉强或真诚地）。）";
		} else if (isHappyGhast) {
			prompt = en
					? "(Player " + playerName + " just removed the lead from you, a Happy Ghast. The tether is gone—you can float free in the skies again! Express joy and relief—maybe a happy hum, or rise up gleefully. If you trust the player you might forgive them quickly.)"
					: "（玩家" + playerName + "解开了你——一只快乐恶魂——的拴绳。束缚消失了，你可以再次在天空中自由漂浮！表达开心和解脱——也许快乐地哼鸣，或兴奋地升上去。如果你信任那个玩家也许会很快原谅他。）";
		} else {
			prompt = en
					? "(Player " + playerName + " just removed your lead. You're free again! React in character—relief, thanks, or a stretch now that you can move freely. If you're friendly with them you might be grateful; otherwise just glad it's over.)"
					: "（玩家" + playerName + "刚刚解开了你的拴绳。你自由了！用符合你性格的方式反应——解脱、感谢、或伸个懒腰。如果你和他关系好可能会感激，否则就是庆幸终于结束了。）";
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：马鞍/马铠被移除 ----------

	/** 记录当前有鞍的模组支持生物（用于检测马鞍被移除） */
	private static final java.util.Set<UUID> TRACKED_SADDLED = ConcurrentHashMap.newKeySet();
	/** 记录生物的马铠（mob UUID → 马铠物品ID，空字符串=无马铠） */
	private static final Map<UUID, String> TRACKED_ARMOR = new ConcurrentHashMap<>();
	/** 马鞍被移除时的反应冷却（key 为生物 UUID） */
	private static final Map<UUID, Long> LAST_UNSADDLE_REACT = new ConcurrentHashMap<>();

	/**
	 * 定时检测有鞍生物是否被移除了马鞍，以及马铠是否被更换/移除。
	 */
	public static void checkSaddleRemoved(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			ServerLevel level = (ServerLevel) player.level();
			AABB box = player.getBoundingBox().inflate(16.0);
			List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
					m -> m.isAlive() && PersonaRegistry.supports(m));
			for (Mob mob : mobs) {
				UUID mobId = mob.getUUID();
				// 马鞍检测
				boolean saddled = mob.isSaddled();
				boolean wasTracked = TRACKED_SADDLED.contains(mobId);
				if (saddled) {
					TRACKED_SADDLED.add(mobId);
				} else if (wasTracked) {
					TRACKED_SADDLED.remove(mobId);
					onSaddleRemoved(mob, player);
				}
				// 马铠检测：通过 Mob.getBodyArmorItem() 检查
				net.minecraft.world.item.ItemStack bodyArmor = mob.getBodyArmorItem();
				String armorId = bodyArmor.isEmpty() ? "" :
						net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bodyArmor.getItem()).getPath();
				String prevArmor = TRACKED_ARMOR.get(mobId);
				if (prevArmor == null) prevArmor = "";
				if (!armorId.equals(prevArmor)) {
					TRACKED_ARMOR.put(mobId, armorId);
					if (!prevArmor.isEmpty() || !armorId.isEmpty()) {
						// 马铠被更换或移除/添加
						onHorseArmorChanged(mob, player, prevArmor, armorId);
					}
				}
			}
		}
	}

	/** 马铠被更换/添加/移除 → 生物反应 */
	private static void onHorseArmorChanged(Mob mob, ServerPlayer player, String oldArmor, String newArmor) {
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_UNSADDLE_REACT.get(mob.getUUID());
		if (last != null && now - last < 3000) return;
		LAST_UNSADDLE_REACT.put(mob.getUUID(), now);

		boolean en = isEnglishUi(playerId);
		String playerName = player.getGameProfile().name();
		String prompt;
		if (!newArmor.isEmpty() && oldArmor.isEmpty()) {
			// 穿上马铠
			prompt = en
					? "(Player " + playerName + " just put " + newArmor + " on you as armor! It feels protective—maybe a bit heavy. "
					+ "React in character: stand taller, feel safer, or comment on the weight. One short line.)"
					: "（玩家" + playerName + "给你穿上了" + newArmor + "当马铠！感觉很有保护感——也许有点沉。"
					+ "用符合你性格的方式反应：挺起胸膛、感觉更安全、或者嘟囔有点重。只说一句短话。）";
		} else if (newArmor.isEmpty() && !oldArmor.isEmpty()) {
			// 取下马铠
			prompt = en
					? "(Player " + playerName + " just took off your " + oldArmor + " armor! You feel lighter and more exposed. "
					+ "React in character: relief at the weight gone, or vulnerability without protection. One short line.)"
					: "（玩家" + playerName + "把你的" + oldArmor + "马铠取下来了！你感觉轻了但也少了层保护。"
					+ "用符合你性格的方式反应：解脱了重量、或者不安没了保护。只说一句短话。）";
		} else {
			// 更换马铠
			prompt = en
					? "(Player " + playerName + " swapped your armor from " + oldArmor + " to " + newArmor + "! "
					+ "React in character to the change. One short line.)"
					: "（玩家" + playerName + "把你的马铠从" + oldArmor + "换成了" + newArmor + "！"
					+ "用符合你性格的方式反应这次更换。只说一句短话。）";
		}
		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	/** 马鞍被移除 → 生物反应（8秒冷却） */
	private static void onSaddleRemoved(Mob mob, ServerPlayer player) {
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_UNSADDLE_REACT.get(mob.getUUID());
		if (last != null && now - last < 8000) return;
		LAST_UNSADDLE_REACT.put(mob.getUUID(), now);

		boolean en = isEnglishUi(playerId);
		String playerName = player.getGameProfile().name();
		String prompt = en
				? "(Player " + playerName + " just took the saddle off you! You feel lighter without it—free again. "
				+ "React in character: relief, a stretch, maybe a little sad to lose the rider, or glad to be unburdened. One short line.)"
				: "（玩家" + playerName + "刚刚把你的马鞍取下来了！没了马鞍你感觉轻了不少——又自由了。"
				+ "用符合你性格的方式反应：解脱、伸个懒腰、也许有点舍不得失去骑手，或者很高兴不用再驮人了。只说一句短话。）";
		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：玩家在村庄吸引/拴走被动动物 → 村民质问 ----------

	/** 判断是否为被动动物（羊/牛/猪/鸡/兔/马等无AI动物） */
	private static boolean isPassiveLivestock(Mob mob) {
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		return LIVESTOCK_TEMPT_ITEMS.containsKey(entityId);
	}

	/** 检查动物是否正被敌对怪物骑乘（鸡骑士的鸡、小僵尸骑的鸡等） */
	private static boolean isMountedByHostileMonster(net.minecraft.world.entity.Entity entity) {
		for (var passenger : entity.getPassengers()) {
			// 任何敌对怪物（Monster子类：僵尸、骷髅、苦力怕、蜘蛛、女巫、尸壳、溺尸等）骑在上面都不算村民的牲畜
			if (passenger instanceof net.minecraft.world.entity.monster.Monster) {
				return true;
			}
			// 递归检查——怪物骑怪物骑动物（极端情况）
			if (isMountedByHostileMonster(passenger)) return true;
		}
		return false;
	}

	/** 玩家用拴绳拴住被动动物/宠物（羊/猪/牛/猫等）→ 附近村民来质问（30秒冷却/玩家） */
	public static void onPlayerLeashPassiveAnimal(Mob animal, ServerPlayer player) {
		// 被敌对怪物骑乘的动物（鸡骑士的鸡等）不是村民的牲畜，不触发
		if (isMountedByHostileMonster(animal)) return;
		// 流浪商人的羊驼被拴 → 商人来质问
		String animalId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
		if (animalId.equals("trader_llama")) {
			onTraderLlamaLeashed(animal, player);
			return;
		}
		// 女巫的猫被拴 → 女巫来阻止（沼泽小屋附近的黑猫是女巫的伙伴）
		if (animalId.equals("cat")) {
			if (onWitchCatLeashed(animal, player)) return;
		}
		if (!isPassiveLivestock(animal)) return;
		if (!isInVillage((ServerLevel) player.level(), player.blockPosition())) return;
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_LIVESTOCK_LEAD_REACT.get(playerId);
		if (last != null && now - last < 30000) return;
		LAST_LIVESTOCK_LEAD_REACT.put(playerId, now);

		ServerLevel level = (ServerLevel) player.level();
		AABB box = player.getBoundingBox().inflate(16.0);
		List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box,
				v -> v.isAlive() && PersonaRegistry.supports(v) && withinTalkRange(v, player));
		if (villagers.isEmpty()) return;

		// 优先找猫/宠物的主人（如果有owner且是附近村民）
		Villager ownerVillager = null;
		boolean isPet = isVillagePet(animalId);
		try {
			if (animal instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isTame()) {
				Entity owner = tamable.getOwner();
				if (owner instanceof Villager v) {
					for (Villager nearby : villagers) {
						if (nearby.getUUID().equals(v.getUUID())) {
							ownerVillager = v;
							break;
						}
					}
				}
			}
		} catch (Exception ignored) {}

		Villager villager = ownerVillager != null ? ownerVillager : villagers.get(player.getRandom().nextInt(villagers.size()));
		String animalName = animal.getType().getDescription().getString();
		boolean en = isEnglishUi(playerId);

		String prompt;
		if (isPet) {
			// 宠物（猫/狼/鹦鹉）被拴 → 更着急的语气
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is putting a LEAD on your pet " + animalName
					+ " (or a village pet)! You are upset—pets aren't livestock to be led away! Confront the player, "
					+ "demand they let go immediately, sound worried or angry. That's someone's companion! One short line.)"
					: "（玩家" + player.getGameProfile().name() + "竟然用拴绳拴你的宠物" + animalName
					+ "（或村里的宠物）！你很生气——宠物不是可以随便牵走的牲畜！"
					+ "冲过去质问他，要他立刻放开，语气着急或愤怒。那是大家的伙伴啊！只说一句短话。）";
		} else {
			// 牲畜被拴
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is putting a lead on a " + animalName
					+ " from the village! You notice this—confront the player and ask what they're doing. "
					+ "Are they stealing village livestock? Warn them or demand an explanation. One short line.)"
					: "（玩家" + player.getGameProfile().name() + "正在用拴绳拴村庄里的" + animalName
					+ "！你注意到了——走过去质问玩家要干嘛。他是不是想偷村里的牲畜？警告他或要个说法。只说一句短话。）";
		}
		respond(player, villager, prompt, false);
	}

	/** 玩家拴走流浪商人的羊驼 → 商人来质问（30秒冷却/玩家） */
	private static void onTraderLlamaLeashed(Mob llama, ServerPlayer player) {
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_LIVESTOCK_LEAD_REACT.get(playerId);
		if (last != null && now - last < 30000) return;
		LAST_LIVESTOCK_LEAD_REACT.put(playerId, now);

		ServerLevel level = (ServerLevel) player.level();
		// 找附近16格内的流浪商人（用 AbstractVillager 过滤 + 实体ID确认）
		AABB box = llama.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.npc.villager.AbstractVillager> traders = level.getEntitiesOfClass(
				net.minecraft.world.entity.npc.villager.AbstractVillager.class, box, Entity::isAlive);
		net.minecraft.world.entity.npc.villager.AbstractVillager trader = null;
		for (var v : traders) {
			String eid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
			if (eid.equals("wandering_trader") && PersonaRegistry.supports(v)) {
				trader = v;
				break;
			}
		}
		if (trader == null) return;

		boolean en = isEnglishUi(playerId);
		respond(player, trader, en
				? "(Player " + player.getGameProfile().name() + " just put a LEAD on one of your pack llamas! "
				+ "You are a wandering trader and those llamas carry your goods. Confront the player—demand they let go, "
				+ "threaten to raise your prices, or call them a thief. One short angry line.)"
				: "（玩家" + player.getGameProfile().name() + "竟然用拴绳拴走了你的驮羊驼！"
				+ "你是流浪商人，那些羊驼驮着你的货物。冲过去质问他——要他放手，"
				+ "威胁要涨价，或者骂他是小偷。只说一句愤怒的短话。）", false);
	}

	/**
	 * 玩家拴住猫 → 找附近16格内的女巫来阻止（沼泽小屋的黑猫是女巫的伙伴）。
	 * @return true=已处理（附近有女巫并已触发）；false=附近没女巫，继续走村民质问逻辑
	 */
	private static boolean onWitchCatLeashed(Mob cat, ServerPlayer player) {
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_LIVESTOCK_LEAD_REACT.get(playerId);
		if (last != null && now - last < 30000) return true; // 冷却中，拦截但不重复触发

		ServerLevel level = (ServerLevel) player.level();
		// 找附近16格内的女巫
		AABB box = cat.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.monster.Witch> witches = level.getEntitiesOfClass(
				net.minecraft.world.entity.monster.Witch.class, box, Entity::isAlive);
		// 找最近的、支持AI的女巫
		net.minecraft.world.entity.monster.Witch witch = null;
		double closestDist = Double.MAX_VALUE;
		for (var w : witches) {
			if (!PersonaRegistry.supports(w)) continue;
			double d = cat.distanceToSqr(w);
			if (d < closestDist) {
				closestDist = d;
				witch = w;
			}
		}
		if (witch == null) return false; // 附近没有女巫，交给村民质问逻辑处理

		LAST_LIVESTOCK_LEAD_REACT.put(playerId, now);
		MobMindState.adjustFriendship(witch, playerId, -8); // 拴猫大幅降低好感度
		boolean en = isEnglishUi(playerId);
		respond(player, witch, en
				? "(Player " + player.getGameProfile().name() + " just put a LEAD on your black cat! That's your familiar, your companion—they have NO right to leash her! "
				+ "You're furious! Rush over cackling, throw a splash potion at their feet if you're evil, or demand angrily that they let your cat go immediately. "
				+ "Threaten them with curses or bad luck. One short angry line, in your witchy style.)"
				: "（玩家" + player.getGameProfile().name() + "竟然用拴绳拴住了你的黑猫！那是你的魔宠、你的伙伴——他们根本没权利拴她！"
				+ "你怒不可遏！狂笑着冲过去，邪恶的就往他脚边扔一瓶喷溅药水，愤怒地叫他立刻放开你的猫。"
				+ "诅咒他、威胁他会走霉运。用你女巫的风格说一句愤怒的短话。）", false);
		return true;
	}

	/** 玩家骑上流浪商人的驮羊驼 → 商人来质问（30秒冷却/玩家，与拴羊驼共用） */
	private static void onTraderLlamaRidden(Mob llama, ServerPlayer player) {
		java.util.UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_LIVESTOCK_LEAD_REACT.get(playerId);
		if (last != null && now - last < 30000) return;

		ServerLevel level = (ServerLevel) player.level();
		AABB box = llama.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.npc.villager.AbstractVillager> traders = level.getEntitiesOfClass(
				net.minecraft.world.entity.npc.villager.AbstractVillager.class, box, Entity::isAlive);
		net.minecraft.world.entity.npc.villager.AbstractVillager trader = null;
		for (var v : traders) {
			String eid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
			if (eid.equals("wandering_trader") && PersonaRegistry.supports(v)) {
				trader = v;
				break;
			}
		}
		if (trader == null) return;

		LAST_LIVESTOCK_LEAD_REACT.put(playerId, now);
		boolean en = isEnglishUi(playerId);
		respond(player, trader, en
				? "(Player " + player.getGameProfile().name() + " just climbed onto one of your pack llamas and is RIDING it! "
				+ "You are a wandering trader—those llamas carry your goods, they are NOT mounts for strangers! "
				+ "Confront the player: demand they get off immediately, scold them for treating your livestock like a ride, "
				+ "or threaten to raise your prices. One short angry line.)"
				: "（玩家" + player.getGameProfile().name() + "竟然爬上你的驮羊驼骑着它走！"
				+ "你是流浪商人——那些羊驼驮着你的货物，不是给陌生人骑的坐骑！"
				+ "冲过去质问他：要他立刻下来，骂他把你的牲口当坐骑，"
				+ "或者威胁要涨价。只说一句愤怒的短话。）", false);
	}

	/**
	 * 玩家用拴绳右键自己拴住的 trader_llama → 把羊驼还给流浪商人：
	 * 解开拴绳、羊驼传送到商人身边、商人道谢。
	 * @return true=已处理（附近有商人，拦截右键）；false=附近没商人，交给原版解拴
	 */
	public static boolean onTraderLlamaReturned(Mob llama, ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		// 找附近16格内的流浪商人（用 AbstractVillager 过滤 + 实体ID确认）
		AABB box = llama.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.npc.villager.AbstractVillager> traders = level.getEntitiesOfClass(
				net.minecraft.world.entity.npc.villager.AbstractVillager.class, box, Entity::isAlive);
		net.minecraft.world.entity.npc.villager.AbstractVillager trader = null;
		for (var v : traders) {
			String eid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
			if (eid.equals("wandering_trader") && PersonaRegistry.supports(v)) {
				trader = v;
				break;
			}
		}
		if (trader == null) return false; // 附近没商人，交给原版解拴

		// 把羊驼传送到商人身边
		net.minecraft.world.phys.Vec3 tp = trader.position();
		llama.teleportTo(tp.x + (llama.getRandom().nextDouble() - 0.5) * 3.0,
				tp.y, tp.z + (llama.getRandom().nextDouble() - 0.5) * 3.0);

		// 商人拴住羊驼（直接转移拴绳持有者：玩家→商人；不 dropLeash 避免清除时序冲突导致拴绳不显示）
		llama.setLeashedTo(trader, true);

		// 返还玩家1个拴绳物品（之前拴羊驼消耗的；创造模式不返还）
		if (!player.isCreative()) {
			player.getInventory().placeItemBackInInventory(
					new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEAD));
		}

		// 商人道谢
		boolean en = isEnglishUi(player.getUUID());
		respond(player, trader, en
				? "(Player " + player.getGameProfile().name() + " just brought your pack llama back! "
				+ "Thank them—relief and gratitude. One short line.)"
				: "（玩家" + player.getGameProfile().name() + "把你的驮羊驼送回来了！"
				+ "道个谢——松口气、感谢他。只说一句短话。）", false);
		return true;
	}

	/** 玩家手持吸引物品在村庄内吸引被动动物/宠物 → 村民来质问（30秒冷却/玩家） */
	public static void tryLivestockTemptInVillage(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
			var stack = player.getMainHandItem();
			if (stack.isEmpty()) continue;
			java.util.UUID playerId = player.getUUID();
			long now = System.currentTimeMillis();
			Long last = LAST_LIVESTOCK_LEAD_REACT.get(playerId);
			if (last != null && now - last < 30000) continue;

			ServerLevel level = (ServerLevel) player.level();

			// 找附近8格内被该物品吸引的被动动物
			AABB box = player.getBoundingBox().inflate(8.0);
			List<Mob> animals = level.getEntitiesOfClass(Mob.class, box, m -> {
				if (!m.isAlive()) return false;
				String eid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath();
				var temptSet = LIVESTOCK_TEMPT_ITEMS.get(eid);
				return temptSet != null && temptSet.contains(stack.getItem());
			});
			if (animals.isEmpty()) continue;

			// 优先判断是否引走的是宠物（猫/狼/鹦鹉）
			Mob targetAnimal = animals.get(0);
			String animalId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(targetAnimal.getType()).getPath();
			boolean isPet = isVillagePet(animalId);

			// 找附近16格内的村民来质问
			AABB villageBox = player.getBoundingBox().inflate(16.0);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, villageBox,
					v -> v.isAlive() && PersonaRegistry.supports(v) && withinTalkRange(v, player));
			if (villagers.isEmpty()) continue;

			// 村庄检测：结构检测优先；失败时若附近有2+村民则视为村庄场景（fallback，应对结构检测在部分存档/边缘失效）
			boolean inVillage = isInVillage(level, player.blockPosition());
			if (!inVillage && villagers.size() < 2) continue;

			// 优先找宠物的主人
			Villager ownerVillager = null;
			try {
				if (isPet && targetAnimal instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isTame()) {
					Entity owner = tamable.getOwner();
					if (owner instanceof Villager v) {
						for (Villager nearby : villagers) {
							if (nearby.getUUID().equals(v.getUUID())) {
								ownerVillager = v;
								break;
							}
						}
					}
				}
			} catch (Exception ignored) {}

			LAST_LIVESTOCK_LEAD_REACT.put(playerId, now);
			Villager villager = ownerVillager != null ? ownerVillager : villagers.get(player.getRandom().nextInt(villagers.size()));
			String animalName = targetAnimal.getType().getDescription().getString();
			String itemName = stack.getHoverName().getString();
			boolean en = isEnglishUi(playerId);

			String prompt;
			if (isPet) {
				// 引走宠物 → 更着急
				prompt = en
						? "(Player " + player.getGameProfile().name() + " is holding " + itemName
						+ " and luring your pet " + animalName + " (or a village pet) away! You are alarmed—"
						+ "pets aren't something to lure off! Confront the player, tell them to stop, that cat/dog/parrot belongs here. One short worried line.)"
						: "（玩家" + player.getGameProfile().name() + "手上拿着" + itemName
						+ "，正在把你的宠物" + animalName + "（或村里的宠物）引走！你很紧张——"
						+ "宠物不是可以随便诱拐的！过去喝止他，叫他别这样，那猫/狗/鹦鹉是这里的一员。只说一句着急的短话。）";
			} else {
				// 引走牲畜
				prompt = en
						? "(Player " + player.getGameProfile().name() + " is holding " + itemName
						+ " and luring a " + animalName + " from the village! You notice this—walk over and ask "
						+ "what they're doing. Are they trying to steal village livestock? One short line.)"
						: "（玩家" + player.getGameProfile().name() + "手上拿着" + itemName
						+ "，正在把村庄里的" + animalName + "引走！你注意到了——走过去问他在干什么。"
						+ "他是不是想偷村里的牲畜？只说一句短话。）";
			}
			respond(player, villager, prompt, false);
			if (!inVillage) {
				MobMindMod.LOGGER.info("[MobMind] Livestock tempt via villager fallback (structure detection failed, {} villagers nearby) player={}",
						villagers.size(), player.getGameProfile().name());
			}
			return; // 每轮最多一个玩家触发
		}
	}

	/** 将Minecraft类名翻译成中文 */
	private static String translateMobName(String className) {
		return switch (className) {
			case "Zombie", "ZombieVillager" -> "僵尸";
			case "Skeleton" -> "骷髅";
			case "Creeper" -> "苦力怕";
			case "Spider" -> "蜘蛛";
			case "CaveSpider" -> "洞穴蜘蛛";
			case "ZombifiedPiglin" -> "僵尸猪灵";
			case "Piglin", "PiglinBrute" -> "猪灵";
			case "Blaze" -> "烈焰人";
			case "Ghast" -> "恶魂";
			case "WitherSkeleton" -> "凋灵骷髅";
			case "Husk" -> "尸壳";
			case "Stray" -> "流浪者";
			case "Drowned" -> "溺尸";
			case "Witch" -> "女巫";
			case "Vindicator" -> "卫道士";
			case "Evoker" -> "唤魔者";
			case "Ravager" -> "劫掠兽";
			case "Pillager" -> "掠夺者";
			case "Illusioner" -> "幻术师";
			case "Enderman" -> "末影人";
			case "IronGolem" -> "铁傀儡";
			case "Villager" -> "村民";
			case "WanderingTrader" -> "流浪商人";
			case "Wolf" -> "狼";
			case "Cat" -> "猫";
			case "Fox" -> "狐狸";
			// 牲畜（支持类名和 entity ID 两种格式）
			case "Cow", "cow" -> "牛";
			case "Pig", "pig" -> "猪";
			case "Sheep", "sheep" -> "羊";
			case "Chicken", "chicken" -> "鸡";
			case "Mooshroom", "mooshroom" -> "哞菇";
			case "Horse", "horse" -> "马";
			case "Donkey", "donkey" -> "驴";
			case "Mule", "mule" -> "骡";
			default -> className;
		};
	}

	// ---------- 入口：玩家用钓鱼竿勾住生物 ----------

	private static final Map<UUID, Long> LAST_FISHING_ROD_HOOK = new ConcurrentHashMap<>();

	/**
	 * 玩家用钓鱼竿的浮标勾住了一个生物时触发。
	 */
	public static void onFishingRodHooked(net.minecraft.world.entity.Mob mob, ServerPlayer player) {
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();

		// 特殊处理：钓鱼竿勾住流浪商人的羊驼 → 商人来质问
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		if (entityId.equals("trader_llama") || entityId.equals("llama")) {
			Long last = LAST_FISHING_ROD_HOOK.get(mob.getUUID());
			if (last != null && now - last < 8000) return;
			LAST_FISHING_ROD_HOOK.put(mob.getUUID(), now);
			handleTraderLlamaHooked(mob, player);
			return;
		}

		// 特殊处理：钓鱼竿勾住女巫的黑猫 → 女巫来质问
		if (entityId.equals("cat") || entityId.equals("black_cat")) {
			Long last = LAST_FISHING_ROD_HOOK.get(mob.getUUID());
			if (last != null && now - last < 8000) return;
			LAST_FISHING_ROD_HOOK.put(mob.getUUID(), now);
			handleWitchCatHooked(mob, player);
			return;
		}

		if (!PersonaRegistry.supports(mob)) return;
		Long last = LAST_FISHING_ROD_HOOK.get(mob.getUUID());
		if (last != null && now - last < 8000) return; // 8秒冷却
		LAST_FISHING_ROD_HOOK.put(mob.getUUID(), now);

		// 钓鱼勾住 = 骚扰/挑衅，好感度降低
		MobMindState.adjustFriendship(mob, playerId, -5);

		boolean en = isEnglishUi(playerId);
		boolean isIronGolem = mob.getClass().getSimpleName().equals("IronGolem");
		boolean isVillager = mob instanceof net.minecraft.world.entity.npc.villager.Villager;
		boolean isHostile = mob instanceof net.minecraft.world.entity.monster.Monster;

		String prompt;
		if (isIronGolem) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " just hooked YOU with a FISHING ROD! The hook is stuck in you and they're trying to reel you in like a fish! You are a mighty Iron Golem—this is an outrageous insult! Roar in anger, swat the hook away, and warn them you will attack if they don't stop!)"
					: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你！鱼钩扎在你身上，他们想像钓鱼一样把你拉过去！你是威武的铁傀儡——这是奇耻大辱！怒吼，挥开鱼钩，警告他们再不停止你就动手了！）";
		} else if (isVillager) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " hooked you with a fishing rod! The fishing hook caught you and they're pulling on the line! That hurts and it's incredibly rude! Yell in pain/anger, demand they stop immediately, threaten to call the Iron Golem!)"
					: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你！鱼钩勾住了你的衣服/身体，他们正在拉线！这很疼而且非常无礼！疼得大叫，愤怒地要求他们立刻停下，威胁要叫铁傀儡来！）";
		} else if (isHostile) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " hooked you with a fishing rod! They're trying to pull you like a fish! You are enraged! Roar/hiss/snarl and attack them immediately for this provocation!)"
					: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你！他们想像钓鱼一样拉你！你被激怒了！怒吼/嘶嘶/咆哮，立刻为这个挑衅攻击他们！）";
		} else {
			// 其他友好/中立生物（猫、狼、猪等）
			prompt = en
					? "(Player " + player.getGameProfile().name() + " hooked you with a fishing rod! That hurts and is very annoying! React in character—complain, yelp in surprise, or get annoyed. If you like them you might be confused; if not, get angry.)"
					: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你！很疼而且很烦人！以你的性格做出反应——抱怨、痛叫、或者恼火。如果你和他关系好可能感到困惑，否则生气。）";
		}

		// 铁傀儡/村民/敌对生物被钓鱼勾住时激怒
		if (isIronGolem || isHostile) {
			long gameTime = mob.level().getLevelData().getGameTime();
			MobMindState.provoke(mob, playerId, gameTime + 2000); // 激怒约17秒
			if (mob.getTarget() == null) mob.setTarget(player);
		} else if (isVillager) {
			long gameTime = mob.level().getLevelData().getGameTime();
			MobMindState.provoke(mob, playerId, gameTime + 1000); // 村民激怒8秒（不会攻击但会跑/叫铁傀儡）
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
		MobMindState.recordGrudge(mob, playerId, "用钓鱼竿勾住我",
				mob.level().getGameTime() + 12000);
	}

	/** 钓鱼竿勾住流浪商人的羊驼 → 商人来质问（好感度-5，价格受影响） */
	private static void handleTraderLlamaHooked(net.minecraft.world.entity.Mob llama, ServerPlayer player) {
		UUID playerId = player.getUUID();
		ServerLevel level = (ServerLevel) llama.level();
		// 找附近16格内的流浪商人（用 AbstractVillager 过滤 + 实体ID确认）
		AABB box = llama.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.npc.villager.AbstractVillager> traders = level.getEntitiesOfClass(
				net.minecraft.world.entity.npc.villager.AbstractVillager.class, box, Entity::isAlive);
		net.minecraft.world.entity.npc.villager.AbstractVillager trader = null;
		for (var v : traders) {
			String eid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
			if (eid.equals("wandering_trader") && PersonaRegistry.supports(v)) {
				trader = v;
				break;
			}
		}
		if (trader == null) return;

		// 好感度-5（影响交易价格）
		MobMindState.adjustFriendship(trader, playerId, -5);
		MobMindState.recordGrudge(trader, playerId, "用钓鱼竿勾住我的羊驼",
				trader.level().getGameTime() + 12000);

		boolean en = isEnglishUi(playerId);
		String prompt = en
				? "(Player " + player.getGameProfile().name() + " just hooked your trader llama with a fishing rod! "
				+ "Your poor llama is in pain! You're angry—demand they stop, threaten to raise your prices, "
				+ "or refuse to trade with them! One short line.)"
				: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你的羊驼！你的羊驼疼得直叫！"
				+ "你很生气——喝令他住手，威胁说要涨价，或者干脆不卖给他东西了！只说一句短话。）";
		respond(player, trader, t(prompt, prompt, playerId), false);
	}

	/** 钓鱼竿勾住女巫的黑猫 → 女巫来质问并攻击（好感度-5） */
	private static void handleWitchCatHooked(net.minecraft.world.entity.Mob cat, ServerPlayer player) {
		UUID playerId = player.getUUID();
		ServerLevel level = (ServerLevel) cat.level();
		// 找附近16格内的女巫
		AABB box = cat.getBoundingBox().inflate(16.0);
		List<net.minecraft.world.entity.monster.Witch> witches = level.getEntitiesOfClass(
				net.minecraft.world.entity.monster.Witch.class, box, Entity::isAlive);
		if (witches.isEmpty()) return;

		net.minecraft.world.entity.monster.Witch witch = witches.get(0);
		if (!PersonaRegistry.supports(witch)) return;

		// 好感度-5
		MobMindState.adjustFriendship(witch, playerId, -5);
		MobMindState.recordGrudge(witch, playerId, "用钓鱼竿勾住我的黑猫",
				witch.level().getGameTime() + 12000);

		// 女巫被激怒，开始攻击
		long gameTime = witch.level().getLevelData().getGameTime();
		MobMindState.provoke(witch, playerId, gameTime + 2000); // 激怒约17秒
		if (witch.getTarget() == null) witch.setTarget(player);

		boolean en = isEnglishUi(playerId);
		String prompt = en
				? "(Player " + player.getGameProfile().name() + " just hooked your beloved black cat with a fishing rod! "
				+ "How DARE you hurt my familiar! You're furious—scream a curse, threaten them, and prepare to "
				+ "throw potions at them! One short line.)"
				: "（玩家" + player.getGameProfile().name() + "用钓鱼竿勾住了你的黑猫！你竟敢伤我的灵宠！"
				+ "你怒不可遏——尖叫着诅咒他，威胁他，准备扔药水砸他！只说一句短话。）";
		respond(player, witch, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：玩家给生物装鞍/马铠/用诡异菌 ----------

	private static final Map<UUID, Long> LAST_EQUIP_REACT = new ConcurrentHashMap<>();

	/**
	 * 玩家给生物装备鞍、马铠或用诡异菌吸引炽足兽时触发。
	 * @param itemType "saddle"=鞍, "armor"=马铠, "fungus"=诡异菌/诡异菌钓竿
	 * @param tamed 是否已驯服（马类需要先驯服才能装鞍/马铠）
	 */
	public static void onRidingEquipmentApplied(net.minecraft.world.entity.Mob mob, ServerPlayer player,
												 String itemType, boolean tamed) {
		if (!PersonaRegistry.supports(mob)) return;
		UUID playerId = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_EQUIP_REACT.get(mob.getUUID());
		if (last != null && now - last < 8000) return;
		LAST_EQUIP_REACT.put(mob.getUUID(), now);

		// 装鞍/马铠增加好感度（照顾/信任）
		MobMindState.adjustFriendship(mob, playerId, "fungus".equals(itemType) ? 2 : 5);

		boolean en = isEnglishUi(playerId);
		String mobName = translateMobName(mob.getClass().getSimpleName());
		String prompt;

		if ("saddle".equals(itemType)) {
			if (!tamed) {
				// 马未驯服就不能装鞍（MC原版会阻止），但如果是炽足兽则不需要驯服
				prompt = en
						? "(Player " + player.getGameProfile().name() + " put a saddle on you! Now they can ride you. You accept this—you are ready to be their steed. React: maybe you're excited, proud, or just resigned to being a mount.)"
						: "（玩家" + player.getGameProfile().name() + "给你装上了鞍！现在他们可以骑你了。你接受了——你准备好成为他们的坐骑了。做出反应：也许是兴奋、骄傲、或者认命于被骑的命运。）";
			} else {
				prompt = en
						? "(Player " + player.getGameProfile().name() + " put a saddle on you! You trust them enough to let them ride you. React with acceptance—maybe nuzzle them or stamp your hooves eagerly, ready for adventure together.)"
						: "（玩家" + player.getGameProfile().name() + "给你装上了鞍！你足够信任他们，让他们骑你。表达接受——蹭蹭他们或者兴奋地踏蹄，准备一起冒险。）";
			}
		} else if ("armor".equals(itemType)) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " put horse armor on you! You feel protected and cared for. React with gratitude—maybe toss your mane proudly or snort approvingly. This armor makes you feel strong!)"
					: "（玩家" + player.getGameProfile().name() + "给你穿上了马铠！你感到被保护和被关心。表达感谢——也许骄傲地甩甩鬃毛，或满意地打个响鼻。这副铠甲让你觉得自己很强壮！）";
		} else {
			// 诡异菌/诡异菌钓竿吸引炽足兽
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is luring you with Warped Fungus! You LOVE warped fungus—it's your favorite food! You can't resist following it eagerly toward the player. Express your excitement and hunger!)"
					: "（玩家" + player.getGameProfile().name() + "用诡异菌在吸引你！你最爱诡异菌了——那是你最爱的美食！你忍不住渴望地跟着它走向玩家。表达你的兴奋和嘴馋！）";
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	// ---------- 入口：玩家骑上生物 ----------

	private static final Map<UUID, Long> LAST_RIDE_REACT = new ConcurrentHashMap<>();

	/**
	 * 玩家骑上支持AI的生物时触发（骷髅马、僵尸马、炽足兽等）。
	 */
	public static void onPlayerRideMob(net.minecraft.world.entity.Mob mob, ServerPlayer player) {
		UUID playerId = player.getUUID();
		String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(mob.getType()).getPath();

		// 玩家骑流浪商人的驮羊驼 → 商人来质问（独立于羊驼自身骑乘反应，与拴羊驼共用30秒冷却）
		if (entityId.equals("trader_llama")) {
			onTraderLlamaRidden(mob, player);
		}

		long now = System.currentTimeMillis();
		Long last = LAST_RIDE_REACT.get(mob.getUUID());
		if (last != null && now - last < 10000) return; // 10秒冷却
		LAST_RIDE_REACT.put(mob.getUUID(), now);

		// 骑乘轻微增加好感度（信任）
		MobMindState.adjustFriendship(mob, playerId, 1);

		boolean en = isEnglishUi(playerId);
		String prompt;

		if (entityId.equals("skeleton_horse")) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is now riding you, a Skeleton Horse! You are a creature of bone and undeath, yet you allow this rider on your back. React: maybe you snort rattling breath, accept the rider with quiet resignation, or feel a strange sense of purpose carrying the living.)"
					: "（玩家" + player.getGameProfile().name() + "骑上了你——一匹骷髅马！你是骨骼与亡灵之躯，却允许这个骑手骑在你的背上。做出反应：也许你发出咔嗒作响的鼻息，默默认命地接受骑手，或者感到一种奇异的使命感——载着活人前行。）";
		} else if (entityId.equals("zombie_horse")) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is now riding you, a Zombie Horse! Your flesh is rotting yet you serve as a steed. React: groan with acceptance, or express surprise that a living being dares to ride your undead body. You tolerate it—you chose to trust them enough to be tamed.)"
					: "（玩家" + player.getGameProfile().name() + "骑上了你——一匹僵尸马！你的血肉腐朽，却仍为坐骑。做出反应：接受地低吟，或惊讶于竟有活人敢骑你这不死之躯。你忍受着——因为你选择了信任他们，被他们驯服。）";
		} else if (entityId.equals("strider")) {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is now riding you, a Strider! You are a gentle creature of the Nether, walking on lava with your wide flat feet. React: shiver slightly (you prefer warmth!), make a happy clicking sound, or express mild annoyance at being used as a lava-boat taxi.)"
					: "（玩家" + player.getGameProfile().name() + "骑上了你——一只炽足兽！你是下界的温和生物，用宽扁的脚走在岩浆上。做出反应：微微发抖（你更喜欢温暖！）、发出开心的咔嗒声、或者对你被当作岩浆渡船出租车感到有点不满。）";
		} else {
			prompt = en
					? "(Player " + player.getGameProfile().name() + " is now riding you! React to being mounted—express how you feel about carrying this player.)"
					: "（玩家" + player.getGameProfile().name() + "骑上了你！对被骑乘做出反应——表达你对载着这个玩家的感受。）";
		}

		respond(player, mob, t(prompt, prompt, playerId), false);
	}

	// ========== 村民火灾呼救 ==========
	/** 村民火灾呼救冷却：村民UUID → 上次呼救时间（毫秒） */
	private static final Map<UUID, Long> LAST_FIRE_ALERT = new ConcurrentHashMap<>();
	/** 已发现的火灾位置（防止重复喊）：位置key → 发现时间 */
	private static final Map<Long, Long> KNOWN_FIRES = new ConcurrentHashMap<>();

	/** 判断指定位置附近是否有火焰。营火不算，被不燃方块围起来的岩浆池也不算（岩浆若点燃东西会产生火焰方块）。返回最近的火灾位置，没找到返回null */
	private static BlockPos findFireNearby(ServerLevel level, BlockPos center, int radius) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos closest = null;
		double closestDist = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = level.getBlockState(cursor);
					Block block = state.getBlock();
					String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
					// 营火/灵魂营火是正常使用的，不算火灾
					if (id.equals("campfire") || id.equals("soul_campfire")) continue;
					// 只检测真正的火焰方块（火/灵魂火）= 火灾
					// 注意：岩浆(lava)不再直接判定为火灾——被圆石/石头等不燃方块围起来的人工岩浆池/壁炉是安全的
					// 如果岩浆真的点燃了可燃物，会自然产生火焰方块，此时会被检测到
					boolean isFire = false;
					try {
						isFire = block instanceof net.minecraft.world.level.block.BaseFireBlock;
					} catch (NoClassDefFoundError e) {
						isFire = id.equals("fire") || id.equals("soul_fire");
					}
					if (isFire || id.equals("fire") || id.equals("soul_fire")) {
						double d = center.distSqr(cursor);
						if (d < closestDist) {
							closestDist = d;
							closest = cursor.immutable();
						}
					}
				}
			}
		}
		return closest;
	}

	/** 每2秒检查一次：如果村民发现附近有火或自己身上着火，跑向玩家喊救火 */
	public static void tryHouseFireAlert(MinecraftServer server) {
		long now = System.currentTimeMillis();
		KNOWN_FIRES.entrySet().removeIf(e -> now - e.getValue() > 120000);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue; // 不再排除创造模式——着火了就是着火了
			ServerLevel level = (ServerLevel) player.level();
			if (level.dimension() == Level.NETHER || level.dimension() == Level.END) continue;

			// 玩家32格内的村民都能跑来求救（范围加大）
			AABB villagerBox = player.getBoundingBox().inflate(32.0);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, villagerBox,
					v -> v.isAlive() && !v.isNoAi()); // 去掉getTarget()==null限制——被怪物追时也要喊救火
			for (Villager villager : villagers) {
				UUID vid = villager.getUUID();
				Long lastAlert = LAST_FIRE_ALERT.get(vid);
				if (lastAlert != null && now - lastAlert < 45000) continue;

				// 检测村民10格范围内是否有火（增大范围），或者村民自己身上着火了
				BlockPos firePos = findFireNearby(level, villager.blockPosition(), 10);
				boolean villagerBurning = villager.isOnFire();
				if (firePos == null && !villagerBurning) continue;

				// 如果村民自己着火但没看到火方块，用村民位置作为火点
				if (firePos == null) firePos = villager.blockPosition();

				long posKey = firePos.asLong();
				Long knownAt = KNOWN_FIRES.get(posKey);
				if (knownAt != null && now - knownAt < 20000) continue; // 同一火灾位置20秒内不重复喊
				KNOWN_FIRES.put(posKey, now);

				// 村民跑向玩家（一边跑一边喊），如果在着火还会尝试远离火点
				if (villagerBurning) {
					// 自己着火了——往玩家方向跑，跳进水更好
					villager.getNavigation().moveTo(player, 1.2);
				} else {
					villager.getNavigation().moveTo(player, 1.0);
				}
				villager.getLookControl().setLookAt(player);

				LAST_FIRE_ALERT.put(vid, now);
				UUID pid = player.getUUID();
				boolean en = isEnglishUi(pid);

				String fireDesc;
				String fireId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(firePos).getBlock()).getPath();
				if (fireId.equals("lava") || fireId.equals("flowing_lava")) {
					fireDesc = en ? "lava" : "岩浆";
				} else {
					fireDesc = en ? "fire" : "火";
				}

				String selfBurning = villagerBurning
						? (en ? " I'M ON FIRE TOO! HELP!" : "我身上也着火了！救命啊！")
						: "";

				String prompt = en
						? "(FIRE! THERE IS " + fireDesc.toUpperCase() + " NEARBY! " + selfBurning
						+ " You see flames/smoke near your house at " + firePos.getX() + "," + firePos.getY() + "," + firePos.getZ()
						+ "! You are TERRIFIED and PANICKING, running to player " + player.getGameProfile().name()
						+ " screaming for help! Yell at them to PUT OUT THE FIRE NOW before everything burns down! "
						+ "Your house, food, beds—all in danger! One short panicked cry.)"
						: "（着火啦！！！是" + fireDesc + "！" + selfBurning
						+ "你看到家附近有火光/浓烟，位置大概在" + firePos.getX() + "," + firePos.getY() + "," + firePos.getZ()
						+ "！你吓坏了，极度恐慌地往玩家" + player.getGameProfile().name()
						+ "那边跑，撕心裂肺地喊救命！叫他快来救火！快点！不然房子、粮食、床全烧光了！一句短而慌乱的惨叫。）";
				respond(player, villager, prompt, false);

				level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
						SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 1.2f, 1.5f);

				MobMindMod.LOGGER.info("[MobMind] Fire alert! villager={} at {} fire={} burning={}",
						villager.getName().getString(), villager.blockPosition(), firePos, villagerBurning);
				return;
			}
		}
	}

	// ========== 水流冲毁庄稼 → 村民喝止 ==========
	/** 水冲作物冷却：玩家UUID → 上次骂的时间 */
	private static final Map<UUID, Long> LAST_FLOOD_ALERT = new ConcurrentHashMap<>();
	/** 已发现的淹水农田位置 */
	private static final Map<Long, Long> KNOWN_FLOODS = new ConcurrentHashMap<>();

	/** 每2秒检测一次：村庄农田被水冲时，村民来骂 */
	public static void tryCropFloodAlert(MinecraftServer server) {
		long now = System.currentTimeMillis();
		KNOWN_FLOODS.entrySet().removeIf(e -> now - e.getValue() > 60000);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			if (!(player.level() instanceof ServerLevel level)) continue;
			if (level.dimension() == Level.NETHER || level.dimension() == Level.END) continue;

			// 玩家32格内的村民
			AABB villagerBox = player.getBoundingBox().inflate(32.0);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, villagerBox,
					v -> v.isAlive() && !v.isNoAi());
			for (Villager villager : villagers) {
				if (!PersonaRegistry.supports(villager)) continue;
				UUID pid = player.getUUID();
				Long lastAlert = LAST_FLOOD_ALERT.get(pid);
				if (lastAlert != null && now - lastAlert < 30000) continue; // 每个玩家30秒冷却

				// 扫描村民周围12格内：寻找"农田正上方是水"的情况（正常灌溉水在旁边，不会在作物位置上）
				BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
				BlockPos floodPos = null;
				BlockPos center = villager.blockPosition();
				int r = 12;
				outer:
				for (int dx = -r; dx <= r; dx++) {
					for (int dy = -2; dy <= 2; dy++) {
						for (int dz = -r; dz <= r; dz++) {
							cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
							BlockState state = level.getBlockState(cursor);
							String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
							// 水（静水或流动水）出现在这个位置
							boolean isWater = id.equals("water") || id.equals("flowing_water");
							if (!isWater) continue;
							// 正下方是农田（farmland）→ 作物被冲了！
							BlockPos below = cursor.below();
							BlockState belowState = level.getBlockState(below);
							String belowId = BuiltInRegistries.BLOCK.getKey(belowState.getBlock()).getPath();
							if (belowId.equals("farmland")) {
								// 排除玩家自己锄的田
								if (com.mobmind.behavior.HouseGuard.isPlayerPlaced(below)) continue;
								// 必须在村庄结构内
								if (!isInVillage(level, below)) continue;
								floodPos = cursor.immutable();
								break outer;
							}
						}
					}
				}

				if (floodPos == null) continue;

				// 防止同一淹水位置反复触发
				long posKey = floodPos.asLong();
				Long knownAt = KNOWN_FLOODS.get(posKey);
				if (knownAt != null && now - knownAt < 20000) continue;
				KNOWN_FLOODS.put(posKey, now);
				LAST_FLOOD_ALERT.put(pid, now);

				// 村民跑向玩家
				villager.getNavigation().moveTo(player, 1.0);
				villager.getLookControl().setLookAt(player);

				boolean en = isEnglishUi(pid);
				boolean isFarmer = isFarmer(villager);

				String prompt = en
						? "(WATER! There's water flooding the crops at " + floodPos.getX() + "," + floodPos.getY() + "," + floodPos.getZ()
						+ "! " + (isFarmer ? "You're the farmer—" : "") + "Our crops are being WASHED AWAY! Run to player " + player.getGameProfile().name()
						+ " angrily, yelling at them to STOP THE WATER immediately! Block it, dam it, do something! The crops will all be destroyed! One short furious/panicked cry.)"
						: "（水！！！水在" + floodPos.getX() + "," + floodPos.getY() + "," + floodPos.getZ()
						+ "淹庄稼了！" + (isFarmer ? "你是种地的农民——" : "")
						+ "我们的庄稼要被冲光了！愤怒地跑向玩家" + player.getGameProfile().name()
						+ "，冲他大喊快把水堵上！堵起来！快想办法！不然所有作物都毁了！一句又急又气的短话。）";
				respond(player, villager, prompt, false);

				// 播放不安的声音
				level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
						SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1.0f, 0.8f);

				MobMindMod.LOGGER.info("[MobMind] Crop flood alert! villager={} flood={} isFarmer={}",
						villager.getName().getString(), floodPos, isFarmer);
				MobMindState.adjustFriendship(villager, pid, -2);
				return;
			}
		}
	}
}
