"""Sprites animados da arara, derivados da grade de gerar_arara.py.

- voo_0..5: ciclo de bater de asas (corpo sem galho, asas abertas em ângulos diferentes).
- fala_0..2: bico fechado, entreaberto e aberto (pop-up reage à voz).
- piscar: olhos fechados.
Uso: python gerar_sprites.py  ->  arte/sprites/*.png (1x, transparente), folha-voo.png, voo.gif e previa.png
"""
import math
from pathlib import Path
from PIL import Image
import gerar_arara as g

PAL = dict(g.PALETA, r=(205, 55, 60))  # língua
PASTA = Path(__file__).parent / "sprites"


TORSO = set("YyOo")


def corpo_voo():
    """Corpo sem galho e sem pés, cauda colada embaixo e SEM as asas fechadas (no voo elas estão abertas)."""
    grade = [list(l) for l in g.GRADE[:30] + g.GRADE[34:]]
    for y in range(17, 30):
        linha = grade[y]
        cols = [x for x, c in enumerate(linha) if c in TORSO]
        if not cols:
            continue
        ini, fim = cols[0] - 1, cols[-1] + 1
        for x in range(len(linha)):
            if x < ini or x > fim:
                linha[x] = "."
        linha[ini] = linha[fim] = "K"
    return grade


def _risco(px, x0, y0, ang, comp, larg, cor, borda):
    """Uma pena: risco de `comp` px na direção `ang` (graus), com fio claro em cima e borda escura embaixo."""
    a = math.radians(ang)
    ca, sa = math.cos(a), math.sin(a)
    pena = {}
    for t10 in range(0, int(comp * 10) + 1):
        t = t10 / 10
        meia = larg * (1 - 0.55 * (t / comp) ** 2)  # afina na ponta
        for s10 in range(int(-meia * 10), int(meia * 10) + 1):
            s = s10 / 10
            pena[(round(x0 + t * ca - s * sa), round(y0 + t * sa + s * ca))] = borda if s > meia - 0.7 else cor
    for (x, y) in pena:  # fio laranja-escuro separa uma pena da outra; o preto fica só na silhueta
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in pena:
                px[(x + dx, y + dy)] = "o"
    px.update(pena)


def asa(angulo):
    """Asa direita vista de baixo, origem no ombro. Ângulo do braço em graus (negativo = para cima).

    Penas de voo douradas em leque (secundárias saem ao longo do braço, primárias saem da ponta e são as mais
    longas), por cima as coberteiras azuis em escama junto ao braço, como na arara-canindé de verdade.
    """
    a = math.radians(angulo)
    ca, sa = math.cos(a), math.sin(a)
    braco = 11
    px = {}
    # penas de voo, de dentro para fora (as de fora ficam por cima)
    for i in range(6):  # secundárias: perpendiculares ao braço, abrindo de leve para fora
        t = 1.5 + i * 1.6
        _risco(px, t * ca, t * sa, angulo + 100 - i * 7, 7 + i * 0.7, 1.2, "Y", "y")
    for i in range(5):  # primárias: da ponta, em leque do "para baixo" ao "para fora"
        _risco(px, braco * ca, braco * sa, angulo + 60 - i * 15, 11 + i * 1.2, 1.1, "Y" if i % 2 else "y", "O")
    # coberteiras azuis: duas fileiras de escamas sobre o braço
    for fila, (dist, cor) in enumerate(((0.6, "L"), (2.0, "B"), (3.4, "B"))):
        for j in range(5 - fila):
            t = 1 + j * 2.2
            cx = t * ca - dist * -sa
            cy = t * sa + dist * ca
            esc = {}
            for dx in range(-1, 2):
                for dy in range(-1, 2):
                    if abs(dx) + abs(dy) <= 2:
                        esc[(round(cx + dx), round(cy + dy))] = cor
            for (x, y) in esc:
                for ddx, ddy in ((0, 1), (1, 0), (-1, 0)):
                    if (x + ddx, y + ddy) not in esc and fila == 2:
                        px.setdefault((x + ddx, y + ddy), "b")
            px.update(esc)
    for t10 in range(0, braco * 10 + 1):  # borda de ataque azul clara com contorno
        t = t10 / 10
        x, y = round(t * ca + sa * 0.6), round(t * sa - ca * 0.6)
        px[(x, y)] = "L"
    return contorno(px)


