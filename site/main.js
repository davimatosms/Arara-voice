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

  if (!anim) return;

  // =====================================================================
  // A ATRIZ: um só elemento de arara, uma máquina de estados explícita.
  //   pousada-no-hero -> voando-com-scroll -> pegando-agente -> saindo-com-agente -> pousada-no-download
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
      kHero, kVoo: estreito ? 2 : 3, kPouso: estreito ? 3 : 4,
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

    // 1) transições (o único lugar que muda o estado)
    const z = zona();
    if (z === "topo") {
      subindo = false;
      const alvo = y <= 4 ? "pousada-no-hero" : "voando-com-scroll";
      if (estado !== alvo) entrar(alvo, agora);
    } else if (z === "agentes") {
      if (estado === "pousada-no-download") { subindo = true; entrar("voando-com-scroll", agora); }
      else if (!subindo && (estado === null || estado === "pousada-no-hero" || estado === "voando-com-scroll")) entrar("pegando-agente", agora);
    } else if (estado !== "pousada-no-download") entrar("pousada-no-download", agora);

    if (!pos) { escala(A.kHero); pos = { ...A.hero }; }

    // 2) fora da tela ela espera colada à borda: ao voltar, entra pela borda mais próxima.
    // A margem usa a maior escala e um passo, para ela não aparecer no mesmo quadro.
    const w = QW * k, h = QH * k, wm = QW * A.kHero + 2 * VMAX, hm = QH * A.kHero + 2 * VMAX;
    const fora = pos.y + h / 2 < y || pos.y - h / 2 > y + vh || pos.x + w / 2 < 0 || pos.x - w / 2 > vw;
    if (fora) {
      pos.x = clamp(pos.x, -wm / 2 - 10, vw + wm / 2 + 10);
      pos.y = clamp(pos.y, y - hm / 2 - 10, y + vh + hm / 2 + 10);
    }

    // 3) movimento do estado atual
    q = Math.floor(agora / MS_QUADRO) % 6;
    visivel = true;
    if (estado === "pousada-no-hero" || estado === "voando-com-scroll") {
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
    const W = QW * k, H = QH * k;
    atriz.style.transform = `translate3d(${Math.round(pos.x - scrollX - W / 2)}px, ${Math.round(pos.y - y - H / 2)}px, 0)`;
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
})();
