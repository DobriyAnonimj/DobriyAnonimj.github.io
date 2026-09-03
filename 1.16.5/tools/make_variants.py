#!/usr/bin/env python3
"""
Генератор версионных вариантов мода Pack Animation.
Автор мода: Dobriy_Anonimj

Мастер-исходник — сама папка PackAnimation (сборка под 1.20.1). Скрипт
делает из неё копии под остальные версии игры, применяя ровно те правки,
которые нужны конкретной версии.

Запуск из папки, СОДЕРЖАЩЕЙ PackAnimation:
    python3 PackAnimation/tools/make_variants.py

Почему так, а не Stonecutter: версии отличаются буквально несколькими
файлами (меню и миксин), и держать их отдельными папками проще, чем
разбираться в директивах препроцессора. Собственный движок анимаций
(client/anim) во всех вариантах одинаков побайтово.
"""

import json
import os
import pathlib
import re
import shutil

MASTER = pathlib.Path("PackAnimation")

# ---------------------------------------------------------------------------
# Версии зависимостей. Все сверены с meta.fabricmc.net и Modrinth API.
# ---------------------------------------------------------------------------
VERSIONS = {
    # range — то, что попадёт в fabric.mod.json. Диапазоны подобраны так, чтобы
    # покрыть версии СПЛОШЬ, без дыр, и при этом не заходить за точку, где
    # ломается какой-нибудь API (см. таблицу признаков ниже).
    "1.16.5": dict(range=">=1.16.2 <1.17", fabric_api_id="fabric", fabric_api_dep=">=0.42.0", yarn="1.16.5+build.10", fabric_api="0.42.0+1.16",
                   modmenu="1.16.23", java=8, loader="0.15.11", pack_format=6,
                   gui="matrix", text_legacy=True, res_legacy=True, log4j=True,
                   packs_legacy=True, removed_field=True, gson_legacy=True,
                   screen_legacy=True, open_screen=True, entity_get_id=True,
                   biped_scale_ctor=True),
    "1.17.1": dict(range=">=1.17 <1.18", fabric_api_id="fabric", yarn="1.17.1+build.65", fabric_api="0.46.1+1.17",
                   modmenu="2.0.17", java=16, loader="0.15.11", pack_format=7,
                   gui="matrix", text_legacy=True, res_legacy=True, log4j=True,
                   packs_legacy=True, gson_legacy=True, screen_legacy=True),
    "1.18.2": dict(range=">=1.18 <1.19", yarn="1.18.2+build.4", fabric_api="0.77.0+1.18.2",
                   modmenu="3.2.5", java=17, loader="0.15.11", pack_format=8,
                   gui="matrix", text_legacy=True, res_legacy=True, log4j=True,
                   packs_legacy=True, gson_legacy=True),
    "1.19.2": dict(range=">=1.19 <1.19.3", yarn="1.19.2+build.28", fabric_api="0.77.0+1.19.2",
                   modmenu="4.2.0-beta.2", java=17, loader="0.19.5", pack_format=9,
                   gui="matrix", packs_legacy=True),
    "1.19.4": dict(range=">=1.19.3 <1.20", yarn="1.19.4+build.2", fabric_api="0.87.2+1.19.4",
                   modmenu="6.3.1", java=17, loader="0.19.5", pack_format=13,
                   gui="matrix"),
    "1.20.1": dict(range=">=1.20 <1.20.2", yarn="1.20.1+build.10", fabric_api="0.92.11+1.20.1",
                   modmenu="7.2.2", java=17, loader="0.19.5", pack_format=15,
                   master=True),
    "1.20.4": dict(range=">=1.20.2 <1.20.5", yarn="1.20.4+build.3", fabric_api="0.97.3+1.20.4",
                   modmenu="9.2.0", java=17, loader="0.19.5", pack_format=22,
                   renderbg4=True),
    "1.20.6": dict(range=">=1.20.5 <1.21", yarn="1.20.6+build.3", fabric_api="0.100.8+1.20.6",
                   modmenu="10.0.0", java=21, loader="0.19.5", pack_format=32,
                   renderbg4=True, scroll4=True, payload=True),
    "1.21.1": dict(range=">=1.21 <1.21.2", yarn="1.21.1+build.3", fabric_api="0.116.16+1.21.1",
                   modmenu="11.0.4", java=21, loader="0.19.5", pack_format=34,
                   renderbg4=True, scroll4=True, payload=True, identifier_of=True),
    "1.21.8": dict(range=">=1.21.2 <1.22", yarn="1.21.8+build.1", fabric_api="0.136.1+1.21.8",
                   modmenu="15.0.2", java=21, loader="0.19.5", pack_format=64,
                   renderbg4=True, scroll4=True, payload=True, identifier_of=True,
                   renderstate=True, model_origin=True),
}

