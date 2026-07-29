package com.mobmind.mixin;

import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * 村民被雷劈变成女巫时保留 MobMind 记忆。
 * MobTransformationMixin 已通过 convertTo 拦截处理，此 Mixin 作为后备保险：
 * 如果 thunderHit 内部不通过 convertTo（而是直接生成女巫+移除村民），
 * 则在 thunderHit 返回后手动找到新生成的女巫并迁移数据。
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

	private UUID mobmind$preThunderId = null;

	@Inject(method = "thunderHit", at = @At("HEAD"))
	private void mobmind$beforeThunder(ServerLevel level, net.minecraft.world.entity.LightningBolt lightning, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		mobmind$preThunderId = self.getUUID();
	}

	@Inject(method = "thunderHit", at = @At("RETURN"))
	private void mobmind$afterThunder(ServerLevel level, net.minecraft.world.entity.LightningBolt lightning, CallbackInfo ci) {
		if (mobmind$preThunderId == null) return;
		UUID oldId = mobmind$preThunderId;
		mobmind$preThunderId = null;

		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide()) return;

		// 如果 MobTransformationMixin 已通过 convertTo 处理，旧数据已被清除，这里不需要再做
		if (MobMindState.getAllFriendship(oldId) == null) return;

		// 后备路径：thunderHit 不通过 convertTo，手动找附近新女巫
		List<Mob> nearby = self.level().getEntitiesOfClass(Mob.class,
				self.getBoundingBox().inflate(3.0),
				m -> m instanceof Witch && m.isAlive());
		if (nearby.isEmpty()) return;

		Mob witch = nearby.get(0);
		MobMindState.transferAllData(oldId, witch.getUUID(), witch);
		com.mobmind.MobMindMod.LOGGER.info("[MobMind] 村民被雷劈变成女巫(后备路径): 记忆已迁移到 {}", witch.getUUID());
	}
}
