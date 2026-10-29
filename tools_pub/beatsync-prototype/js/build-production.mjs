import {build} from 'esbuild';
import {readFile,mkdir,writeFile,copyFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const manifest=JSON.parse((await readFile('js/upstream/UPSTREAM.json','utf8')).replace(/^\uFEFF/,''));
for(const source of manifest.files) {
 const hash=createHash('sha256').update(await readFile(`js/upstream/${source.file}`)).digest('hex');
 if(hash!==source.sha256) throw new Error(`Changed upstream ${source.file}`);
}
const output='../../modules/ltwlan/src/main/resources/beatsync';
await mkdir(output,{recursive:true});
await build({entryPoints:['js/production.ts'],bundle:true,format:'iife',globalName:'LanBeatSync',target:'es2015',platform:'neutral',outfile:`${output}/core.js`,banner:{js:'/* BeatSync 94c38ab; Copyright (c) 2025 freeman-jiang. MIT. See LICENSE. Generated; do not edit. */\nvar console = {log: function(){}};'},alias:{'@beatsync/shared':fileURLToPath(new URL('./shared.ts',import.meta.url))}});
await copyFile('js/upstream/LICENSE',`${output}/LICENSE`);
await writeFile(`${output}/UPSTREAM.json`,JSON.stringify({...manifest,generatedSha256:createHash('sha256').update(await readFile(`${output}/core.js`)).digest('hex')},null,2));
