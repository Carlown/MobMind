package com.mobmind;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BedGuard;
import com.mobmind.behavior.FriendRecall;
import com.mobmind.behavior.GiftActions;
import com.mobmind.behavior.HouseGuard;
import com.mobmind.behavior.TntFear;
import com.mobmind.behavior.VillagerForage;
import com.mobmind.behavior.WeaponAttackGoal;
import com.mobmind.behavior.WeaponRangedAttackGoal;
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
	private int houseGuardCounter = 0;
	private int foodRequestCounter = 0;
	private int autoEatCounter = 0;
	private int villagerForageCounter = 0;
	private int giftCounter = 0;
	private int tntCounter = 0;
	private int deathRecoveryCounter = 0;
	private int pathBlockCounter = 0;
	private int stuckCheckCounter = 0;
	private int spontaneousGiftCounter = 0;
	private int villagerGossipCounter = 0;
	private int temptReactCounter = 0;
	private int unleashCheckCounter = 0;
	private int livestockTemptCounter = 0;
	private int saddleCheckCounter = 0;
	private int fireAlertCounter = 0;
	private int floodAlertCounter = 0;
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
				BlockPos pos = hitResult.getBlockPos();
				BedGuard.tryScoldOnClick(sp, world, pos);
				// 检测是否在村民家附近开容器
				HouseGuard.onUseBlock(sp, (net.minecraft.server.level.ServerLevel) world, pos);
				// 检测是否在猪灵附近开容器（开箱偷东西激怒猪灵）
				HouseGuard.onUseBlockForPiglins(sp, (net.minecraft.server.level.ServerLevel) world, pos);
			}
			return InteractionResult.PASS;
		});

		// 玩家破坏方块 → 检测是否在村民家搞破坏 / 是否挖金块激怒猪灵
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!world.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
				HouseGuard.onBlockBreak(sp, (net.minecraft.server.level.ServerLevel) world, pos, state);
				HouseGuard.onBlockBreakForPiglins(sp, (net.minecraft.server.level.ServerLevel) world, pos, state);
			}
		});

		// 记录玩家放置的方块（用于排除玩家自己种的菜/放的水/放的栅栏等被误判为村庄财产）
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
				var stack = sp.getItemInHand(hand);
				if (!stack.isEmpty()) {
					// 只有方块物品或桶才能放置方块，其他物品（工具/食物等）使用方块时不应标记
					boolean isBlockItem = stack.getItem() instanceof net.minecraft.world.item.BlockItem;
					boolean isBucket = stack.getItem() instanceof net.minecraft.world.item.BucketItem
							|| stack.getItem() instanceof net.minecraft.world.item.SolidBucketItem;
					if (isBlockItem) {
						var ctx = new net.minecraft.world.item.context.BlockPlaceContext(sp, hand, stack, hitResult);
						var pos = ctx.getClickedPos();
						if (pos != null) {
							HouseGuard.markPlayerPlaced(pos);
							// 玩家种植作物 → 村庄农民来感谢
							if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
									&& com.mobmind.behavior.HouseGuard.isCropBlock(bi.getBlock().defaultBlockState())) {
								MobAiService.onPlayerPlantCrop(sp, (net.minecraft.server.level.ServerLevel) world, pos);
							}
						}
					}
					if (isBucket) {
						BlockPos fluidPos = hitResult.getBlockPos().relative(hitResult.getDirection());
						HouseGuard.markPlayerPlaced(fluidPos);
					}
					// 锄头锄地 → 标记为玩家放置（区分村庄农田和玩家自建农田）
					String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
					if (itemId.endsWith("_hoe")) {
						HouseGuard.markPlayerPlaced(hitResult.getBlockPos());
					}
					// 骨粉催熟作物 → 农民来感谢
					if (itemId.equals("bone_meal")) {
						MobAiService.onPlayerBoneMealCrop(sp, (net.minecraft.server.level.ServerLevel) world, hitResult.getBlockPos());
					}
				}
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

			// 归还流浪商人羊驼：玩家用拴绳或空手右键自己拴住的 trader_llama → 羊驼回到商人身边 + 商人道谢 + 商人拴住羊驼
			if ((stack.is(net.minecraft.world.item.Items.LEAD) || stack.isEmpty())
					&& mob.isLeashed() && mob.getLeashHolder() == sp) {
				String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
				if (entityId.equals("trader_llama") && MobAiService.onTraderLlamaReturned(mob, sp)) {
					return InteractionResult.CONSUME;
				}
			}

			// 拴绳检测：玩家用拴绳右键可拴生物 → 触发反应（不阻止原版行为）
			if (stack.is(net.minecraft.world.item.Items.LEAD)
					&& mob.canBeLeashed()
					&& com.mobmind.persona.PersonaRegistry.supports(mob)) {
				MobAiService.onPlayerLeashMob(mob, sp);
				return InteractionResult.PASS; // 让原版处理实际拴绳
			}
			// 拴绳检测：玩家拴住被动动物（羊/猪/牛等）→ 村民质问
			if (stack.is(net.minecraft.world.item.Items.LEAD)
					&& mob.canBeLeashed()
					&& !com.mobmind.persona.PersonaRegistry.supports(mob)) {
				MobAiService.onPlayerLeashPassiveAnimal(mob, sp);
				return InteractionResult.PASS;
			}

				// 剪羊毛检测：玩家用剪刀右键羊 → 触发村民反应（不阻止原版剪毛）
			if (stack.is(net.minecraft.world.item.Items.SHEARS)
					&& entity instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep
					&& !sheep.isSheared()) {
				MobAiService.onSheepShearedByPlayer(sheep, sp, (net.minecraft.server.level.ServerLevel) world);
				return InteractionResult.PASS;
			}

			// 鞍/马铠/诡异菌检测：对马类、炽足兽装备骑乘物品时触发AI反应
			if (com.mobmind.persona.PersonaRegistry.supports(mob) && !stack.isEmpty()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
				boolean isSaddle = itemId.equals("saddle");
				boolean isHorseArmor = itemId.contains("horse_armor");
				boolean isWarpedFungus = itemId.equals("warped_fungus") || itemId.equals("warped_fungus_on_a_stick");

				if (isSaddle || isHorseArmor || isWarpedFungus) {
					// 判断生物类型
					String entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
							.getKey(mob.getType()).getPath();
					boolean isSkeletonHorse = entityId.equals("skeleton_horse"); // 不需要驯服
					boolean isZombieHorse = entityId.equals("zombie_horse");     // 需要手动驯服
					boolean isStrider = entityId.equals("strider");              // 不需要驯服
					if (isSkeletonHorse || isZombieHorse || isStrider) {
						// 僵尸马需要驯服才装鞍/马铠，骷髅马和炽足兽不需要
						boolean tamed = true;
						if (isZombieHorse && mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse ah) {
							tamed = ah.isTamed();
						}
						String itemType = isSaddle ? "saddle" : (isHorseArmor ? "armor" : "fungus");
						MobAiService.onRidingEquipmentApplied(mob, sp, itemType, tamed);
						return InteractionResult.PASS;
					}
				}
			}

			// 刷怪蛋检测：玩家手持刷怪蛋右键模组支持的生物 → 触发反应（不阻止原版生成）
			// 同类刷怪蛋（如村民蛋右键村民）→ "你想复制我？"
			// 异类刷怪蛋（如牛蛋右键村民）→ "你想造什么？这跟我不是同类"
			if (com.mobmind.persona.PersonaRegistry.supports(mob) && !stack.isEmpty()
					&& stack.getItem() instanceof net.minecraft.world.item.SpawnEggItem) {
				var eggType = net.minecraft.world.item.SpawnEggItem.getType(stack);
				var mobType = mob.getType();
				boolean isSameType = eggType != null && eggType.equals(mobType);
				String eggEntityId = "unknown";
				if (eggType != null) {
					var eggKey = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(eggType);
					if (eggKey != null) eggEntityId = eggKey.getPath();
				}
				MobAiService.onSpawnEggUsed(mob, sp, isSameType, eggEntityId);
				return InteractionResult.PASS; // 让原版处理实际生成
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

			// 右键交互规则：
			// - 不蹲右键：食物喂食、铁傀儡修复、铜傀儡除锈/上蜡；装备类物品阻止误触
			// - 蹲下右键：专门给装备（武器/盾牌/盔甲/弹药/图腾），其他物品交给原版/mod处理
			// - 普通礼物（花、面包等非装备物品）：不需要右键，直接丢地上让生物自己捡（GiftActions.tick处理）
			boolean isFood = stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
					&& com.mobmind.util.FoodValues.healFor(stack.getItem()) > 0;
			boolean canEquip = canUseEquipment(mob);
			boolean isEquipItem = !stack.isEmpty() && canEquip && (
					WeaponAttackGoal.isWeapon(stack)
					|| GiftActions.isShield(stack)
					|| isAmmo(stack)
					|| stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
					|| (stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) != null
						&& stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE).slot().getType()
							== net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR)
			);

			// ====== 不蹲右键 ======
			if (!player.isShiftKeyDown()) {
				// 装备类物品阻止误触
				if (isEquipItem) {
					return InteractionResult.FAIL;
				}
				// 其他物品（命名牌、花、普通礼物等）交给原版处理
				return InteractionResult.PASS;
			}

			// ====== 蹲下右键：喂食 + 装备赠送 ======
			// 蹲下喂食：只有支持的生物才能喂
			if (isFood && PersonaRegistry.supports(mob) && mob.getHealth() < mob.getMaxHealth()) {
				float heal = com.mobmind.util.FoodValues.healFor(stack.getItem());
				if (heal > 0) {
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

			// 不能使用装备的生物（铁傀儡/雪傀儡/铜傀儡）→ 交给原版/mod自己处理（如铜傀儡整理箱子）
			if (!canEquip) {
				return InteractionResult.PASS;
			}

			// 送盔甲：能穿的生物会自动穿上，换下的旧装备丢出来（未注册persona的生物也能穿）
			var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			if (equippable != null && mob.canHoldItem(stack)) {
				var slot = equippable.slot();
				if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
					// 南瓜类只做交易/礼物（丢地上送），不要自动戴头上
					if (stack.is(net.minecraft.world.item.Items.PUMPKIN)
							|| stack.is(net.minecraft.world.item.Items.CARVED_PUMPKIN)
							|| stack.is(net.minecraft.world.item.Items.JACK_O_LANTERN)) {
						return InteractionResult.PASS;
					}
					net.minecraft.world.item.ItemStack oldArmor = mob.getItemBySlot(slot);
					if (!isBetterItem(stack, oldArmor)) return InteractionResult.PASS;
					String armorName = stack.getHoverName().getString();
					mob.setItemSlot(slot, stack.copyWithCount(1));
					mob.setGuaranteedDrop(slot);
					if (!sp.isCreative()) stack.shrink(1);
					if (!oldArmor.isEmpty()) {
						net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
								world, mob.getX(), mob.getY() + 0.5, mob.getZ(), oldArmor);
						world.addFreshEntity(drop);
					}
					mob.setPersistenceRequired();
					MobMindState.adjustFriendship(mob, sp.getUUID(), 5);
					if (PersonaRegistry.supports(mob)) {
						MobAiService.onArmorGiven(mob, sp, armorName, slot);
					}
					return InteractionResult.SUCCESS;
				}
			}

			// 给武器：生物装备到主手，启用WeaponAttackGoal（未注册persona的生物也能接收）
			if (mob.canHoldItem(stack) && WeaponAttackGoal.isWeapon(stack)) {
				net.minecraft.world.item.ItemStack oldWeapon = mob.getItemBySlot(EquipmentSlot.MAINHAND);
				if (!isBetterItem(stack, oldWeapon)) return InteractionResult.PASS;
				String weaponName = stack.getHoverName().getString();
				mob.setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
				mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
				if (!sp.isCreative()) stack.shrink(1);
				if (!oldWeapon.isEmpty()) {
					ItemEntity drop = new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), oldWeapon);
					world.addFreshEntity(drop);
				}
				mob.setPersistenceRequired();
				MobMindState.markPlayerGivenWeapon(mob);
				MobMindState.adjustFriendship(mob, sp.getUUID(), 8);
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onWeaponGiven(mob, sp, weaponName);
				}
				return InteractionResult.SUCCESS;
			}

			// 给盾牌：生物装备到副手，启用ShieldBlockGoal（未注册persona的生物也能接收）
			if (mob.canHoldItem(stack) && GiftActions.isShield(stack)) {
				net.minecraft.world.item.ItemStack oldOff = mob.getItemBySlot(EquipmentSlot.OFFHAND);
				if (!isBetterItem(stack, oldOff)) return InteractionResult.PASS;
				String shieldName = stack.getHoverName().getString();
				mob.setItemSlot(EquipmentSlot.OFFHAND, stack.copyWithCount(1));
				mob.setGuaranteedDrop(EquipmentSlot.OFFHAND);
				if (!sp.isCreative()) stack.shrink(1);
				if (!oldOff.isEmpty()) {
					ItemEntity drop = new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), oldOff);
					world.addFreshEntity(drop);
				}
				mob.setPersistenceRequired();
				MobMindState.markPlayerGivenWeapon(mob);
				MobMindState.adjustFriendship(mob, sp.getUUID(), 8);
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onShieldGiven(mob, sp, shieldName);
				}
				return InteractionResult.SUCCESS;
			}

			// 给箭：增加远程武器弹药（箭不经过canHoldItem检查，直接入MobMind弹药库；未注册persona的生物也能收）
			if (isAmmo(stack)) {
				String ammoName = stack.getHoverName().getString();
				int count = stack.getCount();
				String ammoKey = MobMindState.ammoKeyFor(stack);
				if (ammoKey != null) {
					MobMindState.addAmmo(mob, ammoKey, count);
				}
				if (!sp.isCreative()) stack.shrink(count);
				// 关键！只要生物持有弓/弩，收了箭就标记玩家给予武器，让自定义远程AI接管
				if (WeaponRangedAttackGoal.isHoldingRangedWeapon(mob)) {
					MobMindState.markPlayerGivenWeapon(mob);
				}
				mob.setPersistenceRequired();
				MobMindState.adjustFriendship(mob, sp.getUUID(), 4);
				if (PersonaRegistry.supports(mob)) {
					MobAiService.onAmmoGiven(mob, sp, ammoName, count);
				}
				return InteractionResult.SUCCESS;
			}

			// 给不死图腾：濒死时自动复活（未注册persona的生物也能接收）
			if (mob.canHoldItem(stack)
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

			// 蹲下+右键但不是装备物品（花、面包、食物等普通礼物）→ 交给原版处理
			// 这些物品请直接丢地上赠送，生物会通过GiftActions.tick自动捡起
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
			if (++houseGuardCounter >= 100) { // 每5秒清理/衰减作案计数
				houseGuardCounter = 0;
				HouseGuard.tick(server);
			}
			if (++foodRequestCounter >= 200) { // 每10秒检查一次低血量友好生物要食物
				foodRequestCounter = 0;
				MobAiService.tryFoodRequest(server);
			}
			if (++autoEatCounter >= 60) { // 每3秒检查一次低血量自动吃存储食物
				autoEatCounter = 0;
				MobAiService.tickAutoEatFood(server);
			}
			if (++villagerForageCounter >= 60) { // 每3秒检查一次村民低血量觅食（箱子/干草捆/作物/牲畜）
				villagerForageCounter = 0;
				VillagerForage.tick(server);
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
			if (++pathBlockCounter >= 20) { // 每1秒检查一次玩家挡路（降低频率）
				pathBlockCounter = 0;
				tickPathBlocking(server);
			}
			if (++stuckCheckCounter >= 40) { // 每2秒检查一次生物是否卡住（降低频率）
				stuckCheckCounter = 0;
				tickStuckDetection(server);
			}
			if (++spontaneousGiftCounter >= 1200) { // 每60秒（1分钟）检查一次主动送礼
				spontaneousGiftCounter = 0;
				tickSpontaneousGifts(server);
			}
			if (++villagerGossipCounter >= 600) { // 每30秒检查一次村民小声议论
				villagerGossipCounter = 0;
				MobAiService.tryVillagerGossip(server);
			}
			if (++temptReactCounter >= 40) { // 每2秒检查一次手持物品吸引
				temptReactCounter = 0;
				MobAiService.tryTemptReact(server);
			}
			if (++unleashCheckCounter >= 20) { // 每1秒检查一次拴绳解开
				unleashCheckCounter = 0;
				MobAiService.checkUnleashEvents(server);
			}
			if (++livestockTemptCounter >= 60) { // 每3秒检查一次吸引动物
				livestockTemptCounter = 0;
				MobAiService.tryLivestockTemptInVillage(server);
			}
			if (++saddleCheckCounter >= 20) { // 每1秒检查一次马鞍移除
				saddleCheckCounter = 0;
				MobAiService.checkSaddleRemoved(server);
			}
			if (++fireAlertCounter >= 40) { // 每2秒检查一次村民火灾呼救
				fireAlertCounter = 0;
				MobAiService.tryHouseFireAlert(server);
			}
			if (++floodAlertCounter >= 40) { // 每2秒检查一次水冲作物
				floodAlertCounter = 0;
				MobAiService.tryCropFloodAlert(server);
			}
		});

		LOGGER.info("[MobMind] Mob AI intelligence system initialized");
	}

	/** 检测玩家是否挡在生物去路上，大幅提高触发门槛，必须真的被挡住很久才提示 */
	private static void tickPathBlocking(net.minecraft.server.MinecraftServer server) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
			AABB box = player.getBoundingBox().inflate(2.0); // 缩小检测范围到2格
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
				// 玩家必须在生物正前方（角度<32度，非常严格）
				Vec3 mobLook = mob.getLookAngle().normalize();
				Vec3 toPlayer = player.position().subtract(mob.position()).normalize();
				double dot = mobLook.dot(toPlayer);
				if (dot < 0.85) { // 角度小于32度才算在正前方
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				// 距离必须贴得非常近（1.0格以内，几乎脸贴脸）
				double dist = mob.distanceTo(player);
				if (dist > 1.0) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				PathNavigation nav = mob.getNavigation();
				boolean hasNavTarget = nav.isInProgress() && nav.getTargetPos() != null;
				boolean isFriendTryingToApproach = false;
				if (!hasNavTarget) {
					int f = MobMindState.friendship(mob, player.getUUID());
					if (f >= 80 && dist < 0.8) { // 友谊≥80且几乎贴在一起才认为是想靠近
						isFriendTryingToApproach = true;
					}
				}
				if (!hasNavTarget && !isFriendTryingToApproach) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				// 必须完全不动（速度极小）
				Vec3 vel = mob.getDeltaMovement();
				double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
				if (speed > 0.02) {
					BLOCKED_TICKS.remove(mid);
					continue;
				}
				if (cur < 0) continue;
				int ticks = cur + 1;
				BLOCKED_TICKS.put(mid, ticks);
				// 被挡连续12秒才说让开（每1秒检测一次，需要连续12次）
				if (ticks >= 12) {
					BLOCKED_TICKS.put(mid, -300); // 触发后冷却约150秒（2分半），避免频繁提示
					MobAiService.onPlayerBlockingPath(mob, player);
				}
			}
		}
	}

	/**
	 * 检测生物是否卡住（掉坑、被方块困住、长时间不动等），并触发求救。
	 * 大幅提高触发门槛：必须真被困住很久、友好度足够、在视野内才会求救，大幅降低误报。
	 */
	private static void tickStuckDetection(net.minecraft.server.MinecraftServer server) {
		for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level() != level) continue;
				AABB checkBox = player.getBoundingBox().inflate(16.0); // 缩小检测范围到16格
				List<Mob> nearbyMobs = level.getEntitiesOfClass(Mob.class, checkBox,
						m -> m.isAlive() && PersonaRegistry.supports(m) && !m.isNoAi());
				for (Mob mob : nearbyMobs) {
					UUID mid = mob.getUUID();
					Vec3 pos = mob.position();
					double[] prev = STUCK_TRACKER.get(mid);

					// 战斗中/骑乘中不检测
					if (mob.getTarget() != null || mob.getLastHurtByMob() != null
							|| mob.isPassenger() || mob.isVehicle()) {
						STUCK_TRACKER.remove(mid);
						continue;
					}

					// 只有友好度≥30的生物才向玩家求救（陌生人/敌对生物不随便喊救命）
					int friendship = MobMindState.friendship(mob, player.getUUID());
					if (friendship < 30) {
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
					boolean reallyTrapped = (feetInBlock && surrounded) || (onGround && surrounded && headBlocked);

					// 检测是否在跳跃（Y轴速度为正，试图跳出去）
					Vec3 vel = mob.getDeltaMovement();
					boolean isJumping = vel.y > 0.2; // 提高跳跃阈值，必须是明显在跳

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

					// 必须几乎完全不动（水平<0.1格）且：要么真的被方块困住，要么在明显尝试移动走不了，要么在跳跃出不去
					if (distSq < 0.01 && Math.abs(dy) < 0.3) {
						if (reallyTrapped || isTryingToMove || isJumping) {
							double stuckTicks = prev[3] + 1;
							STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, stuckTicks});
							// 被方块困住：连续20秒才喊（每2秒检测一次，10次）；其他情况（撞墙/跳不出去）：连续30秒才喊（15次）
							double threshold = reallyTrapped ? 10 : 15;
							if (stuckTicks >= threshold) {
								// 必须能直接看见玩家（无遮挡）
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
								STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, -150}); // 触发后冷却5分钟
								MobAiService.onStuck(mob, stuckType);
							}
						} else {
							// 没在尝试移动也没被困也没跳，只是站着，不算卡住
							STUCK_TRACKER.put(mid, new double[]{pos.x, pos.y, pos.z, 0});
						}
					} else if (prev[3] >= 0) {
						// 在移动，重置卡住计数（冷却中不重置）
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

	/**
	 * 评估物品的"战力评分"，用于判断是否应该替换旧装备。
	 * 评分越高越好：材质等级 + 附魔加成 + 耐久加成。
	 */
	private static int getItemScore(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		int score = 0;
		net.minecraft.world.item.Item item = stack.getItem();

		// 1. 材质等级（Tier）
		String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
		if (itemName.contains("netherite")) score += 500;
		else if (itemName.contains("diamond")) score += 400;
		else if (itemName.contains("iron")) score += 300;
		else if (itemName.contains("stone") || itemName.contains("chainmail")) score += 200;
		else if (itemName.contains("golden")) score += 150;
		else if (itemName.contains("wooden") || itemName.contains("leather")) score += 100;

		// 2. 附魔加成：每个附魔+等级*20分
		var enchantments = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
		if (enchantments != null) {
			for (var entry : enchantments.entrySet()) {
				score += entry.getIntValue() * 20;
			}
		}

		// 3. 耐久度加成（剩余耐久比例）
		if (stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int damage = stack.getDamageValue();
			if (max > 0) score += (int)((max - damage) * 0.5);
		}

		// 4. 盾牌特殊：有附魔的比没附魔的好
		if (GiftActions.isShield(stack)) score += 200;

		return score;
	}

	/**
	 * 判断新物品是否比旧物品好（应该替换旧物品）。
	 * 同类型物品（如都是铁剑）比较评分，有附魔/更好耐久的更好；
	 * 不同类型比较材质等级。
	 */
	private static boolean isBetterItem(ItemStack newStack, ItemStack oldStack) {
		if (oldStack == null || oldStack.isEmpty()) return true;
		if (newStack == null || newStack.isEmpty()) return false;
		// 同类型物品：比较评分
		if (newStack.getItem() == oldStack.getItem()) {
			return getItemScore(newStack) > getItemScore(oldStack);
		}
		// 不同类型：看材质等级/评分
		return getItemScore(newStack) > getItemScore(oldStack);
	}

	/**
	 * 判断生物是否能使用玩家给的装备（武器/盾牌/盔甲/弹药/图腾）。
	 * 只有铜傀儡不能装备——它有自己的整理箱子功能，强行setItemSlot会导致物品被AI丢弃造成复制bug。
	 * 铁傀儡、雪傀儡等其他所有生物都可以接收装备。
	 */
	private static boolean canUseEquipment(Mob mob) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
		if ("minecraft:copper_golem".equals(id)) {
			return false;
		}
		return true;
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
