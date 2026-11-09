import * as ntp from './upstream/ntp';
export function invoke(json: string): string {
 const request = JSON.parse(json);
 (globalThis as any).__hostNowMs = request.now;
 if(request.op === 'reset') {ntp.resetProbeState();return 'null';}
 if(request.op === 'response') return JSON.stringify(ntp.handleNTPResponse(request.response));
 if(request.op === 'estimate') return JSON.stringify(ntp.calculateOffsetEstimate(request.measurements));
 throw new Error('Unknown host operation');
}
