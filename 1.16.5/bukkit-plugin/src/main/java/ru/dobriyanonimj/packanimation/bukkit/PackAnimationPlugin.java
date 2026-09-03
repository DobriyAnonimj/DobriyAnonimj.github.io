package ru.dobriyanonimj.packanimation.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pack Animation — серверная часть для Bukkit / Spigot / Paper / Purpur.
 * Автор: Dobriy_Anonimj
 *
 * <p>Плагин НЕ проигрывает анимации и вообще ничего не знает про паки — вся
 * анимация живёт на клиенте, в Fabric-моде Pack Animation. Задача плагина
 * ровно одна: запоминать, кто какой пак выбрал, и пересылать это остальным
 * игрокам, чтобы вы видели анимации друг друга (как эмоции в Emotecraft).
 *
 * <p>Используются только обычные plugin messaging каналы Bukkit API — ни NMS,
 * ни ProtocolLib, ни version-specific кода, поэтому плагин работает на всём
 * семействе Bukkit-серверов и не ломается при обновлениях сервера.
 *
 * <p>Протокол (big-endian, совпадает с {@code DataOutputStream}):
 * <pre>
 * C2S packanimation:select_pack   short idLen, byte[idLen] utf8
 * S2C packanimation:sync_pack     int count, count x (long msb, long lsb, short idLen, byte[idLen] utf8)
 * </pre>
 */
public final class PackAnimationPlugin extends JavaPlugin implements PluginMessageListener, Listener {

	public static final String CHANNEL_SELECT = "packanimation:select_pack";
	public static final String CHANNEL_SYNC = "packanimation:sync_pack";

	/** Защита от мусора/абьюза: id пака длиннее этого игнорируется. */
	private static final int MAX_PACK_ID_BYTES = 128;
	/** Задержка перед полной синхронизацией, чтобы клиент успел прогрузиться. */
	private static final long JOIN_SYNC_DELAY_TICKS = 20L;

	/** UUID игрока -> id выбранного пака. Живёт только пока сервер запущен. */
	private final Map<UUID, String> selectedPacks = new ConcurrentHashMap<>();

	@Override
	public void onEnable() {
		getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SYNC);
		getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_SELECT, this);
		getServer().getPluginManager().registerEvents(this, this);
		getLogger().info("Pack Animation relay enabled. Players need the Pack Animation "
				+ "client mod (Fabric) + Player Animator to see each other's animation packs.");
	}

	@Override
	public void onDisable() {
		getServer().getMessenger().unregisterIncomingPluginChannel(this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this);
		selectedPacks.clear();
	}

	// ------------------------------------------------------------------
	// Приём выбора пака от клиента
	// ------------------------------------------------------------------

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!CHANNEL_SELECT.equals(channel)) {
			return;
		}

		String packId = decodeSelect(message);
		if (packId == null) {
			return; // битый пакет — молча игнорируем
		}

		UUID playerId = player.getUniqueId();
		if (packId.isEmpty()) {
			selectedPacks.remove(playerId);
		} else {
			selectedPacks.put(playerId, packId);
		}

		byte[] payload = encodeSync(Map.of(playerId, packId));
		for (Player other : getServer().getOnlinePlayers()) {
			if (other.getUniqueId().equals(playerId)) {
				continue; // отправитель уже применил свой выбор локально
			}
			sendSafely(other, payload);
		}
	}

	// ------------------------------------------------------------------
	// Синхронизация при заходе / выходе
	// ------------------------------------------------------------------

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		getServer().getScheduler().runTaskLater(this, () -> {
			if (!player.isOnline() || selectedPacks.isEmpty()) {
				return;
			}
			sendSafely(player, encodeSync(selectedPacks));
		}, JOIN_SYNC_DELAY_TICKS);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		selectedPacks.remove(event.getPlayer().getUniqueId());
	}

	private void sendSafely(Player player, byte[] payload) {
		try {
			player.sendPluginMessage(this, CHANNEL_SYNC, payload);
		} catch (Exception e) {
			// Игрок мог отключиться между проверкой и отправкой — не повод шуметь в консоль.
			getLogger().fine("Pack Animation: could not send sync to " + player.getName() + ": " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------
	// Кодирование / декодирование
	// ------------------------------------------------------------------

	private static String decodeSelect(byte[] message) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			int length = in.readShort() & 0xFFFF;
			if (length > MAX_PACK_ID_BYTES || length > in.available()) {
				return null;
			}
			byte[] bytes = new byte[length];
			in.readFully(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	private static byte[] encodeSync(Map<UUID, String> entries) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(entries.size());
			for (Map.Entry<UUID, String> entry : entries.entrySet()) {
				out.writeLong(entry.getKey().getMostSignificantBits());
				out.writeLong(entry.getKey().getLeastSignificantBits());
				byte[] id = entry.getValue().getBytes(StandardCharsets.UTF_8);
				if (id.length > MAX_PACK_ID_BYTES) {
					id = new byte[0];
				}
				out.writeShort(id.length);
				out.write(id);
			}
		} catch (IOException e) {
			// ByteArrayOutputStream не может бросить IOException, но компилятор об этом не знает.
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}
}
