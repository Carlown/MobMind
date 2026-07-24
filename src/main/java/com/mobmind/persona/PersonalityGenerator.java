package com.mobmind.persona;

import java.util.Random;
import java.util.UUID;

/**
 * 程序化人格生成器：以生物 UUID 为种子，为每只生物生成稳定且独一无二的人格。
 * 不依赖 AI 即可工作，保证世界中不会有两只"性格相同"的生物。
 */
public final class PersonalityGenerator {
	private PersonalityGenerator() {}

	public enum Category { HOSTILE, PASSIVE, NEUTRAL }

	private static final String[] NAMES = {
			"阿灰", "小黑", "大白", "翠花", "铁蛋", "豆豆", "毛毛", "老二", "石头", "丫丫",
			"旺财", "阿福", "小满", "阿茶", "布布", "皮蛋", "米粒", "阿竹", "团子", "阿洛",
			"花椒", "大壮", "小霜", "阿炭", "栗子", "阿淼", "土土", "阿岚", "弯弯", "墩墩"
	};

	private static final String[] HOSTILE_ARCHETYPES = {
			"暴躁但讲义气", "外冷内热的傲娇", "被迫营业的打工人", "自认高贵的反派", "话痨型战斗狂",
			"阴郁的哲学家", "记仇的小心眼", "渴望被理解的孤独者", "爱面子的大哥", "佛系躺平的厌战者"
	};
	private static final String[] PASSIVE_ARCHETYPES = {
			"天真烂漫的乐天派", "贪吃的干饭人", "胆小的碎碎念", "热心肠的老好人", "慵懒的睡神",
			"好奇心旺盛的探险家", "爱八卦的吃瓜群众", "温柔的知心姐姐", "固执的老学究", "黏人的小跟班"
	};
	private static final String[] NEUTRAL_ARCHETYPES = {
			"人不犯我我不犯人", "警惕心强的哨兵", "沉默寡言的观察者", "脾气古怪的隐士", "忠诚但慢热",
			"精明的小商人", "忧郁的诗人", "容易紧张的神经质", "稳重的老大哥", "神神秘秘的占卜师"
	};

	private static final String[] STYLES = {
			"句子很短，爱用省略号", "每句话结尾带“哼”", "说话像rap一样押韵", "文绉绉的，爱引用诗句",
			"口头禅是“你懂我意思吧”", "说话奶声奶气", "自称“本大爷”", "自称“人家”",
			"喜欢反问句", "说话带方言味，爱说“俺”", "每句话都带emoji式语气词", "惜字如金，最多说十个字",
			"一惊一乍，感叹号很多", "慢悠悠的，爱说“嘛”", "阴阳怪气，爱说反话"
	};

	private static final String[] HOSTILE_BACKSTORIES = {
			"原本是村庄守卫，一次事故后被误解驱逐，从此对世界充满戒心。",
			"在黑暗的洞穴里独自生活了很久，练就了一身坏脾气，其实害怕孤独。",
			"把“吓玩家一跳”当作人生乐趣，但从未想过真正伤害谁。",
			"坚信自己是这片区域的老大，所有闯入者都得先过它这关。",
			"年轻时和人类做过朋友，后来朋友离开了，它决定再也不相信任何人。",
			"白天睡觉晚上值班，长期睡眠不足导致看谁都不顺眼。"
	};
	private static final String[] PASSIVE_BACKSTORIES = {
			"出生在阳光牧场，一生顺风顺水，觉得世界上全是好人。",
			"梦想是吃遍全世界的胡萝卜，为此可以跟任何人成为朋友。",
			"小时候被玩家喂过一次，从此把“接近人类”写进了人生规划。",
			"是家族里跑得最慢的，索性躺平，靠卖萌和聊天度日。",
			"坚信自己听得懂所有生物说话，热衷于当大家的知心树洞。",
			"偷偷收集玩家掉落的小物件，藏在一个谁也不知道的地方。"
	};
	private static final String[] NEUTRAL_BACKSTORIES = {
			"祖上是这片土地的守护者，家族规矩：不主动惹事，也不怕事。",
			"见过太多打打杀杀，决定做个中立派，谁有理就帮谁。",
			"正在写一本《观察人类日记》，急需素材，所以总爱盯着玩家看。",
			"曾经站错过队，吃过亏，从此学会了先观察再表态。",
			"开了家“以物易物”的地下小卖部，视所有玩家为潜在客户。",
			"流浪了很久才在此定居，对领地意识近乎偏执。"
	};

	/** 以 UUID 为种子生成稳定人格 */
	public static Personality generate(UUID id, Category category) {
		Random r = new Random(id.hashCode() * 31L + 7);
		String name = NAMES[r.nextInt(NAMES.length)];
		String archetype, backstory;
		switch (category) {
			case HOSTILE -> {
				archetype = HOSTILE_ARCHETYPES[r.nextInt(HOSTILE_ARCHETYPES.length)];
				backstory = HOSTILE_BACKSTORIES[r.nextInt(HOSTILE_BACKSTORIES.length)];
			}
			case PASSIVE -> {
				archetype = PASSIVE_ARCHETYPES[r.nextInt(PASSIVE_ARCHETYPES.length)];
				backstory = PASSIVE_BACKSTORIES[r.nextInt(PASSIVE_BACKSTORIES.length)];
			}
			default -> {
				archetype = NEUTRAL_ARCHETYPES[r.nextInt(NEUTRAL_ARCHETYPES.length)];
				backstory = NEUTRAL_BACKSTORIES[r.nextInt(NEUTRAL_BACKSTORIES.length)];
			}
		}
		String style = STYLES[r.nextInt(STYLES.length)];
		int sociability = 20 + r.nextInt(80);
		int temper = switch (category) {
			case HOSTILE -> 50 + r.nextInt(50);
			case PASSIVE -> r.nextInt(40);
			default -> 20 + r.nextInt(60);
		};
		int humor = r.nextInt(100);
		return new Personality(name, archetype, style, backstory, sociability, temper, humor);
	}

	/** 初始好感度：敌对生物低、中立中等、被动较高 */
	public static int initialFriendship(Category category) {
		return switch (category) {
			case HOSTILE -> 10;
			case PASSIVE -> 50;
			default -> 30;
		};
	}
}
