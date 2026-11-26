// SafeWalk 诊断日志上报 · 接收接口（Cloudflare Pages Functions）。
//
// 路由：POST /api/diag/upload
// 鉴权：请求头 `Authorization: Bearer <env.UPLOAD_TOKEN>`。
// 存储：绑定的 D1 数据库（binding: DB），表结构见 ../schema.sql。
//
// 红线：只做最小化接收与落库；不做业务逻辑、不回传任何设备数据给第三方。

const MAX_BODY = 64 * 1024; // 整包上限 64KB
const MAX_LOG = 60_000;     // 日志正文上限（与端侧一致）

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

function str(v) {
  return typeof v === "string" ? v.slice(0, 200) : "";
}

function int(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

export async function onRequestPost({ request, env }) {
  if (!isAuthorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  let body;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY) return json({ ok: false, error: "payload too large" }, 413);
    body = JSON.parse(text);
  } catch {
    return json({ ok: false, error: "bad json" }, 400);
  }

  const device = body.device || {};
  const app = body.app || {};

  const mfr = str(device.manufacturer);
  const model = str(device.model);

  let log = typeof body.log === "string" ? body.log : "";
  if (log.length > MAX_LOG) log = log.slice(-MAX_LOG);
  const note = typeof body.note === "string" ? body.note.slice(0, 500) : "";
  const ts = Number(body.ts) || Date.now();

  try {
    const stmt = env.DB.prepare(
      `INSERT INTO diag_reports
         (ts, package, version_name, version_code, manufacturer, model, device, product,
          release, sdk, ui, note, log, client_ip)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      ts,
      str(app.package),
      str(app.versionName),
      int(app.versionCode),
      mfr,
      model,
      str(device.device),
      str(device.product),
      str(device.release),
      int(device.sdk),
      str(device.ui),
      note,
      log,
      request.headers.get("cf-connecting-ip") || ""
    );

    await stmt.run();

    // 按机型各留最新一条：同 manufacturer+model 只保留时间最新（id 兜底）的记录，
    // 其余旧记录连带删除。新上报顶掉同型号历史反馈，库里不堆旧版本日志。
    const clean = env.DB.prepare(
      `DELETE FROM diag_reports
         WHERE manufacturer = ? AND model = ?
           AND id NOT IN (
             SELECT id FROM diag_reports
             WHERE manufacturer = ? AND model = ?
             ORDER BY ts DESC, id DESC LIMIT 1
           )`
    ).bind(mfr, model, mfr, model);
    await clean.run();

    return json({ ok: true });
  } catch (e) {
    return json({ ok: false, error: "db error" }, 500);
  }
}

// 非 POST 方法一律拒绝，避免静态路径误触发。
export function onRequest() {
  return json({ ok: false, error: "method not allowed" }, 405);
}
