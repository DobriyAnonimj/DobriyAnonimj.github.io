package ru.dobriyanonimj.packanimation;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.dobriyanonimj.packanimation.network.PackAnimationNetworking;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pack Animation
 * Автор: Dobriy_Anonimj
 * <p>
 * Общая точка входа — выполняется и на клиенте, и на выделенном Fabric-сервере.
 * <p>
 * Серверная часть делает ровно одно: хранит «кто какой пак выбрал» и
 * пересылает это остальным игрокам. Никакого рендера, анимаций и GUI на
 * сервере нет — всё это строго клиентский код в пакете
 * {@code ru.dobriyanonimj.packanimation.client}.
 * <p>
 * Точно такую же роль выполняет отдельный Bukkit-плагин из папки
 * {@code bukkit-plugin/} — он нужен, если сервер работает на
 * Bukkit / Spigot / Paper / Purpur, куда Fabric-мод поставить нельзя.
 * Ставить нужно что-то одно: либо этот мод (Fabric-сервер), либо плагин.
 */
public class PackAnimationMod implements ModInitializer {

	public static final String MOD_ID = PackAnimationNetworking.MOD_ID;
	public static final Logger LOGGER = LogManager.getLogger("Pack Animation");

	/** UUID игрока -> id выбранного им пака. Живёт только пока сервер запущен. */
	private static final Map<UUID, String> SELECTED_PACKS = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Pack Animation by Dobriy_Anonimj: initializing common logic");

		ServerPlayNetworking.registerGlobalReceiver(PackAnimationNetworking.SELECT_PACK_PACKET,
				(server, player, handler, buf, responseSender) -> {
					String packId = PackAnimationNetworking.decodeSelect(buf);
					// Менять состояние и рассылать пакеты — только в главном потоке сервера.
					server.execute(() -> {
						if (player.removed) {
							return;
						}
						UUID id = player.getUuid();
						if (packId.isEmpty()) {
							SELECTED_PACKS.remove(id);
						} else {
							SELECTED_PACKS.put(id, packId);
						}
						broadcast(server.getPlayerManager().getPlayerList(), player, id, packId);
					});
				});

		// Новому игроку сразу отправляем полный список: кто какой пак использует.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (SELECTED_PACKS.isEmpty()) {
				return;
			}
			ServerPlayNetworking.send(handler.player, PackAnimationNetworking.SYNC_PACK_PACKET,
					PackAnimationNetworking.encodeSync(SELECTED_PACKS));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				SELECTED_PACKS.remove(handler.player.getUuid()));
	}

	private static void broadcast(Iterable<ServerPlayerEntity> players, ServerPlayerEntity source,
									UUID sourceId, String packId) {
		for (ServerPlayerEntity target : players) {
			if (target == source) {
				continue; // отправитель уже применил свой выбор локально
			}
			ServerPlayNetworking.send(target, PackAnimationNetworking.SYNC_PACK_PACKET,
					PackAnimationNetworking.encodeSyncSingle(sourceId, packId));
		}
	}
}
