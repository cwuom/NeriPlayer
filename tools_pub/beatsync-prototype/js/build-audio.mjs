import { build } from 'esbuild';
import { parse } from '@babel/parser';
import { readFile, mkdir, writeFile, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const manifest=JSON.parse((await readFile(`${root}js/upstream/UPSTREAM.json`,'utf8')).replace(/^\uFEFF/,''));
for(const f of manifest.files) {
 if(createHash('sha256').update(await readFile(`${root}js/upstream/${f.file}`)).digest('hex')!==f.sha256) throw Error(`Source changed: ${f.file}`);
}
const source=await readFile(`${root}js/upstream/global.tsx`,'utf8');
const ast=parse(source,{sourceType:'module',plugins:['typescript','jsx']});
const names=['schedulePlay','schedulePause','playAudio','pauseAudio','getCurrentTrackPosition'];
const helpers=['getAudioPlayer','getSocket','MAX_TRUSTWORTHY_OUTPUT_LATENCY_MS','getFilteredOutputLatencyMs','getWaitTimeSeconds'];
const snippets=new Map();
function visit(node) {
 if(!node || typeof node!=='object') return;
 if(node.type==='VariableDeclaration') for(const d of node.declarations) {
  if(helpers.includes(d.id?.name)) snippets.set(d.id.name,source.slice(node.start,node.end));
 }
 if(node.type==='ObjectProperty' && names.includes(node.key?.name) && node.value?.type==='ArrowFunctionExpression') {
  snippets.set(node.key.name,source.slice(node.start,node.end));
 }
 for(const [key,value] of Object.entries(node)) if(key!=='loc') {
  if(Array.isArray(value)) value.forEach(visit); else if(value && typeof value==='object') visit(value);
 }
}
visit(ast);
for(const name of [...names,...helpers]) if(!snippets.has(name)) throw Error(`Missing upstream symbol ${name}`);
await mkdir(`${root}js/generated`,{recursive:true});
const extracted=`// GENERATED unmodified function bodies from BeatSync ${manifest.commit}. MIT.\nimport {audioContextManager} from '../upstream/audioContextManager';\nimport {calculateWaitTimeMilliseconds} from '../upstream/ntp';\nimport {epochNow,ClientActionEnum} from '../audio/shared';\nconst AudioPlayerError={NotInitialized:'NotInitialized'};\nconst IS_DEMO_MODE=false;\nconst extractFileNameFromUrl=(url:string)=>url;\nconst toast={error:(message:string)=>{throw Error(message)},warning:()=>{}};\nconst sendWSRequest=({ws,request}:any)=>ws.send(JSON.stringify(request));\ntype GlobalState=any;\n${helpers.map(n=>snippets.get(n)).join('\n')}\nexport {getFilteredOutputLatencyMs};\nexport function upstreamActions(get:()=>any,set:(value:any)=>void) { return {\n${names.map(n=>snippets.get(n)).join(',\n')}\n}; }\n`;
await writeFile(`${root}js/generated/audio-upstream.ts`,extracted);
const out=fileURLToPath(new URL('../../../modules/ltwlan/src/main/assets/lan-audio/',import.meta.url));
await mkdir(out,{recursive:true});
await build({entryPoints:[`${root}js/audio/entry.ts`],bundle:true,format:'iife',globalName:'NeriBeatSyncAudio',target:'es2018',platform:'browser',outfile:`${out}core.js`,banner:{js:`/* BeatSync ${manifest.commit}. Copyright (c) 2025 freeman-jiang. MIT. See LICENSE. */`},alias:{'@beatsync/shared':`${root}js/audio/shared.ts`}});
await copyFile(`${root}js/upstream/LICENSE`,`${out}LICENSE`);
await writeFile(`${out}index.html`,'<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>LAN audio engine</title></head><body><script src="core.js"></script></body></html>');
await writeFile(`${out}UPSTREAM.json`,JSON.stringify({...manifest,extracted:[...helpers,...names].map(name=>({name,sha256:createHash('sha256').update(snippets.get(name)).digest('hex')})),generatedSha256:createHash('sha256').update(await readFile(`${out}core.js`)).digest('hex')},null,2));
console.log(`Extracted ${snippets.size} unchanged upstream symbols and full AudioContextManager; ${out}`);
