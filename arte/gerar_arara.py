"""Gera a arara-canindé em pixel art (31x42), versão cartunizada (v3).

Grade amostrada da imagem de referência gerada no Gemini e limpa à mão.
Não é espelhada: a leve assimetria (olhos, bico) faz parte do charme.
Uso: python gerar_arara.py  ->  gera arara-v3.png (transparente) e arara-v3-preview.png
"""
from pathlib import Path
from PIL import Image

PALETA = {
    ".": None,                  # transparente
    "K": (5, 5, 5),             # contorno
    "W": (245, 243, 228),       # branco do rosto
    "w": (255, 255, 255),       # brilho do olho
    "g": (120, 245, 80),        # verde claro (brilho da testa)
    "G": (88, 200, 40),         # verde
    "d": (50, 150, 20),         # verde escuro
    "L": (85, 185, 240),        # azul claro
    "B": (40, 135, 225),        # azul
    "b": (25, 85, 165),         # azul escuro
    "Y": (250, 212, 35),        # amarelo
    "y": (245, 170, 20),        # amarelo escuro
    "O": (245, 135, 20),        # laranja
    "o": (205, 100, 10),        # laranja escuro
    "k": (55, 55, 70),          # bico
    "h": (90, 90, 115),         # brilho do bico / pés
    "F": (140, 140, 155),       # brilho claro
    "N": (150, 100, 55),        # galho
    "n": (115, 68, 50),         # galho escuro
}

GRADE = [
    "...............................",
    "............KKKKKKK............",
    ".........KKKgggggggKK..........",
    "........KgggGGGGGGGggKK........",
    ".......KgGGGGGGGGGGGGBLK.......",
    ".......KBBGGGGGGGGGBBBBLK......",
    "......KBWWWGGGGGGdWWWWBBK......",
    "......KWWKWWGGGGdWWKKWWBK......",
    "......KWKwKWKKKdWWKwKKWBBK.....",
    "......KWKKKKkkkKWWKKKKWBBK.....",
    "......KWKkKkFkKkKWKKkKWBBK.....",
    ".....KBWWKKFkkkkkWWKKWWBBK.....",
    ".....KGGWWKFkkkkkKWWWWGGBK.....",
    ".....KGGGGKhkkkkKKYGGGGGBK.....",
    ".....KBGGGKkkkKKkKYGGGGBbK.....",
    "......KBGGYKkKkkKOYGGGBbK......",
    "......KKBYYYKOKKOYYYYBbK.......",
    ".......KKOYYYOOOYYYyObKK.......",
    "......KLbKOyYYYYYYObbKBLK......",
    ".....KLBKyyYOOOOOOOOOBBBLK.....",
    "....KLBBKyYYYOOOYYYOKBBBBLK....",
    "....KLBKyYYYYYYYYYYOKBBBBLK....",
    "...KLBBKOYYYYYYYYYYOKbBBBBLK...",
    "...KLBbKOYYYYYYYYYYOKbBbBBLKK..",
    "..KLBBbKOyYYYYYYYYYOKbbBbBBLK..",
    "..KBBbbKOOyYYYYYYYyOOKbbbBBBK..",
    "...KbbbKoOOyYYYYYyOOOoKbbbbK...",
    "....KKK.KOOOOyyyyOOOOOoKKKK....",
    "........KKoOOOOOOOOOOoK........",
    ".........KKKoOOOOOoKKK.........",
    "KKKKKKKKKFhFKKKKKKKFhFKKKKKKKKK",
    "NNNNNNNNKhKhKNNNNNKhKFKNNNNNNNN",
    "nnnnnnnnnKnKnnnnnnnKnKnnnnnnnnn",
    "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK",
    "...........KbbbbbbbK...........",
    "...........KBbbbbbBK...........",
    "...........KBbBBBbBK...........",
    "...........KBKLBBKBK...........",
    "...........KBKLBBKBK...........",
    "............KKLBBKK............",
    ".............KBLBK.............",
    "..............KBK..............",
]

LARGURA, ALTURA = len(GRADE[0]), len(GRADE)
assert all(len(l) == LARGURA for l in GRADE), "linhas com larguras diferentes"
assert all(c in PALETA for l in GRADE for c in l), "cor fora da paleta"


def desenhar(escala: int, fundo=None) -> Image.Image:
    img = Image.new("RGBA", (LARGURA, ALTURA), fundo or (0, 0, 0, 0))
    for y, linha in enumerate(GRADE):
        for x, c in enumerate(linha):
            if PALETA[c]:
                img.putpixel((x, y), PALETA[c] + (255,))
    # NEAREST mantém os pixels nítidos ao ampliar
    return img.resize((LARGURA * escala, ALTURA * escala), Image.NEAREST)


if __name__ == "__main__":
    pasta = Path(__file__).parent
    desenhar(1).save(pasta / "arara-v3.png")
    desenhar(10, (22, 22, 34, 255)).save(pasta / "arara-v3-preview.png")
    print("ok")
