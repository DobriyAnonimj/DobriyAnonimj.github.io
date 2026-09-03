package ru.dobriyanonimj.packanimation.client.pack;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import ru.dobriyanonimj.packanimation.client.anim.AnimationClip;
import ru.dobriyanonimj.packanimation.client.anim.PlayerAnimationEngine;
import ru.dobriyanonimj.packanimation.PackAnimationMod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decides, tick by tick and per player, whether that player should be
 * playing their pack's idle / walk / run animation (or nothing, i.e.
 * vanilla), and hands it to {@link PlayerAnimationEngine} with a short
 * crossfade whenever the decision changes.
 * <p>
 * One instance is shared for every player (local and remote alike) — the
 * per-player "what was playing last tick" state lives in {@link #state},
 * keyed by the player's UUID, so it works correctly no matter how many
 * other players are on screen.
 */
public final class MovementAnimationController {

	/**
	 * Minimum horizontal distance (in blocks) a player must have covered in
	 * one tick before we consider them "walking" rather than idle. Normal
	 * walking is ~0.215 blocks/tick and sprinting ~0.28 blocks/tick, so this
	 * comfortably catches real movement while ignoring tiny physics jitter.
	 */
	private static final double MOVE_THRESHOLD = 0.02;
	/**
	 * Гистерезис: порог «пошёл» выше порога «остановился». Без этого игрок,
	 * ползущий ровно на границе скорости, мигал бы между idle и walk.
	 */
	private static final double STOP_THRESHOLD = 0.012;
	/** Насколько сильно сглаживается измеренная скорость (0..1, меньше — плавнее). */
	private static final double SPEED_SMOOTHING = 0.35;
	/** Сколько тиков новое состояние должно продержаться, прежде чем мы его применим. */
	private static final int START_MOVING_DEBOUNCE = 2;
	private static final int STOP_MOVING_DEBOUNCE = 4;

	private static final int STATE_FADE_TICKS = 8;
	private static final int PACK_CHANGE_FADE_TICKS = 12;

	private final PackManager packManager;
	private final Map<UUID, PlayerState> state = new HashMap<>();

	public MovementAnimationController(PackManager packManager) {
		this.packManager = packManager;
	}

	public void forget(UUID playerId) {
		state.remove(playerId);
	}

	public void tick(AbstractClientPlayerEntity player) {
		// Часы обязаны совпадать с теми, по которым миксин считает позу, иначе
		// переходы поедут. Миксин получает animationProgress = возраст сущности
		// в тиках, поэтому здесь берём его же.
		float now = player.age / 20f;

		String packId = PackStateTracker.get(player.getUuid());
		PlayerState playerState = state.computeIfAbsent(player.getUuid(), id -> new PlayerState());

		boolean packChanged = !playerState.packId.equals(packId);

		if (packId == null || packId.isEmpty()) {
			if (playerState.appliedState != null || packChanged) {
				PlayerAnimationEngine.play(player.getEntityId(), null, PACK_CHANGE_FADE_TICKS / 20f, now);
				playerState.appliedState = null;
				playerState.packId = "";
			}
			return;
		}

		PackInfo pack = packManager.findPack(packId);
		if (pack == null) {
			// Player picked a custom pack we don't have installed -> just leave vanilla animations.
			if (playerState.appliedState != null || packChanged) {
				PlayerAnimationEngine.play(player.getEntityId(), null, PACK_CHANGE_FADE_TICKS / 20f, now);
				playerState.appliedState = null;
				playerState.packId = packId;
			}
			return;
		}

		MovementState desired = computeState(player, playerState);

		if (!packChanged && desired == playerState.appliedState) {
			playerState.pendingState = null;
			playerState.pendingTicks = 0;
			return; // nothing to do, already playing the right thing
		}

		// Дребезг: у чужих игроков позиция приходит по сети рывками, поэтому
		// применяем новое состояние только если оно продержалось несколько
		// тиков. Начало движения подтверждаем быстрее, чем остановку — так
		// шаг не «опаздывает», а короткая заминка не сбрасывает ходьбу.
		if (!packChanged) {
			if (desired != playerState.pendingState) {
				playerState.pendingState = desired;
				playerState.pendingTicks = 1;
			} else {
				playerState.pendingTicks++;
			}
			int required = desired == MovementState.IDLE ? STOP_MOVING_DEBOUNCE : START_MOVING_DEBOUNCE;
			if (playerState.pendingTicks < required) {
				return;
			}
		}
		playerState.pendingState = null;
		playerState.pendingTicks = 0;

		Identifier animationId;
		if (desired == MovementState.WALK) {
			animationId = pack.walkAnim();
		} else if (desired == MovementState.RUN) {
			animationId = pack.runAnim();
		} else {
			animationId = pack.idleAnim();
		}

		AnimationClip clip = packManager.getAnimation(animationId);
		if (clip == null) {
			PackAnimationMod.LOGGER.warn("Pack Animation: у пака '{}' не найдена анимация {} ({}) — пропускаем",
					pack.id(), desired, animationId);
			return;
		}

		// Длительности перехода заданы в тиках, движок считает в секундах.
		int fadeTicks = packChanged ? PACK_CHANGE_FADE_TICKS : STATE_FADE_TICKS;
		PlayerAnimationEngine.play(player.getEntityId(), clip, fadeTicks / 20f, now);

		playerState.appliedState = desired;
		playerState.packId = packId;
	}

	/**
	 * Works out idle/walk/run purely from how far the player actually moved
	 * this tick, using only {@code Entity.getX()/getZ()} (always public and
	 * stable), rather than depending on any internal vanilla animation
	 * field. Robust for both the local player and every remote player.
	 */
	private static MovementState computeState(AbstractClientPlayerEntity player, PlayerState playerState) {
		double x = player.getX();
		double z = player.getZ();

		if (!playerState.hasPrevPos) {
			playerState.prevX = x;
			playerState.prevZ = z;
			playerState.hasPrevPos = true;
			return MovementState.IDLE;
		}

		double dx = x - playerState.prevX;
		double dz = z - playerState.prevZ;
		playerState.prevX = x;
		playerState.prevZ = z;

		// Экспоненциальное сглаживание: одиночный «рывок» позиции по сети не
		// должен мгновенно переключать анимацию.
		double instantSpeed = Math.sqrt(dx * dx + dz * dz);
		playerState.speed += (instantSpeed - playerState.speed) * SPEED_SMOOTHING;

		boolean wasMoving = playerState.appliedState != null && playerState.appliedState != MovementState.IDLE;
		double threshold = wasMoving ? STOP_THRESHOLD : MOVE_THRESHOLD;

		if (playerState.speed <= threshold || !player.isOnGround()) {
			return MovementState.IDLE;
		}
		return player.isSprinting() ? MovementState.RUN : MovementState.WALK;
	}

	private static final class PlayerState {
		String packId = "";
		MovementState appliedState = null;
		MovementState pendingState = null;
		int pendingTicks;
		double prevX;
		double prevZ;
		double speed;
		boolean hasPrevPos = false;
	}
}
