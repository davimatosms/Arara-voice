"""Sprites da arara por espécie (temas do app), derivados da grade 31x42 de gerar_arara.py.

Cada espécie = troca de paleta por região (cabeça, rosto, bico de cima/baixo, peito, asa fechada, cauda)
+ alguns pixels próprios (anel amarelo no olho, pele do rosto, faixa da asa).
Uso: python arte/gerar_especies.py  ->  app/src/main/resources/br/arara/app/araras/<id>/
     {pousada, fala_1, fala_2, piscar, voo_0..5}.png (1x, transparente) e arte/especies/folha.png.
Autoteste: a canindé sai pixel a pixel igual a arte/sprites (gerar_sprites.py).
"""
from pathlib import Path
from PIL import Image

import gerar_arara as g
import gerar_sprites as gs

AQUI = Path(__file__).parent
SAIDA = AQUI.parent / "app/src/main/resources/br/arara/app/araras"

H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
OLHOS = [(range(7, 12), range(6, 12)), (range(17, 23), range(6, 12))]  # (cols, linhas) de cada olho
BICO = set("khF")


def no_olho(x, y): return any(x in cx and y in cy for cx, cy in OLHOS)
def bico_cima(x, y, c): return c in BICO and 9 <= x <= 18 and 8 <= y <= 12
def bico_baixo(x, y, c): return c in BICO and 9 <= x <= 18 and 13 <= y <= 19


def perto_bico_baixo(grade, x, y, d=2):
    return any(bico_baixo(i, j, grade[j][i]) for j in range(max(0, y - d), min(len(grade), y + d + 1))
               for i in range(max(0, x - d), min(31, x + d + 1)))


# ---------------------------------------------------------------- espécies
# Cada uma: nome, científico, paleta (char -> hex) e regra(x, y, c, grade) -> char.
# Chars novos usam letras livres; chars antigos não citados mantêm a cor da canindé.
ESPECIES = {}


def especie(id_, nome, cientifico, cores, asa):
    def deco(fn):
        pal = dict(g.PALETA, r=(205, 55, 60))
        pal.update({k: H(v) for k, v in cores.items()})
        ESPECIES[id_] = dict(nome=nome, cientifico=cientifico, pal=pal, regra=fn, asa=asa)
        return fn
    return deco


@especie("caninde", "Arara-canindé", "Ara ararauna", {}, {})
def _caninde(x, y, c, gr): return c


@especie("azul", "Arara-azul-grande", "Anodorhynchus hyacinthinus",
         {"1": "#6A8CEB", "2": "#3B57C4", "3": "#283C96", "4": "#1C2A6E",       # cobalto
          "5": "#4A6AD2", "6": "#3A57B8", "7": "#2E48A0", "8": "#233884",       # peito (um tom acima)
          "Y": "#FFD43B", "y": "#F2B11E", "k": "#15151C", "h": "#30303B", "F": "#555564",
          "9": "#1A2566", "0": "#121A4A"},
         {"Y": "2", "y": "3", "O": "9", "o": "0", "L": "1", "B": "5", "b": "3"})
def _azul(x, y, c, gr):
    if c == "W": return "Y" if no_olho(x, y) else "2"           # anel amarelo no olho, resto do rosto azul
    if c in "YyOo" or (c in "GgdBLb" and 11 <= y <= 18):
        if c not in "Ll" and perto_bico_baixo(gr, x, y) and y >= 12: return "Y"   # faixa amarela na base do bico
    if c in "YyOo": return {"Y": "5", "y": "6", "O": "7", "o": "8"}[c]
    if c in "gGdLBb" and y < 34: return {"g": "1", "G": "2", "d": "3", "L": "1", "B": "2", "b": "3"}[c]
    if y >= 34 and c in "LBb": return {"L": "1", "B": "2", "b": "3"}[c]
    return c


@especie("vermelha", "Arara-vermelha", "Ara chloropterus",
         {"1": "#F45B4D", "2": "#DA3428", "3": "#A8221E", "4": "#7A1616",       # vermelho
          "5": "#8ED65F", "6": "#41A543", "7": "#2B7A34",                       # faixa verde da asa
          "8": "#62A2F2", "9": "#2D6BD0", "0": "#1D4A9A", "-": "#14336E",       # azul das penas de voo
          "W": "#F8EEE8", "q": "#E0574D",                                       # rosto claro com fios vermelhos
          "i": "#EFE5CF", "j": "#C8B78F", "F": "#FFFFFF", "k": "#24242C", "h": "#45454F"},
         {"Y": "9", "y": "0", "O": "0", "o": "-", "L": "1", "B": "6", "b": "7"})
