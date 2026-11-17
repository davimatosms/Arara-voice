// Landing v2 do Arara: HTML/CSS/JS puro, sem dependências.
// Sem .anim (prefers-reduced-motion ou sem JS) a página fica estática e legível,
// com a arara parada no herói. Com .anim, a mesma #atriz voa pela página.
(() => {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const anim = document.documentElement.classList.contains("anim");

  // ---------- ondas (alturas do design) ----------
  const ALT = [8, 18, 28, 14, 34, 22, 10, 26, 36, 16, 8, 20, 30, 18, 34, 24, 12, 28];
  for (const el of $$(".ondas")) {
    const n = el.classList.contains("grande") ? 34 : el.classList.contains("pop") ? 16 : 26;
    el.innerHTML = Array.from({ length: n }, (_, i) => `<i style="--h:${ALT[i % ALT.length]};--d:-${((i * 0.37) % 1).toFixed(2)}s"></i>`).join("");
  }

  // ---------- abas do app (funcionam também sem animação) ----------
  const abas = $$('[role="tab"]');
  const ativa = (aba, foco) => {
    for (const a of abas) {
      const sel = a === aba;
      a.setAttribute("aria-selected", String(sel));
      a.tabIndex = sel ? 0 : -1;
      document.getElementById(a.getAttribute("aria-controls")).classList.toggle("ativa", sel);
    }
    if (foco) aba.focus();
  };
  abas.forEach((a, i) => {
    a.addEventListener("click", () => ativa(a));
    a.addEventListener("keydown", (e) => {
      const n = abas.length;
      const j = { ArrowRight: i + 1, ArrowLeft: i - 1 + n, Home: 0, End: n - 1 }[e.key];
      if (j === undefined) return;
      e.preventDefault();
      ativa(abas[j % n], true);
    });
  });

  // ---------- download por sistema: Windows e Mac têm botões iguais; aqui só o botão do topo e o tipo de Mac ----------
  const REL = "https://github.com/davimatosms/Arara-voice/releases/latest/download/";
  const ua = navigator.userAgent;
  // iPad no Safari se diz "Macintosh": o toque separa ele de um Mac de verdade
  const so = /Windows/.test(ua) ? "win" : /Macintosh/.test(ua) && navigator.maxTouchPoints < 2 ? "mac" : /Linux/.test(ua) && !/Android/.test(ua) ? "linux" : "outro";
  // beta é pré-release: /releases/latest/ a ignora, então a URL fixa da tag fica só aqui (trocar na release estável)
  const LINUX_URL = "https://github.com/davimatosms/Arara-voice/releases/download/v0.5.0-beta.1/Arara-x86_64.AppImage";
  for (const el of $$("[data-linux]")) el.href = LINUX_URL;
  for (const el of $$(`[data-so="${so === "outro" ? "win" : so}"]`)) el.classList.add("destaque");
  const topo = $("[data-baixar]");
  if (topo && so === "mac") {
    $("[data-rotulo]", topo).textContent = "Baixar para Mac";
    topo.href = REL + "Arara-Mac-AppleSilicon.dmg";
  } else if (topo && so === "linux") {
    $("[data-rotulo]", topo).textContent = "Baixar para Linux";
    topo.href = LINUX_URL;
  } else if (topo && so === "outro") {
    $("[data-rotulo]", topo).textContent = "Baixar";
    topo.href = "#baixar";
  }
  // Safari não informa a arquitetura (Apple Silicon é a maioria); o Chromium de um Mac Intel informa "x86"
  if (so === "mac") {
    navigator.userAgentData?.getHighEntropyValues(["architecture"]).then((v) => {
      if (v.architecture !== "x86") return;
      for (const el of $$("[data-mac]")) el.href = REL + "Arara-Mac-Intel.dmg";
      if (topo) topo.href = REL + "Arara-Mac-Intel.dmg";
    }).catch(() => {});
  }

  // ---------- voltar ao topo: aparece depois de 600 px, some abaixo de 300 (histerese) ----------
  const topoBtn = $(".topo-btn"), setaTopo = $(".seta", topoBtn), marca = $(".marca");
  const cobrem = $$(".baixar > *, .rodape-grid > *"); // o botão não cobre o download nem o rodapé
  let mostra = false, segura = false, tSome = 0, pedido = false;
  function verBotao() {
    if (segura) return; // durante a puxada quem manda no botão é a arara
    const y = scrollY, vh = innerHeight, vw = innerWidth;
    if (y > 600) mostra = true; else if (y < 300) mostra = false;
    const esq = vw - (vw <= 760 ? 64 : 80); // borda esquerda do botão
    const cobre = cobrem.some((el) => { const r = el.getBoundingClientRect(); return r.top < vh && r.bottom > vh - 96 && r.right > esq; });
    if (mostra && !cobre) {
      clearTimeout(tSome);
      if (topoBtn.hidden) { topoBtn.hidden = false; void topoBtn.offsetWidth; } // reflow para o fade começar de 0
      topoBtn.classList.add("ver");
    } else if (topoBtn.classList.contains("ver")) {
      topoBtn.classList.remove("ver");
      tSome = setTimeout(() => { topoBtn.hidden = true; }, 200); // hidden: fora do Tab e do leitor de tela
    }
  }
  addEventListener("scroll", verBotao, { passive: true });
  addEventListener("resize", verBotao);
  verBotao();
  topoBtn.addEventListener("click", () => {
    if (anim) { pedido = true; return; } // a máquina da arara atende no próximo quadro
    scrollTo({ top: 0, behavior: "instant" }); // movimento reduzido: direto, sem animação
    marca.focus({ preventScroll: true });
  });

  if (!anim) return;

  // =====================================================================
  // A ATRIZ: um só elemento de arara, uma máquina de estados explícita.
  //   pousada-no-hero -> voando-com-scroll -> pegando-agente -> saindo-com-agente -> pousada-no-download
  //   e, pelo botão de voltar ao topo, puxando-para-cima -> pousada-no-hero (o único que ignora a zona)
  // Regras: as transições são decididas só em quadro(), pela zona do scroll; entrar() cancela o
  // estado anterior (solta o cartão, limpa timers); a posição só muda por mover(), com passo
  // máximo VMAX por quadro, então ela nunca teleporta enquanto está na tela.
  // =====================================================================
  const atriz = $("#atriz"), tira = $(".tira", atriz), corpo = $(".corpo", atriz);
  const ninho = $(".ninho"), rastro = $(".rastro"), lista = $(".agentes"), cards = $$(".agente");
  const pouso = $(".pouso"), btn = $("#btn-baixar"), heroEl = $(".hero");
  const pares = $$(".cartoes .par"), demo = $(".demo-palco"), passos = $(".passos");
  const linha = $$(".linha-tempo li");
  const QW = 81, QH = 58, POUSADA = 6, MS_QUADRO = 90, VMAX = 36;
  document.body.append(atriz); // fixa e acima de tudo; o .ninho guarda o lugar dela no herói

  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const suave = (t) => t * t * (3 - 2 * t);
  const pag = (el) => { const r = el.getBoundingClientRect(); return { x: r.left + scrollX, y: r.top + scrollY, w: r.width, h: r.height }; };

  // ---------- âncoras: medidas no load, em fonts.ready e no resize (nunca por quadro) ----------
  let A = null;
  const revelas = [];
  $$(".tela-frente, .telas, .c-atalho, .popup-real").forEach((el, i) => { el.classList.add("revela"); el.style.setProperty("--d", `${(i % 3) * 0.12}s`); revelas.push(el); });
  function medir() {
    const estreito = innerWidth <= 760;
    const kHero = estreito ? 3 : 5;
    const n = pag(ninho), r = pag(rastro), l = pag(lista), p = pag(pouso), b = pag(btn), h = pag(heroEl);
    const r8 = rastro.lastElementChild.getBoundingClientRect();
    A = {
      kHero, kVoo: estreito ? 2 : 3, kPouso: estreito ? 3 : 4, kPuxa: estreito ? 3 : 4,
      hero: { x: n.x + n.w / 2, y: n.y + (QH * kHero) / 2 },
      // fim do voo ligado ao scroll: logo depois do último ponto do rastro na seção 2
      oque: { x: Math.min(r8.right + scrollX + 40, innerWidth - 60), y: r8.top + scrollY - 40 },
      agentes: l.y + Math.min(l.h, 200) / 2,
      pouso: { x: p.x + p.w / 2, y: p.y + p.h },
      baixar: b.y + b.h,
      heroFim: h.y + h.h,
      demo: pag(demo), passos: pag(passos),
      revelas: revelas.map((el) => pag(el).y),
    };
    if (r.w === 0) A.oque = { x: n.x + n.w / 2, y: h.y + h.h + 200 };
  }

  // ---------- zona: só o scroll decide ----------
  function zona() {
    const y = scrollY, vh = innerHeight;
    if (A.baixar <= y + vh * 0.92) return "download";
    if (A.agentes <= y + vh * 0.55) return "agentes";
    return "topo";
  }

  // ---------- estado ----------
  let estado = null, t0 = 0, pos = null, k = 0, q = 1, visivel = true;
  let card = null, pega = null, timers = [], subindo = false, ultimo = -1, vSobe = 0, sumiu = false, pousou = false;

  function soltarCartao() {
    if (!card) return;
    card.classList.remove("pego");
    card.style.transform = "";
    card = null;
  }
  function limpar(manterCartao) {
    timers.forEach(clearTimeout);
    timers = [];
    cards.forEach((c) => c.classList.remove("sumiu"));
    corpo.classList.remove("pousou");
    if (!manterCartao) soltarCartao();
  }
  function entrar(novo, agora) {
    limpar(novo === "saindo-com-agente");
    estado = novo;
    t0 = agora;
    atriz.dataset.estado = novo;
    sumiu = false;
    pousou = false;
    if (novo === "pegando-agente") {
      let i;
      do i = Math.floor(Math.random() * cards.length); while (cards.length > 1 && i === ultimo);
      ultimo = i;
      card = cards[i];
    }
    if (novo === "saindo-com-agente") vSobe = 2;
  }

  function escala(novo) {
    if (novo === k) return;
    k = novo;
    atriz.style.setProperty("--k", k); // muda só na troca de escala, nunca por quadro
  }

  // anda até o alvo; f = fração da distância por quadro, limitada a VMAX px
  function mover(alvo, f) {
    const dx = alvo.x - pos.x, dy = alvo.y - pos.y, d = Math.hypot(dx, dy);
    if (d < 0.75) { pos.x = alvo.x; pos.y = alvo.y; return 0; }
    const s = Math.min(Math.max(d * f, Math.min(d, 1.5)), VMAX);
    pos.x += (dx / d) * s;
    pos.y += (dy / d) * s;
    return d - s;
  }

  // ---------- puxando-para-cima: o botão de voltar ao topo ----------
  // Fases por tempo: aproximação (0-300 ms) -> agarra (120 ms) -> puxa (700 ms, easeInOutCubic) -> solta e
  // pousa (pousada-no-hero). Enquanto vale, pos fica em coordenadas da TELA, porque a página rola por baixo.
  const PUXA = "puxando-para-cima", T_APROX = 300, T_AGARRA = 120, T_PUXA = 700, PATA = 0.45;
  const rastroTopo = document.createElement("div");
  rastroTopo.className = "topo-rastro";
  rastroTopo.setAttribute("aria-hidden", "true");
  rastroTopo.innerHTML = "<i></i>".repeat(5);
  document.body.append(rastroTopo);
  const pontos = [...rastroTopo.children];
  const easeInOutCubic = (t) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2);
  let puxa = null, cancelar = false;

  // wheel/toque/tecla/clique cancelam; o evento scroll não serve, o próprio laço rola a página
  const cancela = (e) => {
    if (estado !== PUXA) return;
    const noBotao = topoBtn.contains(e.target);
    if (e.type === "keydown" ? !(noBotao && (e.key === "Enter" || e.key === " ")) : e.type === "wheel" || !noBotao) cancelar = true;
  };
  for (const t of ["wheel", "touchstart", "keydown", "pointerdown"]) addEventListener(t, cancela, { passive: true, capture: true });

  function iniciarPuxada(agora) {
    const vw = innerWidth, vh = innerHeight, w = QW * k, h = QH * k;
    const sx = pos.x - scrollX, sy = pos.y - scrollY;
    const fora = !visivel || sy + h / 2 < 0 || sy - h / 2 > vh || sx + w / 2 < 0 || sx - w / 2 > vw;
    entrar(PUXA, agora); // solta o cartão, se ela estava com um
    subindo = false;
    escala(A.kVoo);
    const r = setaTopo.getBoundingClientRect();
    puxa = { bx: r.left + r.width / 2, by: r.top + r.height / 2, y0: scrollY, tA: 0, tP: 0, S: null, rastro: [] };
    // fora da tela: começa escondida na borda direita, a mais perto do botão, e entra voando
    pos = fora ? { x: vw + (QW * k) / 2 + 10, y: clamp(puxa.by - QH * k, (QH * k) / 2, vh - (QH * k) / 2) } : { x: sx, y: sy };
    document.documentElement.style.scrollBehavior = "auto"; // o scrollTo de cada quadro tem de ser instantâneo
    segura = true;
  }

  function fimPuxada(ok, agora) {
    pos = { x: pos.x + scrollX, y: pos.y + scrollY }; // de volta às coordenadas da página
    document.documentElement.style.scrollBehavior = "";
    for (const p of pontos) p.style.opacity = "0";
    puxa = null;
    if (ok) {
      // solta a seta: cai 24 px e some em 150 ms; depois o botão volta a ficar escondido
      setaTopo.classList.add("solta");
      setaTopo.style.transform += " translateY(24px) scale(.7)";
      setTimeout(() => {
        topoBtn.classList.remove("ver", "puxando");
        topoBtn.hidden = true;
        setaTopo.classList.remove("solta");
        setaTopo.style.transform = "";
        segura = false;
        verBotao();
      }, 160);
      marca.focus({ preventScroll: true });
      entrar("pousada-no-hero", agora);
    } else {
      // cancelada: a seta volta ao botão em 150 ms e a zona do scroll decide o estado neste mesmo quadro
      setaTopo.classList.add("volta");
      setaTopo.style.transform = "";
      topoBtn.classList.remove("puxando");
      setTimeout(() => setaTopo.classList.remove("volta"), 160);
      segura = false;
      verBotao();
      estado = null;
      subindo = false;
    }
  }

  function quadroPuxada(agora) {
    const vw = innerWidth, vh = innerHeight, H = QH * k, W = QW * k;
    const naTela = (p) => ({ x: clamp(p.x, W / 2, vw - W / 2), y: clamp(p.y, H / 2, vh - H / 2) });
    const perto = naTela({ x: puxa.bx, y: puxa.by - H * PATA - 8 }); // patas logo acima da seta do botão
    const te = agora - t0;
    let seta = null; // onde a seta está na tela (null = no botão)
    if (!puxa.tA) {
      const resta = mover(perto, 0.3);
      if (te >= T_APROX && (resta < 2 || te >= T_APROX + 150)) {
        puxa.tA = agora;
        corpo.classList.add("pousou"); // squash leve ao agarrar
        topoBtn.classList.add("puxando");
      }
    } else if (agora < puxa.tA + T_AGARRA) {
      mover(perto, 0.3);
      q = 0; // asas para cima
      const u = suave((agora - puxa.tA) / T_AGARRA);
      seta = { x: lerp(puxa.bx, pos.x, u), y: lerp(puxa.by, pos.y + H * PATA, u) };
    } else {
      if (!puxa.tP) { puxa.tP = agora; puxa.S = { ...pos }; corpo.classList.remove("pousou"); }
      const u = clamp((agora - puxa.tP) / T_PUXA, 0, 1), e = easeInOutCubic(u);
      scrollTo(0, Math.round(puxa.y0 * (1 - e)));
      escala(e < 0.5 ? A.kVoo : A.kPuxa);
      // curva até um pouco acima do ninho (no topo, página = tela); a pousada desce o resto
      const E = naTela({ x: A.hero.x, y: A.hero.y - 120 });
      const S = puxa.S, arco = Math.sin(Math.PI * e) * Math.min(140, vh * 0.15);
      mover(naTela({ x: lerp(S.x, E.x, e), y: lerp(S.y, E.y, e) - arco }), 1);
      seta = { x: pos.x, y: pos.y + QH * k * PATA };
      if (u >= 1) return fimPuxada(true, agora);
    }
    if (seta) {
      setaTopo.style.transform = `translate(${Math.round(seta.x - puxa.bx)}px, ${Math.round(seta.y - puxa.by)}px)`;
      // rastro de pontos lima: posições antigas da seta durante a puxada
      if (puxa.tP) puxa.rastro.unshift(seta);
      pontos.forEach((p, i) => {
        const r = puxa.rastro[(i + 1) * 3];
        p.style.opacity = r ? String(0.8 - i * 0.14) : "0";
        if (r) p.style.transform = `translate(${Math.round(r.x)}px, ${Math.round(r.y)}px)`;
      });
      puxa.rastro.length = Math.min(puxa.rastro.length, 18);
    }
  }

  function alvoScroll(agora) {
    const y = scrollY, vh = innerHeight;
    const p = clamp(y / Math.max(1, A.oque.y - vh * 0.45), 0, 1), e = suave(p);
    const curva = Math.sin(p * Math.PI) * Math.min(160, innerWidth * 0.12);
    escala(Math.round(lerp(A.kHero, A.kVoo, e)));
    return { x: lerp(A.hero.x, A.oque.x, e) - curva, y: lerp(A.hero.y, A.oque.y, e) + Math.sin(agora / 380) * 6 };
  }

  function quadro(agora) {
    requestAnimationFrame(quadro);
    if (!A) return;
    const y = scrollY, vh = innerHeight, vw = innerWidth;

    // 1) transições (o único lugar que muda o estado); puxando-para-cima não consulta a zona
    if (!pos) { escala(A.kHero); pos = { ...A.hero }; }
    if (cancelar) { cancelar = false; if (estado === PUXA) fimPuxada(false, agora); }
    if (pedido) { pedido = false; if (estado !== PUXA) iniciarPuxada(agora); }
    const z = estado === PUXA ? null : zona();
    if (z === null) {
      // a puxada decide sozinha, pelo tempo
    } else if (z === "topo") {
      subindo = false;
      const alvo = y <= 4 ? "pousada-no-hero" : "voando-com-scroll";
      if (estado !== alvo) entrar(alvo, agora);
    } else if (z === "agentes") {
      if (estado === "pousada-no-download") { subindo = true; entrar("voando-com-scroll", agora); }
      else if (!subindo && (estado === null || estado === "pousada-no-hero" || estado === "voando-com-scroll")) entrar("pegando-agente", agora);
    } else if (estado !== "pousada-no-download") entrar("pousada-no-download", agora);

    // 2) fora da tela ela espera colada à borda: ao voltar, entra pela borda mais próxima.
    // A margem usa a maior escala e um passo, para ela não aparecer no mesmo quadro.
    const w = QW * k, h = QH * k, wm = QW * A.kHero + 2 * VMAX, hm = QH * A.kHero + 2 * VMAX;
    const fora = pos.y + h / 2 < y || pos.y - h / 2 > y + vh || pos.x + w / 2 < 0 || pos.x - w / 2 > vw;
    if (fora && estado !== PUXA) {
      pos.x = clamp(pos.x, -wm / 2 - 10, vw + wm / 2 + 10);
      pos.y = clamp(pos.y, y - hm / 2 - 10, y + vh + hm / 2 + 10);
    }

    // 3) movimento do estado atual
    q = Math.floor(agora / MS_QUADRO) % 6;
    visivel = true;
    if (estado === PUXA) {
      quadroPuxada(agora);
    } else if (estado === "pousada-no-hero" || estado === "voando-com-scroll") {
      mover(alvoScroll(agora), 0.35);
    } else if (estado === "pegando-agente") {
      escala(A.kVoo);
      const c = card.getBoundingClientRect();
      const alvo = { x: c.left + c.width / 2 + scrollX, y: c.top + scrollY - QH * k * 0.18 };
      if (mover(alvo, 0.12) < 2) {
        card.classList.add("pego");
        pega = { x: pos.x, y: pos.y };
        entrar("saindo-com-agente", agora); // continua segurando o cartão
      }
    } else if (estado === "saindo-com-agente") {
      if (!sumiu) {
        vSobe = Math.min(VMAX, vSobe * 1.07 + 0.3);
        pos.y -= vSobe;
        card.style.transform = `translate(${Math.round(pos.x - pega.x)}px, ${Math.round(pos.y - pega.y - 12)}px) rotate(${-7 + Math.sin((agora - t0) / 120) * 2}deg)`;
        if (pos.y + h / 2 < y) { // saiu por cima: some com o cartão; o cartão volta 0,9 s depois
          sumiu = true;
          const c = card;
          soltarCartao();
          c.classList.add("sumiu");
          timers.push(setTimeout(() => c.classList.remove("sumiu"), 900));
        }
      }
      if (sumiu) { visivel = false; pos.y = Math.min(pos.y, y - h / 2 - 10); }
    } else if (estado === "pousada-no-download") {
      // voa na escala de voo até o botão; ao chegar, troca para a pose pousada (squash) e fica
      escala(pousou ? A.kPouso : A.kVoo);
      const resta = mover({ x: A.pouso.x, y: A.pouso.y - (QH * k) / 2 }, pousou ? 0.5 : 0.12);
      if (!pousou && resta < 3) { pousou = true; escala(A.kPouso); corpo.classList.add("pousou"); }
      if (pousou) q = POUSADA;
    }

    // 4) desenho: por quadro, só transform e opacity
    const W = QW * k, H = QH * k, tela = estado === PUXA; // na puxada pos já está na tela
    atriz.style.transform = `translate3d(${Math.round(pos.x - (tela ? 0 : scrollX) - W / 2)}px, ${Math.round(pos.y - (tela ? 0 : scrollY) - H / 2)}px, 0)`;
    atriz.style.opacity = visivel ? "1" : "0";
    tira.style.transform = `translateX(${-q * W}px)`;

    // ---------- o resto da página no mesmo laço ----------
    if (y < A.heroFim) for (const el of pares) el.style.transform = `translateY(${(-y * el.dataset.par).toFixed(1)}px)`;
    for (let i = 0; i < revelas.length; i++) if (A.revelas[i] < y + vh * 0.88) revelas[i].classList.add("in");
    passos.classList.toggle("visivel", A.passos.y < y + vh && A.passos.y + A.passos.h > y);
    const demoNaTela = A.demo.y < y + vh && A.demo.y + A.demo.h > y;
    demo.classList.toggle("visivel", demoNaTela);
    if (demoNaTela) {
      if (!demoT0) demoT0 = agora;
      const t = (agora - demoT0) % 8000;
      const fase = t < 700 ? "vazio" : t < 3600 ? "ouvindo" : t < 5000 ? "transcrevendo" : "colado";
      if (demo.dataset.fase !== fase) {
        demo.dataset.fase = fase;
        const ativo = { vazio: 0, ouvindo: 1, transcrevendo: 1, colado: 2 }[fase];
        linha.forEach((li, i) => li.classList.toggle("ativo", i === ativo));
      }
    } else demoT0 = 0;
  }
  let demoT0 = 0;

  medir();
  document.fonts.ready.then(medir);
  addEventListener("load", medir);
  addEventListener("resize", medir);
  new ResizeObserver(medir).observe(document.body);
  requestAnimationFrame(quadro);

  // contador real de downloads (.exe, .dmg e .AppImage); some se a API falhar ou der 0
  fetch("https://api.github.com/repos/davimatosms/Arara-voice/releases?per_page=100")
    .then((r) => (r.ok ? r.json() : Promise.reject()))
    .then((rels) => {
      const n = rels.flatMap((r) => r.assets).filter((a) => /\.(exe|dmg|AppImage)$/i.test(a.name)).reduce((t, a) => t + a.download_count, 0);
      if (!n) return;
      $("#contador-n").textContent = n.toLocaleString("pt-BR");
      $("#contador").hidden = false;
    })
    .catch(() => {});
})();
