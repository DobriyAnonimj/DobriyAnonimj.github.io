package ru.dobriyanonimj.packanimation.client.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.dobriyanonimj.packanimation.client.anim.AnimationClip;
import ru.dobriyanonimj.packanimation.client.anim.AnimationRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.util.Identifier;
import ru.dobriyanonimj.packanimation.PackAnimationMod;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns every animation pack Pack Animation knows about:
 * <ul>
 *     <li>the 3 built-in packs (ninja / zombie / robot), shipped inside the mod jar
 *     under {@code assets/packanimation/player_animation/builtin/<id>/...}, loaded
 *     загружаются собственным движком мода как обычные ресурсы;</li>
 *     <li>custom packs made by the player, which live in the real, ordinary
 *     Minecraft resource pack folder this mod creates and keeps enabled:
 *     {@code <game dir>/resourcepacks/PackAnimation_CustomPacks/}.</li>
 * </ul>
 * See {@code docs/PACK_FORMAT.md} in the project for the exact json format
 * and how to export packs from Blender.
 */
public final class PackManager {

	private static final String CUSTOM_PACK_FOLDER_NAME = "PackAnimation_CustomPacks";
	private static final String META_FILE = "packanimation_meta.json";

	private static final List<PackInfo> BUILTIN_PACKS = java.util.Arrays.asList(
			builtin("adventure", "packanimation.pack.adventure.name", "Dobriy_Anonimj"),
			builtin("ninja", "packanimation.pack.ninja.name", "Dobriy_Anonimj"),
			builtin("zombie", "packanimation.pack.zombie.name", "Dobriy_Anonimj"),
			builtin("robot", "packanimation.pack.robot.name", "Dobriy_Anonimj")
	);

	private final List<PackInfo> customPacks = new ArrayList<>();

	private static PackInfo builtin(String id, String nameKey, String author) {
		String base = "builtin/" + id + "/";
		return new PackInfo(
				"builtin:" + id,
				nameKey, // resolved through I18n by the GUI, not stored translated
				author,
				PackInfo.PackSource.BUILTIN,
				new Identifier(PackAnimationMod.MOD_ID, base + "idle"),
				new Identifier(PackAnimationMod.MOD_ID, base + "walk"),
				new Identifier(PackAnimationMod.MOD_ID, base + "run")
		);
	}

	public List<PackInfo> getBuiltinPacks() {
		return BUILTIN_PACKS;
	}

	public List<PackInfo> getCustomPacks() {
		return customPacks;
	}

	public List<PackInfo> getAllPacks() {
		List<PackInfo> all = new ArrayList<>(BUILTIN_PACKS);
		all.addAll(customPacks);
		return all;
	}

	public PackInfo findPack(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (PackInfo pack : getAllPacks()) {
			if (pack.id().equals(id)) {
				return pack;
			}
		}
		return null;
	}

	/**
	 * Короткий путь для показа в интерфейсе: полный абсолютный путь в карточку
	 * не влезает и обрезается с конца — то есть ровно там, где написано самое
	 * важное.
	 */
	public String getCustomPacksShortPath() {
		return "resourcepacks/" + CUSTOM_PACK_FOLDER_NAME;
	}

	/** Path to the folder the player drops their own packs into. */
	public Path getCustomPacksRoot() {
		return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(CUSTOM_PACK_FOLDER_NAME);
	}

	/**
	 * Creates the custom-pack folder (with a starter example pack and a
	 * pack.mcmeta) the first time the game runs, enables it as a resource
	 * pack and reloads resources. Safe to call again later — it will just
	 * pick up new/changed packs. Never throws; problems are logged.
	 */
	public void reload() {
		ensureFolderExists();

		try {
			enableCustomPackAndReload();
		} catch (Exception e) {
			PackAnimationMod.LOGGER.warn("Pack Animation: could not enable the custom resource pack automatically. "
					+ "You can enable \"" + CUSTOM_PACK_FOLDER_NAME + "\" manually in Options > Resource Packs.", e);
		}

		try {
			scanCustomPacks();
		} catch (IOException e) {
			PackAnimationMod.LOGGER.warn("Pack Animation: failed scanning custom packs", e);
		}
	}

	/**
	 * Создаёт папку для пользовательских паков (вместе с pack.mcmeta, README и
	 * примером) и возвращает путь к ней. Вызывается и при старте клиента, и при
	 * заходе в мир, и по кнопке в меню — так что даже если какой-то из этих
	 * моментов не сработает, папка всё равно появится.
	 *
	 * @return путь к папке; {@code null}, если создать не удалось
	 */
	public Path ensureFolderExists() {
		Path root = getCustomPacksRoot();
		boolean existedBefore = Files.exists(root);
		try {
			ensureFolderStructure();
			if (!existedBefore) {
				// Печатаем абсолютный путь в лог: чаще всего папку просто ищут
				// не там (она среди ресурспаков, а не в config).
				PackAnimationMod.LOGGER.info("Pack Animation: создана папка для ваших паков анимаций: {}",
						root.toAbsolutePath());
			}
			return root;
		} catch (IOException e) {
			PackAnimationMod.LOGGER.error("Pack Animation: не удалось создать папку {} — "
					+ "проверьте права на запись", root.toAbsolutePath(), e);
			return null;
		}
	}

