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


def corpo_voo():
    """Corpo sem galho e sem pés, com a cauda colada embaixo."""
    return [list(l) for l in g.GRADE[:30] + g.GRADE[34:]]


def asa(angulo):
    """Asa direita como conjunto de pixels {(x, y): cor}, com origem no ombro. Ângulo em graus, negativo = para cima."""
    a = math.radians(angulo)
    ca, sa = math.cos(a), math.sin(a)
    px = {}
    comp = 17
    for t10 in range(0, comp * 10 + 1):
        t = t10 / 10
        # espessura: larga perto do corpo, afina; penas primárias "dedos" na ponta
        meia = 3.6 - 1.6 * (t / comp)
        for s10 in range(int(-meia * 10), int(meia * 10) + 1):
            s = s10 / 10
            if t > comp - 4 and s > 0 and int(t) % 2 == 0:  # recorte entre as penas da ponta
                continue
            x, y = t * ca - s * sa, t * sa + s * ca
            cor = "L" if s < -meia + 1.2 else ("b" if s > meia - 1.6 or t > comp - 4 else "B")
            px[(round(x), round(y))] = cor
    return px


def contorno(px):
    out = dict(px)
    for (x, y) in px:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in px:
                out.setdefault((x + dx, y + dy), "K")
    return out


def quadro_voo(angulo, bob):
    """Monta um quadro 71x52: asas atrás do corpo, corpo deslocado `bob` px na vertical."""
    W, H = 71, 52
    tela = [["."] * W for _ in range(H)]
    cx, topo = W // 2 - 15, 8 + bob
    ombro_y = topo + 19
    direita = contorno(asa(angulo))
    for (x, y), c in direita.items():
        for X, Y in ((cx + 24 + x, ombro_y + y), (cx + 6 - x, ombro_y + y)):  # direita e espelho
            if 0 <= X < W and 0 <= Y < H and (tela[Y][X] == "." or c != "K"):
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
    prev = Image.new("RGBA", (71 * 6 * escala // 1, 52 * 2 * escala), (22, 34, 27, 255))
    for i, im in enumerate(voo):
        prev.alpha_composite(im.resize((im.width * escala, im.height * escala), Image.NEAREST), (i * 71 * escala, 0))
    for i, im in enumerate(fala + [png(piscar())]):
        prev.alpha_composite(im.resize((im.width * escala, im.height * escala), Image.NEAREST), (i * 50 * escala, 52 * escala))
    prev.save(PASTA / "previa.png")
    print("ok")
