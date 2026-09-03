#!/usr/bin/env python3
"""
Генератор встроенных паков анимаций Pack Animation.
Автор мода: Dobriy_Anonimj

Зачем нужен: движок интерполирует между ключевыми кадрами линейно.
Если поставить 2-3 кадра на цикл, движение получается «треугольным» — с
постоянной скоростью и резким разворотом на краях, что выглядит дёрганно.
Поэтому анимации здесь описаны математическими кривыми и сэмплируются в
17-25 кадров на цикл: линейная интерполяция между частыми кадрами
практически неотличима от гладкой кривой.

Запуск (из корня проекта):
    python3 tools/generate_builtin_animations.py
"""

import json
import math
import os

OUT_ROOT = os.path.join(
    "src", "main", "resources", "assets", "packanimation", "player_animation", "builtin"
)

TAU = math.pi * 2


# --------------------------------------------------------------------------
# Кривые
# --------------------------------------------------------------------------

def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def gait(p, skew=0.16):
    """
    Асимметричный шаг вместо чистого синуса: нога выносится вперёд быстрее,
    чем возвращается назад. Так устроена настоящая походка (фаза переноса
    короче опорной), и на глаз это читается заметно приятнее, чем ровный
    маятник. Функция периодична, поэтому цикл по-прежнему замыкается.
    """
    return (math.sin(TAU * p) + skew * math.sin(2 * TAU * p)) / (1 + skew)


def mech(phase, ease=0.30):
    """
    «Механическая» волна: быстрый плавный переход между -1 и 1, затем пауза.
    Даёт роботичность БЕЗ телепортации между кадрами (именно телепорт и читался
    раньше как глюк, а не как робот).
    """
    p = phase % 1.0
    if p < 0.5:
        x = p / 0.5
        return -1.0 + 2.0 * smoothstep(x / ease if ease > 0 else 1.0)
    x = (p - 0.5) / 0.5
    return 1.0 - 2.0 * smoothstep(x / ease if ease > 0 else 1.0)


def fmt_time(t):
    s = "{:.4f}".format(t).rstrip("0")
    if s.endswith("."):
        s += "0"
    return s


def build(length, samples, bones):
    """
    bones: {имя кости: {"rotation"/"position": функция(phase) -> [x, y, z]}}
    Кадр в конце дублирует кадр в начале, чтобы цикл замыкался без рывка.
    """
    out = {}
    for bone, channels in bones.items():
        entry = {}
        for channel, func in channels.items():
            frames = {}
            for i in range(samples + 1):
                phase = i / samples
                t = phase * length
                frames[fmt_time(t)] = [round(v, 2) for v in func(phase % 1.0)]
            entry[channel] = frames
        out[bone] = entry
    return out


def write(pack, state, length, samples, bones):
    key = "builtin/{}/{}".format(pack, state)
    data = {
        "format_version": "1.8.0",
        "animations": {
            key: {
                "loop": True,
                "animation_length": round(length, 4),
                "bones": build(length, samples, bones),
            }
        },
    }
    directory = os.path.join(OUT_ROOT, pack)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, state + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent="\t", ensure_ascii=False)
        f.write("\n")
    print("{:28s} {:>5.2f}s  {:>3d} кадров".format(path, length, samples + 1))


# --------------------------------------------------------------------------
# Разведение конечностей.
#
# ВАЖНО, проверено по Fresh Animations: постоянный развод ног по Z делать
# НЕЛЬЗЯ. Нога вращается вокруг бедра, поэтому наклон по Z уводит в сторону
# ступню — получается «колесом» (буква А). В FA у ног стоит всего 2 градуса,
# причём правой +2, левой -2 (то есть в другую сторону, чем кажется
# интуитивно). Ощущение раздельных ног там создаётся не разводом, а разной
# фазой маха и смещением ног по глубине.
LEG_SPREAD = 2.0
# --------------------------------------------------------------------------

