// Soma downloads das releases; o CDN da Vercel guarda 1h, então o GitHub recebe ~1 chamada/h.
module.exports = async (req, res) => {
  const h = { "User-Agent": "arara-site", Accept: "application/vnd.github+json" };
  if (process.env.GITHUB_TOKEN) h.Authorization = "Bearer " + process.env.GITHUB_TOKEN;
  try {
    const r = await fetch("https://api.github.com/repos/davimatosms/Arara-voice/releases?per_page=100", { headers: h });
    if (!r.ok) throw new Error(r.status);
    const total = (await r.json()).flatMap((x) => x.assets).filter((a) => /\.(exe|dmg|AppImage)$/i.test(a.name)).reduce((t, a) => t + a.download_count, 0);
    res.setHeader("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    res.status(200).json({ total });
  } catch (e) {
    res.setHeader("Cache-Control", "no-store");
    res.status(502).json({ error: "github" });
  }
};
