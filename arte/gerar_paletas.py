"""Gera prévias das paletas candidatas do Arara (PNG) para validação.

Cada prévia mostra as cores com hex, o contraste do texto, botões arredondados,
selos de estado e um cartão "Pronto para ditar" com a arara v3.
Uso: python gerar_paletas.py  ->  arte/paletas/paleta-*.png
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

from gerar_arara import desenhar as desenhar_arara

PASTA = Path(__file__).parent
S = 2  # desenha em 2x e reduz, para suavizar os cantos arredondados
FONTES = Path("C:/Windows/Fonts")

PALETAS = {
    "a-caninde": {
        "nome": "A · Canindé (cores da própria arara)",
        "Verde": "#6CC93A", "Verde claro": "#9BE36A", "Azul": "#1C72CF",
        "Amarelo": "#FFC93C", "Fundo": "#0F1712", "Superfície": "#18221B",
        "Borda": "#2A3A2E", "Texto": "#EEF5EC", "Texto suave": "#9AAE9E",
    },
    "b-maca": {
        "nome": "B · Maçã verde (verde mais claro e vivo)",
        "Verde": "#8BD94A", "Verde claro": "#B5EE7F", "Azul": "#1A72CC",
        "Amarelo": "#F9D44A", "Fundo": "#101512", "Superfície": "#1A201C",
        "Borda": "#2C352F", "Texto": "#F1F6EF", "Texto suave": "#A1AFA4",
    },
    "c-bandeira": {
        "nome": "C · Bandeira (verde e azul mais profundos)",
        "Verde": "#22B45A", "Verde claro": "#5FD488", "Azul": "#2C5FD0",
        "Amarelo": "#FFDA1F", "Fundo": "#0D1220", "Superfície": "#161D2E",
        "Borda": "#28324A", "Texto": "#EEF2FA", "Texto suave": "#9AA6BF",
    },
}

ESCURO = "#0B140D"  # texto sobre botões claros


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def contraste(a, b):
    def lum(h):
        c = [v / 255 for v in rgb(h)]
        c = [v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4 for v in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def fonte(tam, negrito=False):
    nome = "seguisb.ttf" if negrito else "segoeui.ttf"
    try:
        return ImageFont.truetype(str(FONTES / nome), tam * S)
    except OSError:  # fora do Windows
        return ImageFont.load_default(tam * S)


def texto(d, xy, t, cor, tam, negrito=False, centro=False):
    f = fonte(tam, negrito)
    x, y = xy[0] * S, xy[1] * S
    if centro:
        d.text((x, y), t, fill=cor, font=f, anchor="mm")
    else:
        d.text((x, y), t, fill=cor, font=f)


def caixa(d, x, y, w, h, cor, raio=None, borda=None):
    raio = h / 2 if raio is None else raio
    d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], radius=raio * S,
                        fill=cor, outline=borda, width=2 if borda else 0)


def botao(d, x, y, w, rotulo, fundo, cor_texto, borda=None):
    caixa(d, x, y, w, 44, fundo, borda=borda)
    texto(d, (x + w / 2, y + 22), rotulo, cor_texto, 15, True, centro=True)


def gerar(chave, p):
    W, H = 960, 640
    img = Image.new("RGB", (W * S, H * S), p["Fundo"])
    d = ImageDraw.Draw(img)

    texto(d, (40, 28), p["nome"], p["Texto"], 22, True)

    # Amostras
    cores = [k for k in p if k != "nome"]
    for i, k in enumerate(cores):
        x, y = 40 + (i % 5) * 178, 80 + (i // 5) * 110
        caixa(d, x, y, 160, 56, p[k], raio=14, borda=p["Borda"])
        texto(d, (x, y + 62), k, p["Texto"], 13, True)
        texto(d, (x, y + 80), p[k], p["Texto suave"], 12)

    # Contraste (WCAG: 4.5 é o mínimo para texto normal)
    linhas = [
        f"Texto escuro sobre Verde: {contraste(ESCURO, p['Verde']):.1f}:1",
        f"Texto branco sobre Azul: {contraste('#FFFFFF', p['Azul']):.1f}:1",
        f"Texto sobre Fundo: {contraste(p['Texto'], p['Fundo']):.1f}:1",
    ]
    for i, l in enumerate(linhas):
        texto(d, (40 + i * 300, 300), l, p["Texto suave"], 12)

    # Botões e selos
    y = 340
    botao(d, 40, y, 190, "Começar a ditar", p["Verde"], ESCURO)
    botao(d, 246, y, 120, "Copiar", p["Azul"], "#FFFFFF")
    botao(d, 382, y, 170, "Configurações", p["Superfície"], p["Texto"], borda=p["Borda"])
    for i, (rot, cor) in enumerate([("Pronto", p["Verde"]), ("Ouvindo", p["Azul"]),
                                    ("Transcrevendo", p["Amarelo"])]):
        w = 100 if i < 2 else 130
        x = [580, 690, 800][i]
        caixa(d, x, y + 8, w, 28, cor)
        texto(d, (x + w / 2, y + 22), rot, ESCURO if cor != p["Azul"] else "#FFFFFF", 12, True, centro=True)

    # Cartão de exemplo
    cx, cy, cw, ch = 40, 420, 880, 180
    caixa(d, cx, cy, cw, ch, p["Superfície"], raio=24, borda=p["Borda"])
    caixa(d, cx + 24, cy + 20, 140, 140, p["Fundo"], raio=70)
    texto(d, (cx + 196, cy + 40), "Pronto para ditar", p["Texto"], 20, True)
    texto(d, (cx + 196, cy + 72), "Aperte o atalho em qualquer lugar e comece a falar.", p["Texto suave"], 14)
    # Barra de nível de áudio
    for i, h in enumerate([10, 18, 30, 22, 40, 28, 16, 34, 24, 12, 20, 8]):
        x = cx + 196 + i * 18
        cor = p["Verde"] if i < 8 else p["Borda"]
        caixa(d, x, cy + 140 - h, 10, h, cor, raio=5)
    botao(d, cx + cw - 214, cy + 108, 190, "Começar a ditar", p["Verde"], ESCURO)

    img = img.resize((W, H), Image.LANCZOS)
    arara = desenhar_arara(3)  # 93x126, pixels nítidos
    img.paste(arara, (cx + 24 + 70 - arara.width // 2, cy + 20 + 70 - arara.height // 2 + 4), arara)

    saida = PASTA / "paletas"
    saida.mkdir(exist_ok=True)
    img.save(saida / f"paleta-{chave}.png")


if __name__ == "__main__":
    for chave, p in PALETAS.items():
        gerar(chave, p)
    print("ok")
