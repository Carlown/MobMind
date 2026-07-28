package com.mobmind.mixin;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生物变形（convertTo）时保留 MobMind 数据：
 * - 好感度、激怒、安抚标记
 * - 人格、对话历史
 * - 弹药、不死图腾、武器标记
 * - 僵尸村民治愈状态
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

	private static void transferData(Mob oldMob, Mob newMob) {
		java.util.UUID oldId = oldMob.getUUID();
		java.util.UUID newId = newMob.getUUID();

		if (MobMindState.hasPlayerGivenWeapon(oldMob)) {
			MobMindState.markPlayerGivenWeapon(newId);
			newMob.setPersistenceRequired();
		}

		java.util.Map<java.util.UUID, Integer> friendship = MobMindState.getAllFriendship(oldId);
		if (friendship != null) {
			for (java.util.Map.Entry<java.util.UUID, Integer> e : friendship.entrySet()) {
				MobMindState.setFriendship(newId, e.getKey(), e.getValue());
			}
		}

		java.util.Map<java.util.UUID, Long> provoked = MobMindState.getAllProvoked(oldId);
		if (provoked != null) {
			for (java.util.Map.Entry<java.util.UUID, Long> e : provoked.entrySet()) {
				MobMindState.setProvoked(newId, e.getKey(), e.getValue());
			}
		}

		java.util.Map<java.util.UUID, Long> calmed = MobMindState.getAllCalmed(oldId);
		if (calmed != null) {
			for (java.util.Map.Entry<java.util.UUID, Long> e : calmed.entrySet()) {
				MobMindState.setCalmed(newId, e.getKey(), e.getValue());
			}
		}

		Object personality = MobMindState.getPersonalityData(oldId);
		if (personality != null) {
			MobMindState.setPersonalityData(newId, personality);
		}

		Object convHistory = MobMindState.getConversationHistoryData(oldId);
		if (convHistory != null) {
			MobMindState.setConversationHistoryData(newId, convHistory);
		}

		java.util.Map<String, Integer> ammo = MobMindState.getAllAmmo(oldId);
		if (ammo != null) {
			for (java.util.Map.Entry<String, Integer> e : ammo.entrySet()) {
				MobMindState.setAmmo(newId, e.getKey(), e.getValue());
			}
		}

		int totems = MobMindState.getTotemCount(oldId);
		if (totems > 0) {
			MobMindState.setTotemCount(newId, totems);
		}

		Object curing = MobMindState.getCuringData(oldId);
		if (curing != null) {
			MobMindState.setCuringData(newId, curing);
		}

		MobMindState.clearEntityData(oldId);
	}
}
