import vm from 'node:vm';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
const parse = text=>JSON.parse(text.replace(/^\uFEFF/,''));
const cases = [
 {id:'minimum-rtt-not-average',op:'estimate',measurements:[{roundTripDelay:10,clockOffset:100},{roundTripDelay:20,clockOffset:110},{roundTripDelay:200,clockOffset:500},{roundTripDelay:300,clockOffset:800}],expected:{averageOffset:100,averageRoundTrip:132.5}},
 {id:'equal-rtt-first-wins',op:'estimate',measurements:[{roundTripDelay:10,clockOffset:-50},{roundTripDelay:10,clockOffset:250}],expected:{averageOffset:-50,averageRoundTrip:10}},
 {id:'empty',op:'estimate',measurements:[],expected:{averageOffset:0,averageRoundTrip:0}},
 {id:'future',op:'wait',target:11000,offset:500,expected:500},
 {id:'late',op:'wait',target:5000,offset:0,expected:0},
 {id:'negative-offset',op:'wait',target:10300,offset:-200,expected:500},
 ...[0,500,10000].map((rtt,i)=>({id:`schedule-${rtt}`,op:'schedule',rtt,expected:[400,950,3000][i]})),
 {id:'reset-discards-pending',op:'reset',measurement:{t0:0,t1:10,t2:10,t3:20,roundTripDelay:20,clockOffset:0}},
 {id:'send-pair',op:'probeSend'}, {id:'close-between-probes',op:'probeSend',closeBeforeSecond:true}
];
for (const [name, drift, groups, expectedPure] of [['pure',0,[1,1],true],['boundary',5,[1,1],true],['impure',5.001,[1,1],false],['interleaved',0,[1,2],false]]) {
 cases.push({id:name,op:'responses',expectedPure,responses:[
 {t0:1000,t1:1055,t2:1056,t3:1011,probeGroupId:groups[0],probeGroupIndex:0},
 {t0:1025,t1:1080+drift,t2:1081+drift,t3:1038,probeGroupId:groups[1],probeGroupIndex:1}]});
}
let seed=1234567;
const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296};
for(let i=0;i<500;i++) {
 cases.push({id:`generated-estimate-${i}`,op:'estimate',measurements:Array.from({length:16},()=>({roundTripDelay:random()*1000,clockOffset:random()*2000-1000}))});
 cases.push({id:`generated-wait-${i}`,op:'wait',now:1e9+random()*1000,target:1e9+random()*1000,offset:random()*400-200});
 cases.push({id:`generated-schedule-${i}`,op:'schedule',rtt:random()*10000});
}
const context=vm.createContext({console:{log(){}}});
vm.runInContext(await readFile('js/generated/core.js','utf8'),context);
context.input=JSON.stringify(cases);
const outputs=parse(vm.runInContext('BeatSyncPrototype.runJson(input)',context));
cases.forEach((test,i)=>{
 if('expected' in test) assert.deepEqual(outputs[i],test.expected,test.id);
 if('expectedPure' in test) assert.equal(outputs[i].values[1]!==null,test.expectedPure,test.id);
});
assert.equal(outputs[9].value,null);
assert.equal(outputs[9].stats.pureCount,0);
assert.equal(outputs[10].sent.length,2);
assert.equal(outputs[10].sent[1].t0-outputs[10].sent[0].t0,25);
assert.equal(outputs[11].sent.length,1);
await mkdir('results',{recursive:true});
await writeFile('js/generated/scenarios.json',JSON.stringify(cases,null,2));
await writeFile('js/generated/oracle.json',JSON.stringify(outputs,null,2));
await writeFile('results/node-oracle.json',JSON.stringify({runtime:process.version,cases:cases.length,deterministicSeed:1234567,upstreamCommit:'94c38ab16ec861835ce0b4bc3a7db82dabbb9b88',handCheckedCases:16,generatedCases:1500},null,2));
console.log(`Node original-code oracle: ${cases.length} cases, 16 edge/behavior cases checked.`);
