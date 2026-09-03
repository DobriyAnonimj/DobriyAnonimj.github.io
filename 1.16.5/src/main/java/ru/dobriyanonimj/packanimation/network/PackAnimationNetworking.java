package ru.dobriyanonimj.packanimation.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сетевой протокол Pack Animation.
 * <p>
 * Специально сделан на обычных plugin messaging каналах и на «сырых» байтах,
 * без единой мода-специфичной структуры данных — благодаря этому один и тот
 * же протокол умеют говорить обе серверные реализации:
 * <ul>
 *     <li>Fabric-сервер (класс {@code PackAnimationMod} этого же мода);</li>
 *     <li>Bukkit / Spigot / Paper / Purpur — отдельный плагин из папки
 *     {@code bukkit-plugin/} (см. README).</li>
 * </ul>
 *
 * <h2>Формат пакетов</h2>
 * Все числа — big-endian (как у {@code DataOutputStream} в Java и у Netty,
 * поэтому обе стороны читают/пишут их одинаково без конвертаций).
 *
 * <p><b>C2S {@code packanimation:select_pack}</b> — «я выбрал пак»:
 * <pre>
 *   short  idLen
 *   byte[] idLen   — id пака в UTF-8 ("" = ванильные анимации)
 * </pre>
 *
 * <p><b>S2C {@code packanimation:sync_pack}</b> — «вот чьи паки»:
 * <pre>
 *   int    count
 *   count раз:
 *     long   uuidMostSignificantBits
 *     long   uuidLeastSignificantBits
 *     short  idLen
 *     byte[] idLen  — id пака в UTF-8
 * </pre>
 */
public final class PackAnimationNetworking {

	public static final String MOD_ID = "packanimation";

	/** C2S: клиент сообщает серверу, какой пак он выбрал. */
	public static final Identifier SELECT_PACK_PACKET = new Identifier(MOD_ID, "select_pack");

	/** S2C: сервер рассылает клиентам, кто какой пак использует. */
	public static final Identifier SYNC_PACK_PACKET = new Identifier(MOD_ID, "sync_pack");

	/** Защита от мусора/абьюза: id пака длиннее этого просто игнорируется. */
	public static final int MAX_PACK_ID_BYTES = 128;
	/** Защита от мусора: больше этого числа записей в одном пакете не бывает. */
	public static final int MAX_SYNC_ENTRIES = 10000;

	private PackAnimationNetworking() {
	}

	// ------------------------------------------------------------------
	// C2S select_pack
	// ------------------------------------------------------------------

	public static PacketByteBuf encodeSelect(String packId) {
		PacketByteBuf buf = PacketByteBufs.create();
		writeString(buf, packId);
		return buf;
	}

	public static String decodeSelect(PacketByteBuf buf) {
		return readString(buf);
	}

	// ------------------------------------------------------------------
	// S2C sync_pack
	// ------------------------------------------------------------------

	public static PacketByteBuf encodeSync(Map<UUID, String> entries) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeInt(entries.size());
		for (Map.Entry<UUID, String> entry : entries.entrySet()) {
			buf.writeLong(entry.getKey().getMostSignificantBits());
			buf.writeLong(entry.getKey().getLeastSignificantBits());
			writeString(buf, entry.getValue());
		}
		return buf;
	}

	public static PacketByteBuf encodeSyncSingle(UUID playerId, String packId) {
		Map<UUID, String> single = new LinkedHashMap<>();
		single.put(playerId, packId == null ? "" : packId);
		return encodeSync(single);
	}

	public static Map<UUID, String> decodeSync(PacketByteBuf buf) {
		Map<UUID, String> out = new LinkedHashMap<>();
		int count = buf.readInt();
		if (count < 0 || count > MAX_SYNC_ENTRIES) {
			return out;
		}
		for (int i = 0; i < count; i++) {
			long most = buf.readLong();
			long least = buf.readLong();
			out.put(new UUID(most, least), readString(buf));
		}
		return out;
	}

	// ------------------------------------------------------------------

	private static void writeString(PacketByteBuf buf, String value) {
		byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_PACK_ID_BYTES) {
			bytes = new byte[0];
		}
		buf.writeShort(bytes.length);
		buf.writeBytes(bytes);
	}

	private static String readString(PacketByteBuf buf) {
		int length = buf.readShort() & 0xFFFF;
		if (length > MAX_PACK_ID_BYTES || length > buf.readableBytes()) {
			return "";
		}
		byte[] bytes = new byte[length];
		buf.readBytes(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
