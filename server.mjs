import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import path from "node:path";
import dns from "node:dns";
import { fileURLToPath } from "node:url";

dns.setDefaultResultOrder("ipv4first");

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "app", "src", "main", "assets", "www");
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "0.0.0.0";
const UA =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
  };
}

function send(res, status, body, type = "text/plain; charset=utf-8") {
  res.writeHead(status, { ...corsHeaders(), "Content-Type": type });
  res.end(body);
}

function safeJoin(root, rel) {
  const decoded = decodeURIComponent(rel.split("?")[0]);
  const target = path.resolve(root, "." + path.normalize(decoded));
  if (target !== root && !target.startsWith(root + path.sep)) return null;
  return target;
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
}

function requestOnce(url, options, body) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const lib = parsed.protocol === "https:" ? https : http;
    const req = lib.request(
      {
        protocol: parsed.protocol,
        hostname: parsed.hostname,
        port: parsed.port || (parsed.protocol === "https:" ? 443 : 80),
        path: parsed.pathname + parsed.search,
        method: options.method || "GET",
        headers: options.headers || {},
        timeout: 30000,
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () =>
          resolve({
            status: res.statusCode || 502,
            headers: res.headers,
            body: Buffer.concat(chunks),
          })
        );
      }
    );
    req.on("timeout", () => req.destroy(new Error("upstream timeout")));
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

async function upstreamFetch(url, init = {}) {
  let current = url;
  for (let redirects = 0; redirects < 6; redirects++) {
    const result = await requestOnce(current, init, init.body);
    const status = result.status;
    if ([301, 302, 303, 307, 308].includes(status) && result.headers.location) {
      current = new URL(result.headers.location, current).href;
      if (status === 303) init.method = "GET";
      init.body = undefined;
      continue;
    }
    return result;
  }
  throw new Error("too many redirects");
}

async function handleProxy(req, res, searchParams) {
  if (req.method === "OPTIONS") {
    send(res, 204, "", "text/plain");
    return;
  }

  const target = searchParams.get("url");
  if (!target || !/^https?:\/\//i.test(target)) {
    send(res, 400, "missing url", "text/plain; charset=utf-8");
    return;
  }

  const headers = {
    "User-Agent": UA,
    Accept: "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.7",
  };

  const targetUrl = new URL(target);
  if (targetUrl.hostname.includes("zuzhirenshi.com")) {
    headers.Origin = targetUrl.origin;
    headers.Referer = targetUrl.origin + "/";
  } else if (targetUrl.hostname.includes("nfnews.com")) {
    headers.Referer = targetUrl.origin + "/nfdaily/html/";
  }

  const init = {
    method: req.method === "POST" ? "POST" : "GET",
    headers,
    redirect: "follow",
  };

  if (req.method === "POST") {
    const body = await readBody(req);
    init.body = body || "{}";
    headers["Content-Type"] = req.headers["content-type"] || "application/json";
  }

  try {
    const upstream = await upstreamFetch(target, init);
    const contentTypeRaw = upstream.headers["content-type"];
    const contentType = Array.isArray(contentTypeRaw) ? contentTypeRaw[0] : (contentTypeRaw || "text/plain; charset=utf-8");
    res.writeHead(upstream.status, { ...corsHeaders(), "Content-Type": contentType });
    res.end(upstream.body);
  } catch (error) {
    console.error("[proxy]", target, error);
    send(res, 502, "proxy error: " + error.message, "text/plain; charset=utf-8");
  }
}

const server = http.createServer(async (req, res) => {
  const reqUrl = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  if (reqUrl.pathname === "/api/proxy") {
    await handleProxy(req, res, reqUrl.searchParams);
    return;
  }

  if (reqUrl.pathname === "/health") {
    send(res, 200, "ok", "text/plain; charset=utf-8");
    return;
  }

  const rel = reqUrl.pathname === "/" ? "/index.html" : reqUrl.pathname;
  const target = safeJoin(ROOT, rel);
  if (!target) {
    send(res, 403, "forbidden", "text/plain; charset=utf-8");
    return;
  }

  let file = target;
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) {
    file = path.join(file, "index.html");
  }

  try {
    const body = await fs.promises.readFile(file);
    const ext = path.extname(file).toLowerCase();
    const types = {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".json": "application/json; charset=utf-8",
      ".png": "image/png",
      ".jpg": "image/jpeg",
      ".jpeg": "image/jpeg",
      ".webp": "image/webp",
      ".svg": "image/svg+xml",
      ".ico": "image/x-icon",
    };
    res.writeHead(200, { ...corsHeaders(), "Content-Type": types[ext] || "application/octet-stream" });
    res.end(body);
  } catch {
    send(res, 404, "not found", "text/plain; charset=utf-8");
  }
});

server.listen(PORT, HOST, () => {
  console.log(`ReadPaper local server: http://localhost:${PORT}`);
  console.log(`LAN URL: http://<this-pc-ip>:${PORT}`);
});
