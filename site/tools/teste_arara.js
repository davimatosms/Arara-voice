// Teste headless da arara da landing (e as screenshots da v2).
//
// Garante, quadro a quadro, que:
//   1. nunca há mais de 1 arara animada visível (a #atriz e qualquer outro elemento de arara fora
//      dos prints do app, com opacity > 0 e dentro da viewport);
//   2. a arara nunca salta mais de SALTO_MAX px (em coordenadas da página) entre dois quadros
//      seguidos em que está na tela, ou seja, nunca teleporta.
// Padrões: passo a passo até o fim, rápido até o fim, de volta ao topo (rápido e passo a passo),
// pulos pelas âncoras do menu e resize 1440 -> 390 -> 1440, em 1440 e em 390 de largura.
//
// Dependência fora do repo: playwright-core e um Chromium/Edge instalado.
//   npm i --prefix "%TEMP%\arara-pw" playwright-core
//   set NODE_PATH=%TEMP%\arara-pw\node_modules        (bash: export NODE_PATH=...)
//   node site/tools/teste_arara.js                     -> teste (sai com código 1 se falhar)
//   node site/tools/teste_arara.js --fotos site/screenshots/v2   -> screenshots por seção e do voo
// O navegador padrão é o Edge do Windows; outro: ARARA_NAVEGADOR=caminho\do\chrome.exe
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright-core");

const SITE = path.resolve(__dirname, "..");
const NAVEGADOR = process.env.ARARA_NAVEGADOR || "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe";
const SALTO_MAX = 40; // px por quadro; a atriz anda no máximo 36 (VMAX em main.js)
const TIPOS = { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".png": "image/png", ".svg": "image/svg+xml" };
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

function servir() {
  const srv = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split("?")[0]).replace(/^\/+/, "") || "index.html";
    const arq = path.join(SITE, rel);
    if (!arq.startsWith(SITE) || !fs.existsSync(arq) || fs.statSync(arq).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { "Content-Type": TIPOS[path.extname(arq)] || "application/octet-stream" });
    fs.createReadStream(arq).pipe(res);
  });
  return new Promise((ok) => srv.listen(0, "127.0.0.1", () => ok(srv)));
}

