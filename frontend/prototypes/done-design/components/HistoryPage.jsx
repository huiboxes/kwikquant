(function(){
  const {useState,useMemo}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,Stat,fmt,fmtSigned}=ui;

  function HistoryPage(){
    const {data,pushToast}=useApp();
    const [page,setPage]=useState(1);
    const [acc,setAcc]=useState('all');
    const [sym,setSym]=useState('all');
    const pageSize=10;
    const filtered=useMemo(()=>data.trades.filter(t=>{
      if(acc!=='all'&&(acc==='PAPER'?!t.acc.includes('PAPER'):t.acc!=='LIVE'))return false;
      if(sym!=='all'&&!t.symbol.includes(sym))return false;
      return true;
    }),[data.trades,acc,sym]);
    const total=filtered.length;
    const pages=Math.ceil(total/pageSize)||1;
    const cur=filtered.slice((page-1)*pageSize,(page-1)*pageSize+pageSize);
    const totalVol=filtered.reduce((a,b)=>a+b.qty*b.price,0);
    const totalFee=filtered.reduce((a,b)=>a+b.fee,0);
    const totalPnl=filtered.reduce((a,b)=>a+b.pnl,0);

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:14}}>
        <div>
          <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>交易历史</h1>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>订单 + 成交聚合 · 按 账户 / Symbol / 时间筛选 · CSV / JSON 导出</p>
        </div>
        <div style={{display:'flex',gap:8}}>
          <button className="kq-btn-ghost kq-press" style={{fontSize:12,padding:'8px 14px'}} onClick={()=>pushToast({title:'CSV 已导出',body:`${filtered.length} 条记录`,tone:'info'})}>↓ CSV</button>
          <button className="kq-btn-ghost kq-press" style={{fontSize:12,padding:'8px 14px'}} onClick={()=>pushToast({title:'JSON 已导出',body:`${filtered.length} 条记录`,tone:'info'})}>↓ JSON</button>
        </div>
      </div>

      {/* Stats */}
      <div style={{display:'grid',gridTemplateColumns:'repeat(4,1fr)',gap:14}} className="kq-hist-stat">
        <Card><Stat label="成交笔数" value={filtered.length} mono sub="筛选后"/></Card>
        <Card><Stat label="总成交额" value={'$ '+fmt(totalVol,2)} mono sub="USDT"/></Card>
        <Card><Stat label="总手续费" value={fmt(totalFee,2)} mono sub="USDT" tone="warn"/></Card>
        <Card><Stat label="已实现盈亏" value={fmtSigned(totalPnl,2)} mono sub="USDT" tone={totalPnl>=0?'up':'down'}/></Card>
      </div>

      {/* Filters */}
      <Card>
        <div style={{display:'flex',gap:12,alignItems:'flex-end',flexWrap:'wrap'}}>
          <div>
            <label className="kq-label">账户</label>
            <select className="kq-input" style={{width:'auto',padding:'8px 10px'}} value={acc} onChange={e=>{setAcc(e.target.value);setPage(1);}}>
              <option value="all">全部</option><option value="PAPER">PAPER</option><option value="LIVE">LIVE</option>
            </select>
          </div>
          <div>
            <label className="kq-label">Symbol</label>
            <select className="kq-input" style={{width:'auto',padding:'8px 10px'}} value={sym} onChange={e=>{setSym(e.target.value);setPage(1);}}>
              <option value="all">全部</option><option value="BTC">BTC/USDT</option><option value="ETH">ETH/USDT</option><option value="SOL">SOL/USDT</option>
            </select>
          </div>
          <div>
            <label className="kq-label">开始日期</label>
            <input className="kq-input" type="date" defaultValue="2026-07-01" style={{width:'auto',padding:'8px 10px'}} onChange={()=>setPage(1)}/>
          </div>
          <div>
            <label className="kq-label">结束日期</label>
            <input className="kq-input" type="date" defaultValue="2026-07-09" style={{width:'auto',padding:'8px 10px'}} onChange={()=>setPage(1)}/>
          </div>
          <button className="kq-btn-ghost kq-press" style={{fontSize:12,padding:'8px 14px'}} onClick={()=>{setAcc('all');setSym('all');setPage(1);}}>重置</button>
          <div style={{flex:1}}/>
          <div className="kq-mono-row" style={{fontSize:11,color:'var(--ink-3)'}}>{filtered.length} 条 · {(page-1)*pageSize+1}–{Math.min(page*pageSize,total)}</div>
        </div>
      </Card>

      {/* Table */}
      <Card pad={0} style={{overflow:'hidden'}}>
        <div style={{overflow:'auto'}}>
          <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
            <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>时间</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>账户</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>Symbol</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>方向</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>数量</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>价格</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>手续费</th>
              <th style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>已实现</th>
            </tr></thead>
            <tbody>
              {cur.map((t,i)=><tr key={i} className="kq-flash">
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>{t.ts}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>{t.acc==='PAPER'?<span className="kq-paper-badge">PAPER</span>:<span className="kq-live-badge">LIVE</span>}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}>{t.symbol}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)'}}><span style={{color:t.side==='BUY'?'var(--up)':'var(--down)',fontWeight:700}}>{t.side}</span></td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(t.qty,t.qty<1?4:2)}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(t.price,t.price<1?4:2)}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:'var(--warn)'}}>{fmt(t.fee,4)}</td>
                <td style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:t.pnl>=0?'var(--up)':'var(--down)',fontWeight:700}}>{t.pnl?fmtSigned(t.pnl,2):'—'}</td>
              </tr>)}
              {cur.length===0&&<tr><td colSpan={8} style={{padding:24,textAlign:'center',color:'var(--ink-3)'}}>无匹配记录</td></tr>}
            </tbody>
          </table>
        </div>
        {/* Pagination */}
        <div style={{padding:'12px 16px',borderTop:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
          <div style={{fontSize:11,color:'var(--ink-3)'}}>第 {page} / {pages} 页 · 每页 {pageSize} 条</div>
          <div style={{display:'flex',gap:6}}>
            <button disabled={page<=1} onClick={()=>setPage(p=>Math.max(1,p-1))} className="kq-btn-ghost kq-press" style={{padding:'5px 12px',fontSize:12,opacity:page<=1?.4:1}}>‹ 上一页</button>
            {Array.from({length:pages}).map((_,i)=><button key={i} onClick={()=>setPage(i+1)} className="kq-press" style={{padding:'5px 10px',fontSize:12,borderRadius:6,border:'1px solid',borderColor:i+1===page?'var(--brand)':'var(--hair)',background:i+1===page?'var(--brand-soft)':'var(--surface)',color:i+1===page?'var(--brand)':'var(--ink-2)',cursor:'pointer',fontWeight:600}}>{i+1}</button>)}
            <button disabled={page>=pages} onClick={()=>setPage(p=>Math.min(pages,p+1))} className="kq-btn-ghost kq-press" style={{padding:'5px 12px',fontSize:12,opacity:page>=pages?.4:1}}>下一页 ›</button>
          </div>
        </div>
      </Card>

      <style>{`@media(max-width:900px){.kq-hist-stat{grid-template-columns:1fr 1fr !important}}`}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.HistoryPage=HistoryPage;
})();