def _vermelha(x, y, c, gr):
    if bico_cima(x, y, c): return {"k": "i", "h": "j", "F": "F"}[c]          # bico de cima cor de marfim
    if c == "W" and (x, y) in {(8, 12), (19, 12), (21, 12), (7, 11), (22, 11)}: return "q"
    if c in "YyOo": return {"Y": "2", "y": "3", "O": "3", "o": "4"}[c]
    if c in "gGd" or (c in "LBb" and y <= 16): return {"g": "1", "G": "2", "d": "3", "L": "1", "B": "2", "b": "3"}[c]
    if c in "LBb" and 17 <= y <= 21: return {"L": "5", "B": "6", "b": "7"}[c]
    if c in "LBb" and 22 <= y < 34: return {"L": "8", "B": "9", "b": "0"}[c]
    if c in "LBb" and 34 <= y <= 37: return {"L": "1", "B": "2", "b": "3"}[c]  # cauda vermelha, ponta azul
    if c in "LBb" and y >= 38: return {"L": "8", "B": "9", "b": "0"}[c]
    return c


@especie("lear", "Arara-azul-de-lear", "Anodorhynchus leari",
         {"1": "#93B4CF", "2": "#6C93B6", "3": "#4D7398",                       # cabeça azul-acinzentada
          "5": "#6A90D0", "6": "#3E66B2", "7": "#2B4D90", "8": "#1F3B74",       # corpo cobalto fosco
          "Y": "#F6DF83", "y": "#E6C25A", "k": "#15151C", "h": "#30303B", "F": "#555564",
          "9": "#2C4F94", "0": "#18305E"},
         {"Y": "6", "y": "9", "O": "7", "o": "0", "L": "1", "B": "5", "b": "7"})
def _lear(x, y, c, gr):
    if c == "W": return "Y" if no_olho(x, y) or y >= 11 else "2"             # anel + pele amarela grande no rosto
    if c in "YyOoGgdBb" and 11 <= y <= 18 and perto_bico_baixo(gr, x, y): return "Y"
    if c in "YyOo": return {"Y": "6", "y": "7", "O": "7", "o": "8"}[c]
    if c in "gGd" or (c in "LBb" and y <= 16): return {"g": "1", "G": "2", "d": "3", "L": "1", "B": "2", "b": "3"}[c]
    if c in "LBb": return {"L": "5", "B": "6", "b": "7"}[c]
    return c


@especie("maracana", "Maracanã", "Primolius maracana",
         {"1": "#E8402F", "2": "#57BD4C", "3": "#2F8A35", "4": "#1F6326",      # testa vermelha, corpo verde
          "5": "#77CDE8", "6": "#3A8FC0",                                      # nuca azulada
          "7": "#E3402F", "8": "#B42B20",                                      # mancha vermelha da barriga
          "9": "#3E8FD0", "0": "#225C93", "-": "#18456F", "+": "#7CCF5C",
          "k": "#15151C", "h": "#30303B", "F": "#555564"},
         {"Y": "9", "y": "0", "O": "0", "o": "-", "L": "+", "B": "2", "b": "3"})
def _maracana(x, y, c, gr):
    if c == "g" or (c == "G" and y <= 3): return "1"                          # faixa vermelha na testa
    if c in "Gd": return {"G": "2", "d": "3"}[c]
    if c in "LBb" and y <= 16: return {"L": "5", "B": "6", "b": "6"}[c]
    if c in "YyOo":
        if 22 <= y <= 28 and c in "Yy": return "7" if c == "Y" else "8"
        return {"Y": "2", "y": "3", "O": "3", "o": "4"}[c]
    if c in "LBb" and 17 <= y <= 22: return {"L": "+", "B": "2", "b": "3"}[c]
    if c in "LBb" and 23 <= y < 34: return {"L": "5", "B": "9", "b": "0"}[c]
    if c in "LBb" and y >= 34: return {"L": "+", "B": "2", "b": "3"}[c] if y <= 37 else {"L": "5", "B": "9", "b": "0"}[c]
    return c


# ---------------------------------------------------------------- montagem
# Pálpebra do "piscar": nas azuis o branco do olho vira o anel amarelo, então o olho fechado usa o azul da cabeça.
PALPEBRA = {"azul": "2", "lear": "2"}