def legs(spread, swing_amp, swing=True, phase_shift=0.0, amp_left=None, lean=0.0):
    """
    Возвращает пару функций (правая нога, левая нога).
    lean — общий наклон обеих ног вперёд (в FA это 13 град. при ходьбе и 30
    при беге: ноги «подобраны» под наклонённый корпус).
    """
    left_amp = swing_amp if amp_left is None else amp_left

    def right(p):
        x = lean + (swing_amp * gait(p) if swing else 0.0)
        return [x, 0, spread]

    def left(p):
        x = lean - (left_amp * gait(p + phase_shift) if swing else 0.0)
        return [x, 0, -spread]

    return right, left


def arms(spread, swing_amp, lag=0.0, sway=0.0, base=0.0, base_left=None):
    """Возвращает пару функций (правая рука, левая рука)."""
    left_base = base if base_left is None else base_left

    def right(p):
        x = base - swing_amp * gait(p - lag) if swing_amp else base
        return [x, 0, -spread - sway * math.sin(TAU * p)]

    def left(p):
        x = left_base + swing_amp * gait(p - lag) if swing_amp else left_base
        return [x, 0, spread - sway * math.sin(TAU * p)]

    return right, left


# --------------------------------------------------------------------------
# ПРИКЛЮЧЕНИЯ
#
# Движение воспроизводит модель из ресурспака Fresh Animations: Player
# Extension (автор FreshLX). Его условия прямо разрешают "remix, transform and
# build upon its assets ... if credit is given" — авторство указано в README,
# в описании пака и здесь.
#
# Файлы FA сюда НЕ копировались: там формат CEM/EMF, где движение задано
# выражениями вида
#     rlegrx = torad( 13 - 40*cos(ls + cos(ls)/2.5) ) * walk
# Я разобрал эти выражения, свёл их к случаю "идём/бежим по земле" (обнулив
# sneak/prone/wade/strafe/in_air и прочие ветки) и пересчитал в ключевые
# кадры — формат, который читает движок мода.
#
# Что оттуда взято по сути:
#   * мах ноги — не синус, а cos(ls +- cos(ls)/2.5): нога выносится вперёд
#     резче, чем возвращается;
#   * обе ноги постоянно наклонены вперёд (13 град. шагом, 30 бегом) — они
#     "подобраны" под наклонённый корпус;
#   * скрутка корпуса по рысканью крупная (13 град.), крен маленький (3);
#   * руки отстают от ног на pi/7 и имеют постоянный вынос вперёд 10 град.;
#   * развод рук в стороны: 2 град. шагом, 8 бегом.
# --------------------------------------------------------------------------

def adventure_idle():
    # В FA стойка почти неподвижна: дыхание около 1-2 градусов, а вся живость
    # идёт от реакции на поворот камеры (headyaw_drag), чего в зациклённых
    # кадрах не выразить. Поэтому здесь только дыхательная часть.
    # Bt (дыхательный таймер) проходит два полных оборота за цикл, чтобы
    # sin(Bt/2) тоже замкнулся.
    bt = lambda p: 2 * TAU * p
    write("adventure", "idle", 5.0, 24, {
        "body": {"position": lambda p: [0, 0.05 + 0.1 * math.sin(math.pi / 12 + bt(p)),
                                        0.1 * math.sin(bt(p))],
                 "rotation": lambda p: [math.sin(bt(p)), 2 * math.sin(bt(p) / 2), 0]},
        "torso": {"rotation": lambda p: [0.6 * math.sin(bt(p)), 0, 0]},
        "head": {"rotation": lambda p: [0.4 * math.sin(bt(p)), 0, 0]},
        "right_arm": {"rotation": lambda p: [0, math.cos(bt(p)) + 3.5,
                                             math.cos(-math.pi / 12 + bt(p)) / 1.6 + 2]},
        "left_arm": {"rotation": lambda p: [0, -math.cos(bt(p)) - 0.5,
                                            -math.cos(-math.pi / 12 + bt(p)) / 1.6 - 2]},
        "right_leg": {"rotation": lambda p: [0, 0, LEG_SPREAD]},
        "left_leg": {"rotation": lambda p: [0, 0, -LEG_SPREAD]},
    })


