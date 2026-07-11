(function(){
  const {useState,useEffect,useRef}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,Chip,StrategyStatusBadge,Modal,fmt}=ui;

  const AI_RESPONSE=`好的，我看了你的策略上下文。当前 BTC Trend Rider 在 fast/slow 交叉时直接市价买入，止损用 ATR×1.5。

发现两点可以优化：

1. **入场过滤太弱** — 双均线交叉在震荡市会频繁假信号。建议加一个 ADX>25 的趋势过滤，只在强趋势时入场。

2. **止损偏紧** — ATR×1.5 在 BTC 这种高波动品种上容易被插针扫损。考虑放到 ATR×2.5，或用 swing low 而不是固定倍数。

我帮你改一下 \`onBar\`：

\`\`\`python
if fast[0] > slow[0] && fast[1] <= slow[1] && adx[0] > 25:
    await ctx.order({
        side: 'BUY',
        type: 'MARKET',
        qty: ctx.riskQty(0.02, a[0]),
        stop: bar.close - a[0] * 2.5,  # 放宽止损
        takeProfit: bar.close + a[0] * 3,
    })
\`\`\`

要不要我把 ADX 也加到指标依赖里？`;

  function CodeEditor({code}){
    const [val,setVal]=useState(code);
    const taRef=useRef(null);
    const [line,setLine]=useState(1);
    const lines=val.split('\n');
    return <div style={{display:'flex',fontFamily:'ui-monospace,"SF Mono",monospace',fontSize:12.5,background:'var(--surface-2)',overflow:'hidden',height:460}}>
      <div style={{padding:'12px 6px',textAlign:'right',color:'var(--ink-3)',userSelect:'none',background:'var(--surface)',borderRight:'1px solid var(--hair)',minWidth:36}}>
        {lines.map((_,i)=><div key={i} style={{height:20,lineHeight:'20px',fontSize:11,opacity:i+1===line?1:.55}}>{i+1}</div>)}
      </div>
      <textarea
        ref={taRef}
        value={val}
        onChange={e=>{
          setVal(e.target.value);
          const pos=e.target.selectionStart;
          const before=val.slice(0,pos);
          setLine(before.split('\n').length);
        }}
        onKeyUp={e=>{
          const t=e.target;
          setLine(t.value.slice(0,t.selectionStart).split('\n').length);
        }}
        spellCheck={false}
        style={{flex:1,background:'transparent',border:'none',outline:'none',color:'var(--ink)',padding:'12px 12px',fontFamily:'inherit',fontSize:'inherit',lineHeight:'20px',resize:'none',whiteSpace:'pre',overflow:'auto',tabSize:2}}
      />
      <div style={{position:'absolute',right:24,top:8,fontSize:10,color:'var(--ink-3)',display:'flex',gap:10}}>
        <span>Python 3</span><span>·</span><span>UTF-8</span><span>·</span><span>LF</span>
      </div>
    </div>;
  }

  function AIChat({strategy}){
    const [msgs,setMsgs]=useState([
      {role:'ai',text:'我已加载 BTC Trend Rider v1.3.2 的策略上下文（指标依赖、入场条件、风控参数）。需要我帮你改进或加新功能？',ts:'10:38'},
      {role:'user',text:'看看入场过滤和止损，能怎么优化？',ts:'10:39'},
    ]);
    const [streaming,setStreaming]=useState(false);
    const [draft,setDraft]=useState('');
    const [streamText,setStreamText]=useState('');
    const endRef=useRef(null);
    const [showCode,setShowCode]=useState(true);

    useEffect(()=>{if(endRef.current)endRef.current.scrollIntoView({behavior:'smooth',block:'end'});},[msgs,streamText,streaming]);

    const send=()=>{
      if(!draft.trim()||streaming)return;
      const userMsg={role:'user',text:draft.trim(),ts:new Date().toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})};
      setMsgs(m=>[...m,userMsg]);
      setDraft('');
      setStreaming(true);
      setStreamText('');
      let i=0;
      const full=AI_RESPONSE;
      const t=setInterval(()=>{
        i+=Math.max(2,Math.floor(Math.random()*8));
        if(i>=full.length){
          setMsgs(m=>[...m,{role:'ai',text:full,ts:new Date().toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}]);
          setStreamText('');
          setStreaming(false);
          clearInterval(t);
        }else{
          setStreamText(full.slice(0,i));
        }
      },30);
    };

    const suggestions=[
      '加一个 ADX 过滤震荡市',
      '改成 swing low 止损',
      '帮我加上资金费率过滤',
      '把 stop_loss 改成 trailing',
    ];

    return <div style={{display:'flex',flexDirection:'column',height:460,borderRadius:10,overflow:'hidden',border:'1px solid var(--hair)',background:'var(--surface)'}}>
      <div style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
        <div style={{display:'flex',alignItems:'center',gap:8}}>
          <div style={{width:24,height:24,borderRadius:6,background:'var(--brand)',display:'flex',alignItems:'center',justifyContent:'center',color:'#fff',fontWeight:700,fontSize:12}}>AI</div>
          <div>
            <div style={{fontSize:13,fontWeight:600}}>策略编码助手</div>
            <div style={{fontSize:10,color:'var(--ink-3)'}}>已注入上下文 · {strategy.name} · {strategy.version}</div>
          </div>
        </div>
        <Chip tone="brand" style={{fontSize:10}}>SSE 流式</Chip>
      </div>

      <div style={{flex:1,overflow:'auto',padding:'12px 14px',display:'flex',flexDirection:'column',gap:14}}>
        {msgs.map((m,i)=>{
          const isUser=m.role==='user';
          return <div key={i} style={{display:'flex',gap:8,flexDirection:isUser?'row-reverse':'row'}}>
            <div style={{width:24,height:24,borderRadius:6,background:isUser?'var(--surface-3)':'var(--brand)',color:isUser?'var(--ink)':'#fff',display:'flex',alignItems:'center',justifyContent:'center',fontSize:11,fontWeight:700,flexShrink:0}}>{isUser?'你':'AI'}</div>
            <div style={{maxWidth:'82%'}}>
              <div style={{fontSize:10,color:'var(--ink-3)',marginBottom:3,textAlign:isUser?'right':'left'}}>{m.ts}</div>
              <div style={{fontSize:12.5,lineHeight:1.55,color:'var(--ink)',background:isUser?'var(--surface-2)':'var(--brand-soft)',padding:'8px 12px',borderRadius:10,borderTopRightRadius:isUser?2:10,borderTopLeftRadius:isUser?10:2,whiteSpace:'pre-wrap',wordBreak:'break-word'}}>
                {m.text.split('```').map((seg,idx)=>idx%2===1?
                  <pre key={idx} style={{margin:'6px 0',padding:10,background:'var(--surface-2)',borderRadius:6,fontFamily:'ui-monospace,monospace',fontSize:11.5,color:'var(--ink)',overflow:'auto',whiteSpace:'pre'}}>{seg}</pre>
                :<span key={idx}>{seg}</span>)}
              </div>
            </div>
          </div>;
        })}
        {streaming&&<div style={{display:'flex',gap:8}}>
          <div style={{width:24,height:24,borderRadius:6,background:'var(--brand)',color:'#fff',display:'flex',alignItems:'center',justifyContent:'center',fontSize:11,fontWeight:700}}>AI</div>
          <div style={{flex:1}}>
            <div style={{fontSize:10,color:'var(--ink-3)',marginBottom:3}}>正在生成…</div>
            <div className="kq-stream-cursor" style={{fontSize:12.5,lineHeight:1.55,whiteSpace:'pre-wrap',color:'var(--ink)'}}>{streamText}</div>
          </div>
        </div>}
        <div ref={endRef}/>
      </div>

      {!streaming&&<div style={{padding:'0 14px',display:'flex',gap:6,flexWrap:'wrap'}}>
        {suggestions.map(s=><button key={s} onClick={()=>setDraft(s)} className="kq-press" style={{padding:'4px 10px',borderRadius:999,fontSize:11,background:'var(--surface-2)',border:'1px solid var(--hair)',color:'var(--ink-2)',cursor:'pointer'}}>{s}</button>)}
      </div>}

      <div style={{padding:'10px 14px',borderTop:'1px solid var(--hair)',display:'flex',gap:8,alignItems:'flex-end'}}>
        <textarea value={draft} onChange={e=>setDraft(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}}}
          placeholder="问 AI 关于当前策略的问题…（Enter 发送，Shift+Enter 换行）"
          style={{flex:1,resize:'none',border:'1px solid var(--hair)',borderRadius:8,padding:'8px 12px',background:'var(--surface-2)',color:'var(--ink)',fontSize:12.5,minHeight:40,maxHeight:120,outline:'none',fontFamily:'inherit',lineHeight:1.5}}
        />
        <button onClick={send} disabled={streaming||!draft.trim()} className="kq-btn-primary kq-press" style={{padding:'10px 14px',fontSize:12,opacity:streaming||!draft.trim()?.6:1}}>↑ 发送</button>
      </div>
    </div>;
  }

  function StrategyPage(){
    const {data,setPage,pushToast}=useApp();
    const [selected,setSelected]=useState(data.strategies[0]);
    const [showPublish,setShowPublish]=useState(false);
    const [showStart,setShowStart]=useState(false);
    const [showVersions,setShowVersions]=useState(false);
    const [showFSM,setShowFSM]=useState(false);

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      {/* Header */}
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',gap:16,flexWrap:'wrap'}}>
        <div>
          <div style={{display:'flex',alignItems:'center',gap:10,flexWrap:'wrap'}}>
            <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>{selected.name}</h1>
            <StrategyStatusBadge status={selected.status}/>
            <Chip>{selected.version}</Chip>
            <Chip tone="info">{selected.symbol}</Chip>
            <Chip>{selected.exchange}</Chip>
            <Chip>{selected.timeframe}</Chip>
          </div>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>{selected.desc} · {selected.lines} 行 · 更新于 {selected.updated}</p>
        </div>
        <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
          <button className="kq-btn-ghost kq-press" onClick={()=>setPage('backtest')}>↻ 跑回测</button>
          {selected.status==='draft'?
            <button className="kq-btn-ghost kq-press" onClick={()=>pushToast({title:'需要先发布代码',body:'草稿策略无法直接启动',tone:'warn'})}>▶ 启动</button>
          :selected.status==='running'?
            <button className="kq-btn-ghost kq-press" onClick={()=>pushToast({title:'策略已暂停',body:'仅标记不下单，进程仍在运行',tone:'warn'})}>‖ 暂停</button>
          :selected.status==='paused'?
            <button className="kq-btn-primary kq-press" onClick={()=>setShowStart(true)}>▶ 启动</button>
          :
            <button className="kq-btn-ghost kq-press" onClick={()=>pushToast({title:'已停止',body:'需重新编辑回草稿',tone:'warn'})}>已停止</button>}
          <button className="kq-btn-primary kq-press" onClick={()=>setShowPublish(true)}>发布版本</button>
        </div>
      </div>

      {/* Strategy list rail */}
      <div style={{display:'flex',gap:8,overflowX:'auto',paddingBottom:4}}>
        {data.strategies.map(s=>(
          <div key={s.id} onClick={()=>setSelected(s)} className="kq-press" style={{flex:'0 0 220px',padding:'12px 14px',borderRadius:10,border:'1px solid var(--hair)',background:s.id===selected.id?'var(--brand-soft)':'var(--surface)',borderColor:s.id===selected.id?'var(--brand)':'var(--hair)',cursor:'pointer',transition:'all .12s'}}>
            <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
              <strong style={{fontSize:13}}>{s.name}</strong>
              <StrategyStatusBadge status={s.status}/>
            </div>
            <div style={{fontSize:11,color:'var(--ink-3)',marginTop:4}}>{s.symbol} · {s.timeframe}</div>
            <div className="kq-mono-row" style={{fontSize:13,fontWeight:700,marginTop:6,color:s.pnl>=0?'var(--up)':'var(--down)'}}>{s.pnl>=0?'+':''}{fmt(s.pnl,2)} USDT</div>
          </div>
        ))}
        <button onClick={()=>pushToast({title:'新建策略',body:'从空白草稿开始',tone:'brand'})} className="kq-press" style={{flex:'0 0 220px',borderRadius:10,border:'1px dashed var(--hair)',background:'transparent',cursor:'pointer',color:'var(--ink-3)',display:'flex',alignItems:'center',justifyContent:'center',fontSize:13}}>+ 新建策略</button>
      </div>

      {/* Main grid: code + AI chat */}
      <div style={{display:'grid',gridTemplateColumns:'1.4fr 1fr',gap:18}} className="kq-strat-grid">
        <div style={{background:'var(--surface)',border:'1px solid var(--hair)',borderRadius:12,overflow:'hidden',boxShadow:'var(--shadow-card)'}}>
          <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',gap:10,flexWrap:'wrap',padding:'10px 14px',borderBottom:'1px solid var(--hair)',background:'var(--surface)'}}>
            <div style={{display:'flex',gap:6}}>
              <button className="kq-tab active">onBar.py</button>
              <button className="kq-tab">config.json</button>
              <button className="kq-tab">requirements.txt</button>
            </div>
            <div style={{display:'flex',alignItems:'center',gap:10,fontSize:11,color:'var(--ink-3)'}}>
              <span>● 已保存</span><span>·</span><span>自动保存 30s</span>
              <span style={{width:1,height:12,background:'var(--hair)'}}/>
              <button onClick={()=>setShowVersions(true)} className="kq-press" title="查看代码版本时间线" style={{display:'inline-flex',alignItems:'center',gap:6,padding:'3px 10px',borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)',color:'var(--ink-2)',cursor:'pointer',fontSize:11,fontWeight:500}}>
                <span>⟲</span><span>版本</span><Chip tone="info" style={{padding:'0 6px',fontSize:9}}>3 态</Chip>
              </button>
              <button onClick={()=>setShowFSM(true)} className="kq-press" title="查看策略状态机说明" style={{display:'inline-flex',alignItems:'center',gap:6,padding:'3px 10px',borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)',color:'var(--ink-2)',cursor:'pointer',fontSize:11,fontWeight:500}}>
                <span>◉</span><span>状态机</span>
              </button>
            </div>
          </div>
          <div style={{position:'relative'}}>
            <CodeEditor code={data.strategies_code}/>
          </div>
        </div>

        <div>
          <AIChat strategy={selected}/>
        </div>
      </div>

      <style>{`@media(max-width:1100px){.kq-strat-grid{grid-template-columns:1fr !important}}`}</style>

      <Modal open={showPublish} onClose={()=>setShowPublish(false)} title="发布代码版本" width={520}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowPublish(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowPublish(false);pushToast({title:'v1.3.2 已发布',body:'当前草稿已冻结，下次修改将开新草稿',tone:'up'});}}>发布并冻结</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:14}}>
          <div>
            <label className="kq-label">版本号</label>
            <input className="kq-input" defaultValue="v1.3.2"/>
          </div>
          <div>
            <label className="kq-label">变更说明</label>
            <textarea className="kq-input" style={{minHeight:80,resize:'vertical'}} defaultValue="加入 ADX>25 趋势过滤，止损 ATR×1.5 → ATR×2.5"/>
          </div>
          <div style={{padding:12,borderRadius:8,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:12,color:'var(--ink-2)',lineHeight:1.5}}>
            <strong style={{color:'var(--warn)'}}>⚠ 一旦发布即冻结</strong>，不可再修改。要改需开新草稿，当前已发布版本将自动归档。
          </div>
        </div>
      </Modal>

      <Modal open={showStart} onClose={()=>setShowStart(false)} title="启动策略" width={460}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowStart(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowStart(false);pushToast({title:'策略已启动',body:`${selected.name} · Worker 已上线`,tone:'up'});}}>▶ 启动</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div style={{padding:14,borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)'}}>
            <div style={{fontSize:13,fontWeight:600}}>{selected.name}</div>
            <div style={{fontSize:11,color:'var(--ink-3)',marginTop:4}}>{selected.symbol} · {selected.exchange} · {selected.timeframe}</div>
          </div>
          <div style={{fontSize:12,color:'var(--ink-2)',lineHeight:1.5}}>
            启动后 Worker 将自动接收行情并按策略下单。绑定账户：
          </div>
          <select className="kq-input">
            <option>PAPER · 主模拟盘 (acc-paper)</option>
            <option>LIVE · 主账户 (acc-live-1) — 需二次确认</option>
          </select>
          <div style={{padding:10,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:11,color:'var(--brand-ink)',lineHeight:1.5}}>
            ⚠ 启动到 LIVE 账户需高风险二次确认，会触发风控闸门检查。
          </div>
        </div>
      </Modal>

      <Modal open={showVersions} onClose={()=>setShowVersions(false)} title="代码版本" width={560}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowVersions(false)}>关闭</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowVersions(false);setShowPublish(true);}}>发布新版本</button></>}>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',marginBottom:12}}>
          <div style={{fontSize:12,color:'var(--ink-2)'}}>当前策略 · <strong style={{color:'var(--ink)'}}>{selected.name}</strong></div>
          <Chip tone="info">3 态：草稿 / 已发布 / 已归档</Chip>
        </div>
        <div style={{display:'flex',flexDirection:'column',gap:8}}>
          {[
            {v:'v1.3.2 草稿',t:'2分钟前',state:'draft',desc:'加入 ADX 过滤 · 放宽止损',author:'you'},
            {v:'v1.3.1',t:'昨天',state:'published',desc:'修复 qty 计算精度问题',author:'you'},
            {v:'v1.3.0',t:'1周前',state:'archived',desc:'ATR 止损倍数 1.5 → 2.0',author:'AI'},
            {v:'v1.2.0',t:'2周前',state:'archived',desc:'初版双均线突破',author:'you'},
          ].map((v,i)=>(
            <div key={i} style={{display:'flex',gap:10,alignItems:'center',padding:'10px 12px',borderRadius:8,background:v.state==='draft'?'var(--brand-soft)':'var(--surface-2)',border:'1px solid',borderColor:v.state==='draft'?'var(--brand)':'transparent'}}>
              <div style={{width:10,height:10,borderRadius:'50%',border:'2px solid',borderColor:v.state==='draft'?'var(--brand)':v.state==='published'?'var(--up)':'var(--ink-3)',flexShrink:0}}/>
              <div style={{flex:1,minWidth:0}}>
                <div style={{display:'flex',alignItems:'center',gap:8,flexWrap:'wrap'}}>
                  <span style={{fontSize:12,fontWeight:600}}>{v.v}</span>
                  {v.state==='draft'&&<Chip tone="brand" style={{padding:'1px 6px',fontSize:9}}>DRAFT</Chip>}
                  {v.state==='published'&&<Chip tone="up" style={{padding:'1px 6px',fontSize:9}}>PUBLISHED</Chip>}
                  {v.state==='archived'&&<Chip style={{padding:'1px 6px',fontSize:9}}>ARCHIVED</Chip>}
                </div>
                <div style={{fontSize:11,color:'var(--ink-3)',marginTop:2}}>{v.desc} · {v.author}</div>
              </div>
              <div style={{fontSize:10,color:'var(--ink-3)'}}>{v.t}</div>
            </div>
          ))}
        </div>
      </Modal>

      <Modal open={showFSM} onClose={()=>setShowFSM(false)} title="策略状态机说明" width={560}
        footer={<button className="kq-btn-ghost kq-press" onClick={()=>setShowFSM(false)}>关闭</button>}>
        <div style={{display:'flex',flexDirection:'column',gap:14}}>
          <div>
            <div style={{fontSize:11,color:'var(--ink-3)',marginBottom:8,letterSpacing:'.04em'}}>STATE FLOW</div>
            <div style={{fontSize:12,display:'flex',alignItems:'center',gap:6,flexWrap:'wrap'}}>
              {['草稿','就绪','运行中','已暂停','已停止'].map((s,i,arr)=>(
                <span key={s} style={{display:'flex',alignItems:'center',gap:6}}>
                  <span style={{padding:'4px 10px',borderRadius:6,background:s==='运行中'?'var(--brand-soft)':'var(--surface-2)',color:s==='运行中'?'var(--brand)':'var(--ink-2)',border:'1px solid',borderColor:s==='运行中'?'var(--brand)':'var(--hair)',fontSize:11,fontWeight:500}}>{s}</span>
                  {i<arr.length-1&&<span style={{color:'var(--ink-3)'}}>→</span>}
                </span>
              ))}
            </div>
          </div>
          <div style={{padding:12,borderRadius:8,background:'var(--surface-2)',fontSize:12,color:'var(--ink-2)',lineHeight:1.6}}>
            <div style={{fontWeight:600,color:'var(--ink)',marginBottom:6}}>流转规则</div>
            · <strong>草稿 → 就绪</strong>：需先发布代码版本，发布即冻结<br/>
            · <strong>就绪 → 运行中</strong>：Worker 上线接收行情并按策略下单<br/>
            · <strong>运行中 ⇄ 已暂停</strong>：不停进程，只标记不下单<br/>
            · <strong>已停止</strong>：终态，需重新编辑回草稿
          </div>
          <div style={{padding:12,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:11,color:'var(--brand-ink)',lineHeight:1.6}}>
            ⚠ 切到 LIVE 账户需高风险二次确认，会触发风控闸门检查。
          </div>
        </div>
      </Modal>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.StrategyPage=StrategyPage;
})();
