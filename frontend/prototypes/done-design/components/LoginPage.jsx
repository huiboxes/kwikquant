(function(){
  const {useState}=React;
  const useApp=window.__SHAPE__.context.useApp;
  const {fmt}=window.__SHAPE__.ui;

  const PINS=[
    {h:200,kind:'code',title:'BTC Trend Rider',sub:'v1.3.2 · 运行中',code:'if(fast>slow){buy()}'},
    {h:170,kind:'chart',title:'回测权益曲线',sub:'+58.4% · 夏普 2.31',curve:[0,2,5,3,8,6,10,12,9,15]},
    {h:220,kind:'pos',title:'BTC/USDT LONG',sub:'+184.20 USDT · PAPER',pnl:'+184.20'},
    {h:160,kind:'quote',title:'AI native',sub:'低门槛 · 自由开发'},
    {h:200,kind:'code',title:'MCP Agent',sub:'代你下单 · 二次确认',code:'agent.placeOrder({...})'},
    {h:150,kind:'metric',title:'夏普比率',sub:'12 个月',val:'2.31'},
    {h:190,kind:'chart',title:'资金曲线',sub:'+38.1% · BTC Trend',curve:[5,7,6,9,11,10,13,15,14,18]},
    {h:170,kind:'quote',title:'紧急停止',sub:'AI agent 高风险 · 强确认'},
    {h:180,kind:'ticker',title:'ETH/USDT',sub:'实时行情',price:'3142.18',chg:'+2.34%'},
  ];

  function Pin({p,i}){
    const side=(i%3)*8;
    const opacity=0.55+((i%5)/10);
    return <div className="kq-pin" style={{marginBottom:14,opacity}}>
      {p.kind==='code'&&<div style={{height:p.h,background:'var(--surface-2)',padding:'12px 14px',display:'flex',flexDirection:'column',justifyContent:'space-between',fontFamily:'ui-monospace,monospace',fontSize:11}}>
        <div style={{color:'var(--ink-3)',fontSize:10,letterSpacing:'.06em',textTransform:'uppercase'}}>{p.title}</div>
        <div style={{color:'var(--brand)',fontSize:13}}>{p.code}</div>
        <div style={{color:'var(--ink-3)',fontSize:10}}>{p.sub}</div>
      </div>}
      {p.kind==='chart'&&<div style={{height:p.h,background:'var(--surface-2)',padding:'12px 14px',display:'flex',flexDirection:'column'}}>
        <div style={{display:'flex',justifyContent:'space-between',fontSize:11,color:'var(--ink-3)',letterSpacing:'.04em',textTransform:'uppercase'}}><span>{p.title}</span><span>{p.sub}</span></div>
        <svg viewBox="0 0 100 60" preserveAspectRatio="none" style={{flex:1,marginTop:8}}>
          <polyline points={p.curve.map((v,i)=>`${(i/(p.curve.length-1))*100},${60-(v/Math.max(...p.curve))*55-3}`).join(' ')} fill="none" stroke="var(--brand)" strokeWidth="1.5"/>
          <polygon points={`0,60 ${p.curve.map((v,i)=>`${(i/(p.curve.length-1))*100},${60-(v/Math.max(...p.curve))*55-3}`).join(' ')} 100,60`} fill="var(--brand)" opacity="0.15"/>
        </svg>
      </div>}
      {p.kind==='pos'&&<div style={{height:p.h,background:p.pnl.startsWith('+')?'rgba(43,162,152,.1)':'rgba(246,57,105,.1)',padding:'14px',display:'flex',flexDirection:'column',justifyContent:'space-between'}}>
        <div style={{fontSize:12,fontWeight:700,color:'var(--ink)'}}>{p.title}</div>
        <div className="kq-mono-row" style={{fontSize:24,fontWeight:700,color:p.pnl.startsWith('+')?'var(--up)':'var(--down)',letterSpacing:'-.02em'}}>{p.pnl}</div>
        <div style={{fontSize:11,color:'var(--ink-3)'}}>{p.sub}</div>
      </div>}
      {p.kind==='quote'&&<div style={{height:p.h,background:'var(--surface-2)',padding:'14px',display:'flex',flexDirection:'column',justifyContent:'center'}}>
        <div className="font-display" style={{fontSize:22,fontWeight:600,color:'var(--brand)',lineHeight:1.1,letterSpacing:'-.01em'}}>{p.title}</div>
        <div style={{fontSize:11,color:'var(--ink-2)',marginTop:6}}>{p.sub}</div>
      </div>}
      {p.kind==='ticker'&&<div style={{height:p.h,background:'var(--surface-2)',padding:'14px',display:'flex',flexDirection:'column',justifyContent:'space-between'}}>
        <div style={{fontSize:11,color:'var(--ink-3)'}}>{p.sub}</div>
        <div>
          <div style={{fontSize:12,fontWeight:600}}>{p.title}</div>
          <div className="kq-mono-row" style={{fontSize:22,fontWeight:700,color:p.chg.startsWith('+')?'var(--up)':'var(--down)'}}>{p.price}</div>
        </div>
        <div style={{fontSize:12,color:p.chg.startsWith('+')?'var(--up)':'var(--down)',fontWeight:600}}>{p.chg}</div>
      </div>}
      {p.kind==='metric'&&<div style={{height:p.h,background:'var(--brand)',color:'#fff',padding:'14px',display:'flex',flexDirection:'column',justifyContent:'space-between'}}>
        <div style={{fontSize:11,opacity:.85,letterSpacing:'.06em',textTransform:'uppercase'}}>{p.title}</div>
        <div className="font-display" style={{fontSize:38,fontWeight:600,lineHeight:1,letterSpacing:'-.02em'}}>{p.val}</div>
        <div style={{fontSize:11,opacity:.75}}>{p.sub}</div>
      </div>}
      <div className="kq-pin-body" style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
        <div style={{display:'flex',gap:6,alignItems:'center'}}>
          <div style={{width:18,height:18,borderRadius:'50%',background:'var(--brand)'}}/>
          <span style={{fontSize:11,color:'var(--ink-2)'}}>KwikQuant</span>
        </div>
        <div style={{width:22,height:22,borderRadius:'50%',background:'var(--surface-2)',display:'flex',alignItems:'center',justifyContent:'center',color:'var(--ink-3)',fontSize:11}}>↗</div>
      </div>
    </div>;
  }

  function LoginPage(){
    const {setAuthed,pushToast}=useApp();
    const [email,setEmail]=useState('demo@kwikquant.io');
    const [pwd,setPwd]=useState('••••••••');
    const [loading,setLoading]=useState(false);
    const [mode,setMode]=useState('signin'); // signin | signup | invite

    const login=()=>{
      setLoading(true);
      setTimeout(()=>{
        setLoading(false);
        setAuthed(true);
        pushToast({title:'欢迎回来',body:'已进入 KwikQuant 工作台',tone:'up'});
      },600);
    };

    return <div style={{minHeight:'100vh',display:'flex',background:'var(--canvas)'}}>
      {/* LEFT: brand + pin waterflow */}
      <div style={{flex:'1.1',minWidth:560,position:'relative',overflow:'hidden',background:'var(--canvas)',borderRight:'1px solid var(--hair)'}}>
        <div style={{position:'absolute',inset:0,background:'radial-gradient(circle at 30% 20%, var(--brand-soft) 0%, transparent 55%), radial-gradient(circle at 70% 80%, rgba(43,162,152,.08) 0%, transparent 50%)'}}/>
        <div style={{position:'relative',padding:'40px 48px',display:'flex',flexDirection:'column',height:'100vh'}}>
          <div style={{display:'flex',alignItems:'center',gap:10}}>
            <div style={{width:32,height:32,borderRadius:8,background:'var(--brand)',display:'flex',alignItems:'center',justifyContent:'center',color:'#fff',fontWeight:800,fontSize:16,fontFamily:'ui-monospace,monospace'}}>KQ</div>
            <div>
              <div style={{fontWeight:700,fontSize:15,letterSpacing:'-.01em'}}>KwikQuant</div>
              <div style={{fontSize:10,color:'var(--ink-3)',letterSpacing:'.08em',textTransform:'uppercase'}}>AI Native Quant</div>
            </div>
          </div>

          <div style={{flex:1,display:'flex',flexDirection:'column',justifyContent:'center',padding:'24px 0'}}>
            <div className="font-display" style={{fontSize:60,fontWeight:500,lineHeight:1.02,letterSpacing:'-.025em',maxWidth:520}}>
              写代码，<br/>让市场<em style={{color:'var(--brand)',fontStyle:'italic'}}>自己</em>跑。
            </div>
            <div style={{fontSize:15,color:'var(--ink-2)',marginTop:18,maxWidth:480,lineHeight:1.55}}>
              加密货币量化，从编辑器到实盘一个工作台搞定。没有积木、没有黑盒。
            </div>
            <div style={{display:'flex',gap:8,marginTop:24,flexWrap:'wrap'}}>
              <span className="kq-chip" style={{borderColor:'var(--brand)',color:'var(--brand)',background:'var(--brand-soft)'}}><span style={{width:6,height:6,background:'var(--brand)',borderRadius:'50%'}}/>AI 辅助编码</span>
              <span className="kq-chip"><span style={{width:6,height:6,background:'var(--up)',borderRadius:'50%'}}/>PAPER 模拟撮合</span>
              <span className="kq-chip"><span style={{width:6,height:6,background:'var(--warn)',borderRadius:'50%'}}/>ai agent 友好</span>
              <span className="kq-chip"><span style={{width:6,height:6,background:'var(--info)',borderRadius:'50%'}}/>不靠积木</span>
              <span className="kq-chip"><span style={{width:6,height:6,background:'var(--ink-2)',borderRadius:'50%'}}/>零信任 · 安全</span>
            </div>
          </div>

          <div style={{fontSize:11,color:'var(--ink-3)',letterSpacing:'.04em'}}>
            © 2026 KwikQuant · 加密货币量化交易存在风险，请谨慎评估。
          </div>
        </div>

        {/* Pin masonry overlay on right edge */}
        <div style={{position:'absolute',top:0,right:0,bottom:0,width:'46%',minWidth:300,maxWidth:420,overflow:'hidden',opacity:.85,maskImage:'linear-gradient(to left, #000 60%, transparent)'}}>
          <div style={{columnCount:2,columnGap:14,padding:'20px 16px 20px 0'}}>
            {PINS.map((p,i)=><Pin key={i} p={p} i={i}/>)}
          </div>
        </div>
      </div>

      {/* RIGHT: form */}
      <div style={{flex:'.9',minWidth:380,display:'flex',alignItems:'center',justifyContent:'center',padding:32,background:'var(--surface)'}}>
        <div style={{width:'100%',maxWidth:380}}>
          <div style={{display:'flex',gap:0,marginBottom:28,background:'var(--surface-2)',borderRadius:10,padding:3}}>
            <button onClick={()=>setMode('signin')} className="kq-press" style={{flex:1,padding:'8px 0',borderRadius:8,background:mode==='signin'?'var(--surface)':'transparent',border:'none',cursor:'pointer',fontWeight:600,color:mode==='signin'?'var(--ink)':'var(--ink-3)',boxShadow:mode==='signin'?'var(--shadow-card)':'none',transition:'all .15s'}}>登录</button>
            <button onClick={()=>setMode('signup')} className="kq-press" style={{flex:1,padding:'8px 0',borderRadius:8,background:mode==='signup'?'var(--surface)':'transparent',border:'none',cursor:'pointer',fontWeight:600,color:mode==='signup'?'var(--ink)':'var(--ink-3)',boxShadow:mode==='signup'?'var(--shadow-card)':'none',transition:'all .15s'}}>注册</button>
          </div>

          <h1 className="font-display" style={{fontSize:30,fontWeight:500,letterSpacing:'-.02em',marginBottom:6}}>
            {mode==='signin'?'继续你的策略旅程':'创建账户'}
          </h1>
          <p style={{fontSize:13,color:'var(--ink-3)',marginBottom:24,lineHeight:1.5}}>
            {mode==='signin'?'登录后从你上次停下的策略继续 — 编码、回测、模拟或实盘。':'KwikQuant 暂为邀请制，请输入邀请码完成注册。'}
          </p>

          {mode==='signup'&&<div style={{marginBottom:14}}>
            <label className="kq-label">邀请码</label>
            <input className="kq-input" placeholder="KQ-INV-XXXX-XXXX" defaultValue="KQ-INV-7KQ2-9XR8"/>
          </div>}

          <div style={{marginBottom:14}}>
            <label className="kq-label">邮箱</label>
            <input className="kq-input" type="email" value={email} onChange={e=>setEmail(e.target.value)}/>
          </div>
          <div style={{marginBottom:18}}>
            <div style={{display:'flex',justifyContent:'space-between',marginBottom:6}}>
              <label className="kq-label" style={{margin:0}}>密码</label>
              <a style={{fontSize:11,color:'var(--brand)',cursor:'pointer',textDecoration:'none'}}>忘记密码？</a>
            </div>
            <input className="kq-input" type="password" value={pwd} onChange={e=>setPwd(e.target.value)} onKeyDown={e=>{if(e.key==='Enter')login();}}/>
          </div>

          <button onClick={login} className="kq-btn-primary kq-press" disabled={loading} style={{width:'100%',padding:'12px',fontSize:15,display:'flex',alignItems:'center',justifyContent:'center',gap:8,opacity:loading?.7:1}}>
            {loading?<>
              <span className="kq-pulse">·</span><span className="kq-pulse" style={{animationDelay:'.2s'}}>·</span><span className="kq-pulse" style={{animationDelay:'.4s'}}>·</span> 进入中
            </>:mode==='signin'?'进入工作台 →':'创建账户 →'}
          </button>

          <div style={{display:'flex',alignItems:'center',gap:10,margin:'22px 0'}}>
            <div style={{flex:1,height:1,background:'var(--hair)'}}/>
            <span style={{fontSize:11,color:'var(--ink-3)'}}>或继续使用</span>
            <div style={{flex:1,height:1,background:'var(--hair)'}}/>
          </div>

          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',gap:8}}>
            {['Google','GitHub','Solana'].map(p=>(
              <button key={p} className="kq-btn-ghost kq-press" style={{padding:'9px 0',fontSize:13}}>{p}</button>
            ))}
          </div>

          <div style={{marginTop:24,padding:14,borderRadius:10,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:12,color:'var(--ink-2)',lineHeight:1.55}}>
            <strong style={{color:'var(--ink)'}}>演示提示</strong> · 任意邮箱密码可直接登录，进入后即可浏览所有界面（数据为模拟）。
          </div>
        </div>
      </div>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.LoginPage=LoginPage;
})();
