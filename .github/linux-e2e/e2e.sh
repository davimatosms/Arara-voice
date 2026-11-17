#!/usr/bin/env bash
# Teste ponta a ponta do AppImage no Linux (CI). Uso: e2e.sh <AppImage> <pasta de trabalho>
# Env: CP (classpath do :core:e2eCp), JAVA (java 21). Cada item roda sozinho e nunca aborta os outros;
# o resultado fica em <pasta>/resultados.txt e o script sai com 1 se algum item falhou.
set -u
APP=$(readlink -f "$1"); W=$(readlink -f "$2"); HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$W"; RES="$W/resultados.txt"; : > "$RES"; FAIL=0
export APPIMAGE_EXTRACT_AND_RUN=1
REAL_PATH=$PATH

ok()  { echo "PASSOU  $1" | tee -a "$RES"; }
bad() { echo "FALHOU  $1 :: $2" | tee -a "$RES"; FAIL=1; }
sec() { echo; echo "=================== $* ==================="; }
quiet_kill() { pkill -f 'bin/Arara' 2>/dev/null; sleep 1; true; }

# HOME e XDG limpos por item. APPDATA só é definido se o item pedir (WORKAROUND_APPDATA=1).
env_item() {
  export HOME="$W/$1/home"; export XDG_CONFIG_HOME="$HOME/.config" XDG_DATA_HOME="$HOME/.local/share" XDG_CACHE_HOME="$HOME/.cache"
  mkdir -p "$HOME"
  if [ "${WORKAROUND_APPDATA:-0}" = 1 ]; then export APPDATA="$W/$1/appdata"; else unset APPDATA; fi
}
datadir() { echo "${APPDATA:-$HOME/.local/share}/Arara"; }

Xvfb :99 -screen 0 1280x800x24 >"$W/xvfb.log" 2>&1 & XVFB=$!
sleep 2
export DISPLAY=:99
openbox >"$W/openbox.log" 2>&1 & sleep 2 # gerenciador de janelas: foco de verdade para o Robot

# ---------------------------------------------------------------- 1. sobe e fica de pé 20 s (só HOME/XDG)
sec "1a. App sobe e fica de pé 20 s (HOME/XDG em pasta temp, SEM APPDATA)"
env_item boot
"$APP" >"$W/boot.log" 2>&1 & PID=$!
sleep 20
if kill -0 "$PID" 2>/dev/null && ! grep -q "Exception\|não conseguiu" "$W/boot.log"; then ok "1a app de pé após 20 s sem APPDATA"
else
  bad "1a app de pé após 20 s sem APPDATA" "$(grep -m3 -i 'exception\|erro\|error' "$W/boot.log" | tr '\n' '|')"
  WORKAROUND_APPDATA=1; echo "(a partir daqui o teste define APPDATA para conseguir testar o resto)"
fi
cat "$W/boot.log" | head -40
quiet_kill

sec "1b. --diagnostico-colar imprime sessão e ferramentas"
env_item diag
timeout 60 "$APP" --diagnostico-colar >"$W/diag.log" 2>&1; echo "saída $?" >>"$W/diag.log"; cat "$W/diag.log"
if grep -q '^Wayland=false' "$W/diag.log" && grep -q '^wtype: ' "$W/diag.log" && grep -q 'estrategia usada: robot' "$W/diag.log"; then ok "1b diagnóstico (X11: Wayland=false, ferramentas, estratégia robot)"
else bad "1b diagnóstico" "$(head -c 400 "$W/diag.log" | tr '\n' '|')"; fi

# ---------------------------------------------------------------- 2. X11: Whisper + atalho + colagem
sec "2a. Whisper LEVE + atalho global (Smoke) sob Xvfb"
env_item smoke
espeak-ng -v pt-br -w "$W/fala.wav" "Olá, este é um teste do ditado por voz." 2>/dev/null
( cd "$W" && timeout 900 "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CP" br.arara.core.Smoke "$W/smoke" "$W/fala.wav" ) >"$W/smoke.log" 2>&1
grep -E '^(os=|whisper|atalho|SMOKE)' "$W/smoke.log"
tail -5 "$W/smoke.log"
grep -q 'SMOKE OK' "$W/smoke.log" && grep -q 'whisper: .*transcritos' "$W/smoke.log" && ok "2a Whisper LEVE transcreveu: $(grep -m1 'transcritos' "$W/smoke.log" | sed 's/.*: "//')" || bad "2a Whisper smoke" "$(tail -3 "$W/smoke.log" | tr '\n' '|')"
grep -q 'atalho global registrado: true' "$W/smoke.log" && ok "2b atalho global registrado (JNativeHook/XRecord)" || bad "2b atalho global" "$(grep 'atalho' "$W/smoke.log" | head -2 | tr '\n' '|')"

