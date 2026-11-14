"""Gera e copia os assets pixel art da landing para site/assets/img (tudo em 1x).

- paisagem.png: paisagem 320x60 (céu em faixas, nuvens, serra, mata com copas,
  gramado com flores), mesmo método da paisagem do design; o CSS amplia 8x/4x.
- arara-pousada.png: arara v3 sem o galho (31x38), para pousar no botão.
- copia a folha do voo (6 quadros), a arara no galho e o piscar de arte/.
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


if __name__ == "__main__":
    IMG.mkdir(parents=True, exist_ok=True)
    TELAS.mkdir(parents=True, exist_ok=True)
    paisagem().save(IMG / "paisagem.png")
    # sem o galho (linhas 30-33), com a cauda colada ao corpo
    pousada = sprite(g.GRADE[:30] + g.GRADE[34:])
    assert pousada.size == (31, 38), pousada.size
    pousada.save(IMG / "arara-pousada.png")
    sprites = RAIZ / "arte" / "sprites"
    shutil.copy(sprites / "folha-voo.png", IMG / "folha-voo.png")  # 6 quadros 81x58 lado a lado
    shutil.copy(sprites / "piscar.png", IMG / "piscar.png")
    shutil.copy(RAIZ / "arte" / "arara-v3.png", IMG / "arara-galho.png")
    for nome in ["1-inicio", "2-historico", "3-dicionario", "5-popup"]:
        shutil.copy(RAIZ / "design" / "telas" / f"{nome}.png", TELAS / f"{nome}.png")
    print("ok:", sorted(p.name for p in IMG.iterdir()))
