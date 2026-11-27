import { build } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { transformSync } from '@babel/core';
import presetEnv from '@babel/preset-env';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const provenance = JSON.parse((await readFile(path.join(here, 'upstream/UPSTREAM.json'), 'utf8')).replace(/^\uFEFF/, ''));
for (const record of provenance.files) {
  const actual = createHash('sha256').update(await readFile(path.join(here, 'upstream', record.file))).digest('hex');
  if (actual !== record.sha256) throw new Error(`Upstream changed: ${record.file}`);
}
await mkdir(path.join(here, 'generated'), { recursive: true });
await build({entryPoints:[path.join(here,'entry.ts')],bundle:true,format:'iife',globalName:'BeatSyncPrototype',target:'es2015',platform:'neutral',outfile:path.join(here,'generated/core.js'), sourcemap:true, legalComments:'inline', alias:{'@beatsync/shared':path.join(here,'shared.ts')}});
console.log(`Bundled unmodified upstream files; verified ${provenance.files.length} SHA-256 hashes.`);

// Rhino needs ES5 syntax; the Node oracle continues to execute the independent ES2015 bundle.
const bundled = await readFile(path.join(here,'generated/core.js'),'utf8');
const legacy = transformSync(bundled, {filename:'core.js',presets:[[presetEnv,{targets:{ie:'11'},modules:false,useBuiltIns:false}]],sourceMaps:true,comments:true});
await writeFile(path.join(here,'generated/core-es5.js'),legacy.code);
await writeFile(path.join(here,'generated/core-es5.js.map'),JSON.stringify(legacy.map));