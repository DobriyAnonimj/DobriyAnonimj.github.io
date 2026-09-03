package ru.dobriyanonimj.packanimation.client.anim;

import java.util.Arrays;

/**
 * Поза: для каждой кости — поворот (в градусах) и смещение.
 * <p>
 * Объект переиспользуется между кадрами, чтобы не создавать мусор на
 * каждый кадр рендера каждого игрока.
 */
public final class Pose {

	public final float[] rotation = new float[Bones.COUNT * 3];
	public final float[] position = new float[Bones.COUNT * 3];

	public void clear() {
		Arrays.fill(rotation, 0f);
		Arrays.fill(position, 0f);
	}

	public void addRotation(int bone, float x, float y, float z) {
		int i = bone * 3;
		rotation[i] += x;
		rotation[i + 1] += y;
		rotation[i + 2] += z;
	}

	public void addPosition(int bone, float x, float y, float z) {
		int i = bone * 3;
		position[i] += x;
		position[i + 1] += y;
		position[i + 2] += z;
	}

	/** this = this * (1 - t) + other * t */
	public void lerpTowards(Pose other, float t) {
		for (int i = 0; i < rotation.length; i++) {
			rotation[i] += (other.rotation[i] - rotation[i]) * t;
			position[i] += (other.position[i] - position[i]) * t;
		}
	}

	public void copyFrom(Pose other) {
		System.arraycopy(other.rotation, 0, rotation, 0, rotation.length);
		System.arraycopy(other.position, 0, position, 0, position.length);
	}

	public float rot(int bone, int axis) {
		return rotation[bone * 3 + axis];
	}

	public float pos(int bone, int axis) {
		return position[bone * 3 + axis];
	}
}
