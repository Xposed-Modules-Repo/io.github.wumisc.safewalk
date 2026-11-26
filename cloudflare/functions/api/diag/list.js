// SafeWalk 诊断日志上报 · 查看接口（Cloudflare Pages Functions）。
//
// 路由：GET /api/diag/list
// 鉴权：与上传同一 Bearer 令牌（env.UPLOAD_TOKEN）；如后续需要，可拆出独立 ADMIN_TOKEN。
// 仅返回最近 100 条的摘要与日志头部，完整日志按 id 另行查询。

const MAX_ROWS = 100;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function isAuthorized(request, env) {
  const expected = env.UPLOAD_TOKEN;
  if (!expected) return false;
  const header = request.headers.get("Authorization") || "";
  return header === `Bearer ${expected}`;
}

export async function onRequestGet({ request, env }) {
  if (!isAuthorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  try {
    const { results } = await env.DB.prepare(
      `SELECT id, ts, package, version_name, version_code, manufacturer, model,
              release, sdk, ui, note, substr(log, 1, 200) AS log_head
         FROM diag_reports
        ORDER BY ts DESC
        LIMIT ?`
    ).bind(MAX_ROWS).all();

    return json({ ok: true, reports: results || [] });
  } catch (e) {
    return json({ ok: false, error: "db error" }, 500);
  }
}

export function onRequest() {
  return json({ ok: false, error: "method not allowed" }, 405);
}
