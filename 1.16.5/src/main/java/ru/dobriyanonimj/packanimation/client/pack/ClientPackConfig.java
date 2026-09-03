package ru.dobriyanonimj.packanimation.client.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import ru.dobriyanonimj.packanimation.PackAnimationMod;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers which pack the LOCAL player picked, so it can be re-applied
 * automatically every time they join a world/server (the server itself
 * only remembers it for the current login session, not across restarts).
 */
public final class ClientPackConfig {

	private static Path filePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("packanimation").resolve("client.json");
	}

	public static String loadSelectedPackId() {
		Path path = filePath();
		if (Files.notExists(path)) {
			return "";
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement element = new JsonParser().parse(reader);
			if (element != null && element.isJsonObject()) {
				JsonObject obj = element.getAsJsonObject();
				if (obj.has("selectedPack")) {
					return obj.get("selectedPack").getAsString();
				}
			}
		} catch (Exception e) {
			PackAnimationMod.LOGGER.warn("Pack Animation: could not read client.json", e);
		}
		return "";
	}

	public static void saveSelectedPackId(String packId) {
		Path path = filePath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject obj = new JsonObject();
			obj.addProperty("selectedPack", packId == null ? "" : packId);
			Java8Files.writeString(path, obj.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PackAnimationMod.LOGGER.warn("Pack Animation: could not write client.json", e);
		}
	}

	private ClientPackConfig() {
	}
}
