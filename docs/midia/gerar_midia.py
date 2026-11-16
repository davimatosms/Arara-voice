"""Monta a mídia do README em docs/midia: o GIF, o mosaico dos temas, a janela e a imagem social.

Antes, gere os quadros com o próprio app (sem abrir janela nenhuma):
    ./gradlew :app:test --tests "br.arara.app.ScreenshotTest.desenhaVitrine" --tests "br.arara.app.ScreenshotTest.desenhaTemas"
Depois:
    python docs/midia/gerar_midia.py
"""
import shutil
from pathlib import Path

from PIL import Image

RAIZ = Path(__file__).resolve().parents[2]
TELAS = RAIZ / "app/build/screenshots"
QUADROS = TELAS / "vitrine"
SAIDA = Path(__file__).resolve().parent
PASSO_MS = 70  # o teste desenha um quadro a cada 70 ms de relógio do pop-up

arquivos = sorted((QUADROS / "gif").glob("*.png"))
assert arquivos, "sem quadros: rode o ScreenshotTest.desenhaVitrine antes"
quadros = [Image.open(f).convert("RGB") for f in arquivos]

# Uma paleta só para o GIF inteiro, tirada de quadros com todos os estados (sem cintilar entre quadros).
amostra = Image.new("RGB", (quadros[0].width, quadros[0].height * 4))
for i, q in enumerate(quadros[:: max(1, len(quadros) // 4)][:4]):
    amostra.paste(q, (0, i * q.height))
paleta = amostra.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
gif = [q.quantize(palette=paleta, dither=Image.Dither.NONE) for q in quadros]

destino = SAIDA / "arara-demo.gif"
duracoes = [PASSO_MS] * len(gif)
duracoes[-1] = 1200  # respiro antes de recomeçar
gif[0].save(destino, save_all=True, append_images=gif[1:], duration=duracoes, loop=0, optimize=True, disposal=1)
shutil.copy(QUADROS / "social-preview.png", SAIDA / "social-preview.png")

# Temas: o pop-up "Ouvindo" das 5 araras, escuro à esquerda e claro à direita.
araras = ["caninde", "azul", "vermelha", "lear", "maracana"]
pops = [[Image.open(TELAS / f"temas/popup-ouvindo-{a}{m}.png") for m in ("", "-claro")] for a in araras]
pw, ph, gap = pops[0][0].width, pops[0][0].height, 16
mosaico = Image.new("RGB", (2 * pw + 3 * gap, len(araras) * (ph + gap) + gap), (0x0B, 0x12, 0x0D))
for i, par in enumerate(pops):
    for j, img in enumerate(par):
        mosaico.paste(img, (gap + j * (pw + gap), gap + i * (ph + gap)), img)
mosaico.save(SAIDA / "temas.png", optimize=True)
shutil.copy(TELAS / "temas/inicio-caninde.png", SAIDA / "janela-inicio.png")

total = sum(duracoes) / 1000
print(f"{destino.name}: {len(gif)} quadros, {total:.1f} s, {destino.stat().st_size / 1e6:.2f} MB, {quadros[0].size}")
