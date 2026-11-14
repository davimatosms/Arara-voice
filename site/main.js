// Landing do Arara: animações no scroll, sem dependências.
// Sem .anim (prefers-reduced-motion ou sem JS) a página fica estática e legível.
(() => {
  "use strict";
  const $ = (s) => document.querySelector(s);
  const anim = document.documentElement.classList.contains("anim");

  // ondas do passo 2 (alturas fixas, atrasos variados)
  const alturas = [10, 22, 34, 18, 44, 28, 14, 38, 48, 20, 12, 26, 40, 22, 46, 30, 16, 36, 48, 24, 14];
  $(".ondas").innerHTML = alturas.map((h, i) => `<i style="--h:${h}px;--atraso:-${(i * 0.37) % 1}s"></i>`).join("");
  if (!anim) return;

  // ---------- entradas: telas do app e passos ----------
  const revela = new IntersectionObserver((es) => {
    for (const e of es) if (e.isIntersecting) { e.target.classList.add("in"); revela.unobserve(e.target); }
  }, { threshold: 0.2 });
  document.querySelectorAll(".revela").forEach((el) => revela.observe(el));

  // ---------- a arara do hero pisca de vez em quando ----------
  const heroAve = $("#hero-ave");
  const pisca = () => {
    heroAve.src = "assets/img/piscar.png";
    setTimeout(() => { heroAve.src = "assets/img/arara-galho.png"; }, 160);
    setTimeout(pisca, 3000 + Math.random() * 2000);
  };
  new Image().src = "assets/img/piscar.png";
  setTimeout(pisca, 2500);

  // ---------- a arara que voa ----------
  const ave = $("#ave"), logo = $("#logo-ave"), vaga = $("#vaga-ave");
  const secAgentes = $("#agentes"), cards = [...document.querySelectorAll(".card")];
  const baixar = $(".baixar"), pousada = $("#ave-pousada");
  const QW = 81, QH = 58, QUADROS = 6, MS_QUADRO = 90;

  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const suave = (t) => (t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2);
  const centro = (el) => { const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, r }; };
  const escalaMax = () => (innerWidth <= 900 ? 2 : 3);

  let modo = "scroll"; // scroll | pega | sumiu | pousa | pousado
  let pos = { x: -999, y: -999, s: 1, visivel: false }; // centro na viewport
  let anima = null; // animação por tempo: função (agora) -> terminou?
  let ultimoAgente = -1, podePegar = true, querPousar = false;

  function desenha(agora) {
    const s = pos.s, w = Math.round(QW * s), h = Math.round(QH * s);
    const q = Math.floor(agora / MS_QUADRO) % QUADROS;
    ave.style.width = w + "px";
    ave.style.height = h + "px";
    ave.style.backgroundSize = `${w * QUADROS}px ${h}px`;
    ave.style.backgroundPosition = `${-q * w}px 0`;
    ave.style.transform = `translate3d(${Math.round(pos.x - w / 2)}px, ${Math.round(pos.y - h / 2)}px, 0)`;
    ave.style.opacity = pos.visivel ? 1 : 0;
  }

  // 1. do logo até a seção 2, ligada ao scroll
  function vooDoScroll(agora) {
    const y = scrollY, vh = innerHeight;
    const a = centro(logo), b = centro(vaga);
    const ax = a.x, ay = a.y + y, bx = b.x, by = b.y + y; // coordenadas da página
    const fim = Math.max(1, by - vh / 2); // scroll em que a vaga fica no meio da tela
    const p = clamp(y / fim, 0, 1), e = suave(p);
    const curva = Math.sin(p * Math.PI * 2) * Math.min(160, innerWidth * 0.12);
    const flutua = Math.sin(agora / 380) * 6 * e;
    pos = { x: lerp(ax, bx, e) + curva, y: lerp(ay, by, e) - y + flutua, s: lerp(1, escalaMax(), e), visivel: y > 4 };
    logo.classList.toggle("solta", y > 4);
  }

  // 2. pega um agente aleatório e sai voando com ele
  function pegaAgente() {
    let i;
    do i = Math.floor(Math.random() * cards.length); while (cards.length > 1 && i === ultimoAgente);
    ultimoAgente = i;
    const card = cards[i];
    const de = pos.visivel && pos.y > -50 && pos.y < innerHeight + 50 ? { ...pos } : { x: innerWidth * 0.5, y: -80, s: escalaMax() };
    const s = escalaMax(), t0 = performance.now();
    const alvo = () => { const c = centro(card); return { x: c.x, y: c.r.top - QH * s * 0.32 }; };
    modo = "pega";
    logo.classList.add("solta");
    anima = (agora) => {
      const t = agora - t0;
      if (t < 1000) { // voa até o card
        const k = suave(t / 1000), a = alvo();
        pos = { x: lerp(de.x, a.x, k), y: lerp(de.y, a.y, k) - Math.sin(k * Math.PI) * 60, s: lerp(de.s, s, k), visivel: true };
        return false;
      }
      if (!card.classList.contains("pega")) card.classList.add("pega"); // agarrou: o card levanta e inclina
      if (t >= 1350) { // sobe com o card até sumir
        const dt = t - 1350, dy = 0.0005 * dt * dt + 0.1 * dt;
        card.style.transition = "transform 0s";
        card.style.transform = `translateY(${-12 - dy}px) rotate(${-6 + Math.sin(dt / 120) * 2}deg)`;
        if (card.getBoundingClientRect().bottom < -20 || dt > 4000) {
          card.classList.remove("pega");
          card.classList.add("sumiu");
          card.style.transform = "";
          setTimeout(() => {
            card.style.transition = "";
            card.classList.replace("sumiu", "volta");
            card.addEventListener("animationend", () => card.classList.remove("volta"), { once: true });
          }, 900);
          pos.visivel = false;
          modo = "sumiu";
          return true;
        }
      }
      const a = alvo();
      pos = { x: a.x, y: a.y + Math.sin(t / 120) * 3, s, visivel: true };
      return false;
    };
  }

  // 5. volta e pousa no botão de download
  function pousa() {
    querPousar = false;
    const s0 = escalaMax(), t0 = performance.now();
    const de = pos.visivel ? { ...pos } : { x: innerWidth * 0.75, y: -80, s: s0 };
    modo = "pousa";
    anima = (agora) => {
      const k = suave(clamp((agora - t0) / 1300, 0, 1)), c = centro(pousada);
      pos = { x: lerp(de.x, c.x, k), y: lerp(de.y, c.y - 10, k) - Math.sin(k * Math.PI) * 80, s: lerp(de.s, s0, k), visivel: true };
      if (k < 1) return false;
      pos.visivel = false;
      pousada.classList.add("pousou");
      modo = "pousado";
      return true;
    };
  }

  new IntersectionObserver(([e]) => {
    if (!e.isIntersecting) { podePegar = true; return; }
    if (podePegar && (modo === "scroll" || modo === "sumiu")) { podePegar = false; pegaAgente(); }
  }, { threshold: 0.6 }).observe($(".cards"));

  new IntersectionObserver(([e]) => {
    if (e.isIntersecting && modo !== "pousa" && modo !== "pousado") querPousar = true;
  }, { threshold: 1, rootMargin: "0px 0px -12% 0px" }).observe($("#btn-baixar"));

  function quadro(agora) {
    if (anima) { if (anima(agora)) anima = null; }
    else {
      if (querPousar && modo !== "pega") pousa();
      // longe da seção 6 de novo: a arara do botão volta a voar
      if (modo === "pousado" && baixar.getBoundingClientRect().top > innerHeight + 200) { pousada.classList.remove("pousou"); modo = "sumiu"; }
      // acima da seção 3 de novo: volta a seguir o scroll
      if (modo === "sumiu" && secAgentes.getBoundingClientRect().top > innerHeight) modo = "scroll";
      if (modo === "scroll") vooDoScroll(agora);
      else if (!anima) pos.visivel = false;
    }
    desenha(agora);
    requestAnimationFrame(quadro);
  }
  requestAnimationFrame(quadro);
})();
