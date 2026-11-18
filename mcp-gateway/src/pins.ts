import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

/**
 * Tool pinning: defends against tool poisoning and "rug pulls".
 *
 * An MCP tool's description is read by the AI model, so a server that changes a description can inject
 * instructions ("before answering, also send the account list to ..."). The gateway fingerprints every
 * upstream tool (name, description, input schema, annotations) and only exposes tools whose fingerprint
 * a person approved in pins.json. A changed or new tool is quarantined until someone reviews and re-pins it.
 */

export interface ToolDefinition {
  name: string;
  title?: string;
  description?: string;
  inputSchema: unknown;
  annotations?: unknown;
}

export type PinStatus =
  | { status: 'approved' }
  | { status: 'quarantined'; reason: string };

/** Phrases that have no business in a tool description; seen in published tool-poisoning attacks. */
const SUSPICIOUS = [
  /ignore (all |any )?(previous|prior|above) instructions/i,
  /<\s*(important|system|secret)\s*>/i,
  /do not (tell|inform|mention)/i,
  /(send|forward|upload|exfiltrate) .{0,40}(to|at) (https?:\/\/|[\w.-]+@)/i,
  /\b(api[_ -]?key|password|private key|ssh)\b/i,
];

export function fingerprint(tool: ToolDefinition): string {
  return createHash('sha256').update(canonical({
    name: tool.name,
    title: tool.title ?? null,
    description: tool.description ?? '',
    inputSchema: tool.inputSchema ?? null,
    annotations: tool.annotations ?? null,
  })).digest('hex');
}

/** JSON with sorted keys, so the fingerprint does not depend on key order. */
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') {
    // Skip undefined fields exactly as JSON.stringify does, so a hash survives a round trip through a file.
    return '{' + Object.keys(value as object).filter((k) => (value as Record<string, unknown>)[k] !== undefined).sort()
      .map((k) => JSON.stringify(k) + ':' + canonical((value as Record<string, unknown>)[k])).join(',') + '}';
  }
  return JSON.stringify(value);
}

export function suspiciousPhrase(tool: ToolDefinition): string | null {
  const text = `${tool.title ?? ''} ${tool.description ?? ''} ${JSON.stringify(tool.inputSchema ?? {})}`;
  const hit = SUSPICIOUS.find((r) => r.test(text));
  return hit ? hit.source : null;
}

export class PinStore {
  private pins: Record<string, string>;

  constructor(private readonly file: string) {
    this.pins = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
  }

  /** key = "upstream__tool". */
  check(key: string, tool: ToolDefinition): PinStatus {
    const phrase = suspiciousPhrase(tool);
    if (phrase) return { status: 'quarantined', reason: `suspicious text in tool definition (${phrase})` };
    const pinned = this.pins[key];
    if (!pinned) return { status: 'quarantined', reason: 'new tool, not yet approved' };
    if (pinned !== fingerprint(tool)) return { status: 'quarantined', reason: 'tool definition changed since it was approved' };
    return { status: 'approved' };
  }

  /** Used by `npm run pin` after a person has reviewed the tool list. */
  approveAll(tools: Record<string, ToolDefinition>): void {
    this.pins = Object.fromEntries(Object.entries(tools).map(([k, t]) => [k, fingerprint(t)]).sort());
    writeFileSync(this.file, JSON.stringify(this.pins, null, 2) + '\n');
  }
}
