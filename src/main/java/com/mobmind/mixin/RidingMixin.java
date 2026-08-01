package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家骑上生物时触发AI反应。
 * 注入 startRiding：当玩家成功骑上一只支持AI的Mob时触发。
 */
@Mixin(Entity.class)
public abstract class RidingMixin {

	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("TAIL"))
	private void mobmind$onStartRiding(Entity vehicle, boolean force, boolean teleportTo,
									   CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self.level().isClientSide()) return;
		if (!cir.getReturnValue()) return; // 骑乘失败则跳过

		if (!(self instanceof ServerPlayer player)) return;
		if (!(vehicle instanceof Mob mob)) return;
		if (!PersonaRegistry.supports(mob)) return;

		MobAiService.onPlayerRideMob(mob, player);
	}
}
