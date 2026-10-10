// ESM + CJS builds with esbuild. Type declarations come from `tsc -p tsconfig.build.json`.
import { rmSync } from 'node:fs';
import { build } from 'esbuild';

rmSync(new URL('../dist', import.meta.url), { recursive: true, force: true });

const common = {
  entryPoints: ['src/index.ts'],
  bundle: true,
  platform: 'neutral',
  target: 'es2020',
  sourcemap: true,
  legalComments: 'none',
  logLevel: 'warning',
};

await build({ ...common, format: 'esm', outfile: 'dist/esm/index.js' });
await build({ ...common, format: 'cjs', outfile: 'dist/cjs/index.cjs' });
console.log('built dist/esm/index.js and dist/cjs/index.cjs');
