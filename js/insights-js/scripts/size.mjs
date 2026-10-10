// Size budget (CLAUDE.md rule 4): the minified, gzipped bundle a consumer pays for.
import { gzipSync } from 'node:zlib';
import { build } from 'esbuild';

const BUDGET_GZIP_BYTES = 9 * 1024;

const result = await build({
  entryPoints: ['src/index.ts'],
  bundle: true,
  minify: true,
  format: 'esm',
  platform: 'neutral',
  target: 'es2020',
  write: false,
});
const code = result.outputFiles[0].contents;
const gz = gzipSync(code, { level: 9 }).length;
console.log(
  `@devstrike/insights-js: ${code.length} bytes minified, ${gz} bytes gzipped (budget ${BUDGET_GZIP_BYTES})`,
);
if (gz > BUDGET_GZIP_BYTES) {
  console.error(`over budget by ${gz - BUDGET_GZIP_BYTES} bytes`);
  process.exit(1);
}
