import {readFileSync} from 'node:fs';
import {createContext, runInContext} from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';
const src = readFileSync(new URL('../server/cloudflare-worker/worker.js', import.meta.url),'utf8');
const ctx = createContext({Response,Request,URL,TextDecoder,Uint8Array,console,setTimeout,clearTimeout,crypto:globalThis.crypto});
runInContext(src.replace('export default {','globalThis.worker = {'),ctx);
function env() {
 const counts = new Map(); let calls = 0;
 return {LINJIAN_TOKEN:'test-token',STUDY_ROOM_FREE_AI_ENABLED:'true',
 DB:{prepare(sql){return {bind(key){return {async run(){const n=counts.get(key)||0;if(n>=30)return {meta:{changes:0}};counts.set(key,n+1);return {meta:{changes:1}};}}}}}},
 AI:{async run(model,input){calls++;assert.equal(model,'@cf/meta/llama-3.1-8b-instruct-fp8');assert.equal(input.max_tokens,230);return {response:'继续学习。'}}},get calls(){return calls}};
}
const body = {device_id:'phone',subject:'英语',messages:[{role:'user',content:'test'}]};
const request = (data=body,token='test-token',url='https://example.test/api/study_room/reply') => new Request(url,{method:'POST',headers:{'X-Auth-Token':token},body:typeof data==='string'?data:JSON.stringify(data)});
test('authentication, opt-in and bounded request body',async()=>{
 const e=env();
 assert.equal((await ctx.worker.fetch(request(body,'bad'),e)).status,403);
 assert.equal((await ctx.worker.fetch(request(body,'','https://example.test/api/study_room/reply?token=test-token'),e)).status,403);
 delete e.STUDY_ROOM_FREE_AI_ENABLED;
 assert.equal((await ctx.worker.fetch(request(),e)).status,503);
 e.STUDY_ROOM_FREE_AI_ENABLED='true';
 assert.equal((await ctx.worker.fetch(request('x'.repeat(32769)),e)).status,413);
 for(const b of ['{',null,[],{}, {...body,messages:[{role:'system',content:'x'}]}, {...body,messages:[{role:'user',content:123}]}, {...body,messages:[{role:'assistant',content:'x'}]}, {...body,device_id:'../x'}, {...body,messages:[{role:'user',content:'x'.repeat(1001)}]}])
  assert.equal((await ctx.worker.fetch(request(b),e)).status,400,JSON.stringify(b));
 assert.equal(e.calls,0);
});
test('global atomic reservation limits concurrent and changed-device requests',async()=>{
 const e=env(); const replies=await Promise.all(Array.from({length:45},(_,i)=>ctx.worker.fetch(request({...body,device_id:'phone'+i}),e)));
 assert.equal(replies.filter(r=>r.status===200).length,30);
 assert.equal(replies.filter(r=>r.status===429).length,15);
 assert.equal(e.calls,30);
 const out=await replies[0].json();assert.equal(out.same_chatgpt_conversation,false);
});
test('provider errors and empty output are explicit and private',async()=>{
 const e=env();e.AI.run=async()=>{throw Error('secret account information')};
 const r=await ctx.worker.fetch(request(),e);assert.equal(r.status,503);assert.ok(!(await r.text()).includes('secret'));
 e.AI.run=async()=>({response:''});assert.equal((await ctx.worker.fetch(request(),e)).status,503);
});
