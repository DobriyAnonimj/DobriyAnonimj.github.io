package ru.dobriyanonimj.packanimation.client.pack;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Замена {@code Files.writeString} для сборки под 1.16.5.
 * <p>
 * Игра там работает на Java 8, а {@code Files.writeString} появился только
 * в Java 11. Поведение то же самое: записать текст в файл целиком,
 * перезаписав старое содержимое.
 */
public final class Java8Files {

	private Java8Files() {
	}

	public static Path writeString(Path path, CharSequence text, Charset charset) throws IOException {
		return Files.write(path, text.toString().getBytes(charset));
	}
}
