// Prints what ClickHouse holds for one customer key, to check an example end to end.
//   node examples/verify-clickhouse.mjs <customerKeyHash>
const [customerKey] = process.argv.slice(2);
if (!/^[A-Za-z0-9_-]{8,128}$/.test(customerKey ?? '')) {
  console.error('usage: node verify-clickhouse.mjs <customerKeyHash>');
  process.exit(2);
}
const url = process.env.CLICKHOUSE_URL ?? 'http://localhost:8123';
const auth = Buffer.from(`default:${process.env.CLICKHOUSE_PASSWORD ?? 'insights'}`).toString(
  'base64',
);
const database = process.env.CLICKHOUSE_DATABASE ?? 'insights';

async function query(sql) {
  const res = await fetch(`${url}/?database=${database}&default_format=JSONEachRow`, {
    method: 'POST',
    headers: { authorization: `Basic ${auth}` },
    body: sql,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(text);
  return text.trim()
    ? text
        .trim()
        .split('\n')
        .map((l) => JSON.parse(l))
    : [];
}

const where = `customer_key = '${customerKey}'`;
console.log(
  'envelope:',
  await query(
    `SELECT DISTINCT sdk_name, sdk_version, client_lang, client_mode, wrapper_kind, wrapper_version, app_id, app_version, device_os, device_os_version, device_class FROM events WHERE ${where}`,
  ),
);
console.log(
  'kinds:',
  Object.fromEntries(
    (
      await query(
        `SELECT kind, count() AS n FROM events WHERE ${where} GROUP BY kind ORDER BY kind`,
      )
    ).map((r) => [r.kind, Number(r.n)]),
  ),
);
console.log(
  'calls:',
  (await query(`SELECT counts FROM events WHERE ${where} AND kind = 'calls'`)).map((r) => r.counts),
);
console.log(
  'errors:',
  await query(`SELECT code, layer, frames FROM events WHERE ${where} AND kind = 'error'`),
);
console.log(
  'health:',
  await query(`SELECT check_name, ok, detail FROM events WHERE ${where} AND kind = 'health'`),
);
