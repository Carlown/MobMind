package com.mobmind.mixin;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生物变形（convertTo）时保留 MobMind 数据：
 * - 好感度、激怒、安抚标记
 * - 人格、对话历史
 * - 弹药、不死图腾、武器标记
 * - 行为指令（跟随/停留）、砍价记录
 * - 僵尸村民治愈状态
 * 僵尸村民被治愈为村民时，额外给治愈者加大量好感度，确保治愈后保持友好可召唤。
 * 村民被雷劈变成女巫时，通过 convertTo 或 VillagerMixin 后备机制保留所有记忆。
 */
@Mixin(Mob.class)
public abstract class MobTransformationMixin {

	/**
	 * 4参数版本 convertTo(EntityType, ConversionParams, EntitySpawnReason, AfterConversion)
	 */
	@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;", at = @At("RETURN"))
	private <T extends Mob> void mobmind$onConvertTo4(EntityType<T> entityType, ConversionParams params, EntitySpawnReason spawnReason, ConversionParams.AfterConversion<T> afterConversion,
													  CallbackInfoReturnable<T> cir) {
		Mob oldMob = (Mob) (Object) this;
		if (oldMob.level().isClientSide()) return;
		T newMob = cir.getReturnValue();
		if (newMob == null || newMob == oldMob) return;
		transferData(oldMob, newMob);
	}

	/**
	 * 3参数版本 convertTo(EntityType, ConversionParams, AfterConversion)
	 */
	@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;", at = @At("RETURN"))
	private <T extends Mob> void mobmind$onConvertTo3(EntityType<T> entityType, ConversionParams params, ConversionParams.AfterConversion<T> afterConversion,
													  CallbackInfoReturnable<T> cir) {
		Mob oldMob = (Mob) (Object) this;
		if (oldMob.level().isClientSide()) return;
		T newMob = cir.getReturnValue();
		if (newMob == null || newMob == oldMob) return;
		transferData(oldMob, newMob);
	}

	// ---------- 数据迁移 ----------

	@SuppressWarnings("unchecked")
	private static void transferData(Mob oldMob, Mob newMob) {
		java.util.UUID oldId = oldMob.getUUID();
		java.util.UUID newId = newMob.getUUID();
		boolean isZombieVillagerCured = oldMob instanceof ZombieVillager && newMob instanceof Villager;

		// 先迁移所有 MobMind 数据（好感度、人格、对话历史、弹药等）
		MobMindState.transferAllData(oldId, newId, newMob);

		// 僵尸村民治愈完成：给治愈者加大量好感度（至少80，达到友好可召唤）
		if (isZombieVillagerCured) {
			java.util.UUID healerId = null;
			Object curing = MobMindState.getCuringData(newId);
			if (curing instanceof java.util.Map<?, ?> map) {
				healerId = (java.util.UUID) map.get("healer");
			}
			newMob.setPersistenceRequired();
			if (healerId != null) {
				int curFriendship = MobMindState.friendship(newMob, healerId);
				MobMindState.setFriendship(newId, healerId, Math.max(curFriendship, 80));
				com.mobmind.MobMindMod.LOGGER.info("[MobMind] Zombie villager cured: {} cured, healer friendship set to {}",
					newMob.getType().getDescription().getString(), Math.max(curFriendship, 80));
			}
			MobMindState.onZombieVillagerCured(newMob);
		}
	}
}
