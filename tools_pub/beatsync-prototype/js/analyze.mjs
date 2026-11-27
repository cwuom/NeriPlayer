import { readFile, writeFile } from 'node:fs/promises';
const report = JSON.parse(await readFile('results/websocket-loopback.json','utf8'));
const percentile=(xs,q)=>[...xs].sort((a,b)=>a-b)[Math.max(0,Math.ceil(xs.length*q)-1)];
const summaries=[...new Set(report.rounds.map(r=>r.server))].map(server=>{
  const rounds=report.rounds.filter(r=>r.server===server);
  const pairs=rounds.flatMap(r=>r.pairs);
  const rtts=pairs.flatMap(p=>[p.first.rttMs,p.second.rttMs]);
  return {server,rounds:rounds.length,pairs:pairs.length,purePairs:pairs.filter(p=>p.pure).length,pureRate:pairs.filter(p=>p.pure).length/pairs.length,rttMs:{p50:percentile(rtts,.5),p95:percentile(rtts,.95),p99:percentile(rtts,.99),max:Math.max(...rtts)}};
});
await writeFile('results/websocket-summary.json',JSON.stringify({scope:report.scope,limitations:report.limitations,summaries},null,2));
console.log(JSON.stringify(summaries,null,2));
