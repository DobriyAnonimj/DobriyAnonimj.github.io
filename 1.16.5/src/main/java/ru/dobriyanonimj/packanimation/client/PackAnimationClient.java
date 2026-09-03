package ru.dobriyanonimj.packanimation.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import ru.dobriyanonimj.packanimation.PackAnimationMod;
import ru.dobriyanonimj.packanimation.client.gui.PackSelectScreen;
import ru.dobriyanonimj.packanimation.client.pack.ClientPackConfig;
import ru.dobriyanonimj.packanimation.client.pack.MovementAnimationController;
import ru.dobriyanonimj.packanimation.client.pack.PackManager;
import ru.dobriyanonimj.packanimation.client.anim.AnimationRegistry;
import ru.dobriyanonimj.packanimation.client.anim.PlayerAnimationEngine;
import ru.dobriyanonimj.packanimation.client.pack.PackStateTracker;
import ru.dobriyanonimj.packanimation.network.PackAnimationNetworking;

import java.util.Map;
import java.util.UUID;

/**
 * Pack Animation — клиентская точка входа.
 * Автор: Dobriy_Anonimj
 */
@Environment(EnvType.CLIENT)
public class PackAnimationClient implements ClientModInitializer {

	private static final PackManager PACK_MANAGER = new PackManager();
	private static final MovementAnimationController CONTROLLER = new MovementAnimationController(PACK_MANAGER);

	private static KeyBinding openMenuKey;

	public static PackManager getPackManager() {
		return PACK_MANAGER;
	}

	@Override
	public void onInitializeClient() {
		PackAnimationMod.LOGGER.info("Pack Animation by Dobriy_Anonimj: initializing client logic");

		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.packanimation.open_menu",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				"key.category.packanimation"
		));

		// Загрузчик анимаций: подхватывает все паки при каждой перезагрузке
		// ресурсов, включая пользовательские из папки ресурспаков.
		AnimationRegistry.register();

		// Сервер (Fabric-мод или Bukkit-плагин) присылает, кто какой пак использует.
		ClientPlayNetworking.registerGlobalReceiver(PackAnimationNetworking.SYNC_PACK_PACKET,
				(client, handler, buf, responseSender) -> {
					Map<UUID, String> entries = PackAnimationNetworking.decodeSync(buf);
					client.execute(() -> PackStateTracker.applyAll(entries));
				});

		// Папку создаём как можно раньше и не завися ни от каких событий: для
		// этого не нужен ни запущенный клиент, ни загруженный мир.
		PACK_MANAGER.ensureFolderExists();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> PACK_MANAGER.reload());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			PackStateTracker.clear();
			PACK_MANAGER.reload();

			// Свой выбор применяем локально сразу — так пак работает даже на
			// сервере без серверной части (просто другие его не увидят).
			String own = ClientPackConfig.loadSelectedPackId();
			if (client.player != null) {
				PackStateTracker.set(client.player.getUuid(), own);
			}
			sendSelection(own);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PackStateTracker.clear();
			PlayerAnimationEngine.clear();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.openScreen(new PackSelectScreen(PACK_MANAGER));
				}
			}

			if (client.world == null) {
				return;
			}

			for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
				CONTROLLER.tick(player);
			}
		});
	}

	/**
	 * Вызывается из GUI, когда игрок выбрал пак (или «без пака»). Сохраняет
	 * выбор на диск, применяет его локально мгновенно и сообщает серверу,
	 * чтобы пак увидели остальные игроки.
	 */
	public static void selectPack(String packId) {
		String safeId = packId == null ? "" : packId;
		ClientPackConfig.saveSelectedPackId(safeId);

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			PackStateTracker.set(client.player.getUuid(), safeId);
		}
		sendSelection(safeId);
	}

	private static void sendSelection(String packId) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return; // ещё не подключены к миру
		}
		ClientPlayNetworking.send(PackAnimationNetworking.SELECT_PACK_PACKET,
				PackAnimationNetworking.encodeSelect(packId == null ? "" : packId));
	}
}