def recolor(grade, esp):
    regra = ESPECIES[esp]["regra"]
    return [[PALPEBRA.get(esp, "W") if c == "e" else regra(x, y, c, grade) for x, c in enumerate(l)]
            for y, l in enumerate(grade)]


def piscar():
    """gerar_sprites.piscar, mas a pupila fechada vira "e" (pálpebra) em vez de "W"."""
    aberto, fechado = g.GRADE, gs.piscar()
    return [["e" if c == "W" and aberto[y][x] != "W" else c for x, c in enumerate(l)] for y, l in enumerate(fechado)]


def png(grade, pal):
    img = Image.new("RGBA", (len(grade[0]), len(grade)), (0, 0, 0, 0))
    for y, l in enumerate(grade):
        for x, c in enumerate(l):
            if pal.get(c):
                img.putpixel((x, y), pal[c] + (255,))
    return img


def corpo_voo(esp):
    """Igual a gerar_sprites.corpo_voo, mas copia as cores já trocadas (máscara vem da grade original)."""
    orig = gs.corpo_voo()
    cor = recolor([list(l) for l in g.GRADE], esp)
    cor, fonte = cor[:30] + cor[34:], g.GRADE[:30] + g.GRADE[34:]
    # onde corpo_voo trocou o pixel (contorno novo do torso), vale o dele
    grade = [[c if c in ".K" and c != fonte[y][x] else cor[y][x] if c != "." else "." for x, c in enumerate(l)]
             for y, l in enumerate(orig)]
    for y in range(17, 30):  # mesmo espelho de gerar_sprites.corpo_voo, na grade já recolorida
        for c in range(16, 31):
            grade[y][c] = grade[y][30 - c]
    return grade


def quadro_voo(esp, angulo, bob):
    W, Hh = 81, 58
    tela = [["."] * W for _ in range(Hh)]
    cx, topo = W // 2 - 15, 11 + bob
    ombro_y = topo + 18
    mapa = ESPECIES[esp]["asa"]
    for (x, y), c in gs.asa(angulo).items():
        c = mapa.get(c, c)
        for X, Y in ((cx + 23 + x, ombro_y + y), (cx + 7 - x, ombro_y + y)):
            if 0 <= X < W and 0 <= Y < Hh:
                tela[Y][X] = c
    for y, l in enumerate(corpo_voo(esp)):
        for x, c in enumerate(l):
            if c != "." and 0 <= topo + y < Hh:
                tela[topo + y][cx + x] = c
    return tela


CICLO = dict(enumerate([(-55, 1), (-25, 0), (5, -1), (35, -1), (10, 0), (-30, 1)]))


def sprites(esp):
    """Tudo o que o app anima: pousada (= fala_0), fala_1, fala_2, piscar e voo_0..5."""
    pal = ESPECIES[esp]["pal"]
    out = {"pousada": png(recolor([list(l) for l in g.GRADE], esp), pal)}
    for i in (1, 2):
        out[f"fala_{i}"] = png(recolor(gs.bico(i), esp), pal)
    out["piscar"] = png(recolor(piscar(), esp), pal)
    for i in range(6):
        out[f"voo_{i}"] = png(quadro_voo(esp, *CICLO[i]), pal)
    return out


if __name__ == "__main__":
    # autoteste: a canindé tem que sair idêntica aos sprites atuais (arte/sprites, de gerar_sprites.py)
    atual = {"pousada": "fala_0"}
    for nome, im in sprites("caninde").items():
        ref = Image.open(AQUI / "sprites" / f"{atual.get(nome, nome)}.png").convert("RGBA")
        assert im.tobytes() == ref.tobytes(), f"canindé mudou: {nome}"
    k = 4
    folha = Image.new("RGBA", ((31 + 3) * 4 * k + (81 + 3) * 6 * k, (58 + 6) * k * len(ESPECIES)), (22, 22, 34, 255))
    for row, esp in enumerate(ESPECIES):
        pasta = SAIDA / esp
        pasta.mkdir(parents=True, exist_ok=True)
        x = 0
        for nome, im in sprites(esp).items():
            im.save(pasta / f"{nome}.png")
            folha.alpha_composite(im.resize((im.width * k, im.height * k), Image.NEAREST), (x, row * 64 * k + (58 - im.height) * k))
            x += (im.width + 3) * k
    (AQUI / "especies").mkdir(exist_ok=True)
    folha.save(AQUI / "especies" / "folha.png")
    print("ok", list(ESPECIES))
