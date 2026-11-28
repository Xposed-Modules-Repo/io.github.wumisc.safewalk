// SafeWalk 公益照片镜像 · 读取接口（Cloudflare Pages Functions）。
//
// 路由：GET /api/kidimg?u=<encodeURIComponent(原始照片地址)>
//
// 只代理**白名单主机**上的图片：数据源（antiburn-missing-kids）里的照片来自
// baobeihuijia.com，上游 JSON 也可能引用 GitHub raw。其他主机一律拒绝，
// 不做开放代理（避免被拿去当跳板）。
//
// 图片由边缘缓存 7 天：同一张脸反复轮到时不回源，省流量也省上游。

const ALLOW_HOSTS = new Set([
  "baobeihuijia.com",
  "www.baobeihuijia.com",
  "raw.githubusercontent.com",
  "cdn.jsdelivr.net",
]);

const CACHE_SECONDS = 7 * 24 * 3600;

function err(status, error) {
  return new Response(JSON.stringify({ ok: false, error }), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

export async function onRequestGet({ request }) {
  const raw = new URL(request.url).searchParams.get("u") || "";
  let target;
  try {
    target = new URL(raw);
  } catch (_) {
    return err(400, "bad_url");
  }
  if (target.protocol !== "https:" || !ALLOW_HOSTS.has(target.hostname)) {
    return err(403, "host_not_allowed");
  }

  const cache = caches.default;
  const key = new Request(request.url, { method: "GET" });
  const hit = await cache.match(key);
  if (hit) return hit;

  try {
    const res = await fetch(target.toString(), {
      headers: { "User-Agent": "SafeWalk-mirror/1.0" },
      cf: { cacheTtl: CACHE_SECONDS, cacheEverything: true },
    });
    if (!res.ok) return err(502, `upstream_${res.status}`);
    const out = new Response(res.body, {
      status: 200,
      headers: {
        "Content-Type": res.headers.get("Content-Type") || "image/png",
        "Cache-Control": `public, max-age=${CACHE_SECONDS}`,
      },
    });
    await cache.put(key, out.clone());
    return out;
  } catch (_) {
    return err(502, "upstream_unavailable");
  }
}
