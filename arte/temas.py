"""Confere o contraste WCAG AA dos 10 temas de app/src/main/resources/br/arara/app/temas.properties.

O mesmo teste roda no Java (ThemeTest); este serve para checar uma paleta nova antes de abrir o app.
Uso: python arte/temas.py
"""
from pathlib import Path

ARQUIVO = Path(__file__).parent.parent / "app/src/main/resources/br/arara/app/temas.properties"
ESPECIES = ["caninde", "azul", "vermelha", "lear", "maracana"]


def ler():
    """{'caninde': {...}, 'caninde_claro': {...}, ...}; cada tema traz também nome, local e modo."""
    kv = dict(l.split("=", 1) for l in ARQUIVO.read_text("utf-8").splitlines() if l and not l.startswith("#"))
    temas = {}
    for e in ESPECIES:
        for tid, modo in ((e, "escuro"), (e + "_claro", "claro")):
            t = {k.split(".", 1)[1]: v for k, v in kv.items() if k.startswith(tid + ".") and "." not in k[len(tid) + 1:]}
            t.update(nome=kv[e + ".nome"], local=kv[e + ".local"], modo=modo, ave=e)
            temas[tid] = t
    return temas


def lum(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4 for v in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def cr(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def pares(t):
    """(rótulo, frente, fundo). Selos de estado usam texto bold 12-14 px: AA pede 4.5 em todos."""
    p = [("texto/fundo", t["texto"], t["fundo"]), ("texto/superfície", t["texto"], t["sup"]),
         ("suave/fundo", t["suave"], t["fundo"]), ("suave/superfície", t["suave"], t["sup"]),
         ("botão principal", t["sobre_principal"], t["principal"]),
         ("selo Ouvindo", t["sobre_contraste"], t["contraste"]),
         ("Transcrevendo (texto)", t["apoio"], t["sup"]),
         ("Pronto (texto)", t["pronto"], t["sup"])]
    if t["modo"] == "escuro":
        p.append(("item ativo (texto principal)", t["principal"], t["sup"]))
    return p


if __name__ == "__main__":
    temas = ler()
    assert len(temas) == 10
    ruins = 0
    for k, t in temas.items():
        print(f"\n{k}")
        for r, a, b in pares(t):
            v = cr(a, b)
            print(f"  {'ok ' if v >= 4.5 else 'XX '} {v:5.1f}  {r}")
            ruins += v < 4.5
    assert ruins == 0, f"{ruins} pares abaixo de AA"
    print("\nok")
