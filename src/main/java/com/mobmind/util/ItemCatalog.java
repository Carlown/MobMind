package com.mobmind.util;

import com.google.gson.reflect.TypeToken;
import com.mobmind.MobMindMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品目录：把 AI 输出的物品名（中文/英文/注册ID）解析为物品。
 * 中文名来自打包的 items_zh.json（从对应版本 zh_cn.lang 提取）。
 */
public final class ItemCatalog {
	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
	private static volatile Map<String, Item> BY_ZH;
	private static volatile Map<String, Item> BY_EN;

	private ItemCatalog() {}

	/** 在一段文本中识别出的物品（含数量） */
	public record MatchedItem(Item item, String name, int count) {}

	/**
	 * 在自由文本里找物品名（如"三个铁锭换面包"）。
	 * preferLast=true 取文本中靠后的匹配（用于"换"左侧的物品）…实际按最长名称优先、位置其次。
	 */
	public static MatchedItem findInText(String text, boolean preferLast) {
		return findInText(text, preferLast, false);
	}

	public static MatchedItem findInText(String text, boolean preferLast, boolean english) {
		List<MatchedItem> all = findAllInText(text, english);
		if (all.isEmpty()) return null;
		if (preferLast) return all.get(all.size() - 1);
		return all.get(0);
	}

	/** 在自由文本里找出所有互不重叠的物品名及数量 */
	public static List<MatchedItem> findAllInText(String text) {
		return findAllInText(text, false);
	}

	public static List<MatchedItem> findAllInText(String text, boolean english) {
		List<MatchedItem> result = new ArrayList<>();
		if (text == null || text.isEmpty()) return result;
		boolean[] consumed = new boolean[text.length()];
		// 英文模式下优先匹配英文物品名，再回退中文
		if (english) {
			matchCatalog(text, en(), consumed, result, true);
		}
		matchCatalog(text, zh(), consumed, result, false);
		// 按在原文中的位置排序
		result.sort(java.util.Comparator.comparingInt((MatchedItem m) -> {
			int p = text.indexOf(m.name());
			return p < 0 ? Integer.MAX_VALUE : p;
		}));
		return result;
	}

	private static void matchCatalog(String text, Map<String, Item> catalog, boolean[] consumed,
									 List<MatchedItem> result, boolean lowerKey) {
		List<String> keys = new ArrayList<>(catalog.keySet());
		keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
		for (String key : keys) {
			String searchKey = lowerKey ? key.toLowerCase() : key;
			String searchText = lowerKey ? text.toLowerCase() : text;
			int pos = searchText.indexOf(searchKey);
			while (pos >= 0) {
				int end = pos + searchKey.length();
				boolean overlap = false;
				for (int i = pos; i < end && i < consumed.length; i++) {
					if (consumed[i]) { overlap = true; break; }
				}
				if (!overlap) {
					for (int i = pos; i < end && i < consumed.length; i++) consumed[i] = true;
					result.add(new MatchedItem(catalog.get(key), key, countBefore(text, pos, lowerKey)));
				}
				pos = searchText.indexOf(searchKey, pos + 1);
			}
		}
	}

	/** 物品名前的数量（"3个铁锭"/"五颗绿宝石"/"3 apples"），缺省 1 */
	private static int countBefore(String text, int pos) {
		return countBefore(text, pos, false);
	}