	private void ensureFolderStructure() throws IOException {
		Path root = getCustomPacksRoot();
		Files.createDirectories(root);

		Path mcmeta = root.resolve("pack.mcmeta");
		if (Files.notExists(mcmeta)) {
			String json = "{\n"
					+ "  \"pack\": {\n"
					+ "    \"pack_format\": 6,\n"
					+ "    \"description\": \"Pack Animation - your custom animation packs (auto-managed, keep enabled)\"\n"
					+ "  }\n"
					+ "}\n";
			Java8Files.writeString(mcmeta, json, StandardCharsets.UTF_8);
		}

		Path assets = root.resolve("assets");
		Files.createDirectories(assets);

		Path readme = root.resolve("README.txt");
		if (Files.notExists(readme)) {
			Java8Files.writeString(readme, README_TEXT, StandardCharsets.UTF_8);
		}

		// Drop in one ready-made example pack, so people have a working
		// template to copy/study instead of starting from a blank folder.
		Path examplePack = assets.resolve("example_pack");
		if (Files.notExists(examplePack)) {
			writeExamplePack(examplePack);
		}
	}

	private static final String README_TEXT =
			"Pack Animation - custom packs\n"
			+ "==============================\n\n"
			+ "This folder is a normal Minecraft resource pack that Pack Animation manages\n"
			+ "automatically. Keep it enabled in Options > Resource Packs (the mod tries to\n"
			+ "enable it for you on every launch).\n\n"
			+ "To add your own pack:\n"
			+ "  1. Create a new folder under assets/, e.g. assets/my_cool_pack/\n"
			+ "     (only lowercase letters, digits, underscores in the folder name!)\n"
			+ "  2. Inside it, create a player_animation/ folder with 3 files:\n"
			+ "       idle.json, walk.json, run.json\n"
			+ "     See assets/example_pack/ for a working template, and\n"
			+ "     docs/PACK_FORMAT.md in the mod's project for the full format\n"
			+ "     description and the Blender export workflow.\n"
			+ "  3. (optional) add packanimation_meta.json next to player_animation/\n"
			+ "     with {\"name\": \"My Cool Pack\", \"author\": \"You\"}\n"
			+ "  4. In-game, open the Pack Animation menu and press \"Reload pack list\",\n"
			+ "     or just rejoin the world.\n";

	private void writeExamplePack(Path exampleDir) throws IOException {
		Path anim = exampleDir.resolve("player_animation");
		Files.createDirectories(anim);
		Java8Files.writeString(exampleDir.resolve(META_FILE),
				"{\n  \"name\": \"Example pack\",\n  \"author\": \"Dobriy_Anonimj\"\n}\n", StandardCharsets.UTF_8);
		Java8Files.writeString(anim.resolve("idle.json"), EXAMPLE_IDLE, StandardCharsets.UTF_8);
		Java8Files.writeString(anim.resolve("walk.json"), EXAMPLE_WALK, StandardCharsets.UTF_8);
		Java8Files.writeString(anim.resolve("run.json"), EXAMPLE_RUN, StandardCharsets.UTF_8);
	}

	private static final String EXAMPLE_IDLE = "{\n"
			+ "  \"format_version\": \"1.8.0\",\n"
			+ "  \"animations\": {\n"
			+ "    \"idle\": {\n"
			+ "      \"loop\": true,\n"
			+ "      \"animation_length\": 2.0,\n"
			+ "      \"bones\": {\n"
			+ "        \"head\": { \"rotation\": { \"0.0\": [0, 0, 0], \"1.0\": [0, 10, 0], \"2.0\": [0, 0, 0] } }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }\n"
			+ "}\n";
	private static final String EXAMPLE_WALK = "{\n"
			+ "  \"format_version\": \"1.8.0\",\n"
			+ "  \"animations\": {\n"
			+ "    \"walk\": {\n"
			+ "      \"loop\": true,\n"
			+ "      \"animation_length\": 0.7,\n"
			+ "      \"bones\": {\n"
			+ "        \"right_leg\": { \"rotation\": { \"0.0\": [35, 0, 0], \"0.35\": [-35, 0, 0], \"0.7\": [35, 0, 0] } },\n"
			+ "        \"left_leg\": { \"rotation\": { \"0.0\": [-35, 0, 0], \"0.35\": [35, 0, 0], \"0.7\": [-35, 0, 0] } },\n"
			+ "        \"right_arm\": { \"rotation\": { \"0.0\": [-30, 0, 0], \"0.35\": [30, 0, 0], \"0.7\": [-30, 0, 0] } },\n"
			+ "        \"left_arm\": { \"rotation\": { \"0.0\": [30, 0, 0], \"0.35\": [-30, 0, 0], \"0.7\": [30, 0, 0] } }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }\n"
			+ "}\n";
	private static final String EXAMPLE_RUN = "{\n"
			+ "  \"format_version\": \"1.8.0\",\n"
			+ "  \"animations\": {\n"
			+ "    \"run\": {\n"
			+ "      \"loop\": true,\n"
			+ "      \"animation_length\": 0.45,\n"
			+ "      \"bones\": {\n"
			+ "        \"right_leg\": { \"rotation\": { \"0.0\": [55, 0, 0], \"0.225\": [-55, 0, 0], \"0.45\": [55, 0, 0] } },\n"
			+ "        \"left_leg\": { \"rotation\": { \"0.0\": [-55, 0, 0], \"0.225\": [55, 0, 0], \"0.45\": [-55, 0, 0] } },\n"
			+ "        \"right_arm\": { \"rotation\": { \"0.0\": [-55, 0, 0], \"0.225\": [55, 0, 0], \"0.45\": [-55, 0, 0] } },\n"
			+ "        \"left_arm\": { \"rotation\": { \"0.0\": [55, 0, 0], \"0.225\": [-55, 0, 0], \"0.45\": [55, 0, 0] } },\n"
			+ "        \"torso\": { \"rotation\": { \"0.0\": [20, 0, 0], \"0.45\": [20, 0, 0] } }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }\n"
			+ "}\n";

