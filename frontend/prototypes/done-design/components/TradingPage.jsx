(function(){
  const {useState,useMemo}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,OrderStatusBadge,Modal,LivePrice,fmt,fmtSigned,Ticker}=ui;

  const ORDER_TYPES=['MARKET','LIMIT','STOP_MARKET','STOP_LIMIT','TAKE_PROFIT_MARKET','TAKE_PROFIT_LIMIT','TRAILING_STOP'];
  const TIF=['GTC','IOC','FOK','GTD'];

  function OrderForm({mode,acc}){
    const {pushToast}=useApp();
    const [type,setType]=useState('LIMIT');
    const [side,setSide]=useState('BUY');
    const [price,setPrice]=useState('61200');
    const [qty,setQty]=useState('0.1');
    const [tif,setTif]=useState('GTC');
    const [trail,setTrail]=useState('1.5');
    const [confirm,setConfirm]=useState(false);
    const isLive=mode==='LIVE';

    const submit=()=>{
      if(isLive){setConfirm(true);return;}
      pushToast({title:'订单已提交',body:`${side} ${qty} BTC/USDT @ ${type==='MARKET'?'市价':price}`,tone:side==='BUY'?'up':'down'});
    };
    const confirmLive=()=>{
      setConfirm(false);
      pushToast({title:'实盘订单已提交',body:`${side} ${qty} · 风控已通过`,tone:'brand'});
    };

    const notional=parseFloat(qty)*parseFloat(price||0);

    return <Card style={{borderTop:`3px solid ${isLive?'var(--brand)':'var(--up)'}`}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',marginBottom:14}}>
        <div style={{display:'flex',alignItems:'center',gap:8}}>
          <strong style={{fontSize:14}}>下单</strong>
          {isLive?<span className="kq-live-badge">● LIVE · 真金白银</span>:<span className="kq-paper-badge">PAPER · 模拟</span>}
        </div>
        <select className="kq-input" style={{width:'auto',padding:'6px 10px',fontSize:12}} value={acc.id}>
          {useApp().data.accounts.filter(a=>a.isPaper===(mode!=='LIVE')).map(a=><option key={a.id} value={a.id}>{a.label}</option>)}
        </select>
      </div>

      {/* BUY/SELL toggle */}
      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:6,marginBottom:14}}>
        <button onClick={()=>setSide('BUY')} className="kq-press" style={{padding:'10px',borderRadius:8,border:'1px solid',borderColor:side==='BUY'?'var(--up)':'var(--hair)',background:side==='BUY'?'rgba(43,162,152,.15)':'var(--surface-2)',color:side==='BUY'?'var(--up)':'var(--ink-2)',fontWeight:700,fontSize:13,cursor:'pointer',transition:'all .12s'}}>买入 BUY</button>
        <button onClick={()=>setSide('SELL')} className="kq-press" style={{padding:'10px',borderRadius:8,border:'1px solid',borderColor:side==='SELL'?'var(--down)':'var(--hair)',background:side==='SELL'?'rgba(246,57,105,.15)':'var(--surface-2)',color:side==='SELL'?'var(--down)':'var(--ink-2)',fontWeight:700,fontSize:13,cursor:'pointer',transition:'all .12s'}}>卖出 SELL</button>
      </div>

      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:8,marginBottom:10}}>
        {ORDER_TYPES.map(t=>(
          <button key={t} onClick={()=>setType(t)} className="kq-press" style={{padding:'6px 8px',fontSize:10.5,fontWeight:600,borderRadius:6,border:'1px solid',borderColor:type===t?'var(--brand)':'var(--hair)',background:type===t?'var(--brand-soft)':'var(--surface-2)',color:type===t?'var(--brand)':'var(--ink-2)',cursor:'pointer',letterSpacing:'.02em'}}>{t}</button>
        ))}
      </div>

      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:10,marginBottom:10}}>
        <div>
          <label className="kq-label">价格 (USDT)</label>
          <input className="kq-input kq-mono-row" value={price} onChange={e=>setPrice(e.target.value)} disabled={type==='MARKET'} style={{opacity:type==='MARKET'?.5:1}}/>
        </div>
        <div>
          <label className="kq-label">数量 (BTC)</label>
          <input className="kq-input kq-mono-row" value={qty} onChange={e=>setQty(e.target.value)}/>
        </div>
        {type==='TRAILING_STOP'&&<div style={{gridColumn:'1 / -1'}}>
          <label className="kq-label">追踪幅度 (%)</label>
          <input className="kq-input kq-mono-row" value={trail} onChange={e=>setTrail(e.target.value)}/>
        </div>}
        {type.includes('STOP')&&!type.includes('TRAILING')&&<div style={{gridColumn:'1 / -1'}}>
          <label className="kq-label">触发价</label>
          <input className="kq-input kq-mono-row" defaultValue="60500"/>
        </div>}
      </div>

      <div style={{display:'flex',gap:6,marginBottom:14}}>
        {TIF.map(t=>(
          <button key={t} onClick={()=>setTif(t)} className="kq-press" style={{flex:1,padding:'6px',fontSize:11,fontWeight:600,borderRadius:6,border:'1px solid',borderColor:tif===t?'var(--brand)':'var(--hair)',background:tif===t?'var(--brand-soft)':'var(--surface-2)',color:tif===t?'var(--brand)':'var(--ink-2)',cursor:'pointer'}}>{t}</button>
        ))}
      </div>

      <div style={{padding:'10px 12px',borderRadius:8,background:'var(--surface-2)',marginBottom:14}}>
        <div style={{display:'flex',justifyContent:'space-between',fontSize:11,color:'var(--ink-3)'}}>
          <span>订单金额</span><span className="kq-mono-row" style={{color:'var(--ink)',fontWeight:700}}>{fmt(notional,2)} USDT</span>
        </div>
        <div style={{display:'flex',justifyContent:'space-between',fontSize:11,color:'var(--ink-3)',marginTop:4}}>
          <span>预估手续费</span><span className="kq-mono-row">{fmt(notional*0.0004,4)}</span>
        </div>
        {isLive&&<div style={{display:'flex',justifyContent:'space-between',fontSize:11,color:'var(--down)',marginTop:4}}>
          <span>风控闸门</span><span style={{fontWeight:600}}>MAX_NOTIONAL · 检查中</span>
        </div>}
      </div>

      <button onClick={submit} className="kq-btn-primary kq-press" style={{width:'100%',padding:'12px',fontSize:14,background:side==='BUY'?'var(--up)':'var(--down)',color:'#fff'}}>
        {side==='BUY'?'买入':'卖出'} {qty} BTC/USDT
        {isLive&&' · 真金白银'}
      </button>

      {isLive&&<div style={{marginTop:10,padding:10,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:11,color:'var(--brand-ink)',lineHeight:1.55}}>
        ⚠ LIVE 账户订单为真金白银，提交前会通过风控闸门（MAX_NOTIONAL / DAILY_LOSS_LIMIT / ORDER_FREQUENCY），高风险操作需二次确认。
      </div>}
      {!isLive&&<div style={{marginTop:10,padding:10,borderRadius:8,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:11,color:'var(--ink-3)',lineHeight:1.55}}>
        PAPER 模拟盘使用 10 万 USDT 虚拟资金 + 基准交易所行情撮合，可重置。
      </div>}

      <Modal open={confirm} onClose={()=>setConfirm(false)} title="⚠ 实盘下单确认" width={460}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setConfirm(false)}>取消</button><button onClick={confirmLive} style={{padding:'9px 16px',borderRadius:10,fontWeight:700,fontSize:13,background:'var(--down)',color:'#fff',border:'none',cursor:'pointer'}} className="kq-press">确认下单（真金白银）</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div style={{padding:14,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)'}}>
            <div style={{fontSize:13,fontWeight:700,color:'var(--brand-ink)'}}>这是 LIVE 实盘订单</div>
            <div style={{fontSize:11,color:'var(--brand-ink)',marginTop:4,lineHeight:1.5}}>真实交易所、真实资金、真实手续费。请仔细确认订单参数。</div>
          </div>
          <div style={{padding:14,borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)'}}>
            <div style={{display:'flex',justifyContent:'space-between',fontSize:12,padding:'4px 0'}}>
              <span style={{color:'var(--ink-3)'}}>订单类型</span><span><strong>{type}</strong></span>
            </div>
            <div style={{display:'flex',justifyContent:'space-between',fontSize:12,padding:'4px 0'}}>
              <span style={{color:'var(--ink-3)'}}>方向</span><span style={{color:side==='BUY'?'var(--up)':'var(--down)',fontWeight:700}}>{side}</span>
            </div>
            <div style={{display:'flex',justifyContent:'space-between',fontSize:12,padding:'4px 0'}}>
              <span style={{color:'var(--ink-3)'}}>价格</span><span className="kq-mono-row">{price}</span>
            </div>
            <div style={{display:'flex',justifyContent:'space-between',fontSize:12,padding:'4px 0'}}>
              <span style={{color:'var(--ink-3)'}}>数量</span><span className="kq-mono-row">{qty} BTC</span>
            </div>
            <div style={{display:'flex',justifyContent:'space-between',fontSize:12,padding:'4px 0'}}>
              <span style={{color:'var(--ink-3)'}}>总金额</span><span className="kq-mono-row" style={{fontWeight:700}}>{fmt(notional,2)} USDT</span>
            </div>
          </div>
          <label style={{display:'flex',gap:8,alignItems:'flex-start',fontSize:12,color:'var(--ink-2)'}}>
            <input type="checkbox" style={{marginTop:2,accentColor:'var(--brand)'}}/>
            <span>我已确认这是实盘订单，知悉风险</span>
          </label>
        </div>
      </Modal>
    </Card>;
  }

  function OrderBook(){
    const asks=useMemo(()=>{
      const out=[];let p=61250;for(let i=0;i<8;i++){p-=2+Math.random()*4;const q=Math.random()*0.4+0.01;out.push({p,q});}return out.reverse();
    },[]);
    const bids=useMemo(()=>{
      const out=[];let p=61200;for(let i=0;i<8;i++){p-=2+Math.random()*4;const q=Math.random()*0.4+0.01;out.push({p,q});}return out;
    },[]);
    const maxQ=Math.max(...asks.map(a=>a.q),...bids.map(b=>b.q));
    return <Card pad={0} style={{overflow:'hidden'}}>
      <div style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
        <strong style={{fontSize:13}}>订单簿</strong>
        <Chip>BTC/USDT · PERP</Chip>
      </div>
      <div style={{padding:'4px 14px'}}>
        <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em',padding:'6px 0'}}>
          <span>价格</span><span style={{textAlign:'right'}}>数量</span><span style={{textAlign:'right'}}>合计</span>
        </div>
        {asks.map((a,i)=><div key={i} className="kq-mono-row" style={{position:'relative',display:'grid',gridTemplateColumns:'1fr 1fr 1fr',padding:'3px 0',fontSize:12,color:'var(--down)'}}>
          <span style={{background:'rgba(246,57,105,.08)',position:'absolute',right:0,top:0,bottom:0,width:`${(a.q/maxQ)*60}%`}}/>
          <span style={{position:'relative'}}>{fmt(a.p,2)}</span>
          <span style={{position:'relative',textAlign:'right',color:'var(--ink-2)'}}>{fmt(a.q,4)}</span>
          <span style={{position:'relative',textAlign:'right',color:'var(--ink-3)'}}>{fmt(a.p*a.q,2)}</span>
        </div>)}
      </div>
      <div style={{padding:'6px 14px',background:'var(--surface-2)',borderTop:'1px solid var(--hair)',borderBottom:'1px solid var(--hair)'}}>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
          <div>
            <div style={{fontSize:10,color:'var(--ink-3)'}}>最新价</div>
            <LivePrice symbol="BTC/USDT" base={61220.50} chg={2.34} dp={2}/>
          </div>
          <div style={{textAlign:'right'}}>
            <div style={{fontSize:10,color:'var(--ink-3)'}}>24h</div>
            <div style={{fontSize:13,fontWeight:700,color:'var(--up)'}}>▲ +2.34%</div>
          </div>
        </div>
      </div>
      <div style={{padding:'4px 14px'}}>
        {bids.map((b,i)=><div key={i} className="kq-mono-row" style={{position:'relative',display:'grid',gridTemplateColumns:'1fr 1fr 1fr',padding:'3px 0',fontSize:12,color:'var(--up)'}}>
          <span style={{background:'rgba(43,162,152,.08)',position:'absolute',right:0,top:0,bottom:0,width:`${(b.q/maxQ)*60}%`}}/>
          <span style={{position:'relative'}}>{fmt(b.p,2)}</span>
          <span style={{position:'relative',textAlign:'right',color:'var(--ink-2)'}}>{fmt(b.q,4)}</span>
          <span style={{position:'relative',textAlign:'right',color:'var(--ink-3)'}}>{fmt(b.p*b.q,2)}</span>
        </div>)}
      </div>
    </Card>;
  }

  function PositionsTable({mode}){
    const {data,pushToast}=useApp();
    const positions=data.positions.filter(p=>(mode==='LIVE')!==p.acc.includes('paper'));
    const display=mode==='LIVE'?data.positions.filter(p=>!p.acc.includes('paper')):data.positions.filter(p=>p.acc.includes('paper'));
    const list=display.length?display:data.positions;
    return <Card>
      <SectionTitle title="持仓" sub={mode==='LIVE'?'实盘账户持仓':'PAPER 模拟盘持仓'} right={<Chip>{list.length} 个</Chip>}/>
      <div style={{overflow:'auto'}}>
        <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
          <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>账户</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>Symbol</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>方向</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>数量</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>均价</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>未实现</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>已实现</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>操作</th>
          </tr></thead>
          <tbody>
            {list.map((p,i)=><tr key={i}>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>
                {p.acc.includes('paper')?<span className="kq-paper-badge">PAPER</span>:<span className="kq-live-badge">LIVE</span>}
              </td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{p.symbol}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>
                <span style={{color:p.side==='LONG'?'var(--up)':'var(--down)',fontWeight:700}}>{p.side}</span>
              </td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(p.qty,4)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{fmt(p.avg,2)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:p.uPnl>=0?'var(--up)':'var(--down)',fontWeight:700}}>{fmtSigned(p.uPnl,2)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right',color:p.rPnl>=0?'var(--up)':'var(--down)'}}>{fmtSigned(p.rPnl,2)}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>
                <button onClick={()=>pushToast({title:'已平仓',body:p.symbol,tone:mode==='LIVE'?'brand':'up'})} className="kq-btn-ghost kq-press" style={{padding:'4px 10px',fontSize:11}}>平仓</button>
              </td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </Card>;
  }

  function OrdersTable({mode}){
    const {data}=useApp();
    const orders=data.orders.filter(o=>mode==='LIVE'?!o.acc.includes('paper'):o.acc.includes('paper'));
    return <Card>
      <SectionTitle title="当前订单" sub={mode==='LIVE'?'实盘挂单/部分成交':'PAPER 挂单/部分成交'} right={<div style={{display:'flex',gap:6}}>
        <button className="kq-tab active">活动</button>
        <button className="kq-tab">全部</button>
        <button className="kq-tab">已撤销</button>
      </div>}/>
      <div style={{overflow:'auto'}}>
        <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
          <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>订单ID</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>Symbol</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>类型</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>方向</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>价格</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>数量</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>状态</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>时间</th>
          </tr></thead>
          <tbody>
            {orders.map((o,i)=><tr key={i}>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{o.id}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{o.symbol}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}><Chip>{o.type}</Chip></td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}><span style={{color:o.side==='BUY'?'var(--up)':'var(--down)',fontWeight:700}}>{o.side}</span></td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{o.price?fmt(o.price,2):'—'}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{o.qty}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}><OrderStatusBadge status={o.status}/></td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',textAlign:'right'}}>{o.ts}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </Card>;
  }

  function BalanceBar({mode}){
    const {data}=useApp();
    const acc=data.accounts.find(a=>a.isPaper===(mode!=='LIVE'))||data.accounts[0];
    return <Card>
      <div style={{display:'grid',gridTemplateColumns:'repeat(4,1fr)',gap:16}} className="kq-bal-grid">
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>可用</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700,marginTop:4}}>{fmt(acc.balance,2)}</div>
        </div>
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>冻结</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700,marginTop:4,color:'var(--warn)'}}>{fmt(acc.frozen,2)}</div>
        </div>
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>总权益</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700,marginTop:4}}>{fmt(acc.equity,2)}</div>
        </div>
        <div>
          <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.05em'}}>未实现盈亏</div>
          <div className="kq-mono-row" style={{fontSize:20,fontWeight:700,marginTop:4,color:'var(--up)'}}>{fmtSigned(data.positions.reduce((a,b)=>a+b.uPnl,0),2)}</div>
        </div>
      </div>
      {mode!=='LIVE'&&<div style={{marginTop:10,paddingTop:10,borderTop:'1px solid var(--hair)',fontSize:11,color:'var(--ink-3)'}}>
        PAPER 模拟盘 · 基准交易所 <strong>BINANCE</strong> · 余额本地真实化（下单冻结/成交扣减/撤单解冻）
      </div>}
      {mode==='LIVE'&&<div style={{marginTop:10,paddingTop:10,borderTop:'1px solid var(--hair)',fontSize:11,color:'var(--brand-ink)'}}>
        LIVE · 余额由交易所实时维护，每次查询实时拉取
      </div>}
    </Card>;
  }

  function TradingPage(){
    const {data,pushToast,tradeMode,setTradeMode,liveConfirmedThisSession,setLiveConfirmedThisSession}=useApp();
    const [showLiveConfirm,setShowLiveConfirm]=useState(false);
    const isLive=tradeMode==='LIVE';
    const mode=isLive?'LIVE':'PAPER';
    const acc=data.accounts.find(a=>a.isPaper===(mode!=='LIVE'))||data.accounts[0];

    const switchMode=(target)=>{
      if(target==='LIVE'&&tradeMode==='PAPER'){
        if(liveConfirmedThisSession){setTradeMode('LIVE');}
        else{setShowLiveConfirm(true);}
      }else{
        setTradeMode(target);
      }
    };
    const confirmLive=()=>{
      setLiveConfirmedThisSession(true);
      setTradeMode('LIVE');
      setShowLiveConfirm(false);
      pushToast({title:'已切到 LIVE 实盘',body:'本会话内不再重复确认',tone:'brand'});
    };

    const SegMode=({value,label,sub,tone})=>{
      const active=tradeMode===value;
      const bg=active?(tone==='LIVE'?'var(--brand)':'var(--up)'):'transparent';
      const fg=active?'#fff':(tone==='LIVE'?'var(--brand)':'var(--ink-2)');
      return <button onClick={()=>switchMode(value)} className="kq-press" style={{flex:1,padding:'12px 16px',borderRadius:10,border:'1px solid',borderColor:active?(tone==='LIVE'?'var(--brand)':'var(--up)'):'var(--hair)',background:bg,color:fg,cursor:'pointer',display:'flex',flexDirection:'column',alignItems:'center',gap:2,transition:'all .15s'}}>
        <span style={{fontSize:13,fontWeight:700}}>{label}</span>
        <span style={{fontSize:10,opacity:.85}}>{sub}</span>
      </button>;
    };

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      {/* Mode switcher banner */}
      <div style={{padding:'16px 20px',borderRadius:14,background:isLive?'linear-gradient(135deg, var(--brand-soft) 0%, var(--surface) 100%)':'linear-gradient(135deg, rgba(43,162,152,.10) 0%, var(--surface) 100%)',border:`1px solid ${isLive?'var(--brand)':'var(--up)'}`,transition:'all .25s'}}>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',flexWrap:'wrap',gap:14,marginBottom:12}}>
          <div style={{display:'flex',alignItems:'center',gap:10}}>
            {isLive?
              <span className="kq-live-badge" style={{padding:'4px 10px',fontSize:11}}>● LIVE · 实盘</span>
            :<span className="kq-paper-badge" style={{padding:'4px 10px',fontSize:11}}>PAPER · 模拟</span>}
            <div>
              <div style={{fontSize:18,fontWeight:700,letterSpacing:'-.01em'}}>{isLive?'实盘交易':'模拟盘交易'}</div>
              <div style={{fontSize:11,color:'var(--ink-3)',marginTop:2}}>{isLive?'真金白银 · 余额由交易所维护 · 不可重置':'虚拟 10 万 USDT · 基准行情撮合 · 可重置'}</div>
            </div>
          </div>
          {!isLive&&<button className="kq-btn-ghost kq-press" onClick={()=>pushToast({title:'PAPER 已重置',body:'订单清空 + 持仓平仓 + 余额回 10 万 USDT',tone:'warn'})}>↺ 重置模拟盘</button>}
        </div>
        <div style={{display:'flex',gap:8}}>
          <SegMode value="PAPER" label="PAPER · 模拟" sub="10 万 USDT 虚拟" tone="PAPER"/>
          <SegMode value="LIVE" label="LIVE · 实盘" sub="真金白银" tone="LIVE"/>
        </div>
      </div>

      {/* Sticky LIVE badge — fixed below topbar so it doesn't overlap the account chip */}
      {isLive&&<div style={{position:'fixed',top:70,right:18,zIndex:90,pointerEvents:'none'}}>
        <div className="kq-live-badge" style={{padding:'5px 10px',fontSize:11,boxShadow:'var(--shadow-pop)',animation:'kqPulse 2s ease-in-out infinite'}}>● LIVE</div>
      </div>}

      {/* Balance */}
      <BalanceBar mode={mode}/>

      {/* Main 3-col — always 3 columns; horizontal scroll on narrow screens */}
      <div style={{overflowX:'auto',margin:'0 -8px',padding:'0 8px'}}>
        <div style={{display:'grid',gridTemplateColumns:'1.4fr 320px 1fr',gap:18,minWidth:960}} className="kq-trade-grid">
          {/* Chart */}
          <Card pad={0}>
            <div style={{padding:'10px 14px',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
              <strong style={{fontSize:13}}>BTC/USDT · K 线</strong>
              <div style={{display:'flex',gap:6}}>
                {['1m','5m','15m','1h','4h','1d'].map((t,i)=><button key={t} className={`kq-tab ${i===2?'active':''}`} style={{padding:'4px 8px',fontSize:11}}>{t}</button>)}
              </div>
            </div>
            <div style={{padding:10,overflow:'auto'}}>
              <ui.Candles data={data.candles} width={600} height={300}/>
            </div>
            <div style={{padding:'8px 14px',borderTop:'1px solid var(--hair)',display:'flex',gap:14,fontSize:11,color:'var(--ink-3)'}}>
              <span>O <Ticker base={60800} chg={0} dp={2}/></span>
              <span>H <span className="kq-mono-row" style={{color:'var(--up)'}}>62,150</span></span>
              <span>L <span className="kq-mono-row" style={{color:'var(--down)'}}>59,800</span></span>
              <span>C <Ticker base={61220} chg={2.34} dp={2}/></span>
              <span>Vol <span className="kq-mono-row">1.2B</span></span>
            </div>
          </Card>
          {/* Order book */}
          <OrderBook/>
          {/* Order form */}
          <OrderForm mode={mode} acc={acc}/>
        </div>
      </div>

      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:18}} className="kq-trade-bottom">
        <PositionsTable mode={mode}/>
        <OrdersTable mode={mode}/>
      </div>

      <Modal open={showLiveConfirm} onClose={()=>setShowLiveConfirm(false)} title="⚠ 切到 LIVE 实盘" width={440}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowLiveConfirm(false)}>取消</button><button onClick={confirmLive} className="kq-press" style={{padding:'9px 16px',borderRadius:10,fontWeight:700,fontSize:13,background:'var(--brand)',color:'#fff',border:'none',cursor:'pointer'}}>确认切到 LIVE</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div style={{padding:14,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)'}}>
            <div style={{fontSize:13,fontWeight:700,color:'var(--brand-ink)'}}>你正在切到实盘</div>
            <div style={{fontSize:11,color:'var(--brand-ink)',marginTop:4,lineHeight:1.5}}>LIVE 模式下下单使用真实交易所、真实资金、真实手续费。误操作可能造成实际亏损。</div>
          </div>
          <div style={{fontSize:11,color:'var(--ink-3)',lineHeight:1.5}}>本次会话内不会再重复弹出确认。可随时通过顶栏切回 PAPER。</div>
        </div>
      </Modal>

      <style>{`
        @media(max-width:900px){.kq-trade-bottom{grid-template-columns:1fr !important}.kq-bal-grid{grid-template-columns:1fr 1fr !important}}
      `}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.TradingPage=TradingPage;
})();
