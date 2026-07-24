package com.mobmind.persona;

/**
 * 生物的独立人格设定。每个生物拥有专属的性格、说话风格与背景故事，避免同质化。
 */
public class Personality {
	public String name;
	public String archetype;      // 性格原型，如"暴躁但讲义气"
	public String speakingStyle;  // 说话风格/口头禅
	public String backstory;      // 背景故事（可由 AI 补全）
	public int sociability;       // 0-100 影响主动搭话频率
	public int temper;            // 0-100 影响被激怒的难易
	public int humor;             // 0-100 影响说话幽默感
	public int voiceId = -1;      // TTS 音色 ID（-1 = 未分配）
	public String alignment;      // 善恶倾向标签（来自专属设定，首次生成时抽取，如"善良型（善良/可交流）"）
	public boolean alignmentGood; // 是否为善良型
	public Boolean creativeTaunt; // 10%敌对生物：执着于让创造模式玩家换生存模式（null=未抽取）

	public Personality() {}

	public Personality(String name, String archetype, String speakingStyle, String backstory,
					   int sociability, int temper, int humor) {
		this.name = name;
		this.archetype = archetype;
		this.speakingStyle = speakingStyle;
		this.backstory = backstory;
		this.sociability = sociability;
		this.temper = temper;
		this.humor = humor;
	}
}
