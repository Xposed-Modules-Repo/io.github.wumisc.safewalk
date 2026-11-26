// SafeWalk 诊断日志上报 · 接收接口（Cloudflare Pages Functions）。
//
// 路由：POST /api/diag/upload
// 鉴权：请求头 `Authorization: Bearer <env.UPLOAD_TOKEN>`。
// 存储：绑定的 D1 数据库（binding: DB），表结构见 ../schema.sql。
//
// 红线：只做最小化接收与落库；不做业务逻辑、不回传任何设备数据给第三方。
//
// ## 为什么要四道闸（5.7.6 · 128 补上）
//
// 令牌写在 APK 里，反编译就能拿到；免费计划的额度又是全账号共享的
// （Workers 100k 请求/天、D1 100k 行写入/天、5M 行读取/天、5GB 存储）。
// 没有闸的话，一个脚本循环 POST 几个小时就能把当天请求与写入双额度打满，
// 之后真实用户的上传全部 500 —— 花钱倒不会花（免费计划超额直接报错），
// 但当天真正的反馈就全丢了，而且没有任何告警告诉你。
//
// 四道闸，从便宜到贵依次执行，任何一道拦下都直接返回，不再碰数据库：
//   1. 鉴权：Bearer 令牌（挡随手写请求）。
//   2. 单 IP 每日 5 条（upload_quota 表，一次 upsert 计数；挡刷接口）。
//   3. 单机型 + 同日志哈希 10 分钟内去重（挡原地重放 / 端侧重试）。
//   4. 全表 500 行上限（挡随机编机型名把 5GB 存储撑穿）。
// 额度计数只在真正落库成功后才 +1，失败不占用户配额。

const MAX_BODY = 64 * 1024;   // 整包上限 64KB（端侧只会发 ≤60KB 日志）
const MAX_LOG = 60_000;       // 日志正文上限（与端侧一致）
const MAX_ROWS = 500;         // 全表行数上限
const IP_DAILY_LIMIT = 5;     // 单 IP 每日上传次数上限
const DEDUPE_WINDOW_MS = 10 * 60 * 1000;

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

/** 机型名只允许常规字符，且不超过 60 字 —— 挡「随机字符串当机型」。 */
function modelKey(v) {
  return str(v).trim().slice(0, 60).replace(/[\u0000-\u001f]/g, "");
}

function int(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** 当天（UTC）日期串，用于按天分桶限额。 */
function todayKey() {
  return new Date().toISOString().slice(0, 10);
}

export async function onRequestPost({ request, env }) {
  if (!isAuthorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const ip = (request.headers.get("cf-connecting-ip") || "unknown").slice(0, 64);
  const day = todayKey();

  // 闸 2：单 IP 每日上限。先查，超了直接返回，连 body 都不解析。
  try {
    const row = await env.DB.prepare(
      `SELECT n FROM upload_quota WHERE ip = ? AND day = ?`
    ).bind(ip, day).first();
    if (row && Number(row.n) >= IP_DAILY_LIMIT) {
      return json({ ok: false, error: "daily quota reached" }, 429);
    }
  } catch {
    // 计数表读失败不该挡住正常上报：放行，由后面的闸兜底。
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

  const mfr = modelKey(device.manufacturer);
  const model = modelKey(device.model);
  if (!model) return json({ ok: false, error: "model required" }, 400);

  let log = typeof body.log === "string" ? body.log : "";
  if (log.length > MAX_LOG) log = log.slice(-MAX_LOG);
  const note = typeof body.note === "string" ? body.note.slice(0, 500) : "";
  const ts = Number(body.ts) || Date.now();
  const hash = await sha256(`${mfr}|${model}|${log}`);

  try {
    // 闸 3：同机型 + 同内容 10 分钟内不重复收（端侧重试 / 原地重放都挡）。
    const dup = await env.DB.prepare(
      `SELECT id FROM diag_reports
         WHERE manufacturer = ? AND model = ? AND log_hash = ? AND ts >= ?
         LIMIT 1`
    ).bind(mfr, model, hash, Date.now() - DEDUPE_WINDOW_MS).first();
    if (dup) {
      return json({ ok: true, deduped: true });
    }

    // 闸 4：全表行数上限。正常保留策略下真实设备撑不到这个数，
    // 一旦逼近说明有人在编机型名刷存储。
    const total = await env.DB.prepare(
      `SELECT COUNT(*) AS c FROM diag_reports`
    ).first();
    if (total && Number(total.c) >= MAX_ROWS) {
      return json({ ok: false, error: "storage full" }, 429);
    }

    const stmt = env.DB.prepare(
      `INSERT INTO diag_reports
         (ts, package, version_name, version_code, manufacturer, model, device, product,
          release, sdk, ui, note, log, log_hash, client_ip)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
      hash,
      ip
    );

    await stmt.run();

    // 保留策略：按厂商 + 机型分组，只认最新 versionCode 的记录；在最新版本内留最新 3 条。
    // 旧版本记录一律删除；同最新版本超出 3 条的删最旧的。新上报写入后连带清理。
    const clean = env.DB.prepare(
      `DELETE FROM diag_reports
         WHERE manufacturer = ? AND model = ?
           AND (
             version_code <> (
               SELECT MAX(version_code) FROM diag_reports
               WHERE manufacturer = ? AND model = ?
             )
             OR id NOT IN (
               SELECT id FROM (
                 SELECT id FROM diag_reports
                 WHERE manufacturer = ? AND model = ?
                   AND version_code = (
                     SELECT MAX(version_code) FROM diag_reports
                     WHERE manufacturer = ? AND model = ?
                   )
                 ORDER BY ts DESC, id DESC LIMIT 3
               )
             )
           )`
    ).bind(mfr, model, mfr, model, mfr, model, mfr, model);
    await clean.run();

    // 落库成功才计数；顺手清掉前一天的桶，避免计数表自己长大。
    await env.DB.prepare(
      `INSERT INTO upload_quota (ip, day, n) VALUES (?, ?, 1)
         ON CONFLICT(ip, day) DO UPDATE SET n = n + 1`
    ).bind(ip, day).run();
    await env.DB.prepare(
      `DELETE FROM upload_quota WHERE day < ?`
    ).bind(day).run();

    return json({ ok: true });
  } catch (e) {
    return json({ ok: false, error: "db error" }, 500);
  }
}

// 非 POST 方法一律拒绝，避免静态路径误触发。
export function onRequest() {
  return json({ ok: false, error: "method not allowed" }, 405);
}