def contorno(px):
    """Silhueta preta em volta da asa toda."""
    out = dict(px)
    for (x, y) in px:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in px:
                out.setdefault((x + dx, y + dy), "K")
    return out


def quadro_voo(angulo, bob):
    """Monta um quadro 81x58: asas atrás do corpo, corpo deslocado `bob` px na vertical."""
    W, H = 81, 58
    tela = [["."] * W for _ in range(H)]
    cx, topo = W // 2 - 15, 11 + bob
    ombro_y = topo + 18
    direita = asa(angulo)
    for (x, y), c in direita.items():
        for X, Y in ((cx + 23 + x, ombro_y + y), (cx + 7 - x, ombro_y + y)):  # direita e espelho
            if 0 <= X < W and 0 <= Y < H:
                tela[Y][X] = c
    for y, linha in enumerate(corpo_voo()):
        for x, c in enumerate(linha):
            if c != "." and 0 <= topo + y < H:
                tela[topo + y][cx + x] = c
    return tela


def bico(abertura):
    """Grade pousada com o bico inferior (linhas 13-16, colunas 10-17) descido `abertura` px."""
    grade = [list(l) for l in g.GRADE]
    if abertura:
        orig = [row[:] for row in grade]
        for y in range(16, 12, -1):
            for x in range(10, 18):
                if orig[y][x] in "Kkh":
                    grade[y + abertura][x] = orig[y][x]
        for y in range(13, 13 + abertura):
            for x in range(11, 17):
                grade[y][x] = "r" if 12 <= x <= 15 and y > 13 else "K"
    return grade


def piscar():
    """Olhos fechados: pupilas viram branco e uma linha escura no meio vira a pálpebra."""
    grade = [list(l) for l in g.GRADE]
    olhos = list(range(8, 11)) + list(range(18, 22))
    for y in range(7, 11):
        for x in olhos:
            if grade[y][x] in "Kwk":
                grade[y][x] = "W"
    for x in olhos:
        grade[9][x] = "K"
    return grade


def png(grade):
    img = Image.new("RGBA", (len(grade[0]), len(grade)), (0, 0, 0, 0))
    for y, l in enumerate(grade):
        for x, c in enumerate(l):
            if PAL[c]:
                img.putpixel((x, y), PAL[c] + (255,))
    return img


if __name__ == "__main__":
    PASTA.mkdir(exist_ok=True)
    # ciclo: asa no alto, descendo, embaixo, subindo. O corpo sobe 1 px na batida para baixo.
    ciclo = [(-55, 1), (-25, 0), (5, -1), (35, -1), (10, 0), (-30, 1)]
    voo = [png(quadro_voo(a, b)) for a, b in ciclo]
    for i, im in enumerate(voo):
        im.save(PASTA / f"voo_{i}.png")
    folha = Image.new("RGBA", (voo[0].width * len(voo), voo[0].height))
    for i, im in enumerate(voo):
        folha.paste(im, (i * im.width, 0))
    folha.save(PASTA / "folha-voo.png")
    escala = 6
    grande = [im.resize((im.width * escala, im.height * escala), Image.NEAREST) for im in voo]
    fundo = [Image.alpha_composite(Image.new("RGBA", f.size, (22, 34, 27, 255)), f) for f in grande]
    fundo[0].save(PASTA / "voo.gif", save_all=True, append_images=fundo[1:], duration=90, loop=0, disposal=2)
    fala = [png(bico(a)) for a in (0, 1, 2)]
    for i, im in enumerate(fala):
        im.save(PASTA / f"fala_{i}.png")
    png(piscar()).save(PASTA / "piscar.png")
    # prévia: folha de voo em cima, fala + piscar embaixo
    todos = voo + fala + [png(piscar())]
    prev = Image.new("RGBA", (81 * 6 * escala, 58 * 2 * escala), (22, 34, 27, 255))
    for i, im in enumerate(voo):
        prev.alpha_composite(im.resize((im.width * escala, im.height * escala), Image.NEAREST), (i * 81 * escala, 0))
    for i, im in enumerate(fala + [png(piscar())]):
        prev.alpha_composite(im.resize((im.width * escala, im.height * escala), Image.NEAREST), (i * 50 * escala, 58 * escala))
    prev.save(PASTA / "previa.png")
    print("ok")
