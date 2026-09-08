// Publishes the Bank Templates Lite catalogue: reads D1 through Cloudflare's REST API, builds
// index.json and the per-revision layout files, uploads them gzipped to the public R2 bucket, and
// records what it published back in D1. Runs on GitHub Actions (see .github/workflows/publish.yml),
// where there is no CPU limit, instead of inside the Worker, where the Free plan allows 10 ms.
//
// Environment: CF_ACCOUNT_ID, CF_API_TOKEN (D1 Edit + Workers R2 Storage Edit), D1_DATABASE_ID,
// R2_BUCKET, PUBLIC_BASE. Set FORCE=1 to publish even when nothing is marked dirty.
//
// The file shapes are documented in the Worker's CONTRACT.md. Node 20+, no dependencies.
import { gzipSync } from "node:zlib";

const env = (k, fallback) => {
  const v = process.env[k] ?? fallback;
  if (v === undefined || v === "") {
    console.error(`missing ${k}`);
    process.exit(2);
  }
  return v;
};

const ACCOUNT = env("CF_ACCOUNT_ID");
const TOKEN = env("CF_API_TOKEN");
const DB = env("D1_DATABASE_ID");
const BUCKET = env("R2_BUCKET");
const PUBLIC_BASE = env("PUBLIC_BASE").replace(/\/+$/, "");
const FORCE = process.env.FORCE === "1";

const API = `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}`;
const POPULAR_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;
const REPORT_TTL_MS = 90 * 24 * 60 * 60 * 1000;
const INDEX_CACHE = "public, max-age=60";
const LAYOUT_CACHE = "public, max-age=31536000, immutable";

async function d1(sql, params = []) {
  const res = await fetch(`${API}/d1/database/${DB}/query`, {
    method: "POST",
    headers: { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({ sql, params }),
  });
  const body = await res.json();
  if (!res.ok || !body.success) throw new Error(`D1 ${res.status}: ${JSON.stringify(body.errors || body)}`);
  return body.result[0].results;
}

function objectUrl(key) {
  return `${API}/r2/buckets/${BUCKET}/objects/${key.split("/").map(encodeURIComponent).join("/")}`;
}

async function putGzipJson(key, text, cacheControl) {
  const res = await fetch(objectUrl(key), {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Type": "application/json",
      "Content-Encoding": "gzip",
      "Cache-Control": cacheControl,
    },
    body: gzipSync(Buffer.from(text), { level: 9 }),
  });
  if (!res.ok) throw new Error(`R2 put ${key} ${res.status}: ${await res.text()}`);
}

