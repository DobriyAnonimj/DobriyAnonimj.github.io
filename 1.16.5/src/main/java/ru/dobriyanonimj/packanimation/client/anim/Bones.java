package ru.dobriyanonimj.packanimation.client.anim;

import java.util.Locale;

/**
 * Кости, которыми оперирует Pack Animation, и их номера в {@link Pose}.
 * <p>
 * Вариант под Java 8 (сборка 1.16.5): вместо switch-выражения обычные
 * сравнения строк.
 */
public final class Bones {

	public static final int HEAD = 0;
	public static final int TORSO = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;
	/** Смещение/поворот игрока целиком. */
	public static final int BODY = 6;

	public static final int COUNT = 7;

	private Bones() {
	}

	/** @return номер кости или -1, если имя не распознано. */
	public static int index(String name) {
		String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
		if (n.equals("head")) return HEAD;
		if (n.equals("torso") || n.equals("chest") || n.equals("upperbody")) return TORSO;
		if (n.equals("rightarm") || n.equals("armright")) return RIGHT_ARM;
		if (n.equals("leftarm") || n.equals("armleft")) return LEFT_ARM;
		if (n.equals("rightleg") || n.equals("legright")) return RIGHT_LEG;
		if (n.equals("leftleg") || n.equals("legleft")) return LEFT_LEG;
		if (n.equals("body") || n.equals("root")) return BODY;
		return -1;
	}
}