def adventure_walk():
    ls = lambda p: TAU * p
    write("adventure", "walk", 0.9, 22, {
        "body": {
            "rotation": lambda p: [3.5 + math.cos(2 * ls(p)),
                                   13 * math.cos(ls(p)),
                                   -3 * math.sin(ls(p))],
            "position": lambda p: [0, 0.8 * (math.sin(math.pi / 4 + 2 * ls(p)
                                   - math.cos(math.pi / 4 + 2 * ls(p)) / 6) + 0.8), 0],
        },
        "right_arm": {"rotation": lambda p: [
            48 * math.cos(math.pi / 7 + ls(p)) + 10,
            (18 * math.cos(ls(p)) - 10 * (0.8 + math.cos(-1.3 + ls(p)
             - math.sin(-1.3 + ls(p)) / 1.5))) / 2,
            2 + 4 * math.cos(math.pi / 5 + ls(p))]},
        "left_arm": {"rotation": lambda p: [
            -48 * math.cos(math.pi / 7 + ls(p)) + 10,
            (18 * math.cos(ls(p)) + 10 * (0.8 - math.cos(-1.3 + ls(p)
             + math.sin(-1.3 + ls(p)) / 1.5))) / 2,
            -2 + 4 * math.cos(math.pi / 5 + ls(p))]},
        "right_leg": {"rotation": lambda p: [
            13 - 40 * math.cos(ls(p) + math.cos(ls(p)) / 2.5), 0, LEG_SPREAD]},
        "left_leg": {"rotation": lambda p: [
            13 + 40 * math.cos(ls(p) - math.cos(ls(p)) / 2.5), 0, -LEG_SPREAD]},
    })


def adventure_run():
    ls = lambda p: TAU * p
    write("adventure", "run", 0.6, 22, {
        "body": {
            "rotation": lambda p: [17 + 2 * math.cos(2 * ls(p)),
                                   15.6 * math.cos(ls(p)),
                                   -3.3 * math.sin(ls(p))],
            "position": lambda p: [-0.77 * math.cos(ls(p)),
                                   1.0 - math.cos(math.pi / 4 + 2 * ls(p)
                                   - math.cos(2 * ls(p)) / 4), 0],
        },
        "right_arm": {"rotation": lambda p: [
            60 * math.cos(math.pi / 7 + ls(p)) + 10,
            18 * math.cos(ls(p)) - 10 * (0.8 + math.cos(-1.3 + ls(p)
            - math.sin(-1.3 + ls(p)) / 1.5)),
            8 + 4 * math.cos(math.pi / 5 + ls(p))]},
        "left_arm": {"rotation": lambda p: [
            -60 * math.cos(math.pi / 7 + ls(p)) + 10,
            18 * math.cos(ls(p)) + 10 * (0.8 - math.cos(-1.3 + ls(p)
            + math.sin(-1.3 + ls(p)) / 1.5)),
            -8 + 4 * math.cos(math.pi / 5 + ls(p))]},
        "right_leg": {"rotation": lambda p: [
            30 - 60 * math.cos(ls(p) + math.cos(ls(p)) / 4),
            0,
            1 - 6 * math.cos(ls(p) - math.cos(ls(p)) / 1.3)]},
        "left_leg": {"rotation": lambda p: [
            30 + 60 * math.cos(ls(p) - math.cos(ls(p)) / 4),
            0,
            -1 - 6 * math.cos(ls(p) + math.cos(ls(p)) / 1.3)]},
    })


# --------------------------------------------------------------------------
# НИНДЗЯ — лёгкий, пружинистый, широкая низкая стойка
# --------------------------------------------------------------------------