sec "2c. Colagem via Robot numa janela Swing (X11)"
env_item paste
rm -f "$W/colado-x11.txt"
"$JAVA" -cp "$CP" br.arara.core.PasteTarget "$W/colado-x11.txt" 25 >"$W/target.log" 2>&1 &
TG=$!
sleep 6; WID=$(timeout 20 xdotool search --onlyvisible --name PasteTarget | head -1); echo "janela PasteTarget: [$WID]"; xdotool windowactivate --sync "$WID" 2>&1 || xdotool windowfocus "$WID" 2>&1; sleep 1; echo "foco: $(xdotool getwindowfocus getwindowname 2>&1)"
timeout 60 "$APP" --diagnostico-colar >"$W/diag-x11.log" 2>&1; cat "$W/diag-x11.log"
wait "$TG"; echo "colado: [$(cat "$W/colado-x11.txt" 2>/dev/null)]"; echo "--- log do alvo:"; cat "$W/target.log"
[ "$(cat "$W/colado-x11.txt" 2>/dev/null)" = "Teste de colagem do Arara. " ] && ok "2c texto colado no X11 conferido" || bad "2c colagem X11" "recebido=[$(cat "$W/colado-x11.txt" 2>/dev/null)] $($(head -c 600 "$W/target.log" | tr "
" "|"))"

# ---------------------------------------------------------------- 4. --alternar
sec "4. --alternar: segunda instância alterna a primeira"
WORKAROUND_APPDATA=${WORKAROUND_APPDATA:-0}
env_item alt
mkdir -p "$(datadir)"; echo '{"startWithWindows": false}' > "$(datadir)/config.json" # não é primeira execução: sem onboarding
"$APP" >"$W/alt1.log" 2>&1 & A1=$!
for i in $(seq 1 30); do [ -s "$(datadir)/arara.porta" ] && break; sleep 1; done
sleep 3
timeout 30 "$APP" --alternar >"$W/alt2.log" 2>&1; R=$?
sleep 3
echo "--- 1a instância:"; head -30 "$W/alt1.log"; echo "--- 2a instância (saída $R):"; cat "$W/alt2.log"
[ "$R" = 0 ] && ok "4b segunda instância saiu 0" || bad "4b segunda instância" "saída $R: $(head -c 300 "$W/alt2.log")"
grep -q 'alternar: pedido recebido' "$W/alt1.log" && ok "4c primeira instância registrou o toggle no log" || bad "4c log do toggle" "sem 'alternar: pedido recebido' em $W/alt1.log: $(head -c 300 "$W/alt1.log")"
kill -0 "$A1" 2>/dev/null && ok "4d primeira instância continua de pé" || bad "4d primeira instância" "morreu após o toggle: $(tail -5 "$W/alt1.log" | tr '\n' '|')"
quiet_kill

# ---------------------------------------------------------------- 5. autostart
sec "5. Abrir com o sistema: autostart/arara.desktop"
env_item auto
mkdir -p "$(datadir)"; echo '{"startWithWindows": true}' > "$(datadir)/config.json"
"$APP" >"$W/auto.log" 2>&1 & sleep 15
F="$XDG_CONFIG_HOME/autostart/arara.desktop"
echo "--- $F"; cat "$F" 2>&1; echo "--- APPIMAGE esperado: $APP"
if [ -f "$F" ] && grep -qxF "Exec=$APP" "$F"; then ok "5 arara.desktop com Exec=\$APPIMAGE ($APP)"
else bad "5 autostart" "$(cat "$F" 2>&1 | tr '\n' '|') | log: $(head -c 300 "$W/auto.log")"; fi
quiet_kill

# ---------------------------------------------------------------- 6. atualização
sec "6. UpdateChecker: servidor falso, SHA certo troca, SHA errado recusa"
timeout 120 "$JAVA" -cp "$CP" br.arara.core.UpdateE2E 2>&1 | tee "$W/update.log"
grep -q 'UPDATE E2E OK' "$W/update.log" && ok "6 atualização (troca atômica com SHA certo, recusa com SHA errado)" || bad "6 atualização" "$(grep FALHA "$W/update.log" | tr '\n' '|')"

