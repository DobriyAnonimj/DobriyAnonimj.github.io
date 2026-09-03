package ru.dobriyanonimj.packanimation.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import ru.dobriyanonimj.packanimation.client.gui.PackSelectScreen;

/**
 * Интеграция с Mod Menu: кнопка «шестерёнка» напротив Pack Animation в списке
 * модов открывает то же меню выбора паков, что и клавиша P.
 * <p>
 * Mod Menu — необязательная зависимость. Этот класс указан в fabric.mod.json
 * в entrypoint-е {@code "modmenu"}, а такие entrypoint-ы читает только сам
 * Mod Menu. Если его нет — класс просто никогда не загружается, и мод
 * работает как обычно.
 */
@Environment(EnvType.CLIENT)
public class PackAnimationModMenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new PackSelectScreen(PackAnimationClient.getPackManager(), parent);
	}
}
