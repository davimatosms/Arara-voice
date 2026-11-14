"""Gera e copia os assets pixel art da landing v2 para site/assets/img (tudo em 1x).

- atriz.png: folha da arara atriz, 7 quadros 81x58 lado a lado: os 6 do voo
  (arte/sprites/folha-voo.png) e a arara pousada (31x38) no quadro 7, centrada e
  apoiada na base. O CSS amplia em escala inteira (2x a 5x).
- morros.png: serra, mata e gramado da paisagem 320x60, sem o céu (320x34).
- nuvem.png: nuvem pixel 76x24 com sombra embaixo e contorno azul claro.
- arara-pousada.png: arara v3 sem o galho (31x38), usada como favicon.
- copia as telas reais de design/telas para site/assets/telas.

Uso (na raiz do repo): python site/tools/gerar_assets.py
"""
import math
import random
import shutil
import sys
from pathlib import Path

from PIL import Image

RAIZ = Path(__file__).resolve().parents[2]
IMG = RAIZ / "site" / "assets" / "img"
TELAS = RAIZ / "site" / "assets" / "telas"
sys.path.insert(0, str(RAIZ / "arte"))
import gerar_arara as g  # noqa: E402


def sprite(rows):
    img = Image.new("RGBA", (len(rows[0]), len(rows)), (0, 0, 0, 0))
    for y, linha in enumerate(rows):
        for x, c in enumerate(linha):
            if g.PALETA[c]:
                img.putpixel((x, y), g.PALETA[c] + (255,))
    return img


def paisagem(W=320, H=60):
    random.seed(7)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = im.putpixel
    ceu = [(214, 240, 255), (196, 232, 255), (176, 222, 252), (156, 210, 248)]
    for y in range(H):
        for x in range(W):
            px((x, y), ceu[min(3, y // 9)] + (255,))

    def nuvem(cx, cy, w):
        for dx in range(-w, w + 1):
            h = 2 if abs(dx) > w * 0.6 else 3 if abs(dx) > w * 0.3 else 4
            for dy in range(h):
                px((cx + dx, cy - dy), (255, 255, 255, 255))
            px((cx + dx, cy + 1), (226, 240, 250, 255))

    for cx, cy, w in [(14, 9, 6), (62, 12, 9), (110, 7, 6), (168, 14, 11), (214, 8, 6), (262, 12, 9), (304, 7, 6)]:
        nuvem(cx, cy, w)

    def morro(base, amp, freq, fase, cor, topo=None):
        for x in range(W):
            h = int(base + amp * math.sin(x * freq + fase) + amp * 0.5 * math.sin(x * freq * 2.3 + fase))
            for y in range(h, H):
                px((x, y), cor + (255,))
            if topo:
                px((x, h), topo + (255,))

    morro(34, 3, 0.05, 1, (120, 190, 140), (150, 210, 160))   # serra ao fundo
    morro(40, 3, 0.07, 3, (60, 150, 40), (88, 200, 40))       # mata
    for x in range(4, W, 9):                                  # copas
        tx, topo = min(W - 1, max(0, x + random.randint(-2, 2))), 37 + random.randint(-3, 2)
        for y in range(topo, 44):
            px((tx, y), (115, 68, 50, 255))
        for dx in range(-3, 4):
            for dy in range(-2, 2):
                if abs(dx) + abs(dy) < 4 and 0 <= tx + dx < W:
                    px((tx + dx, topo + dy), ((50, 150, 20) if dy > -1 else (88, 200, 40)) + (255,))
    morro(48, 2, 0.09, 5, (88, 200, 40), (155, 227, 106))     # gramado
    for _ in range(W // 3):                                   # flores
        x, y = random.randint(0, W - 1), random.randint(50, H - 1)
        px((x, y), random.choice([(255, 201, 60), (245, 135, 20), (155, 227, 106)]) + (255,))
    return im


def nuvem(W=76, H=24, blobs=((14, 15, 9), (28, 11, 11), (44, 9, 12), (58, 13, 10), (66, 16, 7), (22, 17, 7), (50, 17, 8))):
    m = [[any((x - cx) ** 2 + (y - cy) ** 2 <= r * r for cx, cy, r in blobs) and y < H - 2 for x in range(W)] for y in range(H)]
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for y in range(H):
        for x in range(W):
            if not m[y][x]:
                viz = any(0 <= y + dy < H and 0 <= x + dx < W and m[y + dy][x + dx] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if viz:
                    im.putpixel((x, y), (150, 200, 240, 255))
                continue
            baixo = y + 3 < H and not m[y + 3][x]
            im.putpixel((x, y), ((214, 236, 255) if baixo else (255, 255, 255)) + (255,))
    return im


def morros(pa):
    """Recorta a faixa de baixo da paisagem e apaga os pixels de céu e nuvem."""
    ceu = {(214, 240, 255), (196, 232, 255), (176, 222, 252), (156, 210, 248), (255, 255, 255), (226, 240, 250)}
    m = pa.crop((0, 26, 320, 60))
    for y in range(m.height):
        for x in range(m.width):
            if m.getpixel((x, y))[:3] in ceu:
                m.putpixel((x, y), (0, 0, 0, 0))
    return m


if __name__ == "__main__":
    IMG.mkdir(parents=True, exist_ok=True)
    TELAS.mkdir(parents=True, exist_ok=True)
    morros(paisagem()).save(IMG / "morros.png")
    nuvem().save(IMG / "nuvem.png")
    # sem o galho (linhas 30-33), com a cauda colada ao corpo
    pousada = sprite(g.GRADE[:30] + g.GRADE[34:])
    assert pousada.size == (31, 38), pousada.size
    pousada.save(IMG / "arara-pousada.png")
    voo = Image.open(RAIZ / "arte" / "sprites" / "folha-voo.png").convert("RGBA")  # 6 quadros 81x58
    assert voo.size == (486, 58), voo.size
    atriz = Image.new("RGBA", (81 * 7, 58), (0, 0, 0, 0))
    atriz.paste(voo, (0, 0))
    atriz.paste(pousada, (81 * 6 + (81 - 31) // 2, 58 - 38), pousada)
    atriz.save(IMG / "atriz.png")
    for nome in ["1-inicio", "2-historico", "3-dicionario", "5-popup"]:
        shutil.copy(RAIZ / "design" / "telas" / f"{nome}.png", TELAS / f"{nome}.png")
    print("ok:", sorted(p.name for p in IMG.iterdir()))