// roda na página: amostra cada quadro de animação
function instalarAmostrador() {
  const ARARA = /atriz|arara|folha-voo|pousada|galho|piscar/i;
  const ehArara = (el) => {
    if (el.id === "atriz") return true;
    const src = el.currentSrc || el.getAttribute("src") || "";
    if (src.includes("/telas/")) return false; // prints reais do app são conteúdo
    if (ARARA.test(src)) return true;
    const bg = getComputedStyle(el).backgroundImage;
    return bg !== "none" && ARARA.test(bg) && !bg.includes("/telas/");
  };
  window.__amostras = [];
  const tick = () => {
    const vw = innerWidth, vh = innerHeight;
    const todos = [...document.body.querySelectorAll("*")].filter(ehArara);
    const raizes = todos.filter((el) => !todos.some((o) => o !== el && o.contains(el)));
    let n = 0;
    for (const el of raizes) {
      const r = el.getBoundingClientRect();
      const naTela = r.width > 0 && r.height > 0 && r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh;
      const op = el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
      if (naTela && op && parseFloat(getComputedStyle(el).opacity) > 0) n++;
    }
    const a = document.getElementById("atriz");
    const r = a.getBoundingClientRect();
    const vis = r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh && parseFloat(getComputedStyle(a).opacity) > 0;
    __amostras.push({ n, x: r.left + r.width / 2 + scrollX, y: r.top + r.height / 2 + scrollY, vis, e: a.dataset.estado || "", vw, sy: Math.round(scrollY) });
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

async function abrir(nav, url, w, h) {
  const p = await nav.newPage({ viewport: { width: w, height: h } });
  const erros = [];
  p.on("pageerror", (e) => erros.push(e.message));
  p.on("console", (m) => m.type() === "error" && erros.push(m.text()));
  await p.goto(url, { waitUntil: "networkidle" });
  await p.evaluate(() => document.fonts.ready);
  await espera(400);
  return { p, erros };
}

const rolar = (p, y) => p.evaluate((y) => window.scrollTo({ top: y, behavior: "instant" }), y);
const alturaMax = (p) => p.evaluate(() => document.documentElement.scrollHeight - innerHeight);

const PADROES = {
  async "passo a passo até o fim"(p) { const m = await alturaMax(p); for (let y = 0; y <= m + 120; y += 120) { await rolar(p, y); await espera(70); } await espera(1500); },
  async "rápido até o fim"(p) { await rolar(p, 0); await espera(600); await rolar(p, 1e6); await espera(3000); },
  async "de volta ao topo (rápido)"(p) { await rolar(p, 1e6); await espera(2500); await rolar(p, 0); await espera(2500); },
  async "de volta ao topo (passo a passo)"(p) { await rolar(p, 1e6); await espera(2500); const m = await alturaMax(p); for (let y = m; y >= -150; y -= 150) { await rolar(p, y); await espera(70); } await espera(1500); },
  async "pulos pelas âncoras do menu"(p) {
    for (const id of ["#como-funciona", "#agentes", "#demo", "#app", "#agentes", "#baixar", "#demo", "#como-funciona"]) {
      await p.evaluate((id) => { location.hash = ""; document.querySelector(id).scrollIntoView({ behavior: "smooth" }); }, id);
      await espera(1800);
    }
    await rolar(p, 0); await espera(1500);
  },
  async "resize 1440 → 390 → 1440"(p) {
    for (const f of [0, 0.25, 0.45, 0.7, 1]) {
      await p.setViewportSize({ width: 1440, height: 900 }); await espera(300);
      await rolar(p, (await alturaMax(p)) * f); await espera(1200);
      await p.setViewportSize({ width: 390, height: 844 }); await espera(1500);
      await p.setViewportSize({ width: 1440, height: 900 }); await espera(1500);
    }
  },
};

async function teste(nav, url) {
  const linhas = [];
  let falhou = false;
  for (const [w, h] of [[1440, 900], [390, 844]]) {
    for (const [nome, fn] of Object.entries(PADROES)) {
      if (w === 390 && nome.startsWith("resize")) continue; // o resize já parte de 1440 e passa por 390
      if (process.env.ARARA_PADRAO && !nome.includes(process.env.ARARA_PADRAO)) continue; // roda só um padrão
      const { p, erros } = await abrir(nav, url, w, h);
      await p.evaluate(instalarAmostrador);
      await fn(p);
      const am = await p.evaluate(() => window.__amostras);
      let maxN = 0, maxSalto = 0, saltos = 0;
      const estados = new Set();
      for (let i = 0; i < am.length; i++) {
        maxN = Math.max(maxN, am[i].n);
        if (am[i].e) estados.add(am[i].e);
        if (i && am[i].vis && am[i - 1].vis) {
          const d = Math.hypot(am[i].x - am[i - 1].x, am[i].y - am[i - 1].y);
          maxSalto = Math.max(maxSalto, d);
          if (d > SALTO_MAX && ++saltos <= 3) console.log("  salto", JSON.stringify(am[i - 1]), "->", JSON.stringify(am[i]));
        }
      }
      const ok = maxN <= 1 && saltos === 0 && erros.length === 0;
      falhou ||= !ok;
      linhas.push(`| ${w} | ${nome} | ${am.length} | ${maxN} | ${maxSalto.toFixed(1)} | ${saltos} | ${[...estados].join(", ")} | ${ok ? "ok" : "FALHOU"}${erros.length ? " (" + erros.join("; ") + ")" : ""} |`);
      console.log(linhas.at(-1));
      await p.close();
    }
  }
  return { linhas, falhou };
}

async function fotos(nav, url, saida) {
  fs.mkdirSync(saida, { recursive: true });
  for (const [w, h] of [[1440, 900], [390, 844]]) {
    const { p, erros } = await abrir(nav, url, w, h);
    const foto = (n) => p.screenshot({ path: path.join(saida, `${w}-${n}.png`) });
    const topo = (s, off = 0) => p.evaluate(([s, o]) => document.querySelector(s).getBoundingClientRect().top + scrollY - o, [s, off]);
    const ir = async (y, ms = 900) => { await rolar(p, y); await espera(ms); };
    await espera(600); await foto("1-hero");
    const fimVoo = await topo(".rastro", h * 0.45);
    for (let y = 0; y <= fimVoo * 0.5; y += 40) await ir(y, 40);
    await espera(400); await foto("voo-1-descendo");
    for (let y = fimVoo * 0.5; y <= fimVoo; y += 40) await ir(y, 40);
    await espera(600);
    await ir(await topo(".s-oque"), 900); await foto("2-o-que-e");
    await ir(await topo("#demo"), 5600); await foto("3-demo-colado");
    await espera(2600); await foto("3-demo-ouvindo");
    await ir(await topo("#agentes") - 1, 50);
    for (let i = 0; i < 40; i++) { await ir((await topo("#agentes")) + i * 6, 30); if (await p.evaluate(() => document.querySelector("#atriz").dataset.estado === "pegando-agente")) break; }
    await p.waitForFunction(() => document.querySelector("#atriz").dataset.estado === "saindo-com-agente", null, { timeout: 8000 }).catch(() => {});
    await espera(150); await foto("voo-2-pegou-agente");
    await espera(500); await foto("voo-3-saindo-com-agente");
    await espera(2500); await foto("4-agentes");
    await ir(await topo("#app"), 1500); await foto("5-app");
    await ir(await topo("#como-funciona"), 1200); await foto("6-como-funciona");
    await ir(1e6, 250); await foto("voo-4-chegando-no-download");
    await espera(2500); await foto("7-download-rodape");
    await p.setViewportSize({ width: w, height: h });
    await rolar(p, 0); await espera(300);
    await p.screenshot({ path: path.join(saida, `${w}-pagina-inteira.png`), fullPage: true });
    if (erros.length) console.log("erros", w, erros);
    await p.close();
  }
  // movimento reduzido: tudo estático e legível
  const ctx = await nav.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });
  const p = await ctx.newPage();
  await p.goto(url, { waitUntil: "networkidle" });
  await espera(500);
  await p.screenshot({ path: path.join(saida, "390-reduzido-pagina-inteira.png"), fullPage: true });
  await ctx.close();
}

(async () => {
  const srv = await servir();
  const url = `http://127.0.0.1:${srv.address().port}/`;
  const nav = await chromium.launch({ executablePath: NAVEGADOR, headless: true });
  let codigo = 0;
  try {
    const i = process.argv.indexOf("--fotos");
    if (i > 0) await fotos(nav, url, path.resolve(process.argv[i + 1] || "site/screenshots/v2"));
    else {
      console.log("| largura | padrão | quadros | araras visíveis (máx) | maior salto (px) | saltos > " + SALTO_MAX + " px | estados | resultado |");
      console.log("|---|---|---|---|---|---|---|---|");
      const { falhou } = await teste(nav, url);
      codigo = falhou ? 1 : 0;
      console.log(falhou ? "\nFALHOU" : "\nOK: sempre ≤ 1 arara visível e nenhum teletransporte");
    }
  } finally {
    await nav.close();
    srv.close();
  }
  process.exit(codigo);
})();
