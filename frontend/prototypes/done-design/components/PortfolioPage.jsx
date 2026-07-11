(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,Modal,EquityCurve,Stat,Sparkline,fmt,fmtSigned}=ui;

  function AccountCard({acc}){
    const {pushToast}=useApp();
    const isPaper=acc.isPaper;
    return <Card style={{borderTop:`3px solid ${isPaper?'var(--up)':'var(--brand)'}`}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start'}}>
        <div>
          <div style={{display:'flex',alignItems:'center',gap:8}}>
            {isPaper?<span className="kq-paper-badge">PAPER</span>:<span className="kq-live-badge">● LIVE</span>}
            <strong style={{fontSize:14}}>{acc.label}</strong>
          </div>
          <div style={{fontSize:11,color:'var(--ink-3)',marginTop:4}}>{acc.exchange} · {acc.market}</div>
        </div>
        <div style={{display:'flex',gap:6}}>
          <button className="kq-btn-ghost kq-press" style={{padding:'5px 8px',fontSize:11}} onClick={()=>pushToast({title:'编辑账户',body:acc.label,tone:'info'})}>编辑</button>
          <button className="kq-btn-ghost kq-press" style={{padding:'5px 8px',fontSize:11,color:'var(--down)'}} onClick={()=>pushToast({title:'确认删除账户？',body:'需二次确认',tone:'warn'})}>删除</button>
        </div>
      </div>
      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:10,marginTop:14,padding:'12px 0',borderTop:'1px solid var(--hair)',borderBottom:'1px solid var(--hair)'}}>
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>总权益</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700}}>{fmt(acc.equity,2)}</div>
        </div>
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>可用 / 冻结</div>
          <div className="kq-mono-row" style={{fontSize:13,fontWeight:700}}>{fmt(acc.balance,2)} <span style={{color:'var(--warn)'}}>/{fmt(acc.frozen,2)}</span></div>
        </div>
      </div>
      <div style={{marginTop:10}}>
        <Sparkline data={[1,2,4,3,5,6,5,7,8,7,9]} width={240} height={32} color={isPaper?'var(--up)':'var(--brand)'}/>
      </div>
      <div style={{display:'flex',justifyContent:'space-between',marginTop:10,fontSize:11,color:'var(--ink-3)'}}>
        <span>{isPaper?`基准行情: ${acc.refExchange}`:'交易所维护余额'}</span>
        {isPaper&&<button className="kq-btn-ghost kq-press" style={{padding:'5px 8px',fontSize:11,color:'var(--warn)',borderColor:'var(--hair)'}} onClick={()=>pushToast({title:'PAPER 已重置',body:'清订单+清仓+回 10 万 USDT',tone:'warn'})}>↺ 重置</button>}
      </div>
      {!isPaper&&<div style={{marginTop:8,fontSize:10,color:'var(--ink-3)'}}>API key: <span className="kq-mono-row">...a1b2</span>（加密存储 · 仅露末 4 位）</div>}
    </Card>;
  }

  function PortfolioPage(){
    const {data,setPage,pushToast}=useApp();
    const [showAdd,setShowAdd]=useState(false);
    const [addType,setAddType]=useState('PAPER');
    const totalEquity=data.accounts.reduce((a,b)=>a+b.equity,0);
    const totalPnl=data.positions.reduce((a,b)=>a+b.uPnl,0);
    const allPositions=data.positions;

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:14}}>
        <div>
          <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>组合总览</h1>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>多账户聚合 · 部分账户拉取失败会降级展示</p>
        </div>
        <button className="kq-btn-primary kq-press" onClick={()=>setShowAdd(true)}>+ 接入账户</button>
      </div>

      {/* Portfolio summary */}
      <Card pad={0} style={{overflow:'hidden'}}>
        <div style={{padding:'20px 24px',background:'radial-gradient(circle at 80% 0%, var(--brand-soft) 0%, transparent 50%)'}}>
          <div style={{display:'grid',gridTemplateColumns:'1.4fr 1fr 1fr 1fr 1fr',gap:20,alignItems:'center'}} className="kq-port-grid">
            <div>
              <div style={{fontSize:11,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em',fontWeight:600}}>总资产（USDT 估值）</div>
              <div className="kq-mono-row" style={{fontSize:36,fontWeight:700,marginTop:4,letterSpacing:'-.02em'}}>$ {fmt(totalEquity,2)}</div>
              <div className="kq-mono-row" style={{fontSize:12,color:'var(--up)',fontWeight:600}}>▲ {fmtSigned(totalPnl,2)} 未实现 · +12.4% 30D</div>
            </div>
            <Stat label="账户数" value={data.accounts.length} mono sub={`${data.accounts.filter(a=>a.isPaper).length} PAPER · ${data.accounts.filter(a=>!a.isPaper).length} LIVE`}/>
            <Stat label="持仓数" value={allPositions.length} mono sub="多账户聚合"/>
            <Stat label="已实现" value={fmtSigned(620+85,2)} tone="up" mono sub="30D"/>
            <Stat label="手续费" value={fmt(14.84,2)} mono sub="30D"/>
          </div>
        </div>
        <div style={{padding:'8px 24px',borderTop:'1px solid var(--hair)'}}>
          <EquityCurve data={data.equityCurve} width={1040} height={140} color="var(--brand)"/>
        </div>
      </Card>

      {/* Account cards */}
      <div>
        <SectionTitle title="交易所账户" sub="PAPER 必须选基准交易所 · API key 加密存储"/>
        <div style={{display:'grid',gridTemplateColumns:'repeat(3,1fr)',gap:14}} className="kq-acc-grid">
          {data.accounts.map(a=><AccountCard key={a.id} acc={a}/>)}
        </div>
      </div>

      {/* Positions across accounts */}
      <Card>
        <SectionTitle title="跨账户持仓" sub="实时推送 · 持仓数量/均价/盈亏变化" right={<button onClick={()=>setPage('trade')} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:12}}>管理交易</button>}/>
        <div style={{overflow:'auto'}}>
          <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
            <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>账户</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>Symbol</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>方向</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>数量</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>均价</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>未实现</th>
              <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>占比</th>
            </tr></thead>
            <tbody>
              {allPositions.map((p,i)=><tr key={i}>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{p.acc.includes('paper')?<span className="kq-paper-badge">PAPER</span>:<span className="kq-live-badge">LIVE</span>}</td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{p.symbol}</td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}><span style={{color:p.side==='LONG'?'var(--up)':'var(--down)',fontWeight:700}}>{p.side}</span></td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(p.qty,4)}</td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(p.avg,2)}</td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:p.uPnl>=0?'var(--up)':'var(--down)',fontWeight:700}}>{fmtSigned(p.uPnl,2)}</td>
                <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>
                  <div style={{width:60,height:6,background:'var(--surface-2)',borderRadius:3,overflow:'hidden'}}><div style={{width:`${30+i*15}%`,height:'100%',background:'var(--brand)'}}/></div>
                </td>
              </tr>)}
            </tbody>
          </table>
        </div>
      </Card>

      <Modal open={showAdd} onClose={()=>setShowAdd(false)} title="接入交易所账户" width={560}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowAdd(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowAdd(false);pushToast({title:'账户已接入',body:addType==='PAPER'?'PAPER 模拟盘已就绪 · 10 万 USDT 虚拟资金':'API key 已加密存储',tone:'up'});}}>接入</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div>
            <label className="kq-label">类型</label>
            <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:8}}>
              <button onClick={()=>setAddType('PAPER')} className="kq-press" style={{padding:'10px',borderRadius:8,border:`2px solid ${addType==='PAPER'?'var(--up)':'var(--hair)'}`,background:addType==='PAPER'?'rgba(43,162,152,.08)':'var(--surface-2)',color:addType==='PAPER'?'var(--up)':'var(--ink-2)',fontWeight:600,cursor:'pointer',fontSize:12,transition:'all .12s'}}>PAPER 模拟</button>
              <button onClick={()=>setAddType('LIVE')} className="kq-press" style={{padding:'10px',borderRadius:8,border:`2px solid ${addType==='LIVE'?'var(--brand)':'var(--hair)'}`,background:addType==='LIVE'?'var(--brand-soft)':'var(--surface-2)',color:addType==='LIVE'?'var(--brand)':'var(--ink-2)',fontWeight:600,cursor:'pointer',fontSize:12,transition:'all .12s'}}>LIVE 实盘</button>
            </div>
          </div>
          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:12}}>
            <div>
              <label className="kq-label">交易所</label>
              <select className="kq-input"><option>BINANCE</option><option>OKX</option><option>BITGET</option></select>
            </div>
            <div>
              <label className="kq-label">账户标签</label>
              <input className="kq-input" defaultValue="主账户"/>
            </div>
          </div>
          {addType==='LIVE'&&<div style={{display:'flex',flexDirection:'column',gap:10}}>
            <div>
              <label className="kq-label">API Key</label>
              <input className="kq-input" placeholder="粘贴 API key · 加密存储"/>
            </div>
            <div>
              <label className="kq-label">API Secret</label>
              <input className="kq-input" type="password" placeholder="粘贴 secret · 加密存储"/>
            </div>
          </div>}
          <div style={{padding:10,borderRadius:8,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:11,color:'var(--ink-3)',lineHeight:1.5}}>
            {addType==='PAPER'?
              <>PAPER 模拟盘 · 10 万 USDT 虚拟资金 · 用所选交易所作为基准行情撮合 · 可重置。现货 / 合约在下单时选择，无需提前绑定。</>
            :<>⚠ LIVE 实盘 API key 加密存储，UI 永远不会展示明文，只露末 4 位。现货 / 合约在下单时选择，无需提前绑定。</>}
          </div>
        </div>
      </Modal>

      <style>{`
        @media(max-width:1100px){.kq-acc-grid{grid-template-columns:1fr 1fr !important}.kq-port-grid{grid-template-columns:1fr 1fr !important}}
        @media(max-width:680px){.kq-acc-grid{grid-template-columns:1fr !important}.kq-port-grid{grid-template-columns:1fr !important}}
      `}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.PortfolioPage=PortfolioPage;
})();
