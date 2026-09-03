package ru.dobriyanonimj.packanimation.mixin.client;

import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dobriyanonimj.packanimation.client.anim.PlayerAnimationEngine;

/**
 * Отключает движок на время отрисовки руки от первого лица.
 * <p>
 * Игра рисует руку в первом лице теми же частями модели, что и всего
 * игрока: вызывает {@code setAngles}, а затем отрисовывает одну руку. Без
 * этого выключателя анимация ходьбы опускала бы руку вдоль тела — то есть
 * за пределы кадра, и выглядело бы это как «руки пропали».
 *
 * <h2>Почему у обработчиков нет параметров</h2>
 * Сигнатура {@code renderRightArm} менялась от версии к версии: в 1.20
 * последним параметром была сущность игрока, а в 1.21.8 это уже текстура
 * скина и флаг рукава. Если перечислить параметры, миксин перестаёт
 * применяться на тех версиях, где они другие, — и игра падает при запуске.
 * <p>
 * Mixin разрешает опускать хвост параметров цели: обработчику достаточно
 * одного {@link CallbackInfo}. Так один и тот же файл подходит всем
 * версиям, и подстраивать его под каждую не нужно.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {

	@Inject(method = "renderRightArm", at = @At("HEAD"))
	private void packanimation$suppressRightArm(CallbackInfo ci) {
		PlayerAnimationEngine.suppressed = true;
	}

	@Inject(method = "renderRightArm", at = @At("RETURN"))
	private void packanimation$restoreRightArm(CallbackInfo ci) {
		PlayerAnimationEngine.suppressed = false;
	}

	@Inject(method = "renderLeftArm", at = @At("HEAD"))
	private void packanimation$suppressLeftArm(CallbackInfo ci) {
		PlayerAnimationEngine.suppressed = true;
	}

	@Inject(method = "renderLeftArm", at = @At("RETURN"))
	private void packanimation$restoreLeftArm(CallbackInfo ci) {
		PlayerAnimationEngine.suppressed = false;
	}
}
