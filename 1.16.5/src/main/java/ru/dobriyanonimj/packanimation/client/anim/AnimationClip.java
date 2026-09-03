package ru.dobriyanonimj.packanimation.client.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разобранная анимация: ключевые кадры по костям + выборка позы на момент
 * времени.
 * <p>
 * Понимает тот же json, что и раньше (подмножество формата GeckoLib/Bedrock),
 * поэтому все существующие паки, включая пользовательские, продолжают
 * работать без изменений.
 */
public final class AnimationClip {

	/** Один канал (rotation или position) одной кости. */
	private static final class Track {
		final float[] times;
		final float[] values; // по 3 значения на кадр

		Track(float[] times, float[] values) {
			this.times = times;
			this.values = values;
		}

		/** Линейная интерполяция между соседними кадрами. */
		void sample(float time, float[] out) {
			if (times.length == 0) {
				out[0] = out[1] = out[2] = 0f;
				return;
			}
			if (time <= times[0]) {
				copy(0, out);
				return;
			}
			int last = times.length - 1;
			if (time >= times[last]) {
				copy(last, out);
				return;
			}
			// Кадров немного (обычно 17-25), линейный поиск дешевле бинарного
			// по накладным расходам и проще читается.
			int i = 0;
			while (i < last && times[i + 1] < time) {
				i++;
			}
			float span = times[i + 1] - times[i];
			float t = span <= 0f ? 0f : (time - times[i]) / span;
			int a = i * 3;
			int b = (i + 1) * 3;
			out[0] = values[a] + (values[b] - values[a]) * t;
			out[1] = values[a + 1] + (values[b + 1] - values[a + 1]) * t;
			out[2] = values[a + 2] + (values[b + 2] - values[a + 2]) * t;
		}

		private void copy(int frame, float[] out) {
			int i = frame * 3;
			out[0] = values[i];
			out[1] = values[i + 1];
			out[2] = values[i + 2];
		}
	}

	private final float length;
	private final boolean loop;
	private final Track[] rotations = new Track[Bones.COUNT];
	private final Track[] positions = new Track[Bones.COUNT];

	private AnimationClip(float length, boolean loop) {
		this.length = Math.max(length, 0.05f);
		this.loop = loop;
	}

	public float length() {
		return length;
	}

	public boolean loop() {
		return loop;
	}

	/** Заполняет позу состоянием анимации на момент {@code time} (в секундах). */
	public void sample(float time, Pose out, float[] scratch) {
		float t = loop ? time % length : Math.min(time, length);
		if (t < 0) {
			t += length;
		}
		out.clear();
		for (int bone = 0; bone < Bones.COUNT; bone++) {
			Track rotation = rotations[bone];
			if (rotation != null) {
				rotation.sample(t, scratch);
				out.addRotation(bone, scratch[0], scratch[1], scratch[2]);
			}
			Track position = positions[bone];
			if (position != null) {
				position.sample(t, scratch);
				out.addPosition(bone, scratch[0], scratch[1], scratch[2]);
			}
		}
	}

	// ------------------------------------------------------------------
	// Разбор json
	// ------------------------------------------------------------------

	/**
	 * Разбирает файл анимации. Берётся первая (обычно единственная) запись
	 * из объекта "animations".
	 *
	 * @return разобранная анимация или {@code null}, если файл не подходит
	 */
	public static AnimationClip parse(JsonObject root) {
		if (!root.has("animations") || !root.get("animations").isJsonObject()) {
			return null;
		}
		JsonObject animations = root.getAsJsonObject("animations");
		for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject anim = entry.getValue().getAsJsonObject();
			float length = anim.has("animation_length") ? anim.get("animation_length").getAsFloat() : 1f;
			boolean loop = !anim.has("loop") || parseLoop(anim.get("loop"));

			AnimationClip clip = new AnimationClip(length, loop);
			if (anim.has("bones") && anim.get("bones").isJsonObject()) {
				for (Map.Entry<String, JsonElement> boneEntry : anim.getAsJsonObject("bones").entrySet()) {
					int bone = Bones.index(boneEntry.getKey());
					if (bone < 0 || !boneEntry.getValue().isJsonObject()) {
						continue; // незнакомую кость просто игнорируем
					}
					JsonObject channels = boneEntry.getValue().getAsJsonObject();
					clip.rotations[bone] = parseTrack(channels.get("rotation"));
					clip.positions[bone] = parseTrack(channels.get("position"));
				}
			}
			return clip;
		}
		return null;
	}

	private static boolean parseLoop(JsonElement element) {
		// В формате Bedrock loop бывает и булевым, и строкой "loop"/"hold_on_last_frame".
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		return "loop".equalsIgnoreCase(element.getAsString());
	}

	private static Track parseTrack(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject frames = element.getAsJsonObject();
		List<Float> times = new ArrayList<>();
		List<float[]> values = new ArrayList<>();

		for (Map.Entry<String, JsonElement> frame : frames.entrySet()) {
			float time;
			try {
				time = Float.parseFloat(frame.getKey());
			} catch (NumberFormatException e) {
				continue;
			}
			float[] vec = parseVector(frame.getValue());
			if (vec == null) {
				continue;
			}
			times.add(time);
			values.add(vec);
		}
		if (times.isEmpty()) {
			return null;
		}

		// Ключи в json могут идти в любом порядке — сортируем по времени.
		Integer[] order = new Integer[times.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Float.compare(times.get(a), times.get(b)));

		float[] sortedTimes = new float[order.length];
		float[] flat = new float[order.length * 3];
		for (int i = 0; i < order.length; i++) {
			int src = order[i];
			sortedTimes[i] = times.get(src);
			float[] vec = values.get(src);
			flat[i * 3] = vec[0];
			flat[i * 3 + 1] = vec[1];
			flat[i * 3 + 2] = vec[2];
		}
		return new Track(sortedTimes, flat);
	}

	/** Кадр может быть массивом [x,y,z] или объектом {"vector": [x,y,z]}. */
	private static float[] parseVector(JsonElement element) {
		JsonArray array = null;
		if (element.isJsonArray()) {
			array = element.getAsJsonArray();
		} else if (element.isJsonObject() && element.getAsJsonObject().has("vector")) {
			JsonElement vector = element.getAsJsonObject().get("vector");
			if (vector.isJsonArray()) {
				array = vector.getAsJsonArray();
			}
		}
		if (array == null || array.size() < 3) {
			return null;
		}
		try {
			return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
		} catch (RuntimeException e) {
			return null;
		}
	}
}