# Признаки и версии, начиная с которых они включаются:
#   gui="matrix"     — до 1.20 нет DrawContext, рисуем через MatrixStack
#   text_legacy      — до 1.19 нет Text.translatable, только TranslatableText
#   res_legacy       — до 1.19.3 findResources отдаёт Collection, а не Map
#   renderbg4        — с 1.20.2 renderBackground принимает мышь и delta
#   scroll4          — с 1.20.5 у mouseScrolled появилась горизонтальная ось
#   payload          — с 1.20.5 сеть переписана на CustomPayload
#   identifier_of    — с 1.21 конструктор Identifier закрыт, нужен Identifier.of
#   renderstate      — с 1.21.2 модель получает объект состояния, а не сущность
#   log4j            — до 1.19 в игре нет slf4j, логгер берётся из Log4j 2
#   packs_legacy     — до 1.19.4 у ResourcePackManager нет enable(String)
#   removed_field    — до 1.17 вместо isRemoved() публичное поле removed
#   model_origin     — в yarn 1.21.8 поля ModelPart зовутся origin*, а не pivot*


def to_model_origin(root):
    """В маппингах 1.21.8 точка привязки части модели названа origin, а не pivot.

    Само поле в игре то же самое — переименование чисто в маппингах, — но
    исходник обязан использовать имя той версии, под которую компилируется.
    """
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/anim/PoseApplier.java"
    s = read(p)
    s = s.replace("part.pivotX", "part.originX")
    s = s.replace("part.pivotY", "part.originY")
    s = s.replace("part.pivotZ", "part.originZ")
    s = s.replace("pivotX/Y/Z", "originX/Y/Z")
    s = s.replace("минус по pivotY", "минус по originY")
    write(p, s)


def to_log4j(root):
    """До 1.19 Minecraft не приносит с собой slf4j — логгер берём из Log4j 2."""
    for path in root.rglob("*.java"):
        s = read(path)
        if "org.slf4j" not in s:
            continue
        s = s.replace("import org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\n",
                      "import org.apache.logging.log4j.LogManager;\n"
                      "import org.apache.logging.log4j.Logger;\n")
        s = s.replace("import org.slf4j.Logger;\n", "import org.apache.logging.log4j.Logger;\n")
        s = s.replace("import org.slf4j.LoggerFactory;\n", "import org.apache.logging.log4j.LogManager;\n")
        s = s.replace("LoggerFactory.getLogger(", "LogManager.getLogger(")
        write(path, s)


def to_legacy_packs(root):
    """До 1.19.4 у ResourcePackManager нет enable(String) — только setEnabledProfiles."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/pack/PackManager.java"
    edit(p, ("""		if (manager.enable(profileName)) {
			client.reloadResources();
		}""",
             """		// До 1.19.4 у ResourcePackManager нет enable(String): включённый набор
		// задаётся целиком, поэтому дописываем свой профиль к текущему списку.
		java.util.List<String> enabled = new ArrayList<>(manager.getEnabledNames());
		if (!enabled.contains(profileName)) {
			enabled.add(profileName);
			manager.setEnabledProfiles(enabled);
			client.reloadResources();
		}"""))


def to_legacy_gson(root):
    """Статический JsonParser.parseReader появился только в Gson 2.8.6.

    Minecraft 1.16–1.17 приносит с собой Gson 2.8.0, где такого метода нет;
    работает только нестатический parse(Reader).
    """
    for path in root.rglob("*.java"):
        s = read(path)
        if "JsonParser.parseReader(" not in s:
            continue
        write(path, s.replace("JsonParser.parseReader(", "new JsonParser().parse("))


def to_legacy_screen(root):
    """До 1.18 у Screen методы называются onClose() и isPauseScreen()."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/gui/PackSelectScreen.java"
    edit(p,
         ("	public void close() {", "	public void onClose() {"),
         ("	public boolean shouldPause() {", "	public boolean isPauseScreen() {"),
         ("				close();", "				onClose();"))


def to_open_screen(root):
    """До 1.17.1 у MinecraftClient метод назывался openScreen(Screen)."""
    for path in root.rglob("*.java"):
        s = read(path)
        if ".setScreen(" not in s:
            continue
        write(path, s.replace(".setScreen(", ".openScreen("))


def to_entity_get_id(root):
    """До 1.17 сетевой идентификатор сущности звался getEntityId()."""
    for path in root.rglob("*.java"):
        s = read(path)
        if "player.getId()" not in s:
            continue
        write(path, s.replace("player.getId()", "player.getEntityId()"))


