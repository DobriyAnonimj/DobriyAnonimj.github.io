package ru.dobriyanonimj.packanimation.client.pack;

import net.minecraft.util.Identifier;

/**
 * Описание одного пака анимаций.
 * <p>
 * В сборке под 1.16.5 это обычный класс, а не record: игра там работает на
 * Java 8, где record ещё не существует.
 */
public final class PackInfo {

	public enum PackSource {
		BUILTIN,
		CUSTOM
	}

	private final String id;
	private final String displayName;
	private final String author;
	private final PackSource source;
	private final Identifier idleAnim;
	private final Identifier walkAnim;
	private final Identifier runAnim;

	public PackInfo(String id, String displayName, String author, PackSource source,
					Identifier idleAnim, Identifier walkAnim, Identifier runAnim) {
		this.id = id;
		this.displayName = displayName;
		this.author = author;
		this.source = source;
		this.idleAnim = idleAnim;
		this.walkAnim = walkAnim;
		this.runAnim = runAnim;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public String author() {
		return author;
	}

	public PackSource source() {
		return source;
	}

	public Identifier idleAnim() {
		return idleAnim;
	}

	public Identifier walkAnim() {
		return walkAnim;
	}

	public Identifier runAnim() {
		return runAnim;
	}
}
