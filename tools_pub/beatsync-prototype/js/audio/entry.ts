import {audioContextManager} from '../upstream/audioContextManager';
import {upstreamActions,getFilteredOutputLatencyMs} from '../generated/audio-upstream';
import * as ntp from '../upstream/ntp';
import {epochNow} from './shared';
declare const LanAudioHost:{nowMs():number;post(message:string):void};
const host=globalThis as any;
let loadGeneration=0,revision=-1,closed=false,timer=0;
let samples:any[]=[],buffer:AudioBuffer|null=null,stopAt:number|null=null,clockRtt=Infinity;
const diagnostics:any[]=[];
const post=(event:string,data:any={})=>{if(!closed)LanAudioHost.post(JSON.stringify({event,...data}));};
function diagnostic(kind:string,data:any={}){
 const entry={kind,localMs:Number.isFinite(host.__nativeOffset)?epochNow():null,...data};
 diagnostics.push(entry);if(diagnostics.length>128)diagnostics.shift();post('diagnostic',{detail:entry});
}
let state:any={isInitingSystem:true,isPlaying:false,currentTime:0,playbackStartTime:0,playbackOffset:0,duration:0,
 offsetEstimate:0,nudgeOffsetMs:0,selectedAudioUrl:null,audioSources:[],audioPlayer:null,
 socket:{send:()=>post('resync')},findAudioIndexByUrl:(url:string)=>state.audioSources.findIndex((s:any)=>s.source.url===url)<0?null:0,
 skipToNextTrack:()=>{state.isPlaying=false;state.currentTime=state.duration;post('ended');}};
