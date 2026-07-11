(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,LivePrice,Sparkline,Candles,Heatmap,fmt,fmtSigned,Stat}=ui;

  function MarketPage(){
    const {data}=useApp();
    const [sel,setSel]=useState(data.tickers[0]);
    const candles=useState(()=>{const out=[];let p=sel.last;for(let i=0;i<60;i++){const o=p;const h=o+Math.random()*sel.last*0.012;const l=o-Math.random()*sel.last*0.012;const c=l+Math.random()*(h-l);p=c;out.push({o,h,l,c,v:Math.random()*100+20});}return out;})[0];

    // observe K-line column width so the chart fills available space
    const chartWrapRef=React.useRef(null);
    const [chartW,setChartW]=useState(680);
    React.useEffect(()=>{
      if(!chartWrapRef.current)return;
      const ro=new ResizeObserver(es=>{
        for(const e of es){const w=Math.max(280,e.contentRect.width-8);setChartW(w);}
      });
      ro.observe(chartWrapRef.current);
      return ()=>ro.disconnect();
    },[]);

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:14}}>
        <div>
          <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>行情</h1>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>实时价格 · 历史 K 线 · ticker 与 K 线 WS 推送</p>
        </div>
        <div style={{display:'flex',gap:8}}>
          <button className="kq-btn-ghost kq-press" style={{fontSize:12,padding:'8px 14px'}}>♥ 订阅自选</button>
          <button className="kq-btn-primary kq-press" style={{fontSize:12,padding:'8px 14px'}}>订阅 {sel.symbol}</button>
        </div>
      </div>

      {/* Ticker grid */}
      <div style={{display:'grid',gridTemplateColumns:'repeat(4,1fr)',gap:10}} className="kq-ticker-grid">
        {data.tickers.map(t=>(
          <div key={t.symbol} onClick={()=>setSel(t)} className="kq-press" style={{padding:'12px 14px',borderRadius:10,background:t.symbol===sel.symbol?'var(--brand-soft)':'var(--surface)',border:'1px solid',borderColor:t.symbol===sel.symbol?'var(--brand)':'var(--hair)',cursor:'pointer',transition:'all .12s'}}>
            <div style={{display:'flex',justifyContent:'space-between',alignItems:'baseline'}}>
              <strong style={{fontSize:13}}>{t.symbol}</strong>
              {t.stale?<Chip tone="warn" style={{padding:'1px 6px',fontSize:9}}>STALE</Chip>:<span className="kq-pulse" style={{width:6,height:6,borderRadius:'50%',background:'var(--up)'}}/>}
            </div>
            <div style={{display:'flex',justifyContent:'space-between',alignItems:'baseline',marginTop:6}}>
              <LivePrice symbol={t.symbol} base={t.last} chg={t.chg} dp={t.last<1?4:2}/>
              <span className="kq-mono-row" style={{fontSize:11,fontWeight:700,color:t.chg>=0?'var(--up)':'var(--down)'}}>{t.chg>=0?'+':''}{t.chg}%</span>
            </div>
            <div style={{marginTop:6}}><Sparkline data={[1,2,3,2,4,3,5,4,6,5,7,t.chg>=0?8:6]} width={180} height={20}/></div>
            <div style={{display:'flex',justifyContent:'space-between',marginTop:6,fontSize:10,color:'var(--ink-3)'}}>
              <span>24H H <span className="kq-mono-row">{fmt(t.high,t.last<1?4:0)}</span></span>
              <span>L <span className="kq-mono-row">{fmt(t.low,t.last<1?4:0)}</span></span>
              <span>Vol <span className="kq-mono-row">{t.vol}</span></span>
            </div>
          </div>
        ))}
      </div>

      {/* K-line detail + Order book side-by-side */}
      <div style={{display:'grid',gridTemplateColumns:'1fr 300px',gap:18,alignItems:'start'}} className="kq-mkt-grid">
        <Card pad={0}>
          <div style={{padding:'12px 16px',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center',flexWrap:'wrap',gap:8}}>
            <div style={{display:'flex',alignItems:'center',gap:10,flexWrap:'wrap'}}>
              <strong style={{fontSize:15}}>{sel.symbol}</strong>
              {sel.stale&&<Chip tone="warn">STALE · 行情已断</Chip>}
              <LivePrice symbol={sel.symbol} base={sel.last} chg={sel.chg} dp={sel.last<1?4:2}/>
              <span className="kq-mono-row" style={{fontSize:13,fontWeight:700,color:sel.chg>=0?'var(--up)':'var(--down)'}}>{sel.chg>=0?'+':''}{sel.chg}%</span>
            </div>
            <div style={{display:'flex',gap:4}}>
              {['1m','5m','15m','1h','4h','1d'].map((t,i)=><button key={t} className={`kq-tab ${i===2?'active':''}`} style={{padding:'4px 8px',fontSize:11}}>{t}</button>)}
            </div>
          </div>
          <div ref={chartWrapRef} style={{padding:12,minWidth:0}}>
            <Candles data={candles} width={chartW} height={340}/>
          </div>
          <div style={{padding:'10px 16px',borderTop:'1px solid var(--hair)',display:'flex',gap:18,fontSize:11,color:'var(--ink-3)',flexWrap:'wrap'}}>
            <span>24H 涨跌 <span className="kq-mono-row" style={{color:sel.chg>=0?'var(--up)':'var(--down)',fontWeight:700}}>{sel.chg>=0?'+':''}{sel.chg}%</span></span>
            <span>最高 <span className="kq-mono-row">{fmt(sel.high,sel.last<1?4:0)}</span></span>
            <span>最低 <span className="kq-mono-row">{fmt(sel.low,sel.last<1?4:0)}</span></span>
            <span>24H 量 <span className="kq-mono-row">{sel.vol}</span></span>
            <span>买一 <span className="kq-mono-row" style={{color:'var(--up)'}}>{fmt(sel.last*0.9999,sel.last<1?4:2)}</span></span>
            <span>卖一 <span className="kq-mono-row" style={{color:'var(--down)'}}>{fmt(sel.last*1.0001,sel.last<1?4:2)}</span></span>
          </div>
        </Card>

        {/* Order book sits flush next to the K-line chart */}
        <Card pad={0} style={{display:'flex',flexDirection:'column'}}>
          <div style={{padding:'12px 14px',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
            <div>
              <div style={{fontSize:13,fontWeight:700}}>订单簿深度</div>
              <div style={{fontSize:10,color:'var(--ink-3)'}}>{sel.symbol}</div>
            </div>
            <span className="kq-live-badge">L2</span>
          </div>
          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',padding:'6px 14px 4px',fontSize:9,letterSpacing:'.06em',textTransform:'uppercase',color:'var(--ink-3)'}}>
            <span>价格</span>
            <span style={{textAlign:'right'}}>数量</span>
            <span style={{textAlign:'right'}}>总额</span>
          </div>
          <div style={{padding:'0 14px 8px',fontSize:11}}>
            {[1,2,3,4,5,6].map(i=>{
              const px=sel.last-i*0.5;
              const qty=Math.random()*0.5;
              return <div key={i} className="kq-mono-row" style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',padding:'3px 0',position:'relative'}}>
                <span style={{color:'var(--up)'}}>{fmt(px,2)}</span>
                <span style={{textAlign:'right'}}>{fmt(qty,4)}</span>
                <span style={{textAlign:'right',color:'var(--ink-3)'}}>{fmt(px*qty,0)}</span>
                <span style={{position:'absolute',right:0,top:4,bottom:4,width:`${Math.min(100,qty*180)}%`,background:'linear-gradient(90deg,transparent,rgba(30,142,126,.10))',borderRadius:4,zIndex:0,pointerEvents:'none'}}/>
              </div>;
            })}
          </div>
          <div style={{padding:'8px 14px',borderTop:'1px solid var(--hair)',borderBottom:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',alignItems:'center',background:'var(--surface-2)'}}>
            <span style={{fontSize:11,color:'var(--ink-3)'}}>买一 / 卖一</span>
            <span className="kq-mono-row" style={{fontSize:14,fontWeight:700,color:sel.chg>=0?'var(--up)':'var(--down)'}}>{fmt(sel.last,sel.last<1?4:2)}</span>
            <span style={{fontSize:10,color:'var(--ink-3)'}}>点差 {fmt(0.5,2)}</span>
          </div>
          <div style={{padding:'4px 14px 12px',fontSize:11}}>
            {[1,2,3,4,5,6].map(i=>{
              const px=sel.last+i*0.5;
              const qty=Math.random()*0.5;
              return <div key={i} className="kq-mono-row" style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',padding:'3px 0',position:'relative'}}>
                <span style={{color:'var(--down)'}}>{fmt(px,2)}</span>
                <span style={{textAlign:'right'}}>{fmt(qty,4)}</span>
                <span style={{textAlign:'right',color:'var(--ink-3)'}}>{fmt(px*qty,0)}</span>
                <span style={{position:'absolute',left:0,top:4,bottom:4,width:`${Math.min(100,qty*180)}%`,background:'linear-gradient(270deg,transparent,rgba(230,0,80,.10))',borderRadius:4,zIndex:0,pointerEvents:'none'}}/>
              </div>;
            })}
          </div>
        </Card>
      </div>

      {/* Subscription + PAPER source — side by side below the chart */}
      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:18}} className="kq-mkt-meta">
        <Card>
          <SectionTitle title="订阅状态" sub="REST 订阅 / WS 接收"/>
          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:8,fontSize:12}}>
            {data.tickers.slice(0,6).map(t=>(
              <div key={t.symbol} style={{display:'flex',justifyContent:'space-between',alignItems:'center',padding:'6px 10px',borderRadius:8,background:'var(--surface-2)'}}>
                <span>{t.symbol}</span>
                <div style={{display:'flex',alignItems:'center',gap:6}}>
                  <span className="kq-pulse" style={{width:6,height:6,borderRadius:'50%',background:t.stale?'var(--warn)':'var(--up)'}}/>
                  <span style={{fontSize:10,color:'var(--ink-3)'}}>{t.stale?'断开':'订阅中'}</span>
                </div>
              </div>
            ))}
          </div>
        </Card>
        <Card>
          <SectionTitle title="PAPER 行情来源" sub="PAPER 无自身行情"/>
          <div style={{display:'flex',flexDirection:'column',gap:8}}>
            <div style={{fontSize:11,color:'var(--ink-2)',lineHeight:1.6,padding:10,background:'var(--surface-2)',borderRadius:8}}>
              PAPER 账户走基准交易所 <strong style={{color:'var(--ink)'}}>BINANCE</strong> 行情。UI 不允许对 PAPER 直接查行情，需通过基准交易所。
            </div>
            <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',gap:8,fontSize:11}}>
              <div style={{padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
                <div style={{fontSize:9,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.06em'}}>基准</div>
                <div style={{fontWeight:700,marginTop:2}}>BINANCE</div>
              </div>
              <div style={{padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
                <div style={{fontSize:9,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.06em'}}>延迟</div>
                <div className="kq-mono-row" style={{fontWeight:700,color:'var(--up)',marginTop:2}}>12 ms</div>
              </div>
              <div style={{padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
                <div style={{fontSize:9,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.06em'}}>通道</div>
                <div style={{fontWeight:700,marginTop:2}}>WS · L2</div>
              </div>
            </div>
          </div>
        </Card>
      </div>

      {/* Sector heatmap */}
      <Card>
        <SectionTitle title="板块涨跌热度" sub="多币种 × 多周期 · 行末为行均值" right={<Chip tone="brand">DENSITY</Chip>}/>
        <div style={{overflowX:'auto',paddingBottom:4}}>
          <Heatmap
            data={data.tickers.map(t=>{
              const base=t.chg;
              return [base*0.3+0.4, base*0.5+0.2, base*0.7-0.1, base*0.9, base*1.1, base].map(v=>Math.round(v*100)/100);
            })}
            rowLabels={data.tickers.map(t=>t.symbol.replace('/USDT',''))}
            colLabels={['1m','5m','15m','1h','4h','1d']}
            cellW={70} cellH={36}
          />
        </div>
      </Card>

      <style>{`
        @media(max-width:1100px){.kq-ticker-grid{grid-template-columns:repeat(2,1fr) !important}.kq-mkt-grid{grid-template-columns:1fr !important}.kq-mkt-meta{grid-template-columns:1fr !important}}
        @media(max-width:560px){.kq-ticker-grid{grid-template-columns:1fr !important}}
      `}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.MarketPage=MarketPage;
})();
