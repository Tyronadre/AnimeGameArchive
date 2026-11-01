const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(read,message,limit=25000){const end=Date.now()+limit;while(Date.now()<end){const result=await read();if(result)return result;await sleep(50);}throw Error(message);}
async function withBrowser(server,run){
    const root=path.resolve(__dirname,'..');
    const browser=process.env.STARTUP_TEST_BROWSER||['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(fs.existsSync);
    if(!browser)throw Error('Set STARTUP_TEST_BROWSER to Chromium');
    const profile=fs.mkdtempSync(path.join(root,'.tmp/stage-browser-'));
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const child=spawn(browser,['--headless=new','--no-first-run','--no-default-browser-check','--disable-background-networking',
        '--use-angle=swiftshader','--enable-unsafe-swiftshader','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:'ignore',windowsHide:true});
    let socket;
    try{
        const portFile=path.join(profile,'DevToolsActivePort');await until(()=>fs.existsSync(portFile),'Browser failed to start');
        const [port,endpoint]=fs.readFileSync(portFile,'utf8').trim().split(/\r?\n/);
        socket=new WebSocket('ws://127.0.0.1:'+port+endpoint);
        await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
        const calls=new Map(),errors=[],logs=[];let sequence=0;
        socket.addEventListener('message',event=>{
            const m=JSON.parse(event.data),c=calls.get(m.id);
            if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails);
            if(m.method==='Runtime.consoleAPICalled')logs.push(m.params.args.map(a=>a.description||a.value).join(' '));
            if(c){calls.delete(m.id);clearTimeout(c.timer);m.error?c.reject(Error(m.error.message)):c.resolve(m.result);}
        });
        const cdp=(method,params={},sessionId)=>new Promise((resolve,reject)=>{
            const id=++sequence,timer=setTimeout(()=>{calls.delete(id);reject(Error('CDP timeout '+method));},25000);
            calls.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params,sessionId}));
        });
        const {targetId}=await cdp('Target.createTarget',{url:'about:blank'}),{sessionId}=await cdp('Target.attachToTarget',{targetId,flatten:true});
        const call=(method,params)=>cdp(method,params,sessionId);
        const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
        await call('Page.enable');await call('Runtime.enable');
        await run({base:'http://127.0.0.1:'+server.address().port,call,evaluate,errors,logs,until,sleep,
            screenshot:async name=>{const r=await call('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(root,'.tmp',name),Buffer.from(r.data,'base64'));}});
    }finally{socket?.close();child.kill();server.closeAllConnections();server.close();}
}
module.exports={withBrowser};
