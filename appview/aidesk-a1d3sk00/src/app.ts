// AI Design Desk thin edge. Business logic runs in AgentGateway MCP + pod-side LangServer (kotodama).
// Zero-To-CAD (Apache 2.0) inference → CadQuery → STEP → tsukuru supplierExchange.

interface SecretBinding {
  get(): Promise<string>;
}

interface Env {
  DISPATCHER_URL?: string;
  DISPATCHER_INTERNAL_SECRET?: string | SecretBinding;
  APP_NANOID?: string;
}

interface ExportedHandler<E> {
  fetch(req: Request, env: E): Promise<Response>;
}

const NSID_PREFIX = "com.etzhayyim.apps.aidesk.";

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);

    if (url.pathname === "/health" || url.pathname === "/_app/meta") {
      return json({
        ok: true,
        actor: "did:web:aidesk.etzhayyim.com",
        nanoid: env.APP_NANOID ?? "a1d3sk00",
        execution: "edge-proxy+agentgateway-mcp+langserver",
        model: "ADSKAILab/Zero-To-CAD-Qwen3-VL-2B",
        licenseTier: "apache2",
        businessLogic: "40-engine/kotoba/crates/kotoba-kotodama/py/src/kotodama/primitives/aidesk.py",
        bpmn: "etzhayyim-root/00-contracts/bpmn/com/etzhayyim/aidesk",
        tsukuruIntegration: "com.etzhayyim.apps.tsukuru.supplierExchange.normalizePackage",
      });
    }

    const nsid = url.pathname.startsWith("/xrpc/") ? url.pathname.slice("/xrpc/".length) : "";
    if (nsid.startsWith(NSID_PREFIX) && (req.method === "POST" || req.method === "GET")) {
      const body = await bodyWithQuery(req, url);
      if (body.__invalidJson) return json({ error: "InvalidJson" }, 400);
      return proxyToDispatcher(env, nsid, body);
    }

    return json({ error: "NotFound", message: "aidesk endpoint not found" }, 404);
  },
} satisfies ExportedHandler<Env>;

async function bodyWithQuery(req: Request, url: URL): Promise<Record<string, unknown>> {
  let body: Record<string, unknown> = {};
  if (req.method === "POST") {
    const text = await req.text();
    try {
      body = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      return { __invalidJson: true };
    }
  }
  url.searchParams.forEach((v, k) => {
    if (!(k in body)) body[k] = v;
  });
  return body;
}

async function proxyToDispatcher(env: Env, nsid: string, body: Record<string, unknown>): Promise<Response> {
  const base = (env.DISPATCHER_URL ?? "https://dispatcher.etzhayyim.com").replace(/\/+$/, "");
  const headers: Record<string, string> = { "content-type": "application/json" };
  const trust = await internalTrustSecret(env);
  if (trust) headers["x-internal-trust"] = trust;
  const resp = await fetch(`${base}/xrpc/${nsid}`, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await resp.text();
  return new Response(text, {
    status: resp.status,
    headers: {
      "content-type": resp.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
    },
  });
}

async function internalTrustSecret(env: Env): Promise<string> {
  const binding = env.DISPATCHER_INTERNAL_SECRET;
  if (!binding) return "";
  try {
    return typeof binding === "string" ? binding : await binding.get();
  } catch {
    return "";
  }
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}
