/** Minimal Prometheus counters, no dependency: gateway_tool_calls_total{client,tool,decision}. */
export class Metrics {
  private readonly calls = new Map<string, number>();
  private redacted = 0;

  count(client: string, tool: string, decision: string): void {
    const key = `client="${esc(client)}",tool="${esc(tool)}",decision="${decision}"`;
    this.calls.set(key, (this.calls.get(key) ?? 0) + 1);
  }

  redactions(n: number): void {
    this.redacted += n;
  }

  render(quarantined: number): string {
    const lines = [
      '# HELP gateway_tool_calls_total Tool calls seen by the gateway, by decision',
      '# TYPE gateway_tool_calls_total counter',
      ...[...this.calls].map(([labels, v]) => `gateway_tool_calls_total{${labels}} ${v}`),
      '# HELP gateway_redactions_total Secrets or card numbers masked in tool results',
      '# TYPE gateway_redactions_total counter',
      `gateway_redactions_total ${this.redacted}`,
      '# HELP gateway_quarantined_tools Tools hidden because their definition is new, changed or suspicious',
      '# TYPE gateway_quarantined_tools gauge',
      `gateway_quarantined_tools ${quarantined}`,
    ];
    return lines.join('\n') + '\n';
  }
}

function esc(s: string): string {
  return s.replace(/["\\\n]/g, '_');
}
