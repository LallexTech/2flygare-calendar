import { build } from 'esbuild';
await build({
  entryPoints: ['frontend/planner.js'],
  outdir: 'src/main/resources/static/planner',
  bundle: true,
  minify: true,
  sourcemap: false,
  legalComments: 'external',
  target: ['es2022'],
  loader: { '.woff': 'file', '.woff2': 'file', '.ttf': 'file', '.svg': 'file' },
});
