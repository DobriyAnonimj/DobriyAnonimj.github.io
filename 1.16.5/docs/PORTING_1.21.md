# Порт Pack Animation на 1.21.1 и новее

Документ фиксирует всё, что выяснено про переход. Каждый факт ниже проверен
по официальным источникам (ссылки приведены), кроме отдельно помеченного
раздела «Что осталось выяснить».

## Главное: библиотека меняется

**Player Animator (KosmX) заброшен.** В README ветки 1.21 написано прямо:

> I no longer wish to maintain this project.
> Please use PAL instead: https://docs.zigythebird.com/pal/how_to_port_from_player_animator/
> Major bugfixes on existing releases will be done if needed, nothing else.

Преемник — **PlayerAnimationLibrary (PAL)**, автор ZigyTheBird. Поэтому порт
на 1.21.1 нужно делать сразу на PAL, а не на старую библиотеку: тогда
1.21.7 … 26.2 добавятся потом почти без работы — это та же библиотека,
тот же API.

| MC | Библиотека | Статус |
|----|-----------|--------|
| 1.16.4 – 1.21.7 | Player Animator (KosmX) | заброшена |
| 1.21.1, 1.21.7 – 26.2 | PAL | развивается |

Единственная версия, где есть обе — **1.21.1**. Это и делает её правильной
следующей целью.

## Версии зависимостей для 1.21.1

Проверено по meta.fabricmc.net, Modrinth API и maven-metadata PAL.

```properties
minecraft_version=1.21.1
yarn_mappings=1.21.1+build.3
loader_version=0.19.5
fabric_version=0.116.16+1.21.1
pal_version=1.1.6+mc.1.21.1
```

```gradle
repositories {
    maven {
        name = "RedlanceMinecraft"
        url = uri("https://repo.redlance.org/public")
    }
}

dependencies {
    // Для 1.21.11 и старше — именно modImplementation, не implementation.
    modImplementation "com.zigythebird.playeranim:PlayerAnimationLibFabric:${pal_version}"
}
```

Для сравнения, тот же артефакт под новые версии: `1.2.6+mc.26.2`,
`1.1.10+mc.1.21.11` — то есть один и тот же `PlayerAnimationLibFabric`
покрывает весь диапазон 1.21.1 → 26.2.

## Изменения API библиотеки

По руководству
https://docs.zigythebird.com/pal/gettingstarted/how_to_port_from_player_animator

| Player Animator | PAL |
|-----------------|-----|
| `KeyframeAnimationPlayer` | `PlayerAnimationController` |
| `ModifierLayer` / `AnimationLayer` | встроены в `PlayerAnimationController` |
| `KeyframeAnimation` | `Animation` |
| папка `assets/<mod>/player_animation` | `assets/<mod>/player_animation**s**` |

Регистрация слоя:

```java
PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ANIMATION_LAYER_ID, 1000,
        player -> new PlayerAnimationController(player,
                (controller, state, animSetter) -> PlayState.STOP
        )
);
```

Проигрывание:

```java
PlayerAnimationController controller = (PlayerAnimationController)
        PlayerAnimationAccess.getPlayerAnimationLayer(player, ANIMATION_LAYER_ID);
controller.triggerAnimation(animationID);
```

Обработчик состояния:

```java
private static final RawAnimation WALK_ANIMATION = PlayerRawAnimationBuilder.begin()
        .then(Identifier.fromNamespaceAndPath("my_mod", "walk"), Animation.LoopType.LOOP)
        .build();

(controller, state, animSetter) -> {
    if (state.isMoving()) return animSetter.setAnimation(WALK_ANIMATION);
    return PlayState.STOP;
}
```

**Это заметно упрощает мод.** У PAL есть встроенный `state.isMoving()`, то
есть весь `MovementAnimationController` с ручным замером скорости по позиции,
сглаживанием и защитой от дребезга схлопывается в один обработчик состояния.
Плавные переходы тоже встроены (`replaceWithFade`).

## Изменения формата анимаций

Генератор `tools/generate_builtin_animations.py` уже выпускает **оба**
варианта паков:

