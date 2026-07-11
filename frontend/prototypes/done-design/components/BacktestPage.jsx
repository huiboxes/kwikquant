(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,EquityCurve,BacktestStatusBadge,Modal,Stat,fmt,fmtSigned,Sparkline}=ui;

  function EquityCurveCard({bt,compare}){
    const {data}=useApp();
    // perturb curve per backtest
    const seed=bt.id.charCodeAt(bt.id.length-1);
    const curve=data.equityCurve.map((p,i)=>[p[0],p[1]*(1+Math.sin(i*0.5+seed)*0.06)]);
    const color=compare?'var(--info)':'var(--up)';
    return <Card>
      <SectionTitle title="权益曲线" sub={bt.period} right={<div style={{display:'flex',gap:8}}>
        <button className="kq-tab active">权益</button>
        <button className="kq-tab">回撤</button>
        <button className="kq-tab">月度</button>
      </div>}/>
      <div style={{position:'relative'}}>
        <EquityCurve data={curve} width={1040} height={260} color={color}/>
        {compare&&<EquityCurve data={data.equityCurve} width={1040} height={260} color="var(--brand)"/>}
      </div>
      {compare&&<div style={{display:'flex',gap:14,marginTop:8,fontSize:11}}>
        <span style={{display:'flex',alignItems:'center',gap:6}}><span style={{width:14,height:2,background:'var(--brand)'}}/>当前 {bt.id}</span>
        <span style={{display:'flex',alignItems:'center',gap:6}}><span style={{width:14,height:2,background:'var(--info)'}}/>对比 bt-2204</span>
      </div>}
    </Card>;
  }

  function MetricGrid({bt}){
    const metrics=[
      {label:'总收益率',value:fmtSigned(bt.ret,1)+'%',tone:bt.ret>=0?'up':'down',sub:'vs BTC +38.1%'},
      {label:'夏普比率',value:bt.sharpe.toFixed(2),tone:bt.sharpe>=1.5?'up':'default',sub:'年化'},
      {label:'最大回撤',value:bt.maxDD.toFixed(1)+'%',tone:'down',sub:'2026-04-12'},
      {label:'胜率',value:bt.winRate+'%',sub:bt.trades+' 笔'},
      {label:'盈亏比',value:bt.profitFactor.toFixed(2),sub:'1.5+ 为佳'},
      {label:'交易数',value:bt.trades,sub:'完整周期'},
      {label:'平均持仓',value:bt.avgHold,sub:'时间加权'},
    ];
    return <div style={{display:'grid',gridTemplateColumns:'repeat(7,1fr)',gap:0,background:'var(--surface)',borderRadius:12,overflow:'hidden',border:'1px solid var(--hair)'}} className="kq-metric-grid">
      {metrics.map((m,i)=>(
        <div key={i} style={{padding:'14px 16px',borderRight:i<6?'1px solid var(--hair)':'none'}}>
          <div style={{fontSize:10,color:'var(--ink-3)',letterSpacing:'.06em',textTransform:'uppercase',fontWeight:600}}>{m.label}</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700,marginTop:4,letterSpacing:'-.01em',color:m.tone==='up'?'var(--up)':m.tone==='down'?'var(--down)':'var(--ink)'}}>{m.value}</div>
          <div style={{fontSize:10,color:'var(--ink-3)',marginTop:2}}>{m.sub}</div>
        </div>
      ))}
    </div>;
  }

  function TradeList(){
    const trades=[
      {ts:'2026-06-18 14:02',side:'BUY',price:60200,qty:0.42,pnl:+184.20,equity:118420},
      {ts:'2026-06-15 09:14',side:'SELL',price:62800,qty:0.42,pnl:+218.40,equity:118236},
      {ts:'2026-06-12 22:38',side:'BUY',price:58200,qty:0.42,pnl:-42.10,equity:118018},
      {ts:'2026-06-09 11:02',side:'SELL',price:60100,qty:0.42,pnl:+126.00,equity:118060},
      {ts:'2026-06-05 16:48',side:'BUY',price:55800,qty:0.42,pnl:+84.00,equity:117934},
      {ts:'2026-06-02 08:22',side:'SELL',price:57200,qty:0.42,pnl:+11.20,equity:117850},
    ];
    return <Card>
      <SectionTitle title="交易明细" sub="回测期间每笔成交" right={<button className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:12}}>↓ 导出 CSV</button>}/>
      <div style={{overflow:'auto'}}>
        <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
          <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>时间</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>方向</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>价格</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>数量</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>盈亏</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>权益</th>
          </tr></thead>
          <tbody>
            {trades.map((t,i)=><tr key={i}>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{t.ts}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>
                <span style={{color:t.side==='BUY'?'var(--up)':'var(--down)',fontWeight:700}}>{t.side}</span>
              </td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(t.price,2)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(t.qty,4)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:t.pnl>=0?'var(--up)':'var(--down)',fontWeight:700}}>{fmtSigned(t.pnl,2)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(t.equity,2)}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </Card>;
  }

  function BacktestPage(){
    const {data,setPage,pushToast}=useApp();
    const [selected,setSelected]=useState(data.backtests[0]);
    const [showSubmit,setShowSubmit]=useState(false);
    const [compareMode,setCompareMode]=useState(false);
    const [compareSel,setCompareSel]=useState([data.backtests[0].id,data.backtests[3].id]);

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:14}}>
        <div>
          <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>回测</h1>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>用历史数据验证策略表现 · 异步任务 · 完成回填 reportId</p>
        </div>
        <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
          <button className={`kq-btn-ghost kq-press ${compareMode?'':'kq-active'}`} onClick={()=>setCompareMode(!compareMode)} style={{padding:'8px 14px',fontSize:12,borderColor:compareMode?'var(--brand)':'var(--hair)',color:compareMode?'var(--brand)':'var(--ink)'}}>⇄ 多报告对比</button>
          <button className="kq-btn-ghost kq-press" onClick={()=>pushToast({title:'导入外部结果',body:'支持 JSON 格式回测报告',tone:'info'})}>⇪ 导入</button>
          <button className="kq-btn-primary kq-press" onClick={()=>setShowSubmit(true)}>+ 新回测</button>
        </div>
      </div>

      {/* Backtest list rail */}
      <div style={{display:'flex',gap:8,overflowX:'auto',paddingBottom:4}}>
        {data.backtests.map(bt=>{
          const onPick=()=>{setSelected(bt);setCompareSel([bt.id,compareSel[1]]);};
          return (
          <div key={bt.id} onClick={onPick} className="kq-press" style={{flex:'0 0 240px',padding:'12px 14px',borderRadius:10,border:'1px solid',borderColor:bt.id===selected.id?'var(--brand)':'var(--hair)',background:bt.id===selected.id?'var(--brand-soft)':'var(--surface)',cursor:'pointer'}}>
            <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start'}}>
              <div style={{flex:1}}>
                <div style={{display:'flex',alignItems:'center',gap:6}}>
                  <span style={{fontSize:11,color:'var(--ink-3)',fontFamily:'ui-monospace,monospace'}}>{bt.id}</span>
                  <BacktestStatusBadge status={bt.status}/>
                </div>
                <div style={{fontSize:13,fontWeight:600,marginTop:3}}>{bt.strategy}</div>
                <div style={{fontSize:10,color:'var(--ink-3)'}}>{bt.period}</div>
              </div>
              {compareMode&&<input type="checkbox" checked={compareSel.includes(bt.id)} onChange={e=>{
                if(e.target.checked&&compareSel.length<2)setCompareSel([...compareSel,bt.id]);
                else if(e.target.checked)setCompareSel([compareSel[1],bt.id]);
                else setCompareSel(compareSel.filter(x=>x!==bt.id));
              }} style={{accentColor:'var(--brand)',transform:'scale(1.2)'}} onClick={e=>e.stopPropagation()}/>}
            </div>
            {bt.status==='COMPLETED'?(
              <div style={{display:'flex',gap:10,marginTop:8}}>
                <div style={{fontSize:11,color:'var(--ink-3)'}}>收益</div>
                <div className="kq-mono-row" style={{fontSize:13,fontWeight:700,color:bt.ret>=0?'var(--up)':'var(--down)'}}>{fmtSigned(bt.ret,1)}%</div>
                <div style={{flex:1}}><Sparkline data={[1,3,2,5,4,6,5,7,8,6,9]} width={60} height={20}/></div>
              </div>
            ):bt.status==='RUNNING'?(
              <div style={{marginTop:8}}>
                <div style={{display:'flex',justifyContent:'space-between',fontSize:10,color:'var(--ink-3)'}}><span>运行中…</span><span>{bt.progress}%</span></div>
                <div style={{height:4,background:'var(--surface-2)',borderRadius:2,marginTop:4,overflow:'hidden'}}><div style={{width:bt.progress+'%',height:'100%',background:'var(--brand)'}}/></div>
              </div>
            ):null}
            <div style={{fontSize:10,color:'var(--ink-3)',marginTop:8}}>{bt.ts}</div>
          </div>
        );
        })}
      </div>

      {compareMode?(
        <Card>
          <SectionTitle title="多报告并排对比" sub={`${compareSel.length} 个报告 · 差异化功能`} right={<Chip tone="brand">≥2 个并排</Chip>}/>
          <div style={{overflow:'auto'}}>
            <table style={{width:'100%',borderCollapse:'collapse',fontSize:12}}>
              <thead><tr style={{textAlign:'left'}}>
                <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',fontSize:11,color:'var(--ink-3)',textTransform:'uppercase'}}>指标</th>
                {compareSel.map(id=>{const bt=data.backtests.find(b=>b.id===id);return <th key={id} style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',fontSize:12}}>{bt.strategy}<br/><span style={{fontSize:10,color:'var(--ink-3)'}}>{bt.id}</span></th>;})}
              </tr></thead>
              <tbody>
                {[
                  ['总收益率',b=>fmtSigned(b.ret,1)+'%',b=>b.ret>=0?'var(--up)':'var(--down)'],
                  ['夏普比率',b=>b.sharpe.toFixed(2)],
                  ['最大回撤',b=>b.maxDD.toFixed(1)+'%',b=>'var(--down)'],
                  ['胜率',b=>b.winRate+'%'],
                  ['盈亏比',b=>b.profitFactor.toFixed(2)],
                  ['交易数',b=>b.trades],
                  ['平均持仓',b=>b.avgHold],
                ].map(([label,fmtFn,colorFn],i)=><tr key={i}>
                  <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',color:'var(--ink-2)'}}>{label}</td>
                  {compareSel.map(id=>{const b=data.backtests.find(x=>x.id===id);const v=fmtFn(b);return <td key={id} className="kq-mono-row" style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:(colorFn&&colorFn(b))||'var(--ink)',fontWeight:700}}>{v}</td>;})}
                </tr>)}
              </tbody>
            </table>
          </div>
          <div style={{marginTop:14}}>
            <EquityCurve data={data.equityCurve} width={1040} height={180} color="var(--brand)"/>
          </div>
        </Card>
      ):(
        <>
          {/* Equity curve */}
          <EquityCurveCard bt={selected}/>
          {/* Metrics */}
          <MetricGrid bt={selected}/>
          {/* Trade list */}
          <TradeList/>
        </>
      )}

      <Modal open={showSubmit} onClose={()=>setShowSubmit(false)} title="提交新回测" width={560}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowSubmit(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowSubmit(false);pushToast({title:'回测已提交',body:'异步任务，完成会推送通知',tone:'brand'});}}>提交任务</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:14}}>
          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:12}}>
            <div>
              <label className="kq-label">策略</label>
              <select className="kq-input"><option>BTC Trend Rider v1.3.2</option><option>ETH Mean Reversion v0.4.1</option></select>
            </div>
            <div>
              <label className="kq-label">撮合模式</label>
              <select className="kq-input"><option>FAST (最新价 + 滑点)</option></select>
            </div>
            <div>
              <label className="kq-label">开始日期</label>
              <input className="kq-input" type="date" defaultValue="2025-01-01"/>
            </div>
            <div>
              <label className="kq-label">结束日期</label>
              <input className="kq-input" type="date" defaultValue="2026-06-30"/>
            </div>
            <div>
              <label className="kq-label">初始资金</label>
              <input className="kq-input" defaultValue="100,000 USDT"/>
            </div>
            <div>
              <label className="kq-label">滑点</label>
              <input className="kq-input" defaultValue="0.05%"/>
            </div>
            <div>
              <label className="kq-label">手续费</label>
              <input className="kq-input" defaultValue="0.04%"/>
            </div>
            <div>
              <label className="kq-label">基准</label>
              <select className="kq-input"><option>Buy & Hold BTC</option><option>无</option></select>
            </div>
          </div>
          <div style={{padding:12,borderRadius:8,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:11,color:'var(--ink-2)',lineHeight:1.5}}>
            ⚠ 回测撮合永远用 FAST 模式（最新价+滑点），因为回测引擎只有 K 线数据。回测是异步任务，提交后请等待通知。
          </div>
        </div>
      </Modal>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.BacktestPage=BacktestPage;
})();
