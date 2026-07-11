(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,EquityCurve,Sparkline,Stat,StrategyStatusBadge,fmt,fmtSigned}=ui;

  const JOURNEY=[
    {id:'strategy',step:1,label:'编码策略',desc:'Monaco 编辑器 + AI 流式对话',icon:'❮❯',state:'continue'},
    {id:'backtest',step:2,label:'回测验证',desc:'权益曲线 · 7 项指标 · 多报告对比',icon:'∿',state:'continue'},
    {id:'paper',step:3,label:'模拟验证',desc:'PAPER 10 万 USDT · 真实撮合',icon:'⌬',state:'continue'},
    {id:'live',step:4,label:'实盘上线',desc:'真实账户 · 策略 Worker 自动',icon:'⚡',state:'ready'},
    {id:'portfolio',step:5,label:'持续监控',desc:'组合 · 持仓 · 通知实时推送',icon:'◇',state:'active'},
  ];

  function DashboardPage(){
    const {data,setPage,pushToast}=useApp();
    const running=data.strategies.filter(s=>s.status==='running');
    const paper=data.accounts.find(a=>a.isPaper);
    const live=data.accounts.filter(a=>!a.isPaper);
    const totalEquity=data.accounts.reduce((a,b)=>a+b.equity,0);
    const uPnl=data.positions.reduce((a,b)=>a+b.uPnl,0);

    return <div style={{display:'flex',flexDirection:'column',gap:20}}>
      {/* Hero / Continue journey */}
      <Card pad={0} style={{overflow:'hidden'}}>
        <div style={{padding:'28px 32px',background:'radial-gradient(circle at 90% 10%, var(--brand-soft) 0%, transparent 55%)'}}>
          <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:20}}>
            <div style={{maxWidth:600}}>
              <Chip tone="brand" style={{marginBottom:10}}><span style={{width:6,height:6,background:'var(--brand)',borderRadius:'50%',display:'inline-block'}}/>旅程进行中 · 第 5 步</Chip>
              <h1 className="font-display" style={{fontSize:38,fontWeight:500,letterSpacing:'-.025em',lineHeight:1.05,margin:0}}>
                欢迎回来，<em style={{color:'var(--brand)',fontStyle:'italic'}}>demo</em>。
              </h1>
              <p style={{fontSize:14,color:'var(--ink-2)',marginTop:10,lineHeight:1.6,maxWidth:540}}>
                你有 <strong style={{color:'var(--up)'}}>{running.length} 个策略</strong>在运行，
                最近 <strong style={{color:'var(--ink)'}}>7 天 +12.43%</strong>。继续 <em style={{fontStyle:'italic',color:'var(--ink)'}}>BTC Trend Rider</em> 的回测对比，或开始下一个策略。
              </p>
              <div style={{display:'flex',gap:8,marginTop:18,flexWrap:'wrap'}}>
                <button className="kq-btn-primary kq-press" onClick={()=>setPage('strategy')}>继续编码 →</button>
                <button className="kq-btn-ghost kq-press" onClick={()=>setPage('backtest')}>对比回测</button>
                <button className="kq-btn-ghost kq-press" onClick={()=>setPage('trade')}>打开交易</button>
              </div>
            </div>
            <div style={{display:'flex',flexDirection:'column',gap:10,minWidth:240}}>
              <div style={{padding:'14px 16px',borderRadius:12,background:'var(--surface)',border:'1px solid var(--hair)'}}>
                <div style={{fontSize:11,color:'var(--ink-3)',letterSpacing:'.05em',textTransform:'uppercase',fontWeight:600}}>总资产（USDT 估值）</div>
                <div className="kq-mono-row" style={{fontSize:30,fontWeight:700,marginTop:4,letterSpacing:'-.02em'}}>$ {fmt(totalEquity,2)}</div>
                <div className="kq-mono-row" style={{fontSize:12,color:'var(--up)',fontWeight:600,marginTop:2}}>▲ {fmtSigned(uPnl,2)} 未实现</div>
              </div>
              <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:8}}>
                <div style={{padding:'10px 12px',borderRadius:10,background:'var(--surface)',border:'1px solid var(--hair)'}}>
                  <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>PAPER</div>
                  <div className="kq-mono-row" style={{fontSize:15,fontWeight:700}}>$ {fmt(paper.equity,0)}</div>
                </div>
                <div style={{padding:'10px 12px',borderRadius:10,background:'var(--surface)',border:'1px solid var(--hair)'}}>
                  <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>LIVE</div>
                  <div className="kq-mono-row" style={{fontSize:15,fontWeight:700,color:'var(--brand)'}}>$ {fmt(live.reduce((a,b)=>a+b.equity,0),0)}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </Card>

      {/* Journey map */}
      <Card>
        <SectionTitle
          title="策略旅程"
          sub="编码 → 回测 → 模拟 → 实盘 → 持续监控 · 零割裂"
          right={<Chip tone="brand">操作叙事</Chip>}
        />
        <div style={{display:'flex',alignItems:'stretch',gap:0,overflowX:'auto'}}>
          {JOURNEY.map((j,i)=>{
            const onEnter=(e)=>{e.currentTarget.style.borderColor='var(--brand)';e.currentTarget.style.transform='translateY(-2px)';};
            const onLeave=(e)=>{e.currentTarget.style.borderColor='var(--hair)';e.currentTarget.style.transform='none';};
            return (
            <div key={j.id} style={{flex:1,minWidth:160,position:'relative'}}>
              <div onClick={()=>setPage(j.id)} onMouseEnter={onEnter} onMouseLeave={onLeave} style={{cursor:'pointer',padding:'14px 16px',borderRadius:12,border:'1px solid var(--hair)',background:'var(--surface-2)',marginRight:0,transition:'all .15s'}}>
                <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
                  <div style={{display:'flex',alignItems:'center',gap:8}}>
                    <div style={{width:28,height:28,borderRadius:8,background:j.state==='active'?'var(--brand)':'var(--surface-3)',color:j.state==='active'?'#fff':'var(--ink-2)',display:'flex',alignItems:'center',justifyContent:'center',fontSize:14,fontWeight:700}}>{j.icon}</div>
                    <div>
                      <div style={{fontSize:13,fontWeight:600}}>{j.label}</div>
                      <div style={{fontSize:10,color:'var(--ink-3)',letterSpacing:'.04em',textTransform:'uppercase'}}>第 {j.step} 步</div>
                    </div>
                  </div>
                  {j.state==='active'&&<span className="kq-pulse" style={{width:8,height:8,borderRadius:'50%',background:'var(--up)'}}/>}
                </div>
                <div style={{fontSize:11,color:'var(--ink-3)',marginTop:10,lineHeight:1.4}}>{j.desc}</div>
              </div>
              {i<JOURNEY.length-1&&<div style={{position:'absolute',right:-6,top:34,width:12,height:2,background:'var(--hair)',zIndex:1}}/>}
            </div>
            );
          })}
        </div>
      </Card>

      <div style={{display:'grid',gridTemplateColumns:'1.6fr 1fr',gap:20}} className="kq-grid-2col">
        {/* Running strategies */}
        <Card>
          <SectionTitle title="运行中策略" sub={`${running.length} 个 · 实时持仓推送`} right={<button onClick={()=>setPage('strategy')} className="kq-btn-ghost kq-press" style={{padding:'6px 12px',fontSize:12}}>管理全部</button>}/>
          {data.strategies.map(s=>(
            <div key={s.id} style={{display:'grid',gridTemplateColumns:'1fr 80px 100px 90px 100px',alignItems:'center',gap:12,padding:'12px 0',borderBottom:'1px solid var(--hair)'}} className="kq-strat-row">
              <div>
                <div style={{display:'flex',alignItems:'center',gap:8}}>
                  <strong style={{fontSize:13}}>{s.name}</strong>
                  <StrategyStatusBadge status={s.status}/>
                </div>
                <div style={{fontSize:11,color:'var(--ink-3)',marginTop:3}}>{s.symbol} · {s.exchange} · {s.timeframe} · {s.version} · {s.lines} 行</div>
              </div>
              <div>
                <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>持仓盈亏</div>
                <div className="kq-mono-row" style={{fontSize:14,fontWeight:700,color:s.pnl>=0?'var(--up)':'var(--down)'}}>{fmtSigned(s.pnl,2)}</div>
              </div>
              <div>
                <Sparkline data={[1,2,3,2,4,3,5,4,6,7,5,8]} width={80} height={24}/>
              </div>
              <div>
                <button onClick={()=>{setPage('strategy');pushToast({title:'已打开策略',body:s.name,tone:'brand'});}} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:12,width:'100%'}}>编辑</button>
              </div>
              <div style={{display:'flex',gap:4,justifyContent:'flex-end'}}>
                {s.status==='running'?
                  <button onClick={()=>pushToast({title:'策略已暂停',body:`${s.name} · 标记不下单`,tone:'warn'})} className="kq-btn-ghost kq-press" style={{padding:'5px 8px',fontSize:11}}>‖ 暂停</button>
                :s.status==='paused'?
                  <button onClick={()=>pushToast({title:'策略已启动',body:s.name,tone:'up'})} className="kq-btn-primary kq-press" style={{padding:'5px 8px',fontSize:11}}>▶ 启动</button>
                :<button onClick={()=>pushToast({title:'草稿模式',body:'需先发布代码版本才能启动',tone:'warn'})} className="kq-btn-ghost kq-press" style={{padding:'5px 8px',fontSize:11}}>草稿</button>}
              </div>
            </div>
          ))}
        </Card>

        {/* Activity feed */}
        <Card>
          <SectionTitle title="实时动态" sub="WS 推送 · 订单 / 成交 / 持仓"/>
          <div style={{display:'flex',flexDirection:'column',gap:8}}>
            {[
              {tone:'up',icon:'✓',title:'BTC/USDT BUY 0.42 @ 61200',sub:'PAPER · 全部成交',ts:'14:02'},
              {tone:'brand',icon:'∠',title:'AI 建议优化 ATR 止损倍数',sub:'BTC Trend Rider',ts:'14:01'},
              {tone:'warn',icon:'⛨',title:'风控拦截 o-9006',sub:'触发 MAX_NOTIONAL',ts:'13:58'},
              {tone:'down',icon:'↓',title:'ETH/USDT SHORT -42.10',sub:'PAPER · 未实现',ts:'13:55'},
              {tone:'up',icon:'▶',title:'BTC Trend Rider 启动',sub:'v1.3.2',ts:'13:30'},
              {tone:'brand',icon:'✦',title:'回测完成 bt-2201',sub:'+58.4% · 夏普 2.31',ts:'10:42'},
            ].map((a,i)=><div key={i} style={{display:'flex',gap:10,padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
              <div style={{width:24,height:24,borderRadius:6,display:'flex',alignItems:'center',justifyContent:'center',color:a.tone==='down'?'var(--down)':a.tone==='up'?'var(--up)':a.tone==='warn'?'var(--warn)':'var(--brand)',fontWeight:700,fontSize:12,flexShrink:0,background:'var(--surface)'}}>{a.icon}</div>
              <div style={{flex:1,minWidth:0}}>
                <div style={{fontSize:12,fontWeight:600,whiteSpace:'nowrap',overflow:'hidden',textOverflow:'ellipsis'}}>{a.title}</div>
                <div style={{fontSize:10,color:'var(--ink-3)'}}>{a.sub} · {a.ts}</div>
              </div>
            </div>)}
          </div>
        </Card>
      </div>

      {/* Performance chart */}
      <Card>
        <SectionTitle title="组合权益曲线" sub="近 30 天 · USDT 估值" right={<div style={{display:'flex',gap:8}}>
          <button className="kq-tab active">30D</button>
          <button className="kq-tab">90D</button>
          <button className="kq-tab">YTD</button>
          <button className="kq-tab">All</button>
        </div>}/>
        <EquityCurve data={data.equityCurve} width={1080} height={220} color="var(--brand)"/>
        <div style={{display:'grid',gridTemplateColumns:'repeat(4,1fr)',gap:16,marginTop:16}}>
          <Stat label="累计收益" value="+58.4%" tone="up" mono sub="vs. 基准 +32.1%"/>
          <Stat label="夏普比率" value="2.31" mono sub="年化"/>
          <Stat label="最大回撤" value="-9.8%" tone="down" mono sub="2026-04"/>
          <Stat label="胜率" value="62%" mono sub="184 笔"/>
        </div>
      </Card>

      <style>{`
        @media(max-width:980px){.kq-grid-2col{grid-template-columns:1fr !important}}
        @media(max-width:760px){
          .kq-strat-row{grid-template-columns:1fr !important;gap:6px;padding:12px 0 !important}
        }
      `}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.DashboardPage=DashboardPage;
})();
