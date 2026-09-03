package ru.dobriyanonimj.packanimation.client.gui;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import ru.dobriyanonimj.packanimation.client.PackAnimationClient;
import ru.dobriyanonimj.packanimation.client.pack.PackInfo;
import ru.dobriyanonimj.packanimation.client.pack.PackManager;
import ru.dobriyanonimj.packanimation.client.pack.PackStateTracker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Меню выбора пака анимаций.
 * <p>
 * Всё нарисовано вручную (заливки + текст), без
 * ванильных виджетов — поэтому вид не зависит от текстур ресурспака и
 * выглядит как современный тёмный интерфейс: панель со скруглениями,
 * карточки паков с подсветкой при наведении, акцентная полоса у выбранного,
 * плавный скролл и живой список «кто рядом использует этот пак».
 */
public class PackSelectScreen extends Screen {

	// --- палитра -------------------------------------------------------
	private static final int PANEL_BG = 0xF0141519;
	private static final int PANEL_BORDER = 0x24FFFFFF;
	private static final int DIVIDER = 0x1AFFFFFF;
	private static final int CARD_BG = 0x14FFFFFF;
	private static final int CARD_HOVER = 0x2BFFFFFF;
	private static final int CARD_SELECTED = 0x335AC8FA;
	private static final int ACCENT = 0xFF5AC8FA;
	private static final int TEXT = 0xFFF2F4F7;
	private static final int TEXT_DIM = 0xFF98A2B3;
	private static final int TEXT_MUTED = 0xFF667085;
	private static final int BROKEN = 0xFFFF7A7A;
	private static final int BADGE_BG = 0x22FFFFFF;
	private static final int SCROLL_THUMB = 0x55FFFFFF;

	// --- размеры -------------------------------------------------------
	private static final int CARD_HEIGHT = 34;
	private static final int CARD_GAP = 4;
	private static final int HEADER_HEIGHT = 20;
	private static final int PANEL_HEADER = 44;
	private static final int PANEL_FOOTER = 36;

	private final PackManager packManager;
	private final Screen parent;
	private final List<Entry> entries = new ArrayList<>();

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private int contentHeight;
	private int scroll;

	private int reloadX;
	private int folderX;
	private int closeX;
	private int buttonY;
	private int buttonW;

	public PackSelectScreen(PackManager packManager) {
		this(packManager, null);
	}

	public PackSelectScreen(PackManager packManager, Screen parent) {
		super(new TranslatableText("packanimation.screen.title"));
		this.packManager = packManager;
		this.parent = parent;
	}

	@Override
	protected void init() {
		panelW = Math.min(this.width - 40, 400);
		panelH = Math.min(this.height - 40, 300);
		panelX = (this.width - panelW) / 2;
		panelY = (this.height - panelH) / 2;

		listX = panelX + 10;
		listY = panelY + PANEL_HEADER;
		listW = panelW - 20;
		listH = panelH - PANEL_HEADER - PANEL_FOOTER;

		int gap = 6;
		buttonW = (panelW - 20 - gap * 2) / 3;
		buttonY = panelY + panelH - PANEL_FOOTER + 8;
		reloadX = panelX + 10;
		folderX = reloadX + buttonW + gap;
		closeX = folderX + buttonW + gap;

		rebuildEntries();
	}

