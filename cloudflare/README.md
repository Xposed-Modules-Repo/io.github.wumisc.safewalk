# SafeWalk 诊断日志上报 · Cloudflare 后端

接收 App 端手动上传的诊断日志与机型环境信息，落 D1 供作者排障。
**只收最小化诊断数据**：轨迹、位置、联系人、急救卡内容不上传（端侧已脱敏，服务端不做二次采集）。

## 目录结构

```
cloudflare/
├── functions/
│   └── api/diag/
│       ├── upload.js   # POST 上报接收（Bearer 鉴权 → 写 D1）
│       └── list.js     # GET 摘要列表（同令牌鉴权，只读）
├── public/             # Pages 静态占位（无业务页面）
├── schema.sql          # D1 建表语句
└── wrangler.toml       # Pages 项目与 D1 绑定（database_id 已回填）
```

**已部署**：Pages 项目 `safewalk-diag`，生产域 `https://safewalk-diag.pages.dev`；D1 库 `safewalk-diag`；`UPLOAD_TOKEN` 为 production Secret，端侧 `DiagUploader` 常量与之保持一致。

## 免费额度（Cloudflare Free）

- Pages Functions 计入 Workers 额度：**10 万请求/天**、10ms CPU/次；静态资源无限免费。
- D1：5GB 存储、10 万行写/天。诊断日志量级远用不满。

## 部署步骤

1. **安装 Wrangler**（本机或 CI）：`npm i -g wrangler`，登录 `wrangler login`（或用 API Token 设 `CLOUDFLARE_API_TOKEN`）。
2. **创建 D1 数据库**：
   ```bash
   wrangler d1 create safewalk-diag
   ```
   把输出的 `database_id` 回填到 `wrangler.toml` 的 `database_id`。
3. **建表**：
   ```bash
   wrangler d1 execute safewalk-diag --file=./schema.sql
   ```
4. **设置上传令牌**（Secret，不要写进 wrangler.toml / 仓库）：
   - 控制台：Pages 项目 → Settings → Environment variables → Production，新增 `UPLOAD_TOKEN`；
   - 或 CLI：`wrangler pages secret put UPLOAD_TOKEN`。
5. **部署 Pages 项目**：
   ```bash
   cd cloudflare
   wrangler pages deploy public --project-name safewalk-diag
   ```
   发布后得到 `https://safewalk-diag.pages.dev`（或你的项目名子域）。
6. **回填端侧常量**：`app/.../DiagUploader.kt` 中
   - `ENDPOINT_URL` → `https://<项目名>.pages.dev/api/diag/upload`
   - `UPLOAD_TOKEN` → 与第 4 步一致的令牌。

## 接口约定

### POST /api/diag/upload

- 请求头：`Authorization: Bearer <UPLOAD_TOKEN>`、`Content-Type: application/json`。
- 请求体：
  ```json
  {
    "device": { "manufacturer": "", "model": "", "device": "", "product": "", "release": "", "sdk": 0, "ui": "" },
    "app": { "versionName": "", "versionCode": 0, "package": "" },
    "note": "",
    "ts": 0,
    "log": "已脱敏的诊断日志尾部"
  }
  ```
- 响应：`{ "ok": true, "id": 123 }`；鉴权失败 401、超 64KB 413、JSON 非法 400。

### GET /api/diag/list

同令牌鉴权，返回最近 100 条摘要（日志仅头部 200 字符预览）。

## 安全与限流

- 令牌写在客户端只能挡「随手写请求打接口」，挡不住反编译；正式防护靠 D1 不公开、列表接口鉴权。
- 服务端已做：整包 64KB 上限、字段长度截断、非 POST 拒绝。
- **保留策略**：按厂商 + 机型分组，只保留最新 versionCode 的记录，在最新版本内留最新 3 条；旧版本记录与超出条数的旧记录在新上报写入后连带删除，不堆积历史版本日志。
- 后续若量上来，可加 KV 按来源 IP / 设备指纹做单日条数限流（免费 KV 写 1000 次/天）。

## 国内可达性说明

`*.pages.dev` 在大陆可能被 DNS 污染或 SNI 阻断。端侧已做失败静默降级：上传失败只记本地日志、不阻塞主功能；若可达性不达标，后续可绑自有域名改善。

## 保留策略与三区间分析

- **保留**：按「厂商 + 机型 + 来源 IP」分组，只留最近 3 条，**不看 versionCode**（按版本号清会误删其他用户在旧正式版上的反馈）。
- **白名单**：`dev_whitelist` 表登记维护者本机，落库打 `is_dev = 1`；`GET /api/diag/list` 默认排除，`?include_dev=1` 才返回。
- **短码**：上传成功返回 `code`（log_hash 前 8 位），用户报问题时报这个码即可对号，不用念机型和时间。
- **分析口径**：见 [DIAG-BANDS.md](./DIAG-BANDS.md)，取数用 `tools/bands.sh`（自动取 GitHub 最新正式版 versionCode 作基线，把记录盖成 below / release / dev 三档）。
