package com.mobmind;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BedGuard;
import com.mobmind.behavior.FriendRecall;
import com.mobmind.behavior.GiftActions;
import com.mobmind.behavior.TntFear;
import com.mobmind.behavior.WeaponAttackGoal;
import com.mobmind.config.MobMindConfig;
import com.mobmind.item.FriendSelectorItem;
import com.mobmind.net.MobPackets;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MobMindMod implements ModInitializer {
	public static final String MOD_ID = "mobmind";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> FRIEND_SELECTOR_ID = ResourceKey.create(Registries.ITEM,
			Identifier.fromNamespaceAndPath(MOD_ID, "friend_selector"));
	public static final Item FRIEND_SELECTOR = new FriendSelectorItem(
			new Item.Properties().setId(FRIEND_SELECTOR_ID).stacksTo(1));

	private int saveCounter = 0;
	private int greetCounter = 0;
	private int barterCounter = 0;
	private int bedCounter = 0;
	private int foodRequestCounter = 0;
	private int giftCounter = 0;
	private int tntCounter = 0;
	private int deathRecoveryCounter = 0;
	private int pathBlockCounter = 0;
	private int stuckCheckCounter = 0;
	private int spontaneousGiftCounter = 0;
	/** 生物被挡路累计tick数：mob uuid -> 连续被挡tick计数 */
	private static final Map<UUID, Integer> BLOCKED_TICKS = new ConcurrentHashMap<>();
	/** 生物卡住追踪：mob uuid -> [上次位置X, 上次位置Y, 上次位置Z, 连续不动tick数] */
	private static final Map<UUID, double[]> STUCK_TRACKER = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		MobMindConfig.load();
		MobPackets.registerCommon();

		// 注册朋友选择器物品
		Registry.register(BuiltInRegistries.ITEM, FRIEND_SELECTOR_ID, FRIEND_SELECTOR);

		// 将朋友选择器添加到创造模式物品栏「工具与实用物品」分类
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(
				net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
				.register(output -> output.accept(new ItemStack(FRIEND_SELECTOR)));
		// 使用 /give @p mobmind:friend_selector 获取物品

		// 注册 /mobmind recall <数量> 指令：设置召唤朋友数量并立即召唤
		// /mobmind recall 0 = 召唤全部朋友
		// /mobmind recall 2 = 召唤最近2个（默认值）
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("mobmind")
					.then(Commands.literal("recall")
							.executes(ctx -> {
								// 无参数：使用配置值立即召唤
								CommandSourceStack src = ctx.getSource();
								if (src.getPlayer() != null) {
									FriendRecall.recallAllFriends(src.getPlayer());
								}
								return 1;
							})
							.then(Commands.argument("count", IntegerArgumentType.integer(0, 100))
									.executes(ctx -> cmdRecall(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
					.then(Commands.literal("help")
							.executes(ctx -> {
								ctx.getSource().sendSuccess(() -> Component.translatable("commands.mobmind.help"), false);
								return 1;
							})));
		});

		// 右键有村民在睡的床 → 村民喝止
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
				BedGuard.tryScoldOnClick(sp, world, hitResult.getBlockPos());
			}
			return InteractionResult.PASS;
		});

		// 玩家对生物使用物品：送盔甲自动穿上 / 给武器装备主手 / 铜傀儡除锈上蜡
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) return InteractionResult.PASS;
			if (!(entity instanceof Mob mob)) return InteractionResult.PASS;
			var stack = player.getItemInHand(hand);

			// 朋友选择器：优先处理选择，阻止村民交易界面等原版交互
			if (stack.is(FRIEND_SELECTOR)) {
				InteractionResult result = stack.interactLivingEntity(sp, mob, hand);
				return result.consumesAction() ? result : InteractionResult.CONSUME;
			}

			// 铜傀儡除锈/上蜡（仅在未蹲下时触发，蹲下+斧可作为武器赠送）
			// 除锈：只有铜傀儡有锈(氧化)时用斧头右键才会感谢；上蜡：未上蜡时用蜜脾右键才感谢
			boolean isCopperGolem = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(mob.getType()).toString().equals("minecraft:copper_golem");
			if (isCopperGolem && !player.isShiftKeyDown()
					&& (stack.getItem() instanceof AxeItem || stack.getItem() instanceof HoneycombItem)) {
				boolean isAxe = stack.getItem() instanceof AxeItem;
				boolean isWax = stack.getItem() instanceof HoneycombItem;
				boolean hasRust = false;
				boolean needsWax = false;
				boolean detectedRust = false;
				boolean detectedWax = false;

				// 通过反射检测铜傀儡的氧化/打蜡状态（Copper Golem mod 通常有 getWeatherState() 和 isWaxed()）
				Class<?> mobClass = mob.getClass();
				try {
					// 尝试获取氧化状态：getWeatherState() 返回枚举 (UNAFFECTED/EXPOSED/WEATHERED/OXIDIZED)
					// 或 getAge() 返回数字，>0 表示有锈
					java.lang.reflect.Method weatherMethod = null;
					for (String name : new String[]{"getWeatherState", "getAge", "getOxidationLevel"}) {
						try {
							weatherMethod = mobClass.getMethod(name);
							break;
						} catch (NoSuchMethodException ignored) {}
					}
					if (weatherMethod != null) {
						detectedRust = true;
						Object result = weatherMethod.invoke(mob);
						if (result instanceof Enum<?> e) {
							hasRust = e.ordinal() > 0; // 0=UNAFFECTED(未氧化)
						} else if (result instanceof Number n) {
							hasRust = n.intValue() > 0;
						}
					}
					// 尝试获取打蜡状态
					try {
						java.lang.reflect.Method waxMethod = mobClass.getMethod("isWaxed");
						detectedWax = true;
						needsWax = !(boolean) waxMethod.invoke(mob);
					} catch (NoSuchMethodException ignored) {}
				} catch (Exception ignored) {
					// 反射失败
				}

				// 如果反射完全无法检测状态（找不到任何方法），退回到原有行为（始终感谢以保持兼容）
				boolean shouldThank;
				if (!detectedRust && !detectedWax) {
					shouldThank = true; // 无法检测，保持原有行为
				} else {
					// 斧头除锈：只有有锈时才感谢；蜜脾上蜡：只有未打蜡时才感谢
					shouldThank = (isAxe && (detectedRust ? hasRust : true))
							|| (isWax && (detectedWax ? needsWax : true));
				}
				if (shouldThank) {
					MobAiService.onCopperGolemMaintained(mob, sp, isWax);
				}
				return InteractionResult.PASS;
			}

			// 铁傀儡修复：手持铁锭右键（不蹲）修复血量，每个铁锭恢复25点生命值
			boolean isIronGolem = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(mob.getType()).toString().equals("minecraft:iron_golem");
			if (isIronGolem && !player.isShiftKeyDown()
					&& stack.is(net.minecraft.world.item.Items.IRON_INGOT)
					&& com.mobmind.persona.PersonaRegistry.supports(mob)) {
				if (mob.getHealth() < mob.getMaxHealth()) {
					float healAmount = Math.min(25.0f, mob.getMaxHealth() - mob.getHealth());
					mob.heal(healAmount);
					// 播放原版铁傀儡修复声音
					world.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
							net.minecraft.sounds.SoundEvents.IRON_GOLEM_REPAIR,
							net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
					// 产生修复粒子效果（类似村民繁殖的爱心粒子）
					for (int i = 0; i < 5; i++) {
						double d0 = mob.getRandom().nextGaussian() * 0.02D;
						double d1 = mob.getRandom().nextGaussian() * 0.02D;
						double d2 = mob.getRandom().nextGaussian() * 0.02D;
						((net.minecraft.server.level.ServerLevel) world).sendParticles(
								net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
								mob.getX() + (mob.getRandom().nextDouble() - 0.5D) * (double) mob.getBbWidth() * 2.0D,
								mob.getY() + mob.getRandom().nextDouble() * (double) mob.getBbHeight(),
								mob.getZ() + (mob.getRandom().nextDouble() - 0.5D) * (double) mob.getBbWidth() * 2.0D,
								1, d0, d1, d2, 0.0D);
					}
					if (!sp.isCreative()) stack.shrink(1);
					mob.setPersistenceRequired();
					MobAiService.onIronGolemRepaired(mob, sp, healAmount);
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS; // 满血则不消耗，交给原版
			}

			// 玩家没有蹲下时：
			// - 食物可以直接右键喂（不消耗给装备）
			// - 武器/盔甲/盾牌/弹药：返回 FAIL 明确阻止交互，防止误触或原版意外消耗物品
			// - 其他物品（命名牌、拴绳等）：返回 PASS 交给原版处理
			boolean isFood = stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
					&& com.mobmind.util.FoodValues.healFor(stack.getItem()) > 0;
			boolean isEquipItem = !stack.isEmpty() && (
					WeaponAttackGoal.isWeapon(stack)
					|| GiftActions.isShield(stack)
					|| isAmmo(stack)
					|| stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
					|| stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) != null
			);

			if (!player.isShiftKeyDown()) {
				if (isFood) {
					// 食物继续走喂食逻辑
				} else if (isEquipItem) {
					// 装备类物品未蹲下：明确拒绝交互，防止物品被原版意外消耗
					return InteractionResult.FAIL;
				} else {
					// 其他物品（命名牌、空手等）交由原版
					return InteractionResult.PASS;
				}
			}

			// —— 以下是蹲下+右键的送礼逻辑 ——

			// 送盔甲：能穿的生物会自动穿上，换下的旧装备丢出来
			var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			if (equippable != null && PersonaRegistry.supports(mob) && mob.canHoldItem(stack)) {
				var slot = equippable.slot();
				if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
					// 南瓜类只做交易/礼物，不要自动戴头上
					if (stack.is(net.minecraft.world.item.Items.PUMPKIN)
							|| stack.is(net.minecraft.world.item.Items.CARVED_PUMPKIN)
							|| stack.is(net.minecraft.world.item.Items.JACK_O_LANTERN)) {
						return InteractionResult.PASS;
					}
					if (mob.getItemBySlot(slot).getItem() == stack.getItem()) return InteractionResult.PASS;
					// 先保存物品名（消耗前），避免 shrink(1) 后变成空气
					String armorName = stack.getHoverName().getString();
					net.minecraft.world.item.ItemStack old = mob.getItemBySlot(slot);
					mob.setItemSlot(slot, stack.copyWithCount(1));
					mob.setGuaranteedDrop(slot);
					if (!sp.isCreative()) stack.shrink(1);
					if (!old.isEmpty()) {
						net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
								world, mob.getX(), mob.getY() + 0.5, mob.getZ(), old);
						world.addFreshEntity(drop);
					}
					mob.setPersistenceRequired(); // 穿了装备就不让它消失
					if (PersonaRegistry.supports(mob)) {
						MobAiService.onArmorGiven(mob, sp, armorName, slot);
					}
					return InteractionResult.SUCCESS;
				}
			}

			// 给武器：右键生物时手持武器，生物会装备到主手并正确使用攻击
			// 武器装备后会自动使用武器的伤害值进行攻击（Minecraft 原版 doHurtTarget）
			// 持武器的生物还会通过 WeaponAttackGoal 主动攻击附近的敌人保护玩家
			// 仅支持PersonaRegistry中注册的生物（普通动物如猪牛狼不接收武器）
			if (PersonaRegistry.supports(mob) && mob.canHoldItem(stack) && WeaponAttackGoal.isWeapon(stack)) {
				// 主手已有同款武器则跳过
				if (mob.getItemBySlot(EquipmentSlot.MAINHAND).getItem() == stack.getItem()) return InteractionResult.PASS;
				// 先保存武器名（消耗前），避免 shrink(1) 后变成空气
				String weaponName = stack.getHoverName().getString();
				net.minecraft.world.item.ItemStack oldWeapon = mob.getItemBySlot(EquipmentSlot.MAINHAND);
				mob.setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
				mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
				if (!sp.isCreative()) stack.shrink(1);
				// 旧主手物品丢出来，不直接消失
				if (!oldWeapon.isEmpty()) {
					ItemEntity drop = new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), oldWeapon);
					world.addFreshEntity(drop);
				}
				mob.setPersistenceRequired(); // 拿了武器就不让它消失
				MobMindState.markPlayerGivenWeapon(mob); // 标记为玩家给予武器，启用自定义攻击 Goal
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onWeaponGiven(mob, sp, weaponName);
				}
				return InteractionResult.SUCCESS;
			}

			// 给盾牌：右键生物时手持盾牌，生物会装备到副手并正确使用格挡
			// 持盾牌的生物会通过 ShieldBlockGoal 在受到攻击时举起盾牌格挡伤害
			// 仅支持PersonaRegistry中注册的生物（普通动物如猪牛狼不接收盾牌）
			if (PersonaRegistry.supports(mob) && mob.canHoldItem(stack) && GiftActions.isShield(stack)) {
				// 副手已有同款盾牌则跳过
				if (mob.getItemBySlot(EquipmentSlot.OFFHAND).getItem() == stack.getItem()) return InteractionResult.PASS;
				// 先保存盾牌名（消耗前），避免 shrink(1) 后变成空气
				String shieldName = stack.getHoverName().getString();
				net.minecraft.world.item.ItemStack oldOff = mob.getItemBySlot(EquipmentSlot.OFFHAND);
				mob.setItemSlot(EquipmentSlot.OFFHAND, stack.copyWithCount(1));
				mob.setGuaranteedDrop(EquipmentSlot.OFFHAND);
				if (!sp.isCreative()) stack.shrink(1);
				// 旧副手物品丢出来，不直接消失
				if (!oldOff.isEmpty()) {
					ItemEntity drop = new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), oldOff);
					world.addFreshEntity(drop);
				}
				mob.setPersistenceRequired(); // 拿了盾牌就不让它消失
				MobMindState.markPlayerGivenWeapon(mob); // 标记为玩家给予武器/盾牌，启用自定义格挡 Goal
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onShieldGiven(mob, sp, shieldName);
				}
				return InteractionResult.SUCCESS;
			}

			// 给箭：增加远程武器弹药（弓/弩使用，支持普通箭/光灵箭/药水箭）
			// 仅支持PersonaRegistry中注册的生物（普通动物如猪牛狼不接收弹药）
			if (PersonaRegistry.supports(mob) && mob.canHoldItem(stack) && isAmmo(stack)) {
				String ammoName = stack.getHoverName().getString();
				int count = stack.getCount();
				String ammoKey = MobMindState.ammoKeyFor(stack);
				if (ammoKey != null) {
					MobMindState.addAmmo(mob, ammoKey, count);
				}
				if (!sp.isCreative()) stack.shrink(count);
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onAmmoGiven(mob, sp, ammoName, count);
				}
				return InteractionResult.SUCCESS;
			}

			// 给不死图腾：生物濒死时自动使用复活
			// 仅支持PersonaRegistry中注册的生物（普通动物如猪牛狼不接收图腾）
			if (PersonaRegistry.supports(mob) && mob.canHoldItem(stack)
					&& stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
				int count = stack.getCount();
				MobMindState.addTotem(mob, count);
				if (!sp.isCreative()) stack.shrink(count);
				String totemName = stack.getHoverName().getString();
				MobMindState.adjustFriendship(mob, sp.getUUID(), 10);
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onTotemGiven(mob, sp, totemName, count);
				}
				return InteractionResult.SUCCESS;
			}

			// 喂食物回血：友好生物可以吃玩家手里的食物（苹果/面包/肉/鱼等，蹲下或直接右键均可）
			if (PersonaRegistry.supports(mob) && stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
					&& mob.getHealth() < mob.getMaxHealth()) {
				float heal = com.mobmind.util.FoodValues.healFor(stack.getItem());
				if (heal > 0) {
					// 先保存食物名（消耗前），避免 shrink(1) 后变成空气
					String foodName = stack.getHoverName().getString();
					mob.heal(heal);
					world.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
							net.minecraft.sounds.SoundEvents.GENERIC_EAT,
							net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
					if (!sp.isCreative()) stack.shrink(1);
					MobMindState.adjustFriendship(mob, sp.getUUID(), 3);
					MobAiService.onFoodFed(mob, sp, foodName, heal);
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});

		// 玩家在聊天栏发送消息 → 附近生物会听到并回应
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			MobAiService.handleChatMessage(sender, message.signedContent());
		});

		// 玩家死亡/复活事件：友好生物捡起死亡掉落物并归还
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			com.mobmind.behavior.DeathItemRecovery.onPlayerRespawn(newPlayer);
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			com.mobmind.behavior.DeathItemRecovery.onPlayerDeath(oldPlayer);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> MobMindState.load(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MobMindState.save(server);
			MobMindState.clear();
		});

		// 已建立友好关系的生物重新加载时恢复不消失标记
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Mob mob) {
				MobMindState.ensurePersistenceIfFriendly(mob);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			MobMindState.tickGlow(server); // 清理说话高亮
			FriendSelectorItem.tickGlow(server); // 选中朋友持续光效
			MobMindState.tickBossCalm(server); // 强制安抚末影龙/凋灵
			MobMindState.tickCuringZombieVillagers(server); // 治疗中僵尸村民帮助救助者
			if (++saveCounter >= 6000) { // 每5分钟自动保存
				saveCounter = 0;
				MobMindState.save(server);
			}
			if (MobMindConfig.get().greetingEnabled && ++greetCounter >= 200) {
				greetCounter = 0;
				if (!MobAiService.tryCreativeTaunt(server)) { // 求战型怪物优先搭话
					MobAiService.tryRandomGreeting(server);
				}
			}
			if (++barterCounter >= 20) { // 每秒扫描一次以物易物交付
				barterCounter = 0;
				BarterActions.tickDeals(server);
			}
			if (++bedCounter >= 40) { // 每2秒检查占床事件
				bedCounter = 0;
				BedGuard.tick(server);
			}
			if (++foodRequestCounter >= 200) { // 每10秒检查一次低血量友好生物要食物
				foodRequestCounter = 0;
				MobAiService.tryFoodRequest(server);
			}
			if (++giftCounter >= 10) { // 每0.5秒检查玩家扔给友好生物的礼物
				giftCounter = 0;
				GiftActions.tick(server);
			}
			if (++tntCounter >= 20) { // 每秒检查一次 TNT 恐惧
				tntCounter = 0;
				TntFear.tick(server);
			}
			if (++deathRecoveryCounter >= 20) { // 每秒检查一次友好生物捡玩家死亡掉落物
				deathRecoveryCounter = 0;
				com.mobmind.behavior.DeathItemRecovery.tick(server);
			}
			if (++pathBlockCounter >= 10) { // 每0.5秒检查一次玩家挡路
				pathBlockCounter = 0;
				tickPathBlocking(server);
			}
			if (++stuckCheckCounter >= 20) { // 每秒检查一次生物是否卡住
				stuckCheckCounter = 0;
				tickStuckDetection(server);
			}
			if (++spontaneousGiftCounter >= 1200) { // 每60秒（1分钟）检查一次主动送礼
				spontaneousGiftCounter = 0;
				tickSpontaneousGifts(server);
			}
		});

		LOGGER.info("[MobMind] 生物AI智慧系统已初始化");
	}

	/** 检测玩家是否挡在生物去路上，被挡约5秒后才触发让开提示，降低灵敏度 */
	private static void tickPathBlocking(net.minecraft.server.MinecraftServer server) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
			AABB box = player.getBoundingBox().inflate(4.0);
			List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box, m ->
					m.isAlive() && PersonaRegistry.supports(m) && m != player.getVehicle());
			for (Mob mob : nearby) {
				UUID mid = mob.getUUID();
				int cur = BLOCKED_TICKS.getOrDefault(mid, 0);
				if (cur < 0) {
					BLOCKED_TICKS.put(mid, cur + 1);
				}
				if (mob.getTarget() != null || mob.getLastHurtByMob() != null) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				if (mob.isPassenger() || mob.isVehicle()) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				// 玩家必须在生物正前方（角度<45度，更严格）
				Vec3 mobLook = mob.getLookAngle().normalize();
				Vec3 toPlayer = player.position().subtract(mob.position()).normalize();
				double dot = mobLook.dot(toPlayer);
				if (dot < 0.7) { // 角度小于45度才算在正前方
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				// 距离必须非常近（1.8格以内，几乎贴在一起）
				double dist = mob.distanceTo(player);
				if (dist > 1.8) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				PathNavigation nav = mob.getNavigation();
				boolean hasNavTarget = nav.isInProgress() && nav.getTargetPos() != null;
				boolean isFriendTryingToApproach = false;
				if (!hasNavTarget) {
					int f = MobMindState.friendship(mob, player.getUUID());
					if (f >= 60 && dist < 1.5) {
						isFriendTryingToApproach = true;
					}
				}
				if (!hasNavTarget && !isFriendTryingToApproach) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				// 必须几乎完全不动（速度非常小）
				Vec3 vel = mob.getDeltaMovement();
				double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
				if (speed > 0.05) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				if (cur < 0) continue;
				int ticks = cur + 1;
				BLOCKED_TICKS.put(mid, ticks);
				// 被挡约5秒（10次检测，每次0.5秒）才说让开，大幅降低灵敏度
				if (ticks >= 10) {
					BLOCKED_TICKS.put(mid, -120); // 触发后冷却约60秒
					MobAiService.onPlayerBlockingPath(mob, player);
				}
			}
		}
	}

	/**
	 * 检测生物是否卡住（掉坑、被方块困住、长时间不动等），并触发求救。
	 * 每秒检测一次；必须真被困住（长时间不动+尝试移动/跳跃，或被方块埋住）才触发，降低误报。
	 */
	private static void tickStuckDetection(net.minecraft.server.MinecraftServer server) {
		for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level() != level) continue;
				AABB checkBox = player.getBoundingBox().inflate(48.0);
				List<Mob> nearbyMobs = level.getEntitiesOfClass(Mob.class, checkBox,
						m -> m.isAlive() && PersonaRegistry.supports(m) && !m.isNoAi());
				for (Mob mob : nearbyMobs) {
					UUID mid = mob.getUUID();
					Vec3 pos = mob.position();
					double[] prev = STUCK_TRACKER.get(mid);

					if (mob.getTarget() != null || mob.getLastHurtByMob() != null
							|| mob.isPassenger() || mob.isVehicle()) {
						STUCK_TRACKER.remove(mid);
						continue;
					}

					BlockPos mobPos = mob.blockPosition();
					BlockPos below = mobPos.below();
					boolean onGround = level.getBlockState(below).canOcclude() || mob.onGround();

					// 判断被方块困住：身体在墙里窒息、或者头被方块盖住且在地上且四周被围
					boolean feetInBlock = level.getBlockState(mobPos).canOcclude(); // 窒息中
					boolean headBlocked = !level.getBlockState(mobPos.above(1)).isAir()
							&& level.getBlockState(mobPos.above(1)).canOcclude();
					boolean surrounded = isSolid(level, mobPos.north()) && isSolid(level, mobPos.south())
							&& isSolid(level, mobPos.east()) && isSolid(level, mobPos.west());

					// 真的被困住：在地上+四周都是墙+头被盖住（牢笼/坑），或正在窒息
					// 需要连续检测到3次（3秒）才触发，避免偶尔卡一下就喊
					boolean reallyTrapped = (feetInBlock && surrounded) || (onGround && surrounded && headBlocked);

					// 检测是否在跳跃（Y轴速度为正，试图跳出去）
					Vec3 vel = mob.getDeltaMovement();
					boolean isJumping = vel.y > 0.15;

					if (prev == null) {
						STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
						continue;
					}

					double dx = pos.x - prev[0];
					double dy = pos.y - prev[1];
					double dz = pos.z - prev[2];
					double distSq = dx*dx + dz*dz;

					PathNavigation nav = mob.getNavigation();
					boolean isTryingToMove = nav.isInProgress() && nav.getTargetPos() != null;

					// 几乎没动（水平<0.2格）且：要么在尝试移动走不了，要么在跳跃出不去，要么被方块困住
					if (distSq < 0.04 && Math.abs(dy) < 0.5) {
						if (reallyTrapped || isTryingToMove || isJumping) {
							double stuckTicks = prev[3] + 1;
							STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, stuckTicks});
							// 被方块困住：连续8秒才喊；其他情况（撞墙/跳不出去）：连续12秒才喊
							double threshold = reallyTrapped ? 8 : 12;
							if (stuckTicks >= threshold) {
								// 只有能看见玩家时才求救，隔墙不喊
								if (!mob.hasLineOfSight(player)) {
									STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
									continue;
								}
								int stuckType;
								if (surrounded && !onGround) {
									stuckType = 1; // 掉坑
								} else if (reallyTrapped) {
									stuckType = 0; // 被埋/困住
								} else if (isJumping) {
									stuckType = 1; // 在坑里跳不出去
								} else {
									stuckType = 2; // 撞墙/路被挡
								}
								STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
								MobAiService.onStuck(mob, stuckType);
							}
						} else {
							// 没在尝试移动也没被困也没跳，只是站着，不算卡住
							STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
						}
					} else {
						// 在移动，重置卡住计数
						STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
					}
				}
			}
		}
	}

	private static boolean isSolid(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).canOcclude();
	}

	/**
	 * 主动送礼检测：每分钟检查一次附近对玩家有好感（≥55）的生物，
	 * 根据好感度有概率主动丢出一个礼物并说话。
	 * 好感度越高送礼概率越大，onSpontaneousGift内部有10分钟冷却。
	 */
	private static void tickSpontaneousGifts(net.minecraft.server.MinecraftServer server) {
		java.util.Random random = new java.util.Random();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
			// 检查玩家周围10格内的友好生物
			AABB box = player.getBoundingBox().inflate(10.0);
			List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box, m ->
					m.isAlive() && PersonaRegistry.supports(m)
							&& m.hasLineOfSight(player)
							&& !m.isVehicle() && !m.isPassenger()
							&& m.getTarget() == null && m.getLastHurtByMob() == null);
			for (Mob mob : nearby) {
				int f = MobMindState.friendship(mob, player.getUUID());
				if (f < 55) continue; // 好感度至少55（好朋友级别）才会主动送礼

				// 根据好感度计算概率：55好感=约3%，70=7%，80=12%，90+=18%（每分钟检查一次）
				float chance;
				if (f >= 90) chance = 0.18f;
				else if (f >= 80) chance = 0.12f;
				else if (f >= 70) chance = 0.07f;
				else chance = 0.03f;

				// 距离越近概率越高
				double dist = mob.distanceTo(player);
				if (dist > 8) chance *= 0.5;
				if (dist < 4) chance *= 1.5f;

				if (random.nextFloat() > chance) continue;

				// 有以物易物约定时不主动送礼
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) continue;

				// 送出礼物并触发AI说话（onSpontaneousGift内部有冷却检查）
				net.minecraft.world.item.ItemStack gift = com.mobmind.behavior.BehaviorActions.dropGiftFor(mob, player);
				String giftName = gift.getHoverName().getString();
				MobAiService.onSpontaneousGift(mob, player, giftName);
				break; // 一个玩家每次tick最多触发一个生物送礼，避免刷屏
			}
		}
	}

	/** 判断物品是否是远程武器弹药（普通箭、光灵箭、药箭） */
	private static boolean isAmmo(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.is(net.minecraft.world.item.Items.ARROW)
				|| stack.is(net.minecraft.world.item.Items.SPECTRAL_ARROW)
				|| stack.is(net.minecraft.world.item.Items.TIPPED_ARROW);
	}

	private static int cmdRecall(CommandContext<CommandSourceStack> ctx, int count) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("This command can only be used by players"));
			return 0;
		}
		FriendRecall.recallFriends(player, count, true);
		return 1;
	}
}