async function deleteObject(key) {
  const res = await fetch(objectUrl(key), { method: "DELETE", headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!res.ok && res.status !== 404) throw new Error(`R2 delete ${key} ${res.status}: ${await res.text()}`);
}

// Mirrors indexEntryStatic() in the Worker. Kept in sync by hand; the Worker computes this for new
// writes and this script only fills in rows that have none (migrated data, or a format change).
function indexEntryStatic(row) {
  let data = {};
  try {
    data = JSON.parse(row.layout) || {};
  } catch {
    data = {};
  }
  const tabs = Array.isArray(data.tabs) ? data.tabs : [];
  const items = new Set();
  const tabMeta = tabs.map((t) => {
    let icon = Number.isInteger(t.customIconId) && t.customIconId > 0 ? t.customIconId : 0;
    for (const v of t.layout || []) {
      if (v > 0) {
        items.add(v);
        if (!icon) icon = v;
      }
    }
    return { tab: t.tab, icon };
  });
  return JSON.stringify({
    id: row.id,
    rev: row.rev,
    name: row.name,
    author: row.author,
    anonymous: !!row.anonymous,
    description: row.description,
    created: row.created_at,
    updated: row.updated_at,
    items: items.size,
    columns: data.columns || 8,
    tabs: tabMeta,
  });
}

function layoutFile(row) {
  let data = {};
  try {
    data = JSON.parse(row.layout) || {};
  } catch {
    data = {};
  }
  return JSON.stringify({ id: row.id, rev: row.rev, columns: data.columns || 8, tabs: Array.isArray(data.tabs) ? data.tabs : [] });
}

function withCounts(entry, downloads, recent, reports) {
  return entry.slice(0, -1) + `,"downloads":${downloads},"recent":${recent},"reports":${reports}}`;
}

async function main() {
  const now = Date.now();
  const flags = Object.fromEntries((await d1("SELECT k, v FROM meta WHERE k IN ('dirty', 'counts_dirty')")).map((r) => [r.k, r.v]));
  if (!FORCE && flags.dirty !== "1" && flags.counts_dirty !== "1") {
    console.log("nothing to publish");
    return;
  }

  // 1. Index entries for rows that have none.
  const missing = await d1("SELECT id, rev, name, description, author, anonymous, layout, created_at, updated_at FROM templates WHERE index_entry IS NULL");
  for (const r of missing) {
    await d1("UPDATE templates SET index_entry = ? WHERE id = ?", [indexEntryStatic(r), r.id]);
  }
  if (missing.length) console.log(`computed ${missing.length} index entries`);

  // 2. Layout files for revisions not yet on R2, before anything can point at them.
  const changed = await d1("SELECT id, rev, published_rev, layout FROM templates WHERE status = 'approved' AND rev <> published_rev");
  for (const r of changed) {
    await putGzipJson(`t/${r.id}-${r.rev}.json`, layoutFile(r), LAYOUT_CACHE);
    await d1("UPDATE templates SET published_rev = ? WHERE id = ? AND rev = ?", [r.rev, r.id, r.rev]);
  }
  if (changed.length) console.log(`wrote ${changed.length} layout files`);

  // 3. The index.
  const rows = await d1("SELECT id, index_entry, downloads FROM templates WHERE status = 'approved' AND index_entry IS NOT NULL AND rev = published_rev ORDER BY id");
  const recent = new Map((await d1("SELECT template_id, COUNT(*) AS n FROM imports WHERE ts > ? GROUP BY template_id", [now - POPULAR_WINDOW_MS])).map((r) => [r.template_id, r.n]));
  const reports = new Map((await d1("SELECT template_id, COUNT(*) AS n FROM reports WHERE ts > ? GROUP BY template_id", [now - REPORT_TTL_MS])).map((r) => [r.template_id, r.n]));
  const parts = rows.map((r) => withCounts(r.index_entry, r.downloads, recent.get(r.id) || 0, reports.get(r.id) || 0));
  await putGzipJson("index.json", `{"v":1,"generated":${now},"count":${rows.length},"templates":[${parts.join(",")}]}`, INDEX_CACHE);
  console.log(`published index with ${rows.length} templates`);

  // 4. Retire superseded revision files now the index no longer references them.
  for (const r of changed) {
    if (r.published_rev > 0 && r.published_rev !== r.rev) await deleteObject(`t/${r.id}-${r.published_rev}.json`);
  }

  await d1("UPDATE meta SET v = CASE k WHEN 'published_at' THEN ? ELSE '0' END WHERE k IN ('dirty', 'counts_dirty', 'published_at')", [String(now)]);

  // 5. Check what the world actually sees. A REST upload that dropped the encoding header would
  //    serve raw JSON at six times the size; better to fail the run than publish that quietly.
  const head = await fetch(`${PUBLIC_BASE}/index.json`, { method: "HEAD", headers: { "Accept-Encoding": "gzip" } });
  const enc = head.headers.get("content-encoding");
  const cc = head.headers.get("cache-control");
  if (!head.ok || enc !== "gzip" || cc !== INDEX_CACHE) {
    throw new Error(`served index.json looks wrong: status ${head.status}, content-encoding ${enc}, cache-control ${cc}`);
  }
  console.log(`verified ${PUBLIC_BASE}/index.json: gzip, ${head.headers.get("content-length")} bytes`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
