import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { createHandler } from './handler.mjs';
import { createDatabase } from './database.mjs';
import { createProgressHandler } from './progress-page.mjs';

// Access is through the unpublished bridge address + an operator SSH tunnel.
// This adapter is not an authentication service for LAN/public exposure.
export function createServer({ database, origin = 'http://localhost:8788', executionEnabled = false }) {
  const expected = new URL(origin);
  if (expected.origin !== origin || expected.protocol !== 'http:' || expected.hostname !== 'localhost') {
    throw new Error('LoopbackOriginRequired');
  }
  const handler = createHandler({ authorize: async () => true, database, executionEnabled });
  const progressHandler=createProgressHandler(database);
  const server = http.createServer(async (incoming, outgoing) => {
    const respond = (status, message) => {
      outgoing.writeHead(status, {'Content-Type':'application/json','Cache-Control':'no-store'});
      outgoing.end(JSON.stringify({error: message}));
    };
    if (incoming.headers.host !== expected.host) return respond(403, 'Unexpected host');
    if (!incoming.url?.startsWith('/') || incoming.url.startsWith('//')) return respond(400, 'Invalid request target');
    if (incoming.headers['sec-fetch-site'] === 'cross-site') return respond(403, 'Cross-site request denied');
    try {
      const chunks = []; let length = 0;
      for await (const chunk of incoming) {
        length += chunk.length;
        if (length > 1024) return respond(413, 'Request too large');
        chunks.push(chunk);
      }
      const headers = new Headers();
      for (const [key, value] of Object.entries(incoming.headers)) {
        if (value !== undefined) headers.set(key, Array.isArray(value) ? value.join(', ') : value);
      }
      const method = incoming.method ?? 'GET';
      const request=new Request(new URL(incoming.url, expected), {
        method, headers,
        body: ['GET', 'HEAD'].includes(method) ? undefined : Buffer.concat(chunks),
      });
      const response=await progressHandler(request)??await handler(request);
      outgoing.writeHead(response.status, Object.fromEntries(response.headers));
      outgoing.end(Buffer.from(await response.arrayBuffer()));
    } catch {
      if (!outgoing.headersSent) respond(503, 'Pilot unavailable');
      else outgoing.end();
    }
  });
  server.requestTimeout = 130000;
  server.headersTimeout = 10000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const db = await createDatabase();
  const server = createServer({ database: f => db.transaction(f),
    origin: process.env.PILOT_ORIGIN ?? 'http://localhost:8788',
    executionEnabled: process.env.EXECUTION_ENABLED === 'true' });
  server.listen(8788, '0.0.0.0', () => console.log(JSON.stringify({event:'nas_pilot_listening',port:8788})));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
    server.close(() => db.close().then(() => process.exit(0)));
  });
}
