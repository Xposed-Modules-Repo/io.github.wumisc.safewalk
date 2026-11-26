-- SafeWalk 诊断日志上报 · D1 表结构
--
-- 部署：在 Cloudflare 控制台或 wrangler 建好 D1 数据库后，执行本文件建表。
-- 字段只保留排障必需项：环境信息 + 一段已脱敏日志；不存轨迹、位置、联系人、急救卡内容。

CREATE TABLE IF NOT EXISTS diag_reports (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    ts             INTEGER NOT NULL,              -- 客户端上报时间戳（毫秒）
    package        TEXT,                          -- 应用包名
    version_name   TEXT,                          -- 应用版本名（如 5.7.4-lx102）
    version_code   INTEGER,                        -- 应用版本号
    manufacturer   TEXT,                          -- 厂商
    model          TEXT,                          -- 机型
    device         TEXT,
    product        TEXT,
    release        TEXT,                          -- Android 版本
    sdk            INTEGER,                       -- SDK API 级别
    ui             TEXT,                          -- 识别到的 ROM / UI 名
    note           TEXT,                          -- 用户备注（可空）
    log            TEXT,                          -- 已脱敏的诊断日志尾部
    log_hash       TEXT,                          -- mfr|model|log 的 SHA-256，用于 10 分钟内重复上报去重
    client_ip      TEXT                           -- 边缘记录的来源 IP（仅排障用）
);

CREATE INDEX IF NOT EXISTS idx_diag_reports_ts ON diag_reports (ts DESC);
CREATE INDEX IF NOT EXISTS idx_diag_reports_model ON diag_reports (model, ts DESC);

CREATE INDEX IF NOT EXISTS idx_diag_reports_hash ON diag_reports (model, log_hash, ts DESC);

-- 上传限额：每个 IP 每天一个桶。空表即「今天还没人上传」。
CREATE TABLE IF NOT EXISTS upload_quota (
    ip   TEXT NOT NULL,                           -- cf-connecting-ip
    day  TEXT NOT NULL,                           -- YYYY-MM-DD（UTC）
    n    INTEGER NOT NULL DEFAULT 0,              -- 当日已成功落库次数
    PRIMARY KEY (ip, day)
);
