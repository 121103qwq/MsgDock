import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { brotliCompressSync } from 'node:zlib';

const html = readFileSync(new URL('../web-ui/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const response = (data, status=200) => ({ok:status<400,status,json:async()=>data});
const message = seq => ({seq,body:`test ${seq}`,sender:'synthetic',received_at:1});

test('download links match the Android build and label each platform version separately',()=>{
  const gradle=readFileSync(new URL('../app/build.gradle',import.meta.url),'utf8');
  const version=gradle.match(/versionName '([^']+)'/)[1];
  const links=[...html.matchAll(/<a[^>]+href="([^"]+)"[^>]*>([^<]+)<\/a>/g)];
  const android=links.find(([,url])=>url.endsWith('.apk'));
  const windows=links.find(([,url])=>url.endsWith('.exe'));
  const source=links.find(([,url])=>url.endsWith('.zip'));
  assert(android && windows && source);
  assert.equal(android[1],`https://github.com/121103qwq/MsgDock/releases/download/v${version}/MsgDock-Android-v${version}-debug.apk`);
  assert(android[2].includes(`Android v${version}`));
  assert(source[1].endsWith(`/v${version}/MsgDock-source-v${version}.zip`));
  const windowsVersion=windows[1].match(/MsgDock-Windows-v([\d.]+)\.exe$/)[1];
  const windowsMain=readFileSync(new URL('../windows/main.go',import.meta.url),'utf8');
  // A platform may have newer candidate source without publishing its installer.
  const publishedWindows=process.env.MSGDOCK_WINDOWS_DOWNLOAD_VERSION || windowsMain.match(/appVersion\s*=\s*"([^"]+)"/)[1];
  assert.equal(windowsVersion,publishedWindows);
  assert(windows[2].includes(`Windows v${windowsVersion}`));
  assert(html.includes(`/v${windowsVersion}/SHA256SUMS-v${windowsVersion}.txt`));
  assert(html.includes(`/v${version}/SHA256SUMS-v${version}.txt`));
});

function harness(fetcher=async()=>response({messages:[],next_seq:1})) {
  const timers=new Map(), writes=[], events={}, appEvents={}, storage=new Map();
  let timerId=0;
  const app={addEventListener:(name,fn)=>(appEvents[name]??=[]).push(fn),querySelectorAll:()=>[],set innerHTML(value){writes.push(value);}};
  const status={textContent:''};
  const location={protocol:'https:',origin:'https://msgdock.dpdns.org',href:'https://msgdock.dpdns.org/inbox',pathname:'/inbox'};
  const document={hidden:false,getElementById:id=>id==='app'?app:status,addEventListener:(name,fn)=>events[name]=fn,
    createElement:()=>({remove(){}}),body:{appendChild(){}}};
  const sandbox={document,location,navigator:{onLine:true},Headers,AbortController,URL,console,
    fetch:fetcher,setTimeout:(fn,ms)=>{timers.set(++timerId,{fn,ms});return timerId;},clearTimeout:id=>timers.delete(id),
    localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)},
    history:{pushState:(_,__,path)=>location.pathname=path,replaceState:(_,__,path)=>location.pathname=path},
    window:{addEventListener:(name,fn)=>events[name]=fn}};
  const context=vm.createContext(sandbox);
  // Expose lexical functions only in this VM test; production has no testing globals.
  vm.runInContext(script.replace("window.addEventListener('popstate',renderRoute); bootstrap();",
    "globalThis.testApi={state,api,loadMessages,fetchAfterPages,startPolling,resumePolling,renderRoute};"),context);
  const api=context.testApi;
  api.state.user={id:'user-a'}; api.state.lastSeq=1; api.state.messages=[message(1)];
  return {...api,app,appEvents,events,document,location,timers,writes,storage,status,sandbox};
}

test('source stays self-contained and API requests never use browser response cache',async()=>{
  assert.doesNotMatch(html,/<script[^>]+src=|<link[^>]+(?:stylesheet|preconnect)/);
  let options;
  const h=harness(async(_,init)=>{options=init;return response({});});
  await h.api('/api/v1/me');
  assert.equal(options.cache,'no-store'); assert.equal(options.credentials,'same-origin');
  assert.equal(h.timers.size,0);
  console.log(`HTML=${Buffer.byteLength(html)} bytes; local Brotli=${brotliCompressSync(html).length} bytes (not a live measurement)`);
});

test('unchanged polls neither redraw DOM nor lose messages',async()=>{
  const h=harness(); await h.loadMessages(false);
  assert.equal(h.writes.length,0); assert.equal(h.state.messages[0].seq,1);
  assert.equal(h.state.lastSeq,1); assert.equal([...h.timers.values()][0].ms,3000);
});

