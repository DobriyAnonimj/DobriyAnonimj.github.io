package ru.dobriyanonimj.packanimation.client.anim;

import java.util.HashMap;
import java.util.Map;

/**
 * Собственный движок анимаций Pack Animation.
 * <p>
 * Заменяет внешнюю библиотеку: хранит, что играет у каждого игрока,
 * считает позу на нужный момент времени и плавно переходит между
 * анимациями. Применяет позу к модели миксин
 * {@code PlayerEntityModelMixin}.
 * <p>
 * Зачем свой движок: Player Animator и PAL вместе не покрывают восемь
 * версий игры (1.16.1-1.16.3, весь 1.17, 1.20.2, 1.20.3, 1.20.5, 1.20.6,
 * 1.21.2) — под них библиотеки просто не существует. Свой код от этого не
 * зависит.
 * <p>
 * Цена решения: мы больше не участвуем в общем стеке анимаций, который
 * библиотеки предоставляли для мирного сосуществования модов. С другими
 * модами, анимирующими игрока (например Emotecraft), возможны конфликты.
 */
public final class PlayerAnimationEngine {

	/** Пока флаг поднят, поза не применяется — используется для вида от первого лица. */
	public static boolean suppressed = false;

	private static final Map<Integer, Playback> PLAYBACKS = new HashMap<>();

	private static final Pose SCRATCH_A = new Pose();
	private static final Pose SCRATCH_B = new Pose();
	private static final float[] SCRATCH_VEC = new float[3];

	private PlayerAnimationEngine() {
	}

	private static final class Playback {
		AnimationClip current;
		AnimationClip previous;
		float currentStart;   // секунды
		float previousStart;
		float fadeStart;
		float fadeDuration;
	}

	/**
	 * Начать анимацию с плавным переходом от того, что играет сейчас.
	 *
	 * Ключ — сетевой id сущности, а не UUID: в версиях 1.21.2+ модель получает
	 * объект состояния рендера, в котором UUID вообще нет, а id есть везде.
	 *
	 * @param clip        новая анимация; {@code null} — вернуться к ванильной
	 * @param fadeSeconds длительность перехода
	 * @param now         текущее время в секундах
	 */
	public static void play(int entityId, AnimationClip clip, float fadeSeconds, float now) {
		Playback playback = PLAYBACKS.computeIfAbsent(entityId, id -> new Playback());
		playback.previous = playback.current;
		playback.previousStart = playback.currentStart;
		playback.current = clip;
		playback.currentStart = now;
		playback.fadeStart = now;
		playback.fadeDuration = Math.max(fadeSeconds, 0.001f);
	}

	public static void forget(int entityId) {
		PLAYBACKS.remove(entityId);
	}

	public static void clear() {
		PLAYBACKS.clear();
	}

	public static AnimationClip currentClip(int entityId) {
		Playback playback = PLAYBACKS.get(entityId);
		return playback == null ? null : playback.current;
	}

	/**
	 * Считает позу игрока на данный момент.
	 *
	 * @param out куда записать позу
	 * @return вес позы: 0 — ванильная анимация без изменений, 1 — полностью наша
	 */
	public static float pose(int entityId, float now, Pose out) {
		Playback playback = PLAYBACKS.get(entityId);
		if (playback == null || (playback.current == null && playback.previous == null)) {
			return 0f;
		}

		float fade = Math.min(1f, (now - playback.fadeStart) / playback.fadeDuration);
		if (fade >= 1f) {
			playback.previous = null; // переход закончился
		}

		if (playback.current != null && playback.previous != null) {
			// Переход между двумя анимациями: обе наши, вес полный.
			playback.current.sample(now - playback.currentStart, SCRATCH_A, SCRATCH_VEC);
			playback.previous.sample(now - playback.previousStart, SCRATCH_B, SCRATCH_VEC);
			out.copyFrom(SCRATCH_B);
			out.lerpTowards(SCRATCH_A, fade);
			return 1f;
		}
		if (playback.current != null) {
			// Появляемся поверх ванильной анимации.
			playback.current.sample(now - playback.currentStart, out, SCRATCH_VEC);
			return fade;
		}
		// Уходим обратно к ванильной.
		playback.previous.sample(now - playback.previousStart, out, SCRATCH_VEC);
		return 1f - fade;
	}
}
