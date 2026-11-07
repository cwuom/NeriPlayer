import * as ntp from './upstream/ntp';
import { calculateScheduleTimeMs } from './upstream/config';

export function runScenario(s: any): any {
  (globalThis as any).__hostNowMs = s.now ?? 10000;
  switch (s.op) {
    case 'estimate': return ntp.calculateOffsetEstimate(s.measurements);
    case 'wait': return ntp.calculateWaitTimeMilliseconds(s.target, s.offset);
    case 'schedule': return calculateScheduleTimeMs(s.rtt);
    case 'responses': {
      ntp.resetProbeState();
      const values = s.responses.map((r: any) => {
        (globalThis as any).__hostNowMs = r.t3;
        return ntp.handleNTPResponse(r);
      });
      return { values, stats: ntp.getProbeStats() };
    }
    case 'reset': {
      ntp.resetProbeState();
      ntp.validateProbePair({measurement:s.measurement,probeGroupId:1,probeGroupIndex:0});
      ntp.resetProbeState();
      return {value:ntp.validateProbePair({measurement:s.measurement,probeGroupId:1,probeGroupIndex:1}),stats:ntp.getProbeStats()};
    }
    case 'probeSend': {
      ntp.resetProbeState();
      const host = globalThis as any;
      const sent: any[] = [];
      let pending: (()=>void) | undefined;
      let gap = 0;
      host.WebSocket = { OPEN:1 };
      host.setTimeout = (fn: ()=>void, delay:number) => { pending=fn; gap=delay; return 1; };
      const ws:any = {readyState:1,send:(json:string)=>sent.push(JSON.parse(json))};
      ntp.sendProbePair({ws,currentRTT:12.5,compensationMs:40,nudgeMs:-5});
      host.__hostNowMs += gap;
      if (s.closeBeforeSecond) ws.readyState=3;
      pending!();
      return {sent,gap,stats:ntp.getProbeStats()};
    }
    default: throw new Error(`Unknown op ${s.op}`);
  }
}
export function runJson(json: string): string {
  return JSON.stringify(JSON.parse(json).map(runScenario));
}