test('recovery and recent page start concurrently, deduplicate and advance cursor',async()=>{
  const pending=[];
  const h=harness(url=>new Promise(resolve=>pending.push({url,resolve})));
  h.state.resumeCursor=1; h.state.messages=[];
  const run=h.loadMessages(true);
  assert.equal(pending.length,2);
  assert.match(pending[0].url,/after=1/); assert.doesNotMatch(pending[1].url,/after=/);
  pending[0].resolve(response({messages:[message(2)],next_seq:2}));
  pending[1].resolve(response({messages:[message(1),message(2)],next_seq:2}));
  await run;
  assert.equal(h.state.messages.length,2); assert.equal(h.state.messages[0].seq,2);
  assert.equal(h.storage.get('msgdock:last-seq:user-a'),'2'); assert.equal(h.writes.length,1);
});

test('all recovery pages fetched with bounded retained memory',async()=>{
  const cursors=[];
  const h=harness(async url=>{
    const after=Number(new URL(url,'https://example.test').searchParams.get('after')); cursors.push(after);
    const end=Math.min(after+100,305);
    return response({messages:Array.from({length:end-after},(_,i)=>message(after+i+1)),next_seq:end});
  });
  const result=await h.fetchAfterPages(1);
  assert.deepEqual(cursors,[1,101,201,301]); assert.equal(result.nextSeq,305);
  assert.equal(result.messages.length,200); assert.equal(result.messages[0].seq,106);
});

test('late responses cannot update another account or its cursor',async()=>{
  let finish;
  const h=harness(()=>new Promise(resolve=>finish=resolve));
  const run=h.loadMessages(false); h.state.user={id:'user-b'}; h.state.messages=[]; h.state.lastSeq=0;
  finish(response({messages:[message(2)],next_seq:2})); await run;
  assert.equal(h.state.messages.length,0); assert.equal(h.state.lastSeq,0); assert.equal(h.storage.size,0);
});

test('only one poll is in flight and failures back off without advancing cursor',async()=>{
  let reject, calls=0;
  const h=harness(()=>{calls++;return new Promise((_,no)=>reject=no);});
  const run=h.loadMessages(false); await h.loadMessages(false); assert.equal(calls,1);
  reject(new Error('offline')); await run;
  assert.equal(h.state.lastSeq,1); assert.equal(h.state.retryDelay,6000);
  assert.equal([...h.timers.values()][0].ms,6000);
  h.state.retryDelay=60000;
  const again=h.loadMessages(false); reject(new Error('offline')); await again;
  assert.equal(h.state.retryDelay,60000);
});

test('hidden/offline polling pauses and return schedules immediate recovery',()=>{
  const h=harness(); h.startPolling(); assert.equal(h.timers.size,1);
  h.document.hidden=true; h.resumePolling(); assert.equal(h.timers.size,0);
  h.document.hidden=false; h.sandbox.navigator.onLine=false; h.resumePolling(); assert.equal(h.timers.size,0);
  h.sandbox.navigator.onLine=true; h.resumePolling(); assert.equal([...h.timers.values()][0].ms,0);
});

test('timeout releases busy state and keeps cursor for retry',async()=>{
  const h=harness((_,init)=>new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>reject(new Error('aborted')))));
  const run=h.loadMessages(false);
  [...h.timers.values()].find(timer=>timer.ms===15000).fn(); await run;
  assert.equal(h.state.busy,false); assert.equal(h.state.lastSeq,1); assert.equal(h.state.retryDelay,6000);
});

test('malformed JSON never advances the inbox cursor',async()=>{
  const h=harness(async()=>({ok:true,status:200,json:async()=>{throw new SyntaxError('truncated');}}));
  await h.loadMessages(false); assert.equal(h.state.lastSeq,1); assert.equal(h.state.retryDelay,6000);
});

test('expired session stops polling and clears displayed account data',async()=>{
  const h=harness(async()=>response({error:'unauthorized'},401)); await h.loadMessages(false);
  assert.equal(h.state.user,null); assert.equal(h.state.messages.length,0);
  assert.equal(h.location.pathname,'/login'); assert.equal(h.timers.size,0);
});

test('internal navigation uses existing page; ctrl-click remains native',async()=>{
  const h=harness(); const click=h.appEvents.click.at(-1);
  const link={href:'https://msgdock.dpdns.org/settings',hasAttribute:()=>false};
  let prevented=0;
  click({button:0,target:{closest:()=>link},preventDefault:()=>prevented++});
  assert.equal(prevented,1); assert.equal(h.location.pathname,'/settings');
  click({button:0,ctrlKey:true,target:{closest:()=>link},preventDefault:()=>prevented++});
  assert.equal(prevented,1);
});
