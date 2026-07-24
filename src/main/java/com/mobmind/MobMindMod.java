package com.mobmind;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.BedGuard;
import com.mobmind.behavior.GiftActions;
import com.mobmind.config.MobMindConfig;
import com.mobmind.net.MobPackets;
import com.mobmind.state.MobMindState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoneycombItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MobMindMod implements ModInitializer {
	public static final String MOD_ID = "mobmind";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private int saveCounter = 0;
	private int greetCounter = 0;
	private int barterCounter = 0;
	private int bedCounter = 0;
	private int foodRequestCounter = 0;
	private int giftCounter = 0;

	@Override
	public void onInitialize() {
		MobMindConfig.load();
		MobPackets.registerCommon();

		// 右键有村民在睡的床 → 村民喝止
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
				BedGuard.tryScoldOnClick(sp, world, hitResult.getBlockPos());
			}
			return InteractionResult.PASS;
		});

		// 玩家对生物使用物品：送盔甲自动穿上 / 铜傀儡除锈上蜡
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) return InteractionResult.PASS;
			if (!(entity instanceof Mob mob)) return InteractionResult.PASS;
			var stack = player.getItemInHand(hand);

			// 铜傀儡除锈/上蜡 → 感谢
			boolean isCopperGolem = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(mob.getType()).toString().equals("minecraft:copper_golem");
			if (isCopperGolem && (stack.getItem() instanceof AxeItem || stack.getItem() instanceof HoneycombItem)) {
				MobAiService.onCopperGolemMaintained(mob, sp, stack.getItem() instanceof HoneycombItem);
				return InteractionResult.PASS;
			}

			// 送盔甲：能穿的生物会自动穿上，换下的旧装备丢出来
			var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			if (equippable != null && com.mobmind.persona.PersonaRegistry.supports(mob)) {
				var slot = equippable.slot();
				if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
					if (mob.getItemBySlot(slot).getItem() == stack.getItem()) return InteractionResult.PASS;
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
					MobAiService.onArmorGiven(mob, sp, stack.getHoverName().getString(), slot);
					return InteractionResult.SUCCESS;
				}
			}

			// 喂食物回血：友好生物可以吃玩家手里的食物（苹果/面包/肉/鱼等）
			if (com.mobmind.persona.PersonaRegistry.supports(mob) && stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
					&& mob.getHealth() < mob.getMaxHealth()) {
				float heal = com.mobmind.util.FoodValues.healFor(stack.getItem());
				if (heal > 0) {
					mob.heal(heal);
					world.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
							net.minecraft.sounds.SoundEvents.GENERIC_EAT,
							net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
					if (!sp.isCreative()) stack.shrink(1);
					MobMindState.adjustFriendship(mob, sp.getUUID(), 3);
					MobAiService.onFoodFed(mob, sp, stack.getHoverName().getString(), heal);
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});

		// 玩家在聊天栏发送消息 → 附近生物会听到并回应
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			MobAiService.handleChatMessage(sender, message.signedContent());
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> MobMindState.load(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MobMindState.save(server);
			MobMindState.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			MobMindState.tickGlow(server); // 清理说话高亮
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
		});

		LOGGER.info("[MobMind] 生物AI智慧系统已初始化");
	}
}