	private static int countBefore(String text, int pos, boolean english) {
		String before = text.substring(Math.max(0, pos - 10), pos);
		if (english) {
			java.util.regex.Matcher m = java.util.regex.Pattern
					.compile("([0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|fifty|hundred)(\\s*[a-z]*)?$", java.util.regex.Pattern.CASE_INSENSITIVE)
					.matcher(before);
			if (m.find()) {
				try {
					return Integer.parseInt(m.group(1));
				} catch (NumberFormatException e) {
					return englishNumber(m.group(1).toLowerCase());
				}
			}
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("([0-9]+|[一二三四五六七八九十两])[个颗块只把根堆张片本粒瓶桶]?$").matcher(before);
		if (!m.find()) return 1;
		try {
			return Integer.parseInt(m.group(1));
		} catch (NumberFormatException e) {
			return chineseNumber(m.group(1));
		}
	}

	private static int chineseNumber(String s) {
		if (s == null || s.isEmpty()) return 1;
		String digits = "零一二三四五六七八九";
		if (s.equals("两")) return 2;
		int shi = s.indexOf('十');
		if (shi >= 0) {
			int tens = shi > 0 ? digits.indexOf(s.charAt(0)) : 1;
			int ones = shi < s.length() - 1 ? digits.indexOf(s.charAt(shi + 1)) : 0;
			if (tens > 0) return tens * 10 + Math.max(0, ones);
		}
		int v = digits.indexOf(s.charAt(0));
		return v > 0 ? v : 1;
	}

	private static int englishNumber(String s) {
		return switch (s) {
			case "one" -> 1;
			case "two" -> 2;
			case "three" -> 3;
			case "four" -> 4;
			case "five" -> 5;
			case "six" -> 6;
			case "seven" -> 7;
			case "eight" -> 8;
			case "nine" -> 9;
			case "ten" -> 10;
			case "eleven" -> 11;
			case "twelve" -> 12;
			case "twenty" -> 20;
			case "thirty" -> 30;
			case "fifty" -> 50;
			case "hundred" -> 100;
			default -> 1;
		};
	}

	/** 按名字解析物品，失败返回 null */
	public static Item byName(String raw) {
		if (raw == null) return null;
		String name = raw.trim()
				.replaceAll("[×x]\\s*\\d+$", "")                              // 尾部 "×3"
				.replaceAll("^[0-9零一二三四五六七八九十百千万两]+\\s*[个颗块只把根堆张片本粒瓶桶]?\\s*", "") // 头部 "3个"/"五颗"
				.trim();
		if (name.isEmpty()) return null;
		// 注册ID直查
		if (name.indexOf(':') >= 0) {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(name));
			if (item != null) return item;
		}
		Map<String, Item> zh = zh();
		Item item = zh.get(name);
		if (item != null) return item;
		// 模糊匹配：名字互相包含，取键名最短的（更精确）
		String best = null;
		for (String key : zh.keySet()) {
			if (key.contains(name) || name.contains(key)) {
				if (best == null || key.length() < best.length()) best = key;
			}
		}
		if (best != null) return zh.get(best);
		// 英文回退（服务端语言为 en_us 时的显示名）
		Map<String, Item> en = en();
		String lower = name.toLowerCase();
		item = en.get(lower);
		if (item != null) return item;
		String bestEn = null;
		for (String key : en.keySet()) {
			if (key.contains(lower) || lower.contains(key)) {
				if (bestEn == null || key.length() < bestEn.length()) bestEn = key;
			}
		}
		return bestEn != null ? en.get(bestEn) : null;
	}

	private static Map<String, Item> zh() {
		if (BY_ZH == null) {
			synchronized (ItemCatalog.class) {
				if (BY_ZH == null) {
					Map<String, Item> map = new HashMap<>();
					try (InputStream in = ItemCatalog.class.getResourceAsStream("/assets/mobmind/items_zh.json")) {
						if (in != null) {
							Map<String, String> names = GSON.fromJson(
									new String(in.readAllBytes(), StandardCharsets.UTF_8),
									new TypeToken<Map<String, String>>() {}.getType());
							names.forEach((zhName, id) -> {
								Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
								if (item != null) map.put(zhName, item);
							});
							MobMindMod.LOGGER.info("[MobMind] 物品中文名目录已加载: {} 条", map.size());
						}
					} catch (Exception e) {
						MobMindMod.LOGGER.warn("[MobMind] 物品中文名目录加载失败: {}", e.getMessage());
					}
					BY_ZH = map;
				}
			}
		}
		return BY_ZH;
	}

	private static Map<String, Item> en() {
		if (BY_EN == null) {
			synchronized (ItemCatalog.class) {
				if (BY_EN == null) {
					Map<String, Item> map = new HashMap<>();
					for (Item item : BuiltInRegistries.ITEM) {
						String en = new ItemStack(item).getHoverName().getString().toLowerCase();
						if (!en.isEmpty()) map.putIfAbsent(en, item);
					}
					BY_EN = map;
				}
			}
		}
		return BY_EN;
	}
}
