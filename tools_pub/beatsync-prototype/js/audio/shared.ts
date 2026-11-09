export {NTP_CONSTANTS,LOW_PASS_CONSTANTS} from '../upstream/constants';
export const ClientActionEnum={enum:{NTP_REQUEST:'NTP_REQUEST',SYNC:'SYNC'}};
export const epochNow=()=>{
 const host=globalThis as any;
 if(host.__ntpReceiveAt!==undefined) return host.__ntpReceiveAt;
 if(!Number.isFinite(host.__nativeOffset)) throw Error('CLOCK');
 return performance.now()+host.__nativeOffset;
};