* `assets/packanimation/player_animation/…` — для Player Animator (1.20.1);
* `assets/packanimation/player_animations/…` — для PAL (1.21.1+), у кости
  `body` инвертированы оси вращения X и Y.

Полный список различий систем координат из руководства по переходу:

* положение body: блоки/метры → пиксели;
* ось Y положения инвертирована у всех костей, кроме body;
* оси X и Y вращения инвертированы у кости body;
* оси X и Z инвертированы у плащей;
* оси Z и Y меняются местами у предметов.

Из этого нас касаются только два пункта (body-вращение и единицы положения),
потому что положение мы задаём единственной кости `body`, а плащи и предметы
не трогаем.

> **Проверить первым делом.** Единицы положения body: если PAL ждёт пиксели,
> а числа писались под блоки, амплитуда «дыхания» и подпрыгивания станет в
> 16 раз меньше. Это безопасный отказ (движение станет незаметным, а не
> улетит), но подкрутить надо.

## Изменения самого Minecraft между 1.20.1 и 1.21.1

Затрагивают наш код в четырёх местах:

1. **`Identifier`** — конструктор `new Identifier(ns, path)` закрыт, вместо
   него `Identifier.of(ns, path)` / `Identifier.fromNamespaceAndPath(...)`.
   У нас идентификаторы создаются в `PackInfo`, `PackManager`,
   `PackAnimationNetworking`, `PackAnimationClient`.
2. **Сеть переписана в 1.20.5** — каналы на `PacketByteBuf` заменены на
   записи `CustomPayload` + `PayloadTypeRegistry`. Это касается
   `PackAnimationNetworking`, `PackAnimationMod`, `PackAnimationClient`.
   Формат байтов на проводе можно сохранить прежним — тогда **Bukkit-плагин
   менять не придётся вообще**.
3. **`Screen.renderBackground`** — с 1.20.2 принимает
   `(DrawContext, int mouseX, int mouseY, float delta)` вместо одного
   аргумента. Правится в `PackSelectScreen`.
4. **`mouseScrolled`** — в новых версиях добавлен горизонтальный аргумент:
   `(double, double, double horizontal, double vertical)`. Тоже
   `PackSelectScreen`.

Плюс: 1.20.5 и новее требуют **Java 21** для сборки (у нас сейчас
`options.release = 17`).

## Что осталось выяснить

Одно, но блокирующее: **полные имена пакетов PAL для `import`**. В
документации приведены только имена классов (`PlayerAnimationController`,
`PlayerAnimationFactory`, `PlayerAnimationAccess`, `PlayState`,
`RawAnimation`, `PlayerRawAnimationBuilder`, `Animation.LoopType`), а
javadoc и исходники в открытом вебе не отдаются. Группа артефакта —
`com.zigythebird.playeranim`, но конкретные подпакеты угадывать нельзя:
ошибёшься — получишь стену «cannot find symbol».

Решается за пару минут: рядом с обычным jar на Modrinth и в maven лежит
**`PlayerAnimationLibFabric-1.1.6+mc.1.21.1-sources.jar`**. В нём видно
дерево пакетов целиком.

## Порядок работ

1. Достать sources.jar PAL и выписать точные `import`.
2. Скопировать проект в отдельную папку под 1.21.1 (не трогая рабочую
   1.20.1), поменять `gradle.properties` и `build.gradle` на версии выше.
3. Заменить `new Identifier(...)` на `Identifier.of(...)`.
4. Переписать сеть на `CustomPayload`, сохранив формат байтов.
5. Поправить две сигнатуры в `PackSelectScreen`.
6. Заменить слой анимаций на `PlayerAnimationController`, выбросив ручной
   расчёт состояния в пользу `state.isMoving()`.
7. Собрать, поймать ошибки, поправить.
8. Только после того, как обе версии собираются, — объединять их в один
   исходник через Stonecutter. Раньше не стоит: если конфиг Stonecutter
   окажется неверным, перестанут собираться **обе** версии, включая
   работающую сейчас.
