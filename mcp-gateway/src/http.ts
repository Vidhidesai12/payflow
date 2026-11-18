import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import express, { type NextFunction, type Request, type Response } from 'express';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';
import type { ClientPolicy } from './config.js';
import { createSession, type GatewayDeps } from './session.js';

interface Live {
  transport: StreamableHTTPServerTransport;
  client: string;
  close: () => Promise<void>;
}

/** The HTTP side: Host/Origin checks, client tokens, and one MCP session per connected client. */
export function createApp(deps: GatewayDeps) {
  const { config } = deps;
  const sessions = new Map<string, Live>();
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  // DNS-rebinding and cross-site protection, done here as the SDK now recommends.
  app.use((req: Request, res: Response, next: NextFunction) => {
    const host = req.headers.host ?? '';
    if (config.allowedHosts.length && !config.allowedHosts.includes(host)) {
      res.status(403).json({ error: 'host not allowed' });
      return;
    }
    const origin = req.headers.origin;
    if (origin && !config.allowedOrigins.includes(origin)) {
      res.status(403).json({ error: 'origin not allowed' });
      return;
    }
    next();
  });

  app.get('/health', (_req, res) => {
    res.json({ status: 'UP', upstreams: deps.catalog.health() });
  });

  app.get('/metrics', (_req, res) => {
    const quarantined = deps.catalog.all().filter((e) => e.pin.status === 'quarantined').length;
    res.type('text/plain; version=0.0.4').send(deps.metrics.render(quarantined));
  });

  const authenticate = (req: Request): ClientPolicy | undefined => {
    const header = req.headers.authorization ?? '';
    if (!header.startsWith('Bearer ')) return undefined;
    const presented = createHash('sha256').update(header.slice(7).trim()).digest();
    return config.clients.find((c) => {
      const expected = Buffer.from(c.tokenSha256, 'hex');
      return expected.length === presented.length && timingSafeEqual(expected, presented);
    });
  };

  const handle = async (req: Request, res: Response) => {
    const policy = authenticate(req);
    if (!policy) {
      deps.audit.write({ client: 'unknown', event: 'auth', decision: 'deny', reason: 'missing or invalid token' });
      res.status(401).set('WWW-Authenticate', 'Bearer').json({ error: 'unauthorized' });
      return;
    }

    const sessionId = req.headers['mcp-session-id'];
    if (typeof sessionId === 'string') {
      const live = sessions.get(sessionId);
      if (!live) {
        res.status(404).json({ error: 'unknown session' });
        return;
      }
      // A session belongs to the client that opened it: another client's token can't use it.
      if (live.client !== policy.name) {
        deps.audit.write({ client: policy.name, event: 'auth', decision: 'deny', reason: 'session belongs to another client' });
        res.status(403).json({ error: 'session belongs to another client' });
        return;
      }
      await live.transport.handleRequest(req, res, req.body);
      return;
    }

    if (req.method !== 'POST' || !isInitializeRequest(req.body)) {
      res.status(400).json({ error: 'start a session with an initialize request' });
      return;
    }
    const { server, close } = createSession(policy, deps);
    const transport: StreamableHTTPServerTransport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id) => {
        sessions.set(id, { transport, client: policy.name, close });
      },
    });
    transport.onclose = () => {
      if (transport.sessionId) sessions.delete(transport.sessionId);
      void close();
    };
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  };

  app.post('/mcp', handle);
  app.get('/mcp', handle);
  app.delete('/mcp', handle);

  return { app, sessions };
}
