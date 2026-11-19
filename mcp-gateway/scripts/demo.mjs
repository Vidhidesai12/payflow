// `npm run demo`: drive the running gateway the way an AI assistant would, and show each decision.
// Needs the PayFlow stack, analytics-mcp and the gateway running (see README). Local demo tokens only.
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { ElicitRequestSchema } from '@modelcontextprotocol/sdk/types.js';

const url = new URL(process.env.GATEWAY_URL ?? 'http://127.0.0.1:8085/mcp');

async function connect(token, confirm) {
  const client = new Client({ name: 'gateway-demo', version: '1.0.0' },
    { capabilities: confirm ? { elicitation: { form: {} } } : {} });
  if (confirm) {
    client.setRequestHandler(ElicitRequestSchema, async (request) => {
      console.log(`          person is asked: "${request.params.message}" -> ${confirm}`);
      return confirm === 'yes' ? { action: 'accept', content: { confirm: true } } : { action: 'decline' };
    });
  }
  await client.connect(new StreamableHTTPClientTransport(url, { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
  return client;
}

async function step(title, client, name, args) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content.map((c) => (c.type === 'text' ? c.text : '')).join(' ').replace(/\s+/g, ' ');
  console.log(`${result.isError ? '  BLOCKED' : '  OK     '} ${title}\n          ${name} ->${text.length > 150 ? text.slice(0, 150) + '…' : text}`);
  return result;
}

const reader = await connect('gateway-demo-readonly');
const tools = (await reader.listTools()).tools.map((t) => t.name);
console.log(`\nassistant-readonly sees ${tools.length} tools: ${tools.join(', ')}\n`);
await step('Java server, read', reader, 'payflow__list_payments', {});
await step('Python server, read', reader, 'analytics__volume_by_day', { days: 7 });
await step('Read-only client tries to create a payment', reader, 'payflow__create_payment',
  { debtorAccount: 'ACC10001', creditorAccount: 'ACC20002', amount: '50.00', currency: 'USD', idempotencyKey: `demo-${Date.now()}-r` });

const operator = await connect('gateway-demo-operator', 'yes');
console.log('');
await step('Operator asks for 900.00 (gateway limit for this client is 500)', operator, 'payflow__create_payment',
  { debtorAccount: 'ACC10001', creditorAccount: 'ACC20002', amount: '900.00', currency: 'USD', idempotencyKey: `demo-${Date.now()}-a` });
await step('Operator asks for 120.00; PayFlow asks the person through the gateway', operator, 'payflow__create_payment',
  { debtorAccount: 'ACC10001', creditorAccount: 'ACC20002', amount: '120.00', currency: 'USD', idempotencyKey: `demo-${Date.now()}-b` });

const decliner = await connect('gateway-demo-operator', 'no');
await step('Same request, but the person says no', decliner, 'payflow__create_payment',
  { debtorAccount: 'ACC10001', creditorAccount: 'ACC20002', amount: '120.00', currency: 'USD', idempotencyKey: `demo-${Date.now()}-c` });

console.log('\nEvery decision above is in the gateway audit log. Check its hash chain with: npm run verify-audit');
console.log('(Docker gateway: docker compose cp mcp-gateway:/data/audit.jsonl ./audit-docker.jsonl, then npm run verify-audit -- audit-docker.jsonl)');
for (const c of [reader, operator, decliner]) await c.close();