const actions=upstreamActions(()=>state,(change:any)=>{state={...state,...(typeof change==='function'?change(state):change)};});
Object.assign(state,actions);
function synchronizeClock(){
 let best=Infinity,offset=0;
 for(let i=0;i<16;i++){const before=performance.now(),native=LanAudioHost.nowMs(),after=performance.now();if(after-before<best){best=after-before;offset=native-(before+after)/2;}}
 if(best>5)throw Error('CLOCK');host.__nativeOffset=offset;clockRtt=best;
}
function stopNode(){
 const node=state.audioPlayer?.sourceNode;
 if(node){node.onended=null;try{node.stop();}catch{}try{node.disconnect();}catch{}}
 stopAt=null;state.isPlaying=false;
 if(state.audioPlayer)state.audioPlayer={...state.audioPlayer,sourceNode:audioContextManager.getContext().createBufferSource()};
}
function snapshot(){
 const ctx=audioContextManager.getContext();
 const playing=(state.isPlaying||(stopAt!==null&&ctx.currentTime<stopAt))&&ctx.currentTime>=state.playbackStartTime&&ctx.state==='running';
 const position=playing?Math.min(state.playbackOffset+ctx.currentTime-state.playbackStartTime,state.duration):state.currentTime;
 if(stopAt!==null&&ctx.currentTime>=stopAt)stopAt=null;
 return {playing,positionSeconds:Math.max(0,position),durationSeconds:state.duration,contextState:ctx.state,
 sampleRate:ctx.sampleRate,outputLatencyMs:getFilteredOutputLatencyMs(),baseLatencyMs:(ctx.baseLatency||0)*1000,clockRoundTripMs:clockRtt};
}
async function initialize(){
 synchronizeClock();const ctx=audioContextManager.getContext();await audioContextManager.resume();
 if(ctx.state!=='running')throw Error('AUDIO_SUSPENDED');
 state.audioPlayer={sourceNode:ctx.createBufferSource(),audioContext:ctx};state.isInitingSystem=false;
 audioContextManager.setStateChangeCallback(s=>{post('context',{state:s});if(s!=='running')post('resync');});
 timer=window.setInterval(()=>{try{post('state',snapshot());}catch{}},200);return snapshot();
}
async function load(d:any){
 const generation=++loadGeneration;stopNode();state.audioSources=[];buffer=null;state.selectedAudioUrl=d.key;state.currentTime=d.position||0;
 const response=await fetch(d.url,{cache:'no-store',credentials:'omit'});if(!response.ok)throw Error('SOURCE');
 const bytes=await response.arrayBuffer();if(bytes.byteLength>200*1024*1024)throw Error('MEMORY_LIMIT');
 if(generation!==loadGeneration)throw Error('STALE');
 const decoded=await audioContextManager.decodeAudioData(bytes);
 if(generation!==loadGeneration||closed)throw Error('STALE');
 if(decoded.numberOfChannels>2||decoded.length*decoded.numberOfChannels*4>512*1024*1024)throw Error('MEMORY_LIMIT');
 diagnostic('decode-check',{expectedDurationMs:d.expectedDurationMs||0,actualDurationMs:decoded.duration*1000,frames:decoded.length,sampleRate:decoded.sampleRate,channels:decoded.numberOfChannels});
 if(d.expectedDurationMs>0&&Math.abs(decoded.duration*1000-d.expectedDurationMs)>Math.max(1000,d.expectedDurationMs*.01))throw Error('AUDIO_DURATION');
 buffer=decoded;state.duration=decoded.duration;state.audioSources=[{source:{url:d.key},status:'loaded',buffer:decoded}];
 diagnostic('decoded',{key:d.key,duration:decoded.duration,sampleRate:decoded.sampleRate,channels:decoded.numberOfChannels});return {key:d.key,durationSeconds:decoded.duration};
}
function schedule(d:any){
 if(d.revision<revision)return {accepted:false};
 if(!buffer||state.selectedAudioUrl!==d.key)throw Error('LOADING');
 synchronizeClock();if(!audioContextManager.isReady())throw Error('AUDIO_SUSPENDED');
 revision=d.revision;state.offsetEstimate=d.offsetMs;state.nudgeOffsetMs=d.nudgeMs;
 if(d.type==='PLAY'){
  const position=Math.max(0,d.positionSeconds);if(position>=buffer.duration)throw Error('POSITION');stopAt=null;
  actions.schedulePlay({audioSource:d.key,trackTimeSeconds:position,targetServerTime:d.serverTimeMs});
 }else{
  if(!state.isPlaying&&stopAt===null){state.currentTime=d.positionSeconds;return {accepted:true,...snapshot()};}
  const ctx=audioContextManager.getContext();stopAt=ctx.currentTime+Math.max(0,Math.max(0,d.serverTimeMs-epochNow()-d.offsetMs-d.nudgeMs)-getFilteredOutputLatencyMs())/1000;
  actions.schedulePause({targetServerTime:d.serverTimeMs});
 }
 diagnostic('scheduled',{type:d.type,revision,targetServerMs:d.serverTimeMs,submittedServerMs:epochNow()+d.offsetMs,contextStart:state.playbackStartTime,position:state.playbackOffset,...snapshot()});
 return {accepted:true,...snapshot()};
}
async function command(op:string,d:any):Promise<any>{
 switch(op){
 case 'initialize':return initialize();
 case 'clock':synchronizeClock();return snapshot();
 case 'load':return load(d);
 case 'schedule':return schedule(d);
 case 'pause':state.currentTime=snapshot().positionSeconds;stopNode();loadGeneration++;return snapshot();
 case 'position':stopNode();state.currentTime=Math.max(0,d.positionSeconds);return snapshot();
 case 'nudge':state.nudgeOffsetMs=d.value;if(snapshot().playing)actions.playAudio({offset:Math.max(0,Math.min(snapshot().positionSeconds+d.delta/1000,state.duration-.001)),when:0,audioIndex:0});return snapshot();
 case 'resetNtp':samples=[];ntp.resetProbeState();return null;
 case 'ntp':{
  host.__ntpReceiveAt=d.receivedAt;let sample;try{sample=ntp.handleNTPResponse(d.message);}finally{delete host.__ntpReceiveAt;}
  if(!sample||sample.roundTripDelay<0)return null;if(samples.length===16)samples.shift();samples.push(sample);
  const e=ntp.calculateOffsetEstimate(samples);return {offset:e.averageOffset,rtt:e.averageRoundTrip,synced:samples.length===16};
 }
 case 'diagnostics':return diagnostics;
 case 'dispose':loadGeneration++;stopNode();clearInterval(timer);buffer=null;state.audioSources=[];await audioContextManager.getContext().close();return null;
 default:throw Error('UNKNOWN');
 }
}
export async function dispatch(message:any){
 try{const result=await command(message.op,message.data||{});post('reply',{id:message.id,result});}
 catch(error){post('reply',{id:message.id,error:String((error as Error).message)});}
}
post('ready');
