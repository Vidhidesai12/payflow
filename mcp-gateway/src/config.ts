import { readFileSync } from 'node:fs';

/** One MCP server behind the gateway. Its token comes from an environment variable, never from a client. */
export interface UpstreamConfig {
  name: string;
  url: string;
  tokenEnv: string;
}

/** A numeric argument limit, e.g. payflow__create_payment.amount <= 250. */
export interface ArgumentLimit {
  tool: string;
  field: string;
  max: number;
}

/** What one AI client may do through the gateway. Tool patterns are namespaced, e.g. "payflow__get_*". */
export interface ClientPolicy {
  name: string;
  tokenSha256: string;
  allowTools: string[];
  denyTools: string[];
  callsPerMinute: number;
  argumentLimits: ArgumentLimit[];
}

export interface GatewayConfig {
  port: number;
  allowedHosts: string[];
  allowedOrigins: string[];
  upstreams: UpstreamConfig[];
  clients: ClientPolicy[];
  pinsFile: string;
  auditFile: string;
  maxArgumentBytes: number;
  catalogRefreshSeconds: number;
}

export function loadConfig(path: string): GatewayConfig {
  const raw = JSON.parse(readFileSync(path, 'utf8')) as GatewayConfig;
  const env = process.env;
  return {
    ...raw,
    port: Number(env.PORT ?? raw.port),
    allowedHosts: env.ALLOWED_HOSTS ? env.ALLOWED_HOSTS.split(',') : raw.allowedHosts,
    upstreams: raw.upstreams.map((u) => ({ ...u, url: env[`UPSTREAM_${u.name.toUpperCase()}_URL`] ?? u.url })),
    pinsFile: env.PINS_FILE ?? raw.pinsFile,
    auditFile: env.AUDIT_FILE ?? raw.auditFile,
  };
}