def to_biped_scale_ctor(root):
    """До 1.17 модели строили себя сами: у BipedEntityModel нет конструктора
    от корневой ModelPart, есть только от масштаба."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/mixin/client/PlayerEntityModelMixin.java"
    edit(p,
         ("import net.minecraft.client.model.ModelPart;\n", ""),
         ("""	protected PlayerEntityModelMixin(ModelPart root) {
		super(root);
	}""",
          """	// До 1.17 модель собирала свои части сама, поэтому у BipedEntityModel
	// нет конструктора от корневой ModelPart — только от масштаба. Этот
	// конструктор нужен лишь чтобы устроить компилятор, выполняться он не будет.
	protected PlayerEntityModelMixin(float scale) {
		super(scale);
	}"""))


def to_removed_field(root):
    """До 1.17 у Entity вместо isRemoved() публичное поле removed."""
    for path in root.rglob("*.java"):
        s = read(path)
        if ".isRemoved()" not in s:
            continue
        write(path, s.replace(".isRemoved()", ".removed"))


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def edit(path, *pairs, regex=False):
    """Заменяет пары (было, стало) в файле."""
    s = read(path)
    for old, new in pairs:
        s = re.sub(old, new, s, flags=re.S) if regex else s.replace(old, new)
    write(path, s)


# ---------------------------------------------------------------------------

def apply_versions(root, mc, cfg):
    p = root / "gradle.properties"
    s = read(p)
    s = re.sub(r"minecraft_version=.*", f"minecraft_version={mc}", s)
    s = re.sub(r"yarn_mappings=.*", f"yarn_mappings={cfg['yarn']}", s)
    s = re.sub(r"loader_version=.*", f"loader_version={cfg['loader']}", s)
    s = re.sub(r"fabric_version=.*", f"fabric_version={cfg['fabric_api']}", s)
    s = re.sub(r"modmenu_version=.*", f"modmenu_version={cfg['modmenu']}", s)
    write(p, s)

    b = root / "build.gradle"
    s = read(b)
    s = re.sub(r"it\.options\.release = \d+", f"it.options.release = {cfg['java']}", s)
    write(b, s)

    m = root / "src/main/resources/fabric.mod.json"
    d = json.loads(read(m))
    d["depends"]["minecraft"] = cfg["range"]
    d["depends"]["java"] = f">={cfg['java']}"

    # Идентификатор Fabric API менялся: на ветках 1.16 и 1.17 мод называется
    # "fabric" и никакого provides у него нет, а с 1.18.2 он "fabric-api",
    # который дополнительно объявляет provides: ["fabric"]. Если попросить
    # "fabric-api" на 1.16, загрузчик скажет «отсутствует» даже когда Fabric
    # API стоит в папке mods.
    d["depends"].pop("fabric", None)
    d["depends"].pop("fabric-api", None)
    # На 1.16 сетевой API v1 (ServerPlayNetworking, PacketSender) появился
    # только в поздних сборках Fabric API. Со старой сборкой мод не падает
    # при проверке зависимостей, а падает уже в onInitialize с
    # NoClassDefFoundError — поэтому здесь указан минимум версии, чтобы
    # загрузчик сказал об этом внятно и заранее.
    d["depends"][cfg.get("fabric_api_id", "fabric-api")] = cfg.get("fabric_api_dep", "*")
    write(m, json.dumps(d, ensure_ascii=False, indent="\t") + "\n")

    # compatibilityLevel миксинов обязан совпадать с Java, на которой реально
    # работает игра: Mixin проверяет это при запуске и падает, если уровень
    # выше возможностей JRE (1.16.5 идёт на Java 8, а не 17).
    mx = root / "src/main/resources/packanimation.client.mixins.json"
    edit(mx, (r'"compatibilityLevel": "JAVA_\d+"',
              f'"compatibilityLevel": "JAVA_{cfg["java"]}"'), regex=True)

    # pack_format у автосоздаваемой папки пользовательских паков
    pm = root / "src/main/java/ru/dobriyanonimj/packanimation/client/pack/PackManager.java"
    edit(pm, (r'\\"pack_format\\": \d+', f'\\\\"pack_format\\\\": {cfg["pack_format"]}'), regex=True)


def to_matrix_gui(root):
    """Меню под MatrixStack (до 1.20 в игре нет DrawContext)."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/gui/PackSelectScreen.java"
    s = read(p)
    s = s.replace("import net.minecraft.client.gui.DrawContext;\n",
                  "import net.minecraft.client.util.math.MatrixStack;\n")
    s = s.replace("DrawContext context", "MatrixStack matrices")
    s = s.replace("context.fill(", "fill(matrices, ")
    s = s.replace("context.drawTextWithShadow(this.textRenderer,", "this.textRenderer.drawWithShadow(matrices,")
    s = s.replace("context.drawCenteredTextWithShadow(this.textRenderer,", "this.textRenderer.drawWithShadow(matrices,")
    s = re.sub(r"\n\s*context\.enableScissor\([^;]*;", "", s)
    s = re.sub(r"\n\s*context\.disableScissor\(\);", "", s)
    s = s.replace("(context,", "(matrices,")
    s = s.replace("(context)", "(matrices)")
    s = s.replace(" context;", " matrices;")
    s = s.replace("renderScrollbar(context)", "renderScrollbar(matrices)")
    s = s.replace("this.renderBackground(context)", "this.renderBackground(matrices)")
    s = s.replace("roundedRect(context,", "roundedRect(matrices,")
    # без ножниц строку, вылезающую за край списка, просто не рисуем
    s = s.replace("""			if (y + entry.height < listY || y > listY + listH) {
				continue; // за пределами видимой области
			}""",
                  """			// До 1.20 у Screen нет отсечения по прямоугольнику, поэтому строку,
			// вылезающую за край списка, просто не рисуем.
			if (y < listY || y + entry.height > listY + listH) {
				continue;
			}""")
    write(p, s)


