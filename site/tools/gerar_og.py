"""Gera a imagem de prévia (Open Graph / Twitter Card) 1200x630 em site/assets/og/arara-og.png.

Base: a imagem de lançamento (telas reais do app: janela Início e o pop-up com o texto ao vivo),
o céu azul do hero da landing e as fontes do app (Anton e Nunito Sans).

Uso (na raiz do repo): python site/tools/gerar_og.py [caminho/da/post-lancamento.png]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAIZ = Path(__file__).resolve().parents[2]
FONTES = RAIZ / "app" / "src" / "main" / "resources" / "br" / "arara" / "app" / "fonts"
SAIDA = RAIZ / "site" / "assets" / "og" / "arara-og.png"
BASE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "OneDrive/Desktop/Arara-LinkedIn/post-lancamento-16-11.png"
L, A = 1200, 630
LIMA, BRANCO, ESCURO = (200, 245, 106), (255, 255, 255), (11, 20, 13)


def fonte(nome, tam):
    return ImageFont.truetype(str(FONTES / nome), tam)


def ceu():
    # mesmas cores do topo do gradiente do .hero (styles.css), só a parte azul para o texto branco ler bem
    paradas = [(0, (21, 87, 184)), (0.55, (28, 114, 207)), (1, (62, 155, 230))]
    img = Image.new("RGB", (L, A))
    px = img.load()
    for y in range(A):
        t = y / (A - 1)
        for (t0, c0), (t1, c1) in zip(paradas, paradas[1:]):
            if t0 <= t <= t1:
                k = (t - t0) / (t1 - t0)
                cor = tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
                break
        for x in range(L):
            px[x, y] = cor
    return img


def recorte(base, caixa, raio, largura):
    pedaco = base.crop(caixa)
    alt = round(pedaco.height * largura / pedaco.width)
    pedaco = pedaco.resize((largura, alt), Image.LANCZOS).convert("RGBA")
    mascara = Image.new("L", pedaco.size, 0)
    ImageDraw.Draw(mascara).rounded_rectangle((0, 0, largura - 1, alt - 1), raio, fill=255)
    pedaco.putalpha(mascara)
    return pedaco


def com_sombra(img, peca, xy):
    sombra = Image.new("RGBA", img.size, (0, 0, 0, 0))
    m = Image.new("L", peca.size, 0)
    m.paste(peca.getchannel("A"))
    sombra.paste((0, 0, 0, 110), (xy[0], xy[1] + 14), m)
    img.alpha_composite(sombra.filter(ImageFilter.GaussianBlur(18)))
    img.alpha_composite(peca, xy)


def main():
    base = Image.open(BASE).convert("RGB")
    img = ceu().convert("RGBA")
    d = ImageDraw.Draw(img)

    # selo
    selo = "GRÁTIS · OPEN SOURCE · WINDOWS E MAC"
    f = fonte("NunitoSans-ExtraBold.ttf", 22)
    w = d.textlength(selo, font=f)
    d.rounded_rectangle((64, 56, 64 + w + 40, 98), 21, fill=(11, 20, 13, 150))
    d.text((84, 62), selo, font=f, fill=LIMA)

    # título como no hero
    d.text((60, 108), "FALE.", font=fonte("Anton-Regular.ttf", 150), fill=BRANCO)
    d.text((60, 286), "NÃO DIGITE.", font=fonte("Anton-Regular.ttf", 112), fill=LIMA)

    f = fonte("NunitoSans-Bold.ttf", 30)
    d.text((64, 448), "Ditado por voz em português,", font=f, fill=BRANCO)
    d.text((64, 486), "100% no seu computador.", font=f, fill=BRANCO)

    # atalho
    x = 64
    fk = fonte("NunitoSans-ExtraBold.ttf", 22)
    for i, tecla in enumerate(["Ctrl", "Shift", "Espaço"]):
        tw = d.textlength(tecla, font=fk)
        d.rounded_rectangle((x, 552, x + tw + 28, 592), 10, fill=ESCURO, outline=(255, 255, 255, 60))
        d.text((x + 14, 558), tecla, font=fk, fill=BRANCO)
        x += tw + 28
        if i < 2:
            d.text((x + 8, 556), "+", font=fk, fill=BRANCO)
            x += 30

    # telas reais: janela Início e, por cima, o pop-up com o texto ao vivo, na mesma escala e no mesmo
    # lugar da imagem de lançamento (assim ele cobre o pedaço do pop-up que vem junto no recorte da janela)
    k, jx, jy = 520 / 900, 616, 130
    com_sombra(img, recorte(base, (72, 408, 972, 997), 14, 520), (jx, jy))
    popup = recorte(base, (283, 848, 1031, 1071), 26, round(748 * k))
    com_sombra(img, popup, (jx + round((283 - 72) * k), jy + round((848 - 408) * k)))

    SAIDA.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(SAIDA, optimize=True)
    print(SAIDA, img.size)


if __name__ == "__main__":
    main()
