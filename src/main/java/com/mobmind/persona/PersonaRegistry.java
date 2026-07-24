package com.mobmind.persona;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobmind.MobMindMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 生物设定注册表：加载 assets/mobmind/personas 下的专属设定文件，
 * 按实体类型提供设定文本，并在首次生成时一次性抽取善/恶倾向（之后固定）。
 * 只有注册了设定的生物才启用 AI；未注册的动物不启用。
 */
public final class PersonaRegistry {
	private PersonaRegistry() {}

	/** 一份生物设定 */
	public record Persona(String key, String entityId, boolean baby, int goodPercent,
						  String goodLabel, String evilLabel, String text) {
		/** 以实体 UUID 为种子抽取一次善恶倾向，写入人格档案（幂等，可重复调用结果一致） */
		public void rollAlignment(Personality p, UUID entityUuid) {
			Random r = new Random(entityUuid.hashCode() * 31L + 13);
			boolean good = r.nextInt(100) < goodPercent;
			p.alignmentGood = good;
			p.alignment = good ? goodLabel : evilLabel;
		}
	}

	private static final Map<String, Persona> BY_ENTITY = new HashMap<>();
	private static final Map<String, Persona> BABY_BY_ENTITY = new HashMap<>();

	static {
		load();
	}

	private static void load() {
		try (InputStream in = PersonaRegistry.class.getResourceAsStream("/assets/mobmind/personas/index.json")) {
			if (in == null) {
				MobMindMod.LOGGER.warn("[MobMind] 未找到生物设定索引 index.json");
				return;
			}
			JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String key : root.keySet()) {
				JsonObject o = root.getAsJsonObject(key);
				String entity = o.get("entity").getAsString();
				boolean baby = o.has("baby") && o.get("baby").getAsBoolean();
				Persona p = new Persona(key, entity, baby,
						o.get("goodPercent").getAsInt(),
						o.get("goodLabel").getAsString(),
						o.get("evilLabel").getAsString(),
						readText(key));
				(baby ? BABY_BY_ENTITY : BY_ENTITY).put(entity, p);
			}
			MobMindMod.LOGGER.info("[MobMind] 已加载 {} 份生物专属设定", BY_ENTITY.size() + BABY_BY_ENTITY.size());
		} catch (Exception e) {
			MobMindMod.LOGGER.error("[MobMind] 生物设定加载失败", e);
		}
	}

	private static String readText(String key) throws Exception {
		try (InputStream in = PersonaRegistry.class.getResourceAsStream("/assets/mobmind/personas/" + key + ".txt")) {
			if (in == null) throw new IllegalStateException("缺少设定文件: " + key + ".txt");
			String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (s.startsWith("\uFEFF")) s = s.substring(1);
			return s.trim();
		}
	}

	/** 该生物的专属设定；幼年僵尸使用小僵尸设定；无设定返回 null */
	public static Persona forMob(Mob mob) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
		String entityId = id.toString();
		if (mob.isBaby()) {
			Persona baby = BABY_BY_ENTITY.get(entityId);
			if (baby != null) return baby;
		}
		return BY_ENTITY.get(entityId);
	}

	/** 是否启用 AI（只有设定包内的生物启用，普通动物不启用） */
	public static boolean supports(Mob mob) {
		return forMob(mob) != null;
	}
}