	/** Enables the custom pack (if not already enabled) and triggers a resource reload. */
	private void enableCustomPackAndReload() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		ResourcePackManager manager = client.getResourcePackManager();
		manager.scanPacks();

		String profileName = null;
		for (String name : manager.getNames()) {
			if (name.contains(CUSTOM_PACK_FOLDER_NAME)) {
				profileName = name;
				break;
			}
		}
		if (profileName == null) {
			PackAnimationMod.LOGGER.warn("Pack Animation: custom pack folder was not detected by "
					+ "Minecraft's resource pack manager yet. It should appear after a restart.");
			return;
		}

		// До 1.19.4 у ResourcePackManager нет enable(String): включённый набор
		// задаётся целиком, поэтому дописываем свой профиль к текущему списку.
		java.util.List<String> enabled = new ArrayList<>(manager.getEnabledNames());
		if (!enabled.contains(profileName)) {
			enabled.add(profileName);
			manager.setEnabledProfiles(enabled);
			client.reloadResources();
		}
	}

	private void scanCustomPacks() throws IOException {
		customPacks.clear();
		Path assets = getCustomPacksRoot().resolve("assets");
		if (Files.notExists(assets)) {
			return;
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(assets)) {
			for (Path namespaceDir : stream) {
				if (!Files.isDirectory(namespaceDir)) {
					continue;
				}
				String namespace = namespaceDir.getFileName().toString();
				Path animDir = namespaceDir.resolve("player_animation");
				Path idle = animDir.resolve("idle.json");
				Path walk = animDir.resolve("walk.json");
				Path run = animDir.resolve("run.json");
				if (!Files.exists(idle) || !Files.exists(walk) || !Files.exists(run)) {
					continue; // not a (complete) Pack Animation pack, skip it silently
				}

				String displayName = namespace;
				String author = "?";
				Path meta = namespaceDir.resolve(META_FILE);
				if (Files.exists(meta)) {
					try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
						JsonElement parsed = new JsonParser().parse(reader);
						if (parsed.isJsonObject()) {
							JsonObject obj = parsed.getAsJsonObject();
							if (obj.has("name")) {
								displayName = obj.get("name").getAsString();
							}
							if (obj.has("author")) {
								author = obj.get("author").getAsString();
							}
						}
					} catch (Exception e) {
						PackAnimationMod.LOGGER.warn("Pack Animation: bad {} for pack '{}'", META_FILE, namespace, e);
					}
				}

				// Folder names that are not valid Minecraft namespaces (uppercase
				// letters, spaces, ...) would make `new Identifier(namespace, ...)`
				// throw — skip that one pack instead of breaking the whole scan.
				try {
					customPacks.add(new PackInfo(
							"custom:" + namespace,
							displayName,
							author,
							PackInfo.PackSource.CUSTOM,
							new Identifier(namespace, "idle"),
							new Identifier(namespace, "walk"),
							new Identifier(namespace, "run")
					));
				} catch (Exception e) {
					PackAnimationMod.LOGGER.warn("Pack Animation: '{}' is not a valid pack folder name "
							+ "(use only lowercase letters, digits, '_' and '-')", namespace);
				}
			}
		}
	}

	/**
	 * Возвращает разобранную анимацию по её идентификатору или {@code null},
	 * если такой нет. Идентификатор — это путь к файлу внутри
	 * player_animation, например packanimation:builtin/ninja/idle.
	 */
	public AnimationClip getAnimation(Identifier id) {
		return AnimationRegistry.get(id);
	}
}