	private void rebuildEntries() {
		entries.clear();
		int y = 0;

		y = addCard(y, new Entry("", new TranslatableText("packanimation.screen.none").getString(),
				new TranslatableText("packanimation.screen.none.desc").getString(), false));

		y = addHeader(y, new TranslatableText("packanimation.screen.builtin").getString());
		for (PackInfo pack : packManager.getBuiltinPacks()) {
			String shortId = pack.id().substring(pack.id().indexOf(':') + 1);
			String name = new TranslatableText(pack.displayName()).getString();
			String desc = new TranslatableText("packanimation.pack." + shortId + ".desc").getString();
			y = addCard(y, new Entry(pack.id(), name, desc, isBroken(pack)));
		}

		y = addHeader(y, new TranslatableText("packanimation.screen.custom").getString());
		List<PackInfo> custom = packManager.getCustomPacks();
		if (custom.isEmpty()) {
			y = addCard(y, new Entry(null, new TranslatableText("packanimation.screen.no_custom").getString(),
					packManager.getCustomPacksShortPath(), false));
		} else {
			for (PackInfo pack : custom) {
				String desc = new TranslatableText("packanimation.screen.by", pack.author()).getString();
				y = addCard(y, new Entry(pack.id(), pack.displayName(), desc, isBroken(pack)));
			}
		}

		contentHeight = y;
		scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentHeight - listH));
	}

	private int addCard(int y, Entry entry) {
		entry.y = y;
		entry.height = CARD_HEIGHT;
		entries.add(entry);
		return y + CARD_HEIGHT + CARD_GAP;
	}

	private int addHeader(int y, String title) {
		Entry header = new Entry(null, title, null, false);
		header.isHeader = true;
		header.y = y;
		header.height = HEADER_HEIGHT;
		entries.add(header);
		return y + HEADER_HEIGHT;
	}

	/** Пак «сломан», если его анимации не удалось найти в реестре движка. */
	private boolean isBroken(PackInfo pack) {
		return packManager.getAnimation(pack.idleAnim()) == null
				|| packManager.getAnimation(pack.walkAnim()) == null
				|| packManager.getAnimation(pack.runAnim()) == null;
	}

	// ------------------------------------------------------------------
	// Отрисовка
	// ------------------------------------------------------------------

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);

		// Панель
		roundedRect(matrices, panelX - 1, panelY - 1, panelW + 2, panelH + 2, PANEL_BORDER);
		roundedRect(matrices, panelX, panelY, panelW, panelH, PANEL_BG);

		// Шапка
		this.textRenderer.drawWithShadow(matrices, this.title, panelX + 12, panelY + 12, TEXT);
		String current = currentPackName();
		this.textRenderer.drawWithShadow(matrices,
				new TranslatableText("packanimation.screen.current", current).getString(),
				panelX + 12, panelY + 26, TEXT_MUTED);
		fill(matrices, panelX + 10, panelY + PANEL_HEADER - 6, panelX + panelW - 10, panelY + PANEL_HEADER - 5, DIVIDER);

		// Список (обрезаем всё, что выходит за область прокрутки)
		String selectedId = PackStateTracker.get(this.client != null && this.client.player != null
				? this.client.player.getUuid() : null);
		for (Entry entry : entries) {
			int y = listY + entry.y - scroll;
			// До 1.20 у Screen нет отсечения по прямоугольнику, поэтому строку,
			// вылезающую за край списка, просто не рисуем.
			if (y < listY || y + entry.height > listY + listH) {
				continue;
			}
			if (entry.isHeader) {
				renderHeader(matrices, entry, y);
			} else {
				renderCard(matrices, entry, y, mouseX, mouseY, selectedId);
			}
		}

		renderScrollbar(matrices);

		// Подвал
		fill(matrices, panelX + 10, panelY + panelH - PANEL_FOOTER, panelX + panelW - 10,
				panelY + panelH - PANEL_FOOTER + 1, DIVIDER);
		renderButton(matrices, reloadX, buttonY, buttonW,
				new TranslatableText("packanimation.screen.reload").getString(), mouseX, mouseY);
		renderButton(matrices, folderX, buttonY, buttonW,
				new TranslatableText("packanimation.screen.open_folder").getString(), mouseX, mouseY);
		renderButton(matrices, closeX, buttonY, buttonW,
				new TranslatableText("packanimation.screen.close").getString(), mouseX, mouseY);

		super.render(matrices, mouseX, mouseY, delta);
	}

	private void renderHeader(MatrixStack matrices, Entry entry, int y) {
		this.textRenderer.drawWithShadow(matrices, entry.title, listX + 2, y + 8, TEXT_MUTED);
	}

	private void renderCard(MatrixStack matrices, Entry entry, int y, int mouseX, int mouseY, String selectedId) {
		boolean selectable = entry.id != null;
		boolean selected = selectable && entry.id.equals(selectedId);
		boolean hovered = selectable && isInside(mouseX, mouseY, listX, y, listW, entry.height)
				&& mouseY >= listY && mouseY < listY + listH;

		int background = selected ? CARD_SELECTED : (hovered ? CARD_HOVER : CARD_BG);
		roundedRect(matrices, listX, y, listW, entry.height, background);

		if (selected) {
			// Акцентная полоса слева у выбранного пака
			fill(matrices, listX, y + 3, listX + 2, y + entry.height - 3, ACCENT);
		}

		int textX = listX + 10;
		int titleColor = entry.broken ? BROKEN : (selectable ? TEXT : TEXT_DIM);
		this.textRenderer.drawWithShadow(matrices,
				fit(entry.title, listW - 80), textX, y + 7, titleColor);

		String subtitle = entry.broken
				? new TranslatableText("packanimation.screen.broken").getString()
				: entry.subtitle;
		if (subtitle != null && !subtitle.isEmpty()) {
			this.textRenderer.drawWithShadow(matrices,
					fit(subtitle, listW - 80), textX, y + 19, entry.broken ? BROKEN : TEXT_MUTED);
		}

		// Правая часть: «выбрано» или сколько игроков рядом использует этот пак
		if (selected) {
			drawBadge(matrices, listX + listW - 8, y + entry.height / 2 - 5,
					new TranslatableText("packanimation.screen.selected").getString(), ACCENT);
		} else if (selectable && !entry.id.isEmpty()) {
			int users = countNearbyUsers(entry.id);
			if (users > 0) {
				drawBadge(matrices, listX + listW - 8, y + entry.height / 2 - 5, String.valueOf(users), TEXT_DIM);
			}
		}
	}

	/** Рисует маленький бейдж, выровненный по правому краю (rightX — его правая граница). */
	private void drawBadge(MatrixStack matrices, int rightX, int y, String text, int textColor) {
		int textWidth = this.textRenderer.getWidth(text);
		int width = textWidth + 10;
		roundedRect(matrices, rightX - width, y, width, 11, BADGE_BG);
		this.textRenderer.drawWithShadow(matrices, text, rightX - width + 5, y + 2, textColor);
	}

	private void renderScrollbar(MatrixStack matrices) {
		int max = contentHeight - listH;
		if (max <= 0) {
			return;
		}
		int trackX = listX + listW - 2;
		int thumbHeight = Math.max(20, listH * listH / contentHeight);
		int thumbY = listY + (listH - thumbHeight) * scroll / max;
		roundedRect(matrices, trackX, thumbY, 3, thumbHeight, SCROLL_THUMB);
	}

	private void renderButton(MatrixStack matrices, int x, int y, int width, String label, int mouseX, int mouseY) {
		boolean hovered = isInside(mouseX, mouseY, x, y, width, 18);
		roundedRect(matrices, x, y, width, 18, hovered ? CARD_HOVER : CARD_BG);
		int textX = x + (width - this.textRenderer.getWidth(label)) / 2;
		this.textRenderer.drawWithShadow(matrices, label, textX, y + 5, hovered ? ACCENT : TEXT_DIM);
	}

	/** Прямоугольник со «срезанными» угловыми пикселями — дешёвая имитация скругления. */
	private static void roundedRect(MatrixStack matrices, int x, int y, int width, int height, int color) {
		if (width <= 2 || height <= 2) {
			fill(matrices, x, y, x + width, y + height, color);
			return;
		}
		fill(matrices, x + 1, y, x + width - 1, y + height, color);
		fill(matrices, x, y + 1, x + 1, y + height - 1, color);
		fill(matrices, x + width - 1, y + 1, x + width, y + height - 1, color);
	}

	// ------------------------------------------------------------------
	// Ввод
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (isInside((int) mouseX, (int) mouseY, reloadX, buttonY, buttonW, 18)) {
				packManager.reload();
				rebuildEntries();
				return true;
			}
			if (isInside((int) mouseX, (int) mouseY, folderX, buttonY, buttonW, 18)) {
				// Создаём папку (если её ещё нет) и открываем в проводнике —
				// так её точно не придётся искать вручную.
				Path folder = packManager.ensureFolderExists();
				if (folder != null) {
					Util.getOperatingSystem().open(folder.toFile());
				}
				return true;
			}
			if (isInside((int) mouseX, (int) mouseY, closeX, buttonY, buttonW, 18)) {
				onClose();
				return true;
			}
			if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
				for (Entry entry : entries) {
					int y = listY + entry.y - scroll;
					if (entry.id != null && !entry.isHeader
							&& mouseY >= y && mouseY < y + entry.height) {
						PackAnimationClient.selectPack(entry.id);
						return true;
					}
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		int max = Math.max(0, contentHeight - listH);
		scroll = MathHelper.clamp(scroll - (int) (amount * 16), 0, max);
		return true;
	}

	@Override
	public void onClose() {
		if (this.client != null) {
			this.client.openScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------
	// Вспомогательное
	// ------------------------------------------------------------------

	private String currentPackName() {
		if (this.client == null || this.client.player == null) {
			return new TranslatableText("packanimation.screen.none").getString();
		}
		String id = PackStateTracker.get(this.client.player.getUuid());
		PackInfo pack = packManager.findPack(id);
		if (pack == null) {
			return new TranslatableText("packanimation.screen.none").getString();
		}
		return pack.source() == PackInfo.PackSource.BUILTIN
				? new TranslatableText(pack.displayName()).getString()
				: pack.displayName();
	}

	private int countNearbyUsers(String packId) {
		if (this.client == null || this.client.world == null) {
			return 0;
		}
		int count = 0;
		for (AbstractClientPlayerEntity player : this.client.world.getPlayers()) {
			if (packId.equals(PackStateTracker.get(player.getUuid()))) {
				count++;
			}
		}
		return count;
	}

	private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	/** Обрезает строку до нужной ширины, добавляя многоточие. */
	private String fit(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (this.textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			if (this.textRenderer.getWidth(builder.toString() + text.charAt(i) + "...") > maxWidth) {
				break;
			}
			builder.append(text.charAt(i));
		}
		return builder + "...";
	}

	private static final class Entry {
		final String id; // null => не выбирается (заголовок или подсказка)
		final String title;
		final String subtitle;
		final boolean broken;
		boolean isHeader;
		int y;
		int height;

		Entry(String id, String title, String subtitle, boolean broken) {
			this.id = id;
			this.title = title;
			this.subtitle = subtitle;
			this.broken = broken;
		}
	}
}
