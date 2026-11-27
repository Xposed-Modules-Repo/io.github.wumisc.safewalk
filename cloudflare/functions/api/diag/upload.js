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
//
// ## 保留策略（136 改）
//
// 老做法是「按厂商+机型分组，只留最新 versionCode 的 3 条」——那是错的：
// 开发者本机永远跑着最新开发版，一上传就把别人在旧正式版上的记录整组删掉，
// 用户的反馈还没看就没了。现在改成按「厂商 + 机型 + 来源 IP」分组，只留最近 3 条，
// **不看版本号**。同一台机器刷 3 次就只剩最近 3 次，不同用户的记录互不影响。
//
// ## 开发者白名单（136 新增）
//
// `dev_whitelist` 表里登记的（厂商, 机型）视为维护者本机，落库时打 `is_dev = 1`。
// 这些行留着自己验证规则，统计其他用户问题时要显式排除（list 接口默认就不返回它们）。

const MAX_BODY = 64 * 1024;   // 整包上限 64KB（端侧只会发 ≤60KB 日志）
const MAX_LOG = 60_000;       // 日志正文上限（与端侧一致）
const MAX_ROWS = 500;         // 全表行数上限
const IP_DAILY_LIMIT = 5;     // 单 IP 每日上传次数上限
const DEDUPE_WINDOW_MS = 10 * 60 * 1000;
const KEEP_PER_SOURCE = 3;    // 同一「厂商+机型+IP」最多留 3 条（与版本号无关）

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

/** 这台机器是不是维护者本机：dev_whitelist 里按（厂商, 机型）登记。查不到就当普通用户。 */
async function isDeveloper(env, mfr, model) {
  try {
    const row = await env.DB.prepare(
      `SELECT 1 AS hit FROM dev_whitelist WHERE manufacturer = ? AND model = ? LIMIT 1`
    ).bind(mfr, model).first();
    return row ? 1 : 0;
  } catch {
    // 白名单表缺失/读失败：按普通用户处理，宁可多算一次也不把用户记录当自测
    return 0;
  }
}

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * 上报短码：log_hash 前 8 位（大写）。用户看到它就能报「我传的那条是 3F2A91C4」，
 * 不用把整机信息或时间戳念给维护者，也不涉及隐私。
 */
function shortCode(hash) {
  return String(hash).slice(0, 8).toUpperCase();
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
      return json({ ok: true, deduped: true, code: shortCode(hash) });
    }

    // 闸 4：全表行数上限。正常保留策略下真实设备撑不到这个数，
    // 一旦逼近说明有人在编机型名刷存储。
    const total = await env.DB.prepare(
      `SELECT COUNT(*) AS c FROM diag_reports`
    ).first();
    if (total && Number(total.c) >= MAX_ROWS) {
      return json({ ok: false, error: "storage full" }, 429);
    }

    const dev = await isDeveloper(env, mfr, model);

    const stmt = env.DB.prepare(
      `INSERT INTO diag_reports
         (ts, package, version_name, version_code, manufacturer, model, device, product,
          release, sdk, ui, note, log, log_hash, client_ip, is_dev)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
      ip,
      dev
    );

    await stmt.run();

    /*
     * 保留策略：按「厂商 + 机型 + 来源 IP」分组，只留最近 3 条。
     *
     * 刻意不看 versionCode：维护者本机永远是最新开发版，按版本号清会连带删掉
     * 其他用户在旧正式版上的反馈（用户明确禁止过这种做法）。
     * 用 IP 分桶而不是「整机型只留 3 条」，是为了让两个不同用户的机器互不挤占。
     */
    await env.DB.prepare(
      `DELETE FROM diag_reports
         WHERE manufacturer = ? AND model = ? AND client_ip = ?
           AND id NOT IN (
             SELECT id FROM (
               SELECT id FROM diag_reports
                WHERE manufacturer = ? AND model = ? AND client_ip = ?
                ORDER BY ts DESC, id DESC LIMIT ?
             )
           )`
    ).bind(mfr, model, ip, mfr, model, ip, KEEP_PER_SOURCE).run();

    // 落库成功才计数；顺手清掉前一天的桶，避免计数表自己长大。
    await env.DB.prepare(
      `INSERT INTO upload_quota (ip, day, n) VALUES (?, ?, 1)
         ON CONFLICT(ip, day) DO UPDATE SET n = n + 1`
    ).bind(ip, day).run();
    await env.DB.prepare(
      `DELETE FROM upload_quota WHERE day < ?`
    ).bind(day).run();

    return json({ ok: true, code: shortCode(hash) });
  } catch (e) {
    return json({ ok: false, error: "db error" }, 500);
  }
}

// 非 POST 方法一律拒绝，避免静态路径误触发。
export function onRequest() {
  return json({ ok: false, error: "method not allowed" }, 405);
}