# ---------------------------------------------------------------- 3. Wayland headless (sway)
sec "3. Wayland headless (sway)"
export XDG_RUNTIME_DIR="$W/run"; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
cat > "$W/sway.conf" <<EOF
exec sh -c 'echo \$DISPLAY > $W/sway-display'
EOF
unset DISPLAY
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman sway -c "$W/sway.conf" >"$W/sway.log" 2>&1 & SWAY=$!
for i in $(seq 1 20); do ls "$XDG_RUNTIME_DIR"/wayland-* >/dev/null 2>&1 && break; sleep 1; done
SOCK=$(basename "$(ls "$XDG_RUNTIME_DIR"/wayland-[0-9] 2>/dev/null | head -1)")
sleep 2
if [ -z "$SOCK" ]; then bad "3 sway headless" "não subiu: $(tail -5 "$W/sway.log" | tr '\n' '|')"
else
  export WAYLAND_DISPLAY=$SOCK XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=sway
  XD=$(cat "$W/sway-display" 2>/dev/null); [ -n "$XD" ] && export DISPLAY=$XD
  echo "sway: $SOCK  DISPLAY(XWayland)=${XD:-<nenhum>}"
  env_item wl

  sec "3a0. Controle: wl-copy + wtype direto (sem o Arara) no cliente GTK"
  GDK_BACKEND=wayland python3 "$HERE/alvo_gtk.py" "$W/controle-wl.txt" 14 >"$W/gtk0.log" 2>&1 & GTK=$!
  sleep 5; swaymsg -t get_seats | head -c 600; echo; swaymsg -t get_tree | grep -E '"(focused|app_id|name)"' | head -12
  printf 'controle' | wl-copy; sleep 1; wtype -M ctrl v -m ctrl; wait "$GTK"; cat "$W/gtk0.log"
  echo "controle: [$(cat "$W/controle-wl.txt" 2>/dev/null)]"
  [ "$(cat "$W/controle-wl.txt" 2>/dev/null)" = controle ] && ok "3a-controle wtype direto cola no cliente GTK (harness ok)" || bad "3a-controle (harness)" "wtype direto não colou: [$(cat "$W/controle-wl.txt" 2>/dev/null)] $(head -c 300 "$W/gtk0.log" | tr '
' '|')"

  sec "3a. Colagem no Wayland (wtype) num cliente GTK nativo"
  rm -f "$W/colado-wl.txt"
  GDK_BACKEND=wayland python3 "$HERE/alvo_gtk.py" "$W/colado-wl.txt" 30 >"$W/gtk.log" 2>&1 & GTK=$!
  sleep 5
  wtype -k shift 2>/dev/null; sleep 1 # garante o "teclado" virtual no seat antes de colar
  timeout 60 "$APP" --diagnostico-colar >"$W/diag-wl.log" 2>&1; cat "$W/diag-wl.log"
  wait "$GTK"; echo "colado: [$(cat "$W/colado-wl.txt" 2>/dev/null)]"; cat "$W/gtk.log" | head -10
  grep -q '^Wayland=true' "$W/diag-wl.log" && ok "3a0 Wayland detectado" || bad "3a0 detecção Wayland" "$(head -2 "$W/diag-wl.log" | tr '\n' '|')"
  grep -q 'wtype: sim' "$W/diag-wl.log" && grep -q 'estrategia usada: wtype' "$W/diag-wl.log" && [ "$(cat "$W/colado-wl.txt" 2>/dev/null)" = "Teste de colagem do Arara. " ] \
    && ok "3a colou via wtype no Wayland e o texto confere" \
    || bad "3a colagem Wayland" "recebido=[$(cat "$W/colado-wl.txt" 2>/dev/null)] | $(tail -3 "$W/diag-wl.log" | tr '\n' '|')"

  sec "3b. Só copiado (sem wtype/ydotool/portal)"
  MIN="$W/pathmin"; rm -rf "$MIN"; mkdir -p "$MIN"
  for t in timeout sh bash dirname readlink uname env cat mkdir rm ls sed grep tr id basename wl-copy wl-paste; do p=$(PATH=$REAL_PATH command -v $t) && ln -s "$p" "$MIN/$t"; done
  GDK_BACKEND=wayland python3 "$HERE/alvo_gtk.py" "$W/colado-wl2.txt" 25 >"$W/gtk2.log" 2>&1 & GTK=$!
  sleep 5
  PATH="$MIN" timeout 60 "$APP" --diagnostico-colar >"$W/diag-co.log" 2>&1; cat "$W/diag-co.log"
  wait "$GTK"
  grep -q 'wtype: nao' "$W/diag-co.log" && grep -q 'NENHUMA estrategia funcionou: Texto copiado: cole com Ctrl+V.' "$W/diag-co.log" \
    && ok "3b mensagem 'Texto copiado: cole com Ctrl+V.'" || bad "3b só copiado" "$(tail -4 "$W/diag-co.log" | tr '\n' '|')"
  CLIP=$(wl-paste 2>&1)
  [ "$CLIP" = "Teste de colagem do Arara. " ] && ok "3b texto ficou no clipboard Wayland (wl-paste)" || bad "3b clipboard Wayland" "wl-paste=[$CLIP]"
  [ -z "$(cat "$W/colado-wl2.txt" 2>/dev/null)" ] && ok "3b nada foi colado sozinho no alvo" || bad "3b colou sem estratégia" "[$(cat "$W/colado-wl2.txt")]"
fi
kill "$SWAY" 2>/dev/null; kill "$XVFB" 2>/dev/null; quiet_kill

sec "RESUMO"; cat "$RES"
exit $FAIL
