package ru.dobriyanonimj.packanimation.client.anim;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import ru.dobriyanonimj.packanimation.PackAnimationMod;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Загружает анимации из ресурсов игры при каждой перезагрузке ресурспаков.
 * <p>
 * Ключ анимации — <b>путь к файлу</b>: файл
 * {@code assets/packanimation/player_animation/builtin/ninja/idle.json}
 * получает идентификатор {@code packanimation:builtin/ninja/idle}.
 * <p>
 * Это заметно понятнее прежнего правила внешней библиотеки, где ключом было
 * имя анимации <i>внутри</i> json, а имя файла игнорировалось — из-за него
 * паки молча не находились. Существующие паки при этом продолжают работать:
 * у встроенных имя внутри и так совпадает с путём, а у пользовательских
 * файлы называются idle/walk/run.
 */
public final class AnimationRegistry {

	private static final String FOLDER = "player_animation";
	private static final String SUFFIX = ".json";

	private static final Map<Identifier, AnimationClip> CLIPS = new HashMap<>();

	private AnimationRegistry() {
	}

	public static AnimationClip get(Identifier id) {
		return CLIPS.get(id);
	}

	public static int size() {
		return CLIPS.size();
	}

	/** Вызывается один раз при инициализации клиента. */
	public static void register() {
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
				new SimpleSynchronousResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return new Identifier(PackAnimationMod.MOD_ID, "animations");
					}

					@Override
					public void reload(ResourceManager manager) {
						load(manager);
					}
				});
	}

	private static void load(ResourceManager manager) {
		CLIPS.clear();
		// До 1.19 findResources принимал Predicate<String> и отдавал просто
		// список идентификаторов — содержимое достаётся отдельным вызовом.
		java.util.Collection<Identifier> found =
				manager.findResources(FOLDER, path -> path.endsWith(SUFFIX));

		for (Identifier file : found) {
			String path = file.getPath();
			// "player_animation/builtin/ninja/idle.json" -> "builtin/ninja/idle"
			String name = path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length());

			// getInputStream() есть во всех версиях игры, а getReader() появился
			// только в 1.19.3 — так один и тот же класс переносится назад без правок.
			try (Reader reader = new InputStreamReader(
					manager.getResource(file).getInputStream(), StandardCharsets.UTF_8)) {
				JsonElement root = new JsonParser().parse(reader);
				if (!root.isJsonObject()) {
					continue;
				}
				AnimationClip clip = AnimationClip.parse(root.getAsJsonObject());
				if (clip == null) {
					PackAnimationMod.LOGGER.warn("Pack Animation: {} — не удалось разобрать анимацию", file);
					continue;
				}
				CLIPS.put(new Identifier(file.getNamespace(), name), clip);
			} catch (Exception e) {
				PackAnimationMod.LOGGER.warn("Pack Animation: ошибка чтения {}", file, e);
			}
		}

		PackAnimationMod.LOGGER.info("Pack Animation: загружено анимаций — {}", CLIPS.size());
	}
}
