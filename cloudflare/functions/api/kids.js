// SafeWalk 公益数据镜像 · 读取接口（Cloudflare Pages Functions）。
//
// 路由：GET /api/kids  →  原样返回上游 kids.json
//
// 为什么需要这一层：真机日志里 raw.githubusercontent.com 与 cdn.jsdelivr.net
// 在国内网络常年被 reset（`[kids] JSON 下载异常`），App 只能退回包内快照，
// 用户看到的永远是同一批孩子。Pages 域名在国内可达，由边缘节点去取上游，
// 取到后按 1 小时缓存，请求量也压到最低。
//
// 这是公开只读数据（宝贝回家公开寻亲信息），不需要令牌。

const UPSTREAM = [
  "https://raw.githubusercontent.com/wumisc/antiburn-missing-kids/main/kids.json",
  "https://cdn.jsdelivr.net/gh/wumisc/antiburn-missing-kids@main/kids.json",
];

const CACHE_SECONDS = 3600;

function fail(status, error) {
  return new Response(JSON.stringify({ ok: false, error }), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
    },
  });
}

export async function onRequestGet({ request }) {
  // 边缘缓存命中直接返回，不打上游
  const cache = caches.default;
  const cached = await cache.match(request);
  if (cached) return cached;

  for (const url of UPSTREAM) {
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": "SafeWalk-mirror/1.0" },
        cf: { cacheTtl: CACHE_SECONDS, cacheEverything: true },
      });
      if (!res.ok) continue;
      const body = await res.text();
      // 只回上游原样 JSON：客户端解析逻辑完全不变
      const out = new Response(body, {
        status: 200,
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": `public, max-age=${CACHE_SECONDS}`,
          "Access-Control-Allow-Origin": "*",
        },
      });
      await cache.put(request, out.clone());
      return out;
    } catch (_) {
      // 换下一个源
    }
  }
  return fail(502, "upstream_unavailable");
}
