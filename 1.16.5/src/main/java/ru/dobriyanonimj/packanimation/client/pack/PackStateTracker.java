package ru.dobriyanonimj.packanimation.client.pack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиентская «карта мира»: какой пак анимаций использует каждый игрок.
 * <p>
 * Заполняется из пакетов {@code packanimation:sync_pack}, которые присылает
 * сервер — неважно, Fabric-сервер с этим модом или Bukkit/Paper/Purpur с
 * плагином из папки {@code bukkit-plugin/}. Свой собственный выбор клиент
 * записывает сюда сразу, не дожидаясь ответа сервера, поэтому свою анимацию
 * вы видите мгновенно и даже на сервере вообще без серверной части
 * (просто вас тогда не увидят другие).
 */
public final class PackStateTracker {

	private static final Map<UUID, String> PACKS = new ConcurrentHashMap<>();

	private PackStateTracker() {
	}

	/** Возвращает id пака игрока или "" (ванильные анимации). Никогда не null. */
	public static String get(UUID playerId) {
		if (playerId == null) {
			return "";
		}
		String value = PACKS.get(playerId);
		return value == null ? "" : value;
	}

	public static void set(UUID playerId, String packId) {
		if (packId == null || packId.isEmpty()) {
			PACKS.remove(playerId);
		} else {
			PACKS.put(playerId, packId);
		}
	}

	public static void applyAll(Map<UUID, String> entries) {
		entries.forEach(PackStateTracker::set);
	}

	/** Вызывается при заходе в мир и при отключении от сервера. */
	public static void clear() {
		PACKS.clear();
	}
}
