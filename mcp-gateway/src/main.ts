import { AuditLog } from './audit.js';
import { Catalog } from './catalog.js';
import { loadConfig } from './config.js';
import { createApp } from './http.js';
import { Metrics } from './metrics.js';
import { PinStore } from './pins.js';
import { RateLimiter } from './policy.js';

const config = loadConfig(process.env.GATEWAY_CONFIG ?? 'gateway.config.json');
const audit = new AuditLog(config.auditFile);
const catalog = new Catalog(config.upstreams, new PinStore(config.pinsFile), audit);

await catalog.refresh();
setInterval(() => void catalog.refresh(), config.catalogRefreshSeconds * 1000).unref();

const { app } = createApp({ config, catalog, audit, limiter: new RateLimiter(), metrics: new Metrics() });
app.listen(config.port, process.env.HOST ?? '127.0.0.1', () => {
  const tools = catalog.all();
  console.log(`mcp-gateway on :${config.port}; ${tools.filter((t) => t.pin.status === 'approved').length} tools approved, `
    + `${tools.filter((t) => t.pin.status === 'quarantined').length} quarantined; upstreams ${JSON.stringify(catalog.health())}`);
});
