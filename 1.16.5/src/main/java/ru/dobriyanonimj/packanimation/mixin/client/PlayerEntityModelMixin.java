package ru.dobriyanonimj.packanimation.mixin.client;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dobriyanonimj.packanimation.client.anim.Pose;
import ru.dobriyanonimj.packanimation.client.anim.PlayerAnimationEngine;
import ru.dobriyanonimj.packanimation.client.anim.PoseApplier;

/**
 * Место, где наш движок встречается с моделью игрока.
 * <p>
 * Вклиниваемся в самый конец {@code setAngles}: ванильный код уже расставил
 * углы конечностей, а мы поверх подмешиваем свою позу с весом. Вес меньше
 * единицы во время плавного перехода — тогда ванильная и наша анимации
 * смешиваются, и переход выглядит гладко.
 * <p>
 * Наследование от {@link BipedEntityModel} нужно, чтобы получить доступ к
 * унаследованным полям {@code head}, {@code body}, рукам и ногам — это
 * обычный приём для миксинов. Конструктор здесь только чтобы устроить
 * компилятор, выполняться он не будет.
 * <p>
 * Важно: выход из метода всегда идёт через {@code PoseApplier}. Даже когда
 * анимации нет, наложенный в прошлый раз сдвиг надо снять — иначе части
 * модели останутся смещёнными с прошлого кадра.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> extends BipedEntityModel<T> {

	@Unique
	private static final Pose packanimation$pose = new Pose();

	// До 1.17 модель собирала свои части сама, поэтому у BipedEntityModel
	// нет конструктора от корневой ModelPart — только от масштаба. Этот
	// конструктор нужен лишь чтобы устроить компилятор, выполняться он не будет.
	protected PlayerEntityModelMixin(float scale) {
		super(scale);
	}

	@Inject(method = "setAngles", at = @At("TAIL"))
	private void packanimation$applyAnimation(T entity, float limbAngle, float limbDistance,
											float animationProgress, float headYaw, float headPitch,
											CallbackInfo ci) {
		if (PlayerAnimationEngine.suppressed || !(entity instanceof AbstractClientPlayerEntity)) {
			packanimation$restoreVanilla();
			return;
		}
		AbstractClientPlayerEntity player = (AbstractClientPlayerEntity) entity;

		// animationProgress — возраст сущности в тиках плюс доля текущего
		// тика, то есть уже сглаженное время. Двадцать тиков в секунде.
		float now = animationProgress / 20f;
		float weight = PlayerAnimationEngine.pose(player.getEntityId(), now, packanimation$pose);
		if (weight <= 0.001f) {
			packanimation$restoreVanilla();
			return;
		}

		PoseApplier.apply(packanimation$pose, weight,
				this.head, this.body, this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
		packanimation$copyToOuterLayers();
	}

	@Unique
	private void packanimation$restoreVanilla() {
		PoseApplier.reset(this.head, this.body, this.rightArm, this.leftArm,
				this.rightLeg, this.leftLeg);
		packanimation$copyToOuterLayers();
	}

	/**
	 * Ванильный setAngles уже скопировал трансформации во внешние слои
	 * (куртка, рукава, штанины, шляпа), а мы поменяли базовые части после
	 * этого — значит копирование нужно повторить, иначе одежда «отстанет»
	 * от тела.
	 */
	@Unique
	private void packanimation$copyToOuterLayers() {
		PlayerEntityModel<?> model = (PlayerEntityModel<?>) (Object) this;
		this.hat.copyTransform(this.head);
		model.jacket.copyTransform(this.body);
		model.rightSleeve.copyTransform(this.rightArm);
		model.leftSleeve.copyTransform(this.leftArm);
		model.rightPants.copyTransform(this.rightLeg);
		model.leftPants.copyTransform(this.leftLeg);
	}
}