def ninja_idle():
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=0, swing=False)
    write("ninja", "idle", 3.2, 20, {
        "body": {"position": lambda p: [0, 0.5 * math.sin(TAU * p), 0]},
        "torso": {"rotation": lambda p: [5 + 1.8 * math.sin(TAU * p), 0, 0]},
        "head": {"rotation": lambda p: [-3 + 1.0 * math.sin(TAU * p),
                                        5 * math.sin(TAU * p - 0.7), 0]},
        "right_arm": {"rotation": lambda p: [11 + 2.5 * math.sin(TAU * p - 0.5), 0, -6]},
        "left_arm": {"rotation": lambda p: [11 + 2.5 * math.sin(TAU * p - 0.5), 0, 6]},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": left_leg},
    })


def ninja_walk():
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=38)
    right_arm, left_arm = arms(spread=7, swing_amp=34, lag=0.25)
    write("ninja", "walk", 0.8, 16, {
        "body": {"position": lambda p: [0, 0.5 - 0.5 * math.cos(2 * TAU * p), 0]},
        "torso": {"rotation": lambda p: [9 + 1.5 * math.sin(2 * TAU * p),
                                         -5 * math.sin(TAU * p), 0]},
        "head": {"rotation": lambda p: [-4, 3 * math.sin(TAU * p), 0]},
        "right_arm": {"rotation": right_arm},
        "left_arm": {"rotation": left_arm},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": left_leg},
    })


def ninja_run():
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=55)
    right_arm, left_arm = arms(spread=10, swing_amp=58, lag=0.22)
    write("ninja", "run", 0.55, 16, {
        "body": {"position": lambda p: [0, 0.75 - 0.75 * math.cos(2 * TAU * p), 1.5]},
        "torso": {"rotation": lambda p: [22 + 2 * math.sin(2 * TAU * p),
                                         -7 * math.sin(TAU * p), 0]},
        "head": {"rotation": lambda p: [-15, 4 * math.sin(TAU * p), 0]},
        "right_arm": {"rotation": right_arm},
        "left_arm": {"rotation": left_arm},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": left_leg},
    })


# --------------------------------------------------------------------------
# ЗОМБИ — сутулый, руки вперёд, походка с хромотой
# --------------------------------------------------------------------------

def zombie_idle():
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=0, swing=False)
    write("zombie", "idle", 4.0, 20, {
        "body": {"position": lambda p: [0.3 * math.sin(TAU * p), 0, 0]},
        "torso": {"rotation": lambda p: [19 + 2.5 * math.sin(TAU * p),
                                         3 * math.sin(TAU * p - 0.6), 0]},
        "head": {"rotation": lambda p: [11 + 1.5 * math.sin(TAU * p + 0.5),
                                        7 * math.sin(TAU * p - 1.0),
                                        6 * math.sin(TAU * p)]},
        "right_arm": {"rotation": lambda p: [-73 + 4 * math.sin(TAU * p), 0,
                                             -11 - 3 * math.sin(TAU * p)]},
        "left_arm": {"rotation": lambda p: [-71 + 4 * math.sin(TAU * p + 0.9), 0,
                                            11 + 3 * math.sin(TAU * p + 0.9)]},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": lambda p: [2, 0, -LEG_SPREAD]},
    })


def zombie_walk():
    # Хромота: правая нога делает полный шаг, левая — короче и с задержкой.
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=24, phase_shift=0.06, amp_left=16)
    write("zombie", "walk", 1.7, 20, {
        "body": {"position": lambda p: [0.4 * math.sin(TAU * p),
                                        0.45 - 0.45 * math.cos(2 * TAU * p), 0]},
        "torso": {"rotation": lambda p: [20 + 2 * math.sin(2 * TAU * p),
                                         -5 * math.sin(TAU * p), 0]},
        "head": {"rotation": lambda p: [12, 6 * math.sin(TAU * p - 0.8),
                                        5 * math.sin(TAU * p)]},
        "right_arm": {"rotation": lambda p: [-72 + 5 * math.sin(TAU * p), 0, -12]},
        "left_arm": {"rotation": lambda p: [-69 + 5 * math.sin(TAU * p + 0.7), 0, 12]},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": left_leg},
    })


