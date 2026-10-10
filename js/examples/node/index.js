// A Node service that uses @acme/chat. It knows nothing about Insights.
import { AcmeChat } from '@acme/chat';

AcmeChat.init({
  apiKey: process.env.ACME_API_KEY ?? 'sk_test_support_bot',
  insightsEndpoint: process.env.INSIGHTS_ENDPOINT ?? 'http://127.0.0.1:8787/v1/batches',
});
AcmeChat.connect();
AcmeChat.sendMessage('support', 'hello');
AcmeChat.sendMessage('', 'this text must never reach Insights');
AcmeChat.sendMessageSync('support', 'legacy');
await AcmeChat.flushTelemetry();
console.log('acme-support-bot: done');
