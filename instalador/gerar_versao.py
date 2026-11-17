"""Gera site/versao.json (manifesto de atualização que o app consulta) a partir dos artefatos da release.

Uso, com os arquivos que vão na release (os nomes com versão):
    python instalador/gerar_versao.py 0.3.0 "Notas curtas em PT-BR." \
        Arara-Setup-0.3.0.exe Arara-0.3.0-Mac-AppleSilicon.dmg Arara-0.3.0-Mac-Intel.dmg

As URLs apontam para https://github.com/davimatosms/Arara-voice/releases/download/v<versão>/<arquivo>,
então os arquivos precisam ser anexados à release com esses mesmos nomes.
"""
import argparse
import datetime
import hashlib
import json
import os
import re

RELEASES = "https://github.com/davimatosms/Arara-voice/releases/download/"
SAIDA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site", "versao.json")


def plataforma(nome):
    if nome.endswith(".exe"):
        return "windows"
    if nome.endswith(".dmg") and ("AppleSilicon" in nome or "arm64" in nome):
        return "mac-arm64"
    if nome.endswith(".dmg") and ("Intel" in nome or "x64" in nome):
        return "mac-x64"
    if nome.endswith(".AppImage"):
        return "linux-x64"
    raise SystemExit(f"Não sei de que plataforma é {nome}")


def sha256(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description="Gera site/versao.json a partir dos artefatos da release.")
    p.add_argument("versao", help="ex.: 0.3.0 (sem o v)")
    p.add_argument("notas", help="notas curtas em PT-BR, mostradas no aviso de atualização")
    p.add_argument("arquivos", nargs="+", help="Setup .exe, os dois .dmg e (opcional) o .AppImage, com a versão no nome")
    p.add_argument("--data", default=datetime.date.today().isoformat())
    p.add_argument("--saida", default=SAIDA)
    a = p.parse_args()
    # O app aceita só números com ponto (UpdateChecker.VERSION)
    if not re.fullmatch(r"\d+(\.\d+)*", a.versao):
        raise SystemExit("Versão sem o v e só com números, ex.: 0.3.0")

    arquivos = {}
    for caminho in a.arquivos:
        nome = os.path.basename(caminho)
        if a.versao not in nome:
            raise SystemExit(f"{nome} não tem a versão {a.versao} no nome")
        arquivos[plataforma(nome)] = {"url": f"{RELEASES}v{a.versao}/{nome}", "sha256": sha256(caminho)}
    faltando = {"windows", "mac-arm64", "mac-x64"} - arquivos.keys()
    if faltando:
        raise SystemExit(f"Faltou o arquivo de: {', '.join(sorted(faltando))}")

    manifesto = {"versao": a.versao, "data": a.data, "notas": a.notas,
                 "arquivos": {k: arquivos[k] for k in ("windows", "mac-arm64", "mac-x64", "linux-x64") if k in arquivos}}
    with open(a.saida, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifesto, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{a.saida}: {a.versao}")


if __name__ == "__main__":
    main()