def to_legacy_text(root):
    """До 1.19 не было Text.translatable / Text.literal."""
    for path in root.rglob("*.java"):
        s = read(path)
        if "Text.translatable(" not in s and "Text.literal(" not in s:
            continue
        s = re.sub(r"Text\.translatable\(", "new TranslatableText(", s)
        s = re.sub(r"Text\.literal\(", "new LiteralText(", s)
        s = s.replace("import net.minecraft.text.Text;",
                      "import net.minecraft.text.LiteralText;\n"
                      "import net.minecraft.text.Text;\n"
                      "import net.minecraft.text.TranslatableText;")
        write(path, s)


def to_legacy_resources(root):
    """До 1.19 findResources отдавал Collection<Identifier>, а не Map."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/anim/AnimationRegistry.java"
    s = read(p)
    s = s.replace("import net.minecraft.resource.Resource;\n", "")
    s = s.replace("""		Map<Identifier, Resource> found =
				manager.findResources(FOLDER, id -> id.getPath().endsWith(SUFFIX));

		for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
			Identifier file = entry.getKey();""",
                  """		// До 1.19 findResources принимал Predicate<String> и отдавал просто
		// список идентификаторов — содержимое достаётся отдельным вызовом.
		java.util.Collection<Identifier> found =
				manager.findResources(FOLDER, path -> path.endsWith(SUFFIX));

		for (Identifier file : found) {""")
    s = s.replace("""			try (Reader reader = new InputStreamReader(
					entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {""",
                  """			try (Reader reader = new InputStreamReader(
					manager.getResource(file).getInputStream(), StandardCharsets.UTF_8)) {""")
    write(p, s)


def to_renderbg4(root, cfg):
    """С 1.20.2 renderBackground принимает координаты мыши и delta."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/gui/PackSelectScreen.java"
    edit(p, ("		this.renderBackground(context);",
             "		// С 1.20.2 renderBackground принимает ещё координаты мыши и delta.\n"
             "		this.renderBackground(context, mouseX, mouseY, delta);"))


def to_scroll4(root):
    """С 1.20.5 у mouseScrolled появилась горизонтальная ось."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/gui/PackSelectScreen.java"
    edit(p, ("	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {",
             "	// С 1.20.5 у прокрутки появилась горизонтальная ось.\n"
             "	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {"),
         ("scroll - (int) (amount * 16)", "scroll - (int) (vertical * 16)"))


def to_identifier_of(root):
    """С 1.21 публичный конструктор Identifier закрыт."""
    for path in root.rglob("*.java"):
        s = read(path)
        if "new Identifier(" in s:
            write(path, s.replace("new Identifier(", "Identifier.of("))


def to_payload_networking(root):
    """1.20.5+: сеть на CustomPayload."""
    write(root / "src/main/java/ru/dobriyanonimj/packanimation/network/PackAnimationNetworking.java",
          PAYLOAD_NETWORKING)
    write(root / "src/main/java/ru/dobriyanonimj/packanimation/PackAnimationMod.java", PAYLOAD_MOD)

    c = root / "src/main/java/ru/dobriyanonimj/packanimation/client/PackAnimationClient.java"
    edit(c,
         ("""		ClientPlayNetworking.registerGlobalReceiver(PackAnimationNetworking.SYNC_PACK_PACKET,
				(client, handler, buf, responseSender) -> {
					Map<UUID, String> entries = PackAnimationNetworking.decodeSync(buf);
					client.execute(() -> PackStateTracker.applyAll(entries));
				});""",
          """		ClientPlayNetworking.registerGlobalReceiver(PackAnimationNetworking.SyncPackPayload.ID,
				(payload, context) -> {
					Map<UUID, String> entries = payload.entries();
					context.client().execute(() -> PackStateTracker.applyAll(entries));
				});"""),
         ("""		ClientPlayNetworking.send(PackAnimationNetworking.SELECT_PACK_PACKET,
				PackAnimationNetworking.encodeSelect(packId == null ? "" : packId));""",
          """		ClientPlayNetworking.send(
				new PackAnimationNetworking.SelectPackPayload(packId == null ? "" : packId));"""),
         ("import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;\n", ""),
         ("import net.minecraft.network.PacketByteBuf;\n", ""))



PAYLOAD_NETWORKING = """package ru.dobriyanonimj.packanimation.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сетевой протокол Pack Animation для 1.20.5 и новее.
 * <p>
 * В 1.20.5 Mojang переписали пользовательские пакеты: вместо каналов с
 * «сырым» PacketByteBuf теперь записи CustomPayload с кодеками. Формат
 * байтов на проводе оставлен прежним, поэтому Bukkit-плагин работает со
 * всеми версиями мода без правок.
 */
public final class PackAnimationNetworking {

\tpublic static final String MOD_ID = "packanimation";

\tpublic static final int MAX_PACK_ID_BYTES = 128;
\tpublic static final int MAX_SYNC_ENTRIES = 10000;

\tprivate PackAnimationNetworking() {
\t}

\tpublic record SelectPackPayload(String packId) implements CustomPayload {

\t\tpublic static final CustomPayload.Id<SelectPackPayload> ID =
\t\t\t\tnew CustomPayload.Id<>(Identifier.of(MOD_ID, "select_pack"));

\t\tpublic static final PacketCodec<PacketByteBuf, SelectPackPayload> CODEC =
\t\t\t\tCustomPayload.codecOf(SelectPackPayload::write, SelectPackPayload::new);

\t\tprivate SelectPackPayload(PacketByteBuf buf) {
\t\t\tthis(readString(buf));
\t\t}

\t\tprivate void write(PacketByteBuf buf) {
\t\t\twriteString(buf, packId);
\t\t}

\t\t@Override
\t\tpublic CustomPayload.Id<? extends CustomPayload> getId() {
\t\t\treturn ID;
\t\t}
\t}

\tpublic record SyncPackPayload(Map<UUID, String> entries) implements CustomPayload {

\t\tpublic static final CustomPayload.Id<SyncPackPayload> ID =
\t\t\t\tnew CustomPayload.Id<>(Identifier.of(MOD_ID, "sync_pack"));

\t\tpublic static final PacketCodec<PacketByteBuf, SyncPackPayload> CODEC =
\t\t\t\tCustomPayload.codecOf(SyncPackPayload::write, SyncPackPayload::new);

\t\tpublic static SyncPackPayload single(UUID playerId, String packId) {
\t\t\tMap<UUID, String> map = new LinkedHashMap<>();
\t\t\tmap.put(playerId, packId == null ? "" : packId);
\t\t\treturn new SyncPackPayload(map);
\t\t}

\t\tprivate SyncPackPayload(PacketByteBuf buf) {
\t\t\tthis(readEntries(buf));
\t\t}

\t\tprivate void write(PacketByteBuf buf) {
\t\t\tbuf.writeInt(entries.size());
\t\t\tfor (Map.Entry<UUID, String> entry : entries.entrySet()) {
\t\t\t\tbuf.writeLong(entry.getKey().getMostSignificantBits());
\t\t\t\tbuf.writeLong(entry.getKey().getLeastSignificantBits());
\t\t\t\twriteString(buf, entry.getValue());
\t\t\t}
\t\t}

\t\tprivate static Map<UUID, String> readEntries(PacketByteBuf buf) {
\t\t\tMap<UUID, String> out = new LinkedHashMap<>();
\t\t\tint count = buf.readInt();
\t\t\tif (count < 0 || count > MAX_SYNC_ENTRIES) {
\t\t\t\treturn out;
\t\t\t}
\t\t\tfor (int i = 0; i < count; i++) {
\t\t\t\tlong most = buf.readLong();
\t\t\t\tlong least = buf.readLong();
\t\t\t\tout.put(new UUID(most, least), readString(buf));
\t\t\t}
\t\t\treturn out;
\t\t}

\t\t@Override
\t\tpublic CustomPayload.Id<? extends CustomPayload> getId() {
\t\t\treturn ID;
\t\t}
\t}

\tprivate static void writeString(PacketByteBuf buf, String value) {
\t\tbyte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
\t\tif (bytes.length > MAX_PACK_ID_BYTES) {
\t\t\tbytes = new byte[0];
\t\t}
\t\tbuf.writeShort(bytes.length);
\t\tbuf.writeBytes(bytes);
\t}

\tprivate static String readString(PacketByteBuf buf) {
\t\tint length = buf.readShort() & 0xFFFF;
\t\tif (length > MAX_PACK_ID_BYTES || length > buf.readableBytes()) {
\t\t\treturn "";
\t\t}
\t\tbyte[] bytes = new byte[length];
\t\tbuf.readBytes(bytes);
\t\treturn new String(bytes, StandardCharsets.UTF_8);
\t}
}
"""

PAYLOAD_MOD = """package ru.dobriyanonimj.packanimation;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dobriyanonimj.packanimation.network.PackAnimationNetworking;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pack Animation — общая точка входа (вариант для 1.20.5+).
 * Автор: Dobriy_Anonimj
 */
public class PackAnimationMod implements ModInitializer {

\tpublic static final String MOD_ID = PackAnimationNetworking.MOD_ID;
\tpublic static final Logger LOGGER = LoggerFactory.getLogger("Pack Animation");

\tprivate static final Map<UUID, String> SELECTED_PACKS = new ConcurrentHashMap<>();

\t@Override
\tpublic void onInitialize() {
\t\tLOGGER.info("Pack Animation by Dobriy_Anonimj: initializing common logic");

\t\t// С 1.20.5 типы пакетов регистрируются заранее и обязательно до обработчиков.
\t\tPayloadTypeRegistry.playC2S().register(
\t\t\t\tPackAnimationNetworking.SelectPackPayload.ID,
\t\t\t\tPackAnimationNetworking.SelectPackPayload.CODEC);
\t\tPayloadTypeRegistry.playS2C().register(
\t\t\t\tPackAnimationNetworking.SyncPackPayload.ID,
\t\t\t\tPackAnimationNetworking.SyncPackPayload.CODEC);

\t\tServerPlayNetworking.registerGlobalReceiver(PackAnimationNetworking.SelectPackPayload.ID,
\t\t\t\t(payload, context) -> {
\t\t\t\t\tServerPlayerEntity player = context.player();
\t\t\t\t\tif (player.isRemoved()) {
\t\t\t\t\t\treturn;
\t\t\t\t\t}
\t\t\t\t\tString packId = payload.packId();
\t\t\t\t\tUUID id = player.getUuid();
\t\t\t\t\tif (packId.isEmpty()) {
\t\t\t\t\t\tSELECTED_PACKS.remove(id);
\t\t\t\t\t} else {
\t\t\t\t\t\tSELECTED_PACKS.put(id, packId);
\t\t\t\t\t}
\t\t\t\t\tbroadcast(player, id, packId);
\t\t\t\t});

\t\tServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
\t\t\tif (SELECTED_PACKS.isEmpty()) {
\t\t\t\treturn;
\t\t\t}
\t\t\tServerPlayNetworking.send(handler.player,
\t\t\t\t\tnew PackAnimationNetworking.SyncPackPayload(Map.copyOf(SELECTED_PACKS)));
\t\t});

\t\tServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
\t\t\t\tSELECTED_PACKS.remove(handler.player.getUuid()));
\t}

\tprivate static void broadcast(ServerPlayerEntity source, UUID sourceId, String packId) {
\t\tif (source.getServer() == null) {
\t\t\treturn;
\t\t}
\t\tPackAnimationNetworking.SyncPackPayload payload =
\t\t\t\tPackAnimationNetworking.SyncPackPayload.single(sourceId, packId);
\t\tfor (ServerPlayerEntity target : source.getServer().getPlayerManager().getPlayerList()) {
\t\t\tif (target == source) {
\t\t\t\tcontinue;
\t\t\t}
\t\t\tServerPlayNetworking.send(target, payload);
\t\t}
\t}
}
"""


def to_render_state(root):
    """1.21.2+: модель получает объект состояния, а не сущность."""
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/mixin/client/PlayerEntityModelMixin.java"
    write(p, RENDER_STATE_MIXIN)


RENDER_STATE_MIXIN = '''package ru.dobriyanonimj.packanimation.mixin.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dobriyanonimj.packanimation.client.anim.Pose;
import ru.dobriyanonimj.packanimation.client.anim.PlayerAnimationEngine;
import ru.dobriyanonimj.packanimation.client.anim.PoseApplier;

/**
 * Вариант миксина для 1.21.2 и новее.
 * <p>
 * В этих версиях рендер сущностей переведён на объекты состояния: модель
 * больше не получает саму сущность, а получает {@link PlayerEntityRenderState}.
 * Из-за этого:
 * <ul>
 *     <li>UUID игрока недоступен — движок ключуется по {@code state.id},
 *     сетевому идентификатору сущности;</li>
 *     <li>время берётся из {@code state.age} (возраст в тиках, уже с учётом
 *     доли кадра) вместо прежнего параметра animationProgress.</li>
 * </ul>
 * <p>
 * Выход из метода всегда идёт через {@code PoseApplier}: даже когда анимации
 * нет, наложенный в прошлый раз сдвиг pivot надо снять, иначе части модели
 * останутся смещёнными с прошлого кадра.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin extends BipedEntityModel<PlayerEntityRenderState> {

\t@Unique
\tprivate static final Pose packanimation$pose = new Pose();

\tprotected PlayerEntityModelMixin(ModelPart root) {
\t\tsuper(root);
\t}

\t@Inject(method = "setAngles", at = @At("TAIL"))
\tprivate void packanimation$applyAnimation(PlayerEntityRenderState state, CallbackInfo ci) {
\t\tif (PlayerAnimationEngine.suppressed) {
\t\t\tpackanimation$restoreVanilla();
\t\t\treturn;
\t\t}

\t\tfloat now = state.age / 20f;
\t\tfloat weight = PlayerAnimationEngine.pose(state.id, now, packanimation$pose);
\t\tif (weight <= 0.001f) {
\t\t\tpackanimation$restoreVanilla();
\t\t\treturn;
\t\t}

\t\tPoseApplier.apply(packanimation$pose, weight,
\t\t\t\tthis.head, this.body, this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
\t\tpackanimation$copyToOuterLayers();
\t}

\t@Unique
\tprivate void packanimation$restoreVanilla() {
\t\tPoseApplier.reset(this.head, this.body, this.rightArm, this.leftArm,
\t\t\t\tthis.rightLeg, this.leftLeg);
\t\tpackanimation$copyToOuterLayers();
\t}

\t@Unique
\tprivate void packanimation$copyToOuterLayers() {
\t\tPlayerEntityModel model = (PlayerEntityModel) (Object) this;
\t\tthis.hat.copyTransform(this.head);
\t\tmodel.jacket.copyTransform(this.body);
\t\tmodel.rightSleeve.copyTransform(this.rightArm);
\t\tmodel.leftSleeve.copyTransform(this.leftArm);
\t\tmodel.rightPants.copyTransform(this.rightLeg);
\t\tmodel.leftPants.copyTransform(this.leftLeg);
\t}
}
'''


def to_java8(root):
    """1.16.5 работает на Java 8: убираем record, switch-выражения, var, pattern matching."""
    # PackInfo: record -> обычный класс
    p = root / "src/main/java/ru/dobriyanonimj/packanimation/client/pack/PackInfo.java"
    write(p, JAVA8_PACKINFO)

    c = root / "src/main/java/ru/dobriyanonimj/packanimation/client/pack/MovementAnimationController.java"
    edit(c, ("""		var animationId = switch (desired) {
			case IDLE -> pack.idleAnim();
			case WALK -> pack.walkAnim();
			case RUN -> pack.runAnim();
		};""",
             """		Identifier animationId;
		if (desired == MovementState.WALK) {
			animationId = pack.walkAnim();
		} else if (desired == MovementState.RUN) {
			animationId = pack.runAnim();
		} else {
			animationId = pack.idleAnim();
		}"""),
         ("import net.minecraft.client.network.AbstractClientPlayerEntity;",
          "import net.minecraft.client.network.AbstractClientPlayerEntity;\nimport net.minecraft.util.Identifier;"))

    b = root / "src/main/java/ru/dobriyanonimj/packanimation/client/anim/Bones.java"
    write(b, JAVA8_BONES)

    # Files.writeString — это Java 11. Заводим крошечную замену и переключаем
    # на неё все вызовы: так не приходится городить регулярку по многострочным
    # аргументам.
    write(root / "src/main/java/ru/dobriyanonimj/packanimation/client/pack/Java8Files.java",
          JAVA8_FILES)
    for path in root.rglob("*.java"):
        s = read(path)
        if "Files.writeString(" not in s:
            continue
        s = s.replace("Files.writeString(", "Java8Files.writeString(")
        if "client/pack/" not in path.as_posix():
            s = s.replace("import java.nio.file.Files;",
                          "import java.nio.file.Files;\n"
                          "import ru.dobriyanonimj.packanimation.client.pack.Java8Files;")
        write(path, s)

    # List.of / Map.of / Set.of появились в Java 9 — под Java 8 нужен Arrays.asList
    for path in root.rglob("*.java"):
        s = read(path)
        if not any(t in s for t in ("List.of(", "Map.of(", "Set.of(",
                                    "Map.copyOf(", "List.copyOf(")):
            continue
        s = s.replace("List.of(", "java.util.Arrays.asList(")
        s = s.replace("Map.copyOf(", "new java.util.LinkedHashMap<>(")
        s = s.replace("List.copyOf(", "new java.util.ArrayList<>(")
        write(path, s)

    m = root / "src/main/java/ru/dobriyanonimj/packanimation/mixin/client/PlayerEntityModelMixin.java"
    edit(m, ("if (PlayerAnimationEngine.suppressed || !(entity instanceof AbstractClientPlayerEntity player)) {\n"
             "\t\t\tpackanimation$restoreVanilla();\n\t\t\treturn;\n\t\t}",
             "if (PlayerAnimationEngine.suppressed || !(entity instanceof AbstractClientPlayerEntity)) {\n"
             "\t\t\tpackanimation$restoreVanilla();\n"
             "\t\t\treturn;\n\t\t}\n"
             "\t\tAbstractClientPlayerEntity player = (AbstractClientPlayerEntity) entity;"))



JAVA8_FILES = '''package ru.dobriyanonimj.packanimation.client.pack;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Замена {@code Files.writeString} для сборки под 1.16.5.
 * <p>
 * Игра там работает на Java 8, а {@code Files.writeString} появился только
 * в Java 11. Поведение то же самое: записать текст в файл целиком,
 * перезаписав старое содержимое.
 */
public final class Java8Files {

\tprivate Java8Files() {
\t}

\tpublic static Path writeString(Path path, CharSequence text, Charset charset) throws IOException {
\t\treturn Files.write(path, text.toString().getBytes(charset));
\t}
}
'''

JAVA8_PACKINFO = '''package ru.dobriyanonimj.packanimation.client.pack;

import net.minecraft.util.Identifier;

/**
 * Описание одного пака анимаций.
 * <p>
 * В сборке под 1.16.5 это обычный класс, а не record: игра там работает на
 * Java 8, где record ещё не существует.
 */
public final class PackInfo {

\tpublic enum PackSource {
\t\tBUILTIN,
\t\tCUSTOM
\t}

\tprivate final String id;
\tprivate final String displayName;
\tprivate final String author;
\tprivate final PackSource source;
\tprivate final Identifier idleAnim;
\tprivate final Identifier walkAnim;
\tprivate final Identifier runAnim;

\tpublic PackInfo(String id, String displayName, String author, PackSource source,
\t\t\t\t\tIdentifier idleAnim, Identifier walkAnim, Identifier runAnim) {
\t\tthis.id = id;
\t\tthis.displayName = displayName;
\t\tthis.author = author;
\t\tthis.source = source;
\t\tthis.idleAnim = idleAnim;
\t\tthis.walkAnim = walkAnim;
\t\tthis.runAnim = runAnim;
\t}

\tpublic String id() {
\t\treturn id;
\t}

\tpublic String displayName() {
\t\treturn displayName;
\t}

\tpublic String author() {
\t\treturn author;
\t}

\tpublic PackSource source() {
\t\treturn source;
\t}

\tpublic Identifier idleAnim() {
\t\treturn idleAnim;
\t}

\tpublic Identifier walkAnim() {
\t\treturn walkAnim;
\t}

\tpublic Identifier runAnim() {
\t\treturn runAnim;
\t}
}
'''

JAVA8_BONES = '''package ru.dobriyanonimj.packanimation.client.anim;

import java.util.Locale;

/**
 * Кости, которыми оперирует Pack Animation, и их номера в {@link Pose}.
 * <p>
 * Вариант под Java 8 (сборка 1.16.5): вместо switch-выражения обычные
 * сравнения строк.
 */
public final class Bones {

\tpublic static final int HEAD = 0;
\tpublic static final int TORSO = 1;
\tpublic static final int RIGHT_ARM = 2;
\tpublic static final int LEFT_ARM = 3;
\tpublic static final int RIGHT_LEG = 4;
\tpublic static final int LEFT_LEG = 5;
\t/** Смещение/поворот игрока целиком. */
\tpublic static final int BODY = 6;

\tpublic static final int COUNT = 7;

\tprivate Bones() {
\t}

\t/** @return номер кости или -1, если имя не распознано. */
\tpublic static int index(String name) {
\t\tString n = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
\t\tif (n.equals("head")) return HEAD;
\t\tif (n.equals("torso") || n.equals("chest") || n.equals("upperbody")) return TORSO;
\t\tif (n.equals("rightarm") || n.equals("armright")) return RIGHT_ARM;
\t\tif (n.equals("leftarm") || n.equals("armleft")) return LEFT_ARM;
\t\tif (n.equals("rightleg") || n.equals("legright")) return RIGHT_LEG;
\t\tif (n.equals("leftleg") || n.equals("legleft")) return LEFT_LEG;
\t\tif (n.equals("body") || n.equals("root")) return BODY;
\t\treturn -1;
\t}
}
'''


def build_variant(mc, cfg):
    root = pathlib.Path(f"PackAnimation-{mc}")
    if root.exists():
        shutil.rmtree(root)
    shutil.copytree(MASTER, root,
                    ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__", "bukkit-plugin"))

    apply_versions(root, mc, cfg)

    if cfg.get("gui") == "matrix":
        to_matrix_gui(root)
    if cfg.get("text_legacy"):
        to_legacy_text(root)
    if cfg.get("res_legacy"):
        to_legacy_resources(root)
    if cfg.get("log4j"):
        to_log4j(root)
    if cfg.get("packs_legacy"):
        to_legacy_packs(root)
    if cfg.get("gson_legacy"):
        to_legacy_gson(root)
    if cfg.get("screen_legacy"):
        to_legacy_screen(root)
    if cfg.get("open_screen"):
        to_open_screen(root)
    if cfg.get("entity_get_id"):
        to_entity_get_id(root)
    if cfg.get("biped_scale_ctor"):
        to_biped_scale_ctor(root)
    if cfg.get("removed_field"):
        to_removed_field(root)
    if cfg.get("renderbg4"):
        to_renderbg4(root, cfg)
    if cfg.get("scroll4"):
        to_scroll4(root)
    if cfg.get("payload"):
        to_payload_networking(root)
    if cfg.get("identifier_of"):
        to_identifier_of(root)
    if cfg.get("renderstate"):
        to_render_state(root)
    if cfg.get("model_origin"):
        to_model_origin(root)
    if cfg["java"] == 8:
        to_java8(root)

    shutil.copytree(MASTER / "bukkit-plugin", root / "bukkit-plugin")
    yml = root / "bukkit-plugin/src/main/resources/plugin.yml"
    api = ".".join(mc.split(".")[:2])
    edit(yml, (r"api-version: '[^']*'", f"api-version: '{api}'"), regex=True)

    flags = [k for k in ("gui", "text_legacy", "res_legacy", "log4j", "packs_legacy",
                         "gson_legacy", "screen_legacy", "open_screen", "entity_get_id",
                         "biped_scale_ctor", "removed_field", "model_origin", "renderbg4", "scroll4",
                         "payload", "identifier_of", "renderstate") if cfg.get(k)]
    print(f"  PackAnimation-{mc:8s} {cfg['range']:20s} Java {cfg['java']:<3d} {', '.join(flags) or 'без правок'}")
    return root


if __name__ == "__main__":
    if not MASTER.exists():
        raise SystemExit("Запускать из папки, содержащей PackAnimation/")
    print("Собираю варианты:")
    for mc, cfg in VERSIONS.items():
        if cfg.get("master"):
            print(f"  PackAnimation-{mc}  (мастер, копируется как есть)")
            root = pathlib.Path(f"PackAnimation-{mc}")
            if root.exists():
                shutil.rmtree(root)
            shutil.copytree(MASTER, root,
                            ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__"))
            # даже мастеру нужен сплошной диапазон вместо ~1.20.1
            apply_versions(root, mc, cfg)
            edit(root / "bukkit-plugin/src/main/resources/plugin.yml",
                 (r"api-version: '[^']*'", "api-version: '1.20'"), regex=True)
            continue
        build_variant(mc, cfg)
    print("\nГотово.")
