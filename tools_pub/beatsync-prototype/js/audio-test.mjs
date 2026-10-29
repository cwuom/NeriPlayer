import vm from 'node:vm';
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
const bundle=await readFile('../../modules/ltwlan/src/main/assets/lan-audio/core.js','utf8');
let perf=1000,latency=.024,pendingDecode=null;const messages=[],starts=[],stops=[];
class AudioNode {
 constructor(){this.gain={value:1};this.frequency={value:0};this.Q={value:0};this.buffer=null;this.onended=null;}
 connect(){} disconnect(){} start(at,offset){if(at!==undefined)starts.push({at,offset,node:this});} stop(at){stops.push({at,node:this});}
}
class AudioContext {
 constructor(){this.state='running';this.sampleRate=48000;this.destination={};this.baseLatency=.01;}
 get currentTime(){return perf/1000} get outputLatency(){return latency}
 createBufferSource(){return new AudioNode()} createGain(){return new AudioNode()} createBiquadFilter(){return new AudioNode()} createOscillator(){return new AudioNode()}
 async resume(){this.state='running'} async close(){this.state='closed'}
 async decodeAudioData(){if(pendingDecode)await pendingDecode;return {duration:120,length:120*48000,numberOfChannels:2,sampleRate:48000}}
}
const context=vm.createContext({performance:{now:()=>perf},AudioContext,navigator:{userAgent:'Android test',maxTouchPoints:0},console:{log(){},warn(){},error(){}},setInterval:()=>1,clearInterval(){},setTimeout:()=>1,
 fetch:async()=>({ok:true,arrayBuffer:async()=>new ArrayBuffer(40)}),LanAudioHost:{nowMs:()=>perf+40000,post:s=>messages.push(JSON.parse(s))}});
context.window=context;vm.runInContext(bundle,context);let id=0,checks=0;
async function command(op,data={}){const n=++id;await context.NeriBeatSyncAudio.dispatch({id:n,op,data});const reply=messages.find(m=>m.event==='reply'&&m.id===n);assert.ok(reply,op);if(reply.error)throw Error(reply.error);return reply.result;}
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-8,`${a} != ${b}`);
const action=(extra={})=>({type:'PLAY',key:'song',revision:1,positionSeconds:5,serverTimeMs:perf+40500,offsetMs:0,nudgeMs:0,...extra});
await command('initialize');checks++;
await command('load',{key:'song',url:'https://local/test',position:0});checks++;
await command('schedule',action());near(starts.at(-1).at,1.476);near(starts.at(-1).offset,5);checks++;
perf=1120;await command('schedule',action({revision:2,serverTimeMs:41500}));near(starts.at(-1).at,1.476);checks++;
const old=starts.at(-1).node;await command('schedule',action({revision:3,serverTimeMs:42000}));assert.ok(stops.some(s=>s.node===old));assert.equal(old.onended,null);checks++;
const count=starts.length;assert.equal((await command('schedule',action({revision:2}))).accepted,false);assert.equal(starts.length,count);checks++;
await command('schedule',action({type:'PAUSE',revision:4,serverTimeMs:42500}));near(stops.at(-1).at,2.476);checks++;
latency=.65;await command('schedule',action({revision:5,serverTimeMs:43000}));near(starts.at(-1).at,3);checks++;
latency=.024;perf=4500;await command('schedule',action({revision:6,serverTimeMs:44000,positionSeconds:10}));near(starts.at(-1).at,4.75);near(starts.at(-1).offset,10.75);checks++;
await command('schedule',action({revision:7,serverTimeMs:45000,nudgeMs:100}));near(starts.at(-1).at,4.876);checks++;
await command('pause');const beforeCancel=starts.length;perf=5500;assert.equal(starts.length,beforeCancel);checks++;
let release;pendingDecode=new Promise(resolve=>release=resolve);
const loading=command('load',{key:'stale',url:'https://local/test'}).then(()=>false,error=>error.message);
await Promise.resolve();await Promise.resolve();await command('pause');release();assert.equal(await loading,'STALE');checks++;
pendingDecode=null;await assert.rejects(command('load',{key:'song',url:'https://local/test',expectedDurationMs:30000}),/AUDIO_DURATION/);checks++;
await command('load',{key:'song',url:'https://local/test',expectedDurationMs:120000});
await assert.rejects(command('schedule',action({revision:8,positionSeconds:999})),/POSITION/);checks++;
await command('resetNtp');
for(let group=0;group<16;group++){
 const t0=1000+group*50;
 assert.equal(await command('ntp',{receivedAt:t0+11,message:{t0,t1:t0+55,t2:t0+56,probeGroupId:group,probeGroupIndex:0}}),null);
 const result=await command('ntp',{receivedAt:t0+36,message:{t0:t0+25,t1:t0+80,t2:t0+81,probeGroupId:group,probeGroupIndex:1}});
 near(result.offset,50);near(result.rtt,10);assert.equal(result.synced,group===15);
}checks++;
await command('dispose');checks++;
await writeFile('results/web-audio-original-code.json',JSON.stringify({checks,status:'passed',scope:'Unmodified extracted functions with simulated Web Audio. No acoustic accuracy claim.'},null,2));
console.log(`Web Audio original-code checks: ${checks} passed`);
