import { build } from 'esbuild';
import { copyFile, mkdir } from 'node:fs/promises';
await mkdir('generated/resources/web', { recursive: true });
await build({ entryPoints: ['web/src/app.ts'], bundle: true, format: 'esm', target: 'es2022', sourcemap: true,
  outfile: 'generated/resources/web/app.js' });
for (const file of ['index.html', 'style.css']) await copyFile(`web/${file}`, `generated/resources/web/${file}`);