def zombie_run():
    right_leg, left_leg = legs(spread=LEG_SPREAD, swing_amp=40, phase_shift=0.05, amp_left=31)
    write("zombie", "run", 1.1, 18, {
        "body": {"position": lambda p: [0.5 * math.sin(TAU * p),
                                        0.75 - 0.75 * math.cos(2 * TAU * p), 0]},
        "torso": {"rotation": lambda p: [26 + 2.5 * math.sin(2 * TAU * p),
                                         -7 * math.sin(TAU * p), 0]},
        "head": {"rotation": lambda p: [14, 8 * math.sin(TAU * p - 0.8),
                                        6 * math.sin(TAU * p)]},
        "right_arm": {"rotation": lambda p: [-76 + 9 * math.sin(TAU * p), 0, -14]},
        "left_arm": {"rotation": lambda p: [-70 + 9 * math.sin(TAU * p + 0.7), 0, 14]},
        "right_leg": {"rotation": right_leg},
        "left_leg": {"rotation": left_leg},
    })


# --------------------------------------------------------------------------
# РОБОТ — чёткие фиксации, но переходы плавные (не телепорт)
# --------------------------------------------------------------------------

def robot_idle():
    write("robot", "idle", 3.0, 24, {
        "body": {"position": lambda p: [0, 0.15 * mech(p, 0.25), 0]},
        "torso": {"rotation": lambda p: [1, 2 * mech(p, 0.2), 0]},
        "head": {"rotation": lambda p: [0, 16 * mech(p, 0.18), 0]},
        "right_arm": {"rotation": lambda p: [2 * mech(p, 0.2), 0, -6 - 2 * mech(p, 0.2)]},
        "left_arm": {"rotation": lambda p: [-2 * mech(p, 0.2), 0, 6 + 2 * mech(p, 0.2)]},
        "right_leg": {"rotation": lambda p: [0, 0, LEG_SPREAD]},
        "left_leg": {"rotation": lambda p: [0, 0, -LEG_SPREAD]},
    })


def robot_walk():
    write("robot", "walk", 0.9, 20, {
        "body": {"position": lambda p: [0, 0.3 - 0.3 * math.cos(2 * TAU * p), 0]},
        "torso": {"rotation": lambda p: [5, -3 * mech(p, 0.3), 0]},
        "head": {"rotation": lambda p: [0, 3 * mech(p, 0.25), 0]},
        "right_arm": {"rotation": lambda p: [-42 * mech(p, 0.4), 0, -6]},
        "left_arm": {"rotation": lambda p: [42 * mech(p, 0.4), 0, 6]},
        "right_leg": {"rotation": lambda p: [42 * mech(p, 0.4), 0, LEG_SPREAD]},
        "left_leg": {"rotation": lambda p: [-42 * mech(p, 0.4), 0, -LEG_SPREAD]},
    })


def robot_run():
    write("robot", "run", 0.6, 18, {
        "body": {"position": lambda p: [0, 0.6 - 0.6 * math.cos(2 * TAU * p), 1]},
        "torso": {"rotation": lambda p: [11, -4 * mech(p, 0.35), 0]},
        "head": {"rotation": lambda p: [-4, 3 * mech(p, 0.3), 0]},
        "right_arm": {"rotation": lambda p: [-62 * mech(p, 0.45), 0, -7]},
        "left_arm": {"rotation": lambda p: [62 * mech(p, 0.45), 0, 7]},
        "right_leg": {"rotation": lambda p: [58 * mech(p, 0.45), 0, LEG_SPREAD]},
        "left_leg": {"rotation": lambda p: [-58 * mech(p, 0.45), 0, -LEG_SPREAD]},
    })


if __name__ == "__main__":
    for generator in (adventure_idle, adventure_walk, adventure_run,
                      ninja_idle, ninja_walk, ninja_run,
                      zombie_idle, zombie_walk, zombie_run,
                      robot_idle, robot_walk, robot_run):
        generator()
    print("\nГотово. Встроенные паки перегенерированы.")
