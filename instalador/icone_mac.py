"""Gera assets/arara.icns (macOS) a partir de assets/icone-256.png, sem borrar os pixels.

Uso: python instalador/icone_mac.py
O ícone de 256 é ampliado 3x (escala inteira, vizinho mais próximo) para 768 px e centralizado numa tela de 1024,
com a margem que os ícones do macOS têm (a grade da Apple usa ~824 de 1024 para o corpo do ícone).
"""
from pathlib import Path
from PIL import Image

A = Path(__file__).parent / "assets"
art = Image.open(A / "icone-256.png").convert("RGBA").resize((768, 768), Image.NEAREST)
canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
canvas.paste(art, (128, 128))
canvas.save(A / "icone-1024.png")
canvas.save(A / "arara.icns")  # o Pillow gera os tamanhos de 16 a 1024 a partir deste
