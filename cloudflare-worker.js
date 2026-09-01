const BACKENDS = [
  'https://premiertranspo.onrender.com',
  'https://premier-i4es.onrender.com',
];

const ALLOWED_ORIGINS = new Set([
  'https://premierusers.vercel.app',
  'https://premieradmin.vercel.app',
  'https://premier-staff.vercel.app',
]);

const ALLOWED_ORIGIN_PATTERNS = [
  /^https:\/\/premierusers(?:-[a-z0-9-]+)?\.vercel\.app$/,
  /^https:\/\/premieradmin(?:-[a-z0-9-]+)?\.vercel\.app$/,
  /^https:\/\/premier-staff(?:-[a-z0-9-]+)?\.vercel\.app$/,
];

let counter = 0;

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const origin = request.headers.get('Origin');

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }

    if (url.pathname === '/health') {
      return jsonResponse({
        status: 'ok',
        service: 'Premier API Proxy',
        worker: 'Cloudflare',
        timestamp: new Date().toISOString(),
      }, 200, origin);
    }

    if (url.pathname === '/backend-health') return backendHealthCheck(origin);
    if (url.pathname === '/backend-health/primary') return singleBackendHealth(BACKENDS[0], 'primary', origin);
    if (url.pathname === '/backend-health/fallback') return singleBackendHealth(BACKENDS[1], 'fallback', origin);

    if (origin && !isAllowedOrigin(origin)) {
      return jsonResponse({ error: 'Origin not allowed' }, 403, null);
    }

    if (url.pathname === '/ws-native') {
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
        return jsonResponse({ error: 'WebSocket upgrade required' }, 426, origin);
      }
      return proxyWebSocket(request, url, origin);
    }

    return proxyHttp(request, url, origin);
  },
};

async function proxyHttp(request, url, origin) {
  const startIndex = counter++ % BACKENDS.length;
  const safeToRetry = ['GET', 'HEAD'].includes(request.method);
  let lastError;

  for (let offset = 0; offset < BACKENDS.length; offset += 1) {
    const backend = BACKENDS[(startIndex + offset) % BACKENDS.length];
    try {
      const response = await fetch(`${backend}${url.pathname}${url.search}`, {
        method: request.method,
        headers: forwardHeaders(request.headers),
        body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
        redirect: 'follow',
      });

      // Do not send a request that may have changed money or account state to
      // a second backend. The client can safely retry with its idempotency key.
      if (response.status < 500 || !safeToRetry) return withResponseHeaders(response, origin);
      lastError = `Backend returned ${response.status}`;
    } catch {
      if (!safeToRetry) {
        return jsonResponse({ error: 'Backend request failed. Please retry.' }, 502, origin);
      }
      lastError = 'Backend unreachable';
    }
  }

  return jsonResponse({ error: 'All backends unreachable', detail: lastError }, 502, origin);
}

async function proxyWebSocket(request, url, origin) {
  const startIndex = counter++ % BACKENDS.length;
  let lastError;

  for (let offset = 0; offset < BACKENDS.length; offset += 1) {
    const backend = BACKENDS[(startIndex + offset) % BACKENDS.length];
    try {
      const headers = forwardHeaders(request.headers);
      // Workers performs the WebSocket handshake when this header is present.
      headers.delete('connection');
      headers.set('Upgrade', 'websocket');

      const response = await fetch(`${backend}${url.pathname}${url.search}`, {
        method: 'GET',
        headers,
      });

      if (response.status === 101 && response.webSocket) {
        // Return the upstream upgrade response unchanged. Constructing a new
        // Response here would drop the WebSocket object and break the handshake.
        return response;
      }

      // Authentication/origin failures are valid upstream responses and must
      // not be sent to the other backend.
      if (response.status < 500) return response;
      lastError = `Backend returned ${response.status}`;
    } catch {
      lastError = 'Backend unreachable';
    }
  }

  return jsonResponse({ error: 'WebSocket backend unavailable', detail: lastError }, 502, origin);
}

function withResponseHeaders(response, origin) {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(corsHeaders(origin))) headers.set(key, value);
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

async function backendHealthCheck(origin) {
  const results = await Promise.all(BACKENDS.map((backend, index) => healthResult(backend, index === 0 ? 'primary' : 'fallback')));
  const healthyCount = results.filter((result) => result.healthy).length;
  return jsonResponse({
    status: healthyCount === BACKENDS.length ? 'healthy' : healthyCount ? 'degraded' : 'unhealthy',
    service: 'Premier Backend Infrastructure',
    timestamp: new Date().toISOString(),
    healthyBackends: healthyCount,
    totalBackends: BACKENDS.length,
    backends: results,
  }, healthyCount ? 200 : 503, origin);
}

async function singleBackendHealth(backend, name, origin) {
  const result = await healthResult(backend, name);
  return jsonResponse({
    status: result.healthy ? 'healthy' : 'unhealthy',
    backend: name,
    url: backend,
    httpStatus: result.status,
    responseTimeMs: result.responseTimeMs,
    ...(result.error ? { error: result.error } : {}),
    timestamp: new Date().toISOString(),
  }, result.healthy ? 200 : 503, origin);
}

async function healthResult(backend, name) {
  const start = Date.now();
  try {
    const response = await fetch(`${backend}/actuator/health`, {
      method: 'GET',
      headers: { 'User-Agent': 'Premier-Cloudflare-Health-Check' },
    });
    return {
      name,
      backend,
      status: response.status,
      responseTimeMs: Date.now() - start,
      healthy: response.status >= 200 && response.status < 400,
    };
  } catch {
    return {
      name,
      backend,
      status: null,
      responseTimeMs: Date.now() - start,
      healthy: false,
      error: 'Backend unreachable',
    };
  }
}

function jsonResponse(data, status, origin) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) },
  });
}

function corsHeaders(origin) {
  const common = {
    'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, Accept, Origin, X-Requested-With, Idempotency-Key',
    'Access-Control-Allow-Credentials': 'true',
  };
  if (!origin) return common;
  if (!isAllowedOrigin(origin)) return {};
  return {
    ...common,
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Max-Age': '86400',
    Vary: 'Origin',
  };
}

function isAllowedOrigin(origin) {
  return ALLOWED_ORIGINS.has(origin) || ALLOWED_ORIGIN_PATTERNS.some((pattern) => pattern.test(origin));
}

function forwardHeaders(incoming) {
  const headers = new Headers();
  const skip = new Set([
    'host', 'connection', 'content-length', 'cf-ray', 'cf-connecting-ip',
    'cf-visitor', 'cf-worker', 'x-forwarded-for', 'x-forwarded-host',
    'x-forwarded-proto', 'x-real-ip',
  ]);
  for (const [key, value] of incoming.entries()) {
    if (!skip.has(key.toLowerCase())) headers.set(key, value);
  }
  return headers;
}
