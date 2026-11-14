"""Gera assets/icone-16.png (retocado à mão) e assets/arara.ico (16, 32, 48 e 256).

Uso: python instalador/icone.py <PNG do frame "Ícone .ico · 16" exportado do FigCraft>
O frame de 16 px é uma redução borrada; aqui cada caractere de G é um pixel desenhado à mão.
O fundo (gradiente e cantos arredondados) vem do frame exportado. 32, 48 e 256 vão como exportados.
"""
import sys
from pathlib import Path
from PIL import Image
G = """
................
................
....KKKKKKKK....
...KLLLGGLLLK...
..KGGGGGGGGGGK..
.KAWWWGGGGWWWAK.
.KWWKWGDDGWKWWK.
.KWWKWDDDDWKWWK.
.KAWWDDddDDWWAK.
.KGGGKDDDDKGGGK.
..KGGGKDdKGGGK..
..KYYOOKKOOYYK..
...KYYYOOYYYK...
....KKKKKKKK....
................
................
"""
C = dict(K=(5,5,5), L=(120,245,80), G=(88,200,40), A=(40,135,225), W=(245,243,228),
         D=(55,55,70), d=(30,30,38), Y=(250,212,35), O=(248,174,28))
base = Image.open(sys.argv[1]).convert('RGBA')
orig = base.copy()
for y, row in enumerate(G.strip('\n').split('\n')):
    assert len(row) == 16, (y, row)
    for x, ch in enumerate(row):
        a = base.getpixel((x, y))[3]
        bg = orig.getpixel((7 if y < 3 or y > 14 else 0, y))[:3]  # limpa restos da redução
        base.putpixel((x, y), (C[ch] if ch != '.' else bg) + (255 if ch != '.' else a,))
A = Path(__file__).parent / "assets"
base.save(A / "icone-16.png")
ims = [Image.open(A / f"icone-{n}.png").convert("RGBA") for n in (16, 32, 48)]
Image.open(A / "icone-256.png").convert("RGBA").save(
    A / "arara.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)], append_images=ims)
