"""Ícones por arara no estilo de instalador/assets/icone-*.png (quadrado arredondado com degradê + arara em pixel).

Só para a chave "Usar o ícone da arara escolhida", com o app aberto: o ícone do programa, do instalador e do
atalho continua sendo a canindé de instalador/assets.
- 256: arara pousada 4x; 48: cabeça 2x; 32: cabeça 1x (mesmos enquadramentos do ícone atual).
- 16: desenhado à mão (grade de instalador/icone.py), cores por espécie.
- bandeja: cabeça 16 sem fundo (e 32 para 200% e para a barra de menus do Mac, colorida).
A máscara dos cantos vem do ícone atual (instalador/assets/icone-N.png), então o formato é idêntico.
Uso: python arte/gerar_icones_araras.py  ->  app/src/main/resources/br/arara/app/araras/<esp>/
     icone-{16,32,48,256}.png e bandeja-{16,32}.png, e arte/especies/icones.png (prévia).
Autoteste: o 16 da canindé bate pixel a pixel com o ícone atual.
"""
from pathlib import Path
from PIL import Image
from gerar_especies import ESPECIES, SAIDA, recolor, png
import gerar_arara as g

AQ = Path(__file__).parent
REF = AQ.parent / "instalador" / "assets"
H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))

# degradê de cima para baixo; a canindé é o céu do ícone atual
FUNDO = {"caninde": ("#1557B8", "#8ED2EC"), "azul": ("#E9A612", "#FFE07A"), "vermelha": ("#1E7A48", "#8ED65F"),
         "lear": ("#B8743E", "#F4E0B4"), "maracana": ("#B82A20", "#FF8E70")}

G16 = """
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
BASE16 = dict(K="#050505", L="#78F550", G="#58C828", A="#2887E1", W="#F5F3E4", D="#373746", d="#1E1E26",
              Y="#FAD423", O="#F8AE1C", U="#373746", u="#1E1E26")
# por espécie: cores e trocas de caractere (U/u = bico de baixo quando o de cima muda de cor)
COR16 = {
    "caninde": ({}, {}),
    "azul": (dict(L="#6A8CEB", G="#3B57C4", A="#283C96", W="#FFD43B", Y="#3B57C4", O="#FFD43B",
                  D="#202028", d="#101016"), {}),
    "vermelha": (dict(L="#F45B4D", G="#DA3428", A="#A8221E", W="#F8EEE8", Y="#DA3428", O="#A8221E",
                      D="#EFE5CF", d="#C8B78F"), {"bico_baixo_escuro": True}),
    "lear": (dict(L="#93B4CF", G="#6C93B6", A="#3E66B2", W="#F6DF83", Y="#3E66B2", O="#F6DF83",
                  D="#202028", d="#101016"), {}),
    "maracana": (dict(L="#E8402F", G="#57BD4C", A="#3A8FC0", Y="#57BD4C", O="#2F8A35",
                      D="#202028", d="#101016"), {}),
}
LINHAS16 = G16.strip("\n").split("\n")
assert all(len(l) == 16 for l in LINHAS16)


def degrade(n, esp):
    a, b = (H(c) for c in FUNDO[esp])
    im = Image.new("RGBA", (n, n))
    for y in range(n):
        t = y / (n - 1)
        im.paste(tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,), (0, y, n, y + 1))
    return im


def mascara(img, n):
    alfa = Image.open(REF / f"icone-{n}.png").convert("RGBA").getchannel("A")
    img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", img.size, 0), alfa))
    return img


def cabeca16(esp, fundo=None):
    cores, trocas = COR16[esp]
    pal = {k: H(v) for k, v in {**BASE16, **cores}.items()}
    im = fundo.copy() if fundo else Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, l in enumerate(LINHAS16):
        for x, c in enumerate(l):
            if trocas.get("bico_baixo_escuro") and y >= 9 and c in "Dd": c = c.replace("D", "U").replace("d", "u")
            if c != ".":
                im.putpixel((x, y), pal[c] + (255,))
    return im


def icones(esp):
    pal = ESPECIES[esp]["pal"]
    arara = png(recolor([list(l) for l in g.GRADE], esp), pal)
    out = {}
    i256 = degrade(256, esp); i256.alpha_composite(arara.resize((124, 168), Image.NEAREST), (66, 44))
    out["icone-256"] = mascara(i256, 256)
    i48 = degrade(48, esp); i48.alpha_composite(arara.crop((4, 1, 28, 22)).resize((48, 42), Image.NEAREST), (0, 6))
    out["icone-48"] = mascara(i48, 48)
    i32 = degrade(32, esp); i32.alpha_composite(arara.crop((0, 0, 31, 25)), (1, 7))
    out["icone-32"] = mascara(i32, 32)
    out["icone-16"] = mascara(cabeca16(esp, degrade(16, esp)), 16)
    out["bandeja-16"] = cabeca16(esp)
    b32 = Image.new("RGBA", (32, 32)); b32.alpha_composite(arara.crop((5, 1, 26, 19)).resize((21, 18)), (5, 7))
    out["bandeja-32"] = b32
    return out


if __name__ == "__main__":
    prev = Image.new("RGBA", (256 + 48 + 32 + 16 + 16 + 32 + 7 * 12, 256 * len(ESPECIES) + 12 * 6), (236, 236, 236, 255))
    for row, esp in enumerate(ESPECIES):
        pasta = SAIDA / esp
        pasta.mkdir(parents=True, exist_ok=True)
        x = 12
        for k, im in icones(esp).items():
            im.save(pasta / f"{k}.png")
            prev.alpha_composite(im, (x, 12 + row * (256 + 12)))
            x += im.width + 12
    prev.save(AQ / "especies" / "icones.png")
    # autoteste: o 16 da canindé tem que bater pixel a pixel com o ícone atual nas áreas desenhadas
    ref = Image.open(REF / "icone-16.png").convert("RGBA")
    meu = Image.open(SAIDA / "caninde" / "icone-16.png")
    dif = sum(1 for y, l in enumerate(LINHAS16) for x, c in enumerate(l) if c != "." and ref.getpixel((x, y))[:3] != meu.getpixel((x, y))[:3])
    assert dif == 0, f"16 da canindé difere em {dif} px"
    print("ok")
