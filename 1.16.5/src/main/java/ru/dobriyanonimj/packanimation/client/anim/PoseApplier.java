package ru.dobriyanonimj.packanimation.client.anim;

import net.minecraft.client.model.ModelPart;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Накладывает позу на части модели игрока.
 * <p>
 * Вынесено из миксина в обычный класс по двум причинам: миксины плохо
 * дружат с вложенными классами, и эту математику можно будет переиспользовать
 * при портах на другие версии — меняться там будет только сам миксин.
 *
 * <h2>Почему сдвиги хранятся, а повороты нет</h2>
 * Ванильный {@code setAngles} каждый кадр присваивает {@code pitch/yaw/roll}
 * заново, поэтому прибавить к ним свой угол безопасно: на следующем кадре
 * прибавка исчезнет вместе с присваиванием.
 * <p>
 * А вот {@code pivotX/Y/Z} ванилла заново не выставляет — они заданы один раз
 * при сборке модели. Если каждый кадр к ним прибавлять, сдвиг копится, и через
 * секунду туловище уезжает на десятки блоков. Поэтому здесь запоминается,
 * сколько было наложено в прошлый раз, и перед новым сдвигом старый снимается.
 * Модель одна на всех игроков, но {@code setAngles} и отрисовка идут подряд
 * для каждого игрока по очереди, так что «снять прошлое, наложить новое»
 * работает и когда игроков много.
 */
public final class PoseApplier {

	/** Сколько мы сейчас прибавили к pivot каждой части: [x, y, z]. */
	private static final Map<ModelPart, float[]> APPLIED = new IdentityHashMap<>();

	/**
	 * Перезагрузка ресурсов создаёт модель заново, и старые части остаются в
	 * карте мусором. Их немного (12 на модель), но пусть карта не растёт вечно.
	 */
	private static final int MAX_TRACKED_PARTS = 64;

	private PoseApplier() {
	}

	/**
	 * @param weight 0 — оставить ванильные углы, 1 — полностью наша поза.
	 *               Промежуточные значения дают плавный переход.
	 */
	public static void apply(Pose pose, float weight, ModelPart head, ModelPart torso,
							ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		rotate(head, pose, Bones.HEAD, weight);
		rotate(torso, pose, Bones.TORSO, weight);
		rotate(rightArm, pose, Bones.RIGHT_ARM, weight);
		rotate(leftArm, pose, Bones.LEFT_ARM, weight);
		rotate(rightLeg, pose, Bones.RIGHT_LEG, weight);
		rotate(leftLeg, pose, Bones.LEFT_LEG, weight);

		// Поворот всего тела применяем к торсу и голове: без корневой кости
		// это ближайшее, что можно сделать, не трогая рендерер.
		rotate(torso, pose, Bones.BODY, weight);

		// Кость body двигает игрока целиком. Части модели — соседи в одном
		// пространстве, поэтому одинаковый сдвиг каждой равносилен сдвигу
		// всего тела, и отдельный миксин в рендерер не нужен.
		float bx = pose.pos(Bones.BODY, 0) * weight;
		float by = pose.pos(Bones.BODY, 1) * weight;
		float bz = pose.pos(Bones.BODY, 2) * weight;

		translate(head, pose, Bones.HEAD, weight, bx, by, bz);
		translate(torso, pose, Bones.TORSO, weight, bx, by, bz);
		translate(rightArm, pose, Bones.RIGHT_ARM, weight, bx, by, bz);
		translate(leftArm, pose, Bones.LEFT_ARM, weight, bx, by, bz);
		translate(rightLeg, pose, Bones.RIGHT_LEG, weight, bx, by, bz);
		translate(leftLeg, pose, Bones.LEFT_LEG, weight, bx, by, bz);
	}

	/**
	 * Возвращает частям ванильные pivot: снимает всё, что мы наложили.
	 * Вызывать всегда, когда анимации нет — иначе части останутся смещёнными
	 * с прошлого кадра.
	 */
	public static void reset(ModelPart head, ModelPart torso, ModelPart rightArm,
							ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		setOffset(head, 0f, 0f, 0f);
		setOffset(torso, 0f, 0f, 0f);
		setOffset(rightArm, 0f, 0f, 0f);
		setOffset(leftArm, 0f, 0f, 0f);
		setOffset(rightLeg, 0f, 0f, 0f);
		setOffset(leftLeg, 0f, 0f, 0f);
	}

	private static void rotate(ModelPart part, Pose pose, int bone, float weight) {
		part.pitch += (float) Math.toRadians(pose.rot(bone, 0)) * weight;
		part.yaw += (float) Math.toRadians(pose.rot(bone, 1)) * weight;
		part.roll += (float) Math.toRadians(pose.rot(bone, 2)) * weight;
	}

	private static void translate(ModelPart part, Pose pose, int bone, float weight,
								float bodyX, float bodyY, float bodyZ) {
		setOffset(part,
				pose.pos(bone, 0) * weight + bodyX,
				pose.pos(bone, 1) * weight + bodyY,
				pose.pos(bone, 2) * weight + bodyZ);
	}

	/**
	 * Делает так, чтобы к pivot части был прибавлен ровно этот сдвиг — не
	 * больше и не меньше, сколько бы кадров ни прошло.
	 */
	private static void setOffset(ModelPart part, float x, float y, float z) {
		float[] applied = APPLIED.get(part);
		if (applied == null) {
			if (x == 0f && y == 0f && z == 0f) {
				return; // нечего снимать и нечего накладывать
			}
			if (APPLIED.size() >= MAX_TRACKED_PARTS) {
				APPLIED.clear();
			}
			applied = new float[3];
			APPLIED.put(part, applied);
		}

		// В пространстве модели ось Y направлена вниз, поэтому «вверх» в
		// анимации — это минус по pivotY.
		part.pivotX += x - applied[0];
		part.pivotY -= y - applied[1];
		part.pivotZ += z - applied[2];

		applied[0] = x;
		applied[1] = y;
		applied[2] = z;
	}
}
