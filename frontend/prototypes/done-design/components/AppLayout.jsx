(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Chip}=ui;

  const NAV=[
    {group:'主线旅程',items:[
      {id:'dashboard',label:'主页',short:'主页',icon:'⌂',sub:'继续旅程'},
      {id:'strategy',label:'策略工作台',short:'策略',icon:'❮❯',sub:'编码 + AI'},
      {id:'backtest',label:'回测',short:'回测',icon:'∿',sub:'验证策略'},
      {id:'trade',label:'交易',short:'交易',icon:'⚡',sub:'PAPER / LIVE'},
    ]},
    {group:'监控与管理',items:[
      {id:'portfolio',label:'组合总览',short:'组合',icon:'◇',sub:'多账户聚合'},
      {id:'market',label:'行情',short:'行情',icon:'♺',sub:'实时 K 线'},
      {id:'risk',label:'风控',short:'风控',icon:'⛨',sub:'下单闸门'},
      {id:'history',label:'交易历史',short:'历史',icon:'≡',sub:'复盘'},
      {id:'settings',label:'设置',short:'设置',icon:'⚙',sub:'AI · MCP · 通知'},
    ]},
  ];

  const PAGE_MAP={
    dashboard:'主页',strategy:'策略工作台',backtest:'回测',
    trade:'交易',portfolio:'组合总览',market:'行情',
    risk:'风控',history:'交易历史',settings:'设置'
  };

  // Lucide-style line icons (24x24 viewBox, stroke=currentColor, no fill)
  const ICON_PATHS={
    dashboard:<g><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/></g>,
    strategy:<g><path d="m16 18 6-6-6-6"/><path d="m8 6-6 6 6 6"/></g>,
    backtest:<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>,
    trade:<g><path d="M3 3v18h18"/><path d="M7 8v8"/><path d="M7 16h2"/><path d="M7 8h2"/><path d="M12 5v11"/><path d="M12 16h2"/><path d="M12 5h2"/><path d="M17 11v6"/><path d="M17 17h2"/><path d="M17 11h2"/></g>,
    portfolio:<g><rect width="6" height="5" x="9" y="2" rx="1"/><path d="M2 7h20v13a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7z"/><path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></g>,
    market:<g><path d="m22 7-8.5 8.5-5-5L2 17"/><path d="M16 7h6v6"/></g>,
    risk:<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>,
    history:<g><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></g>,
    settings:<g><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></g>,
  };
  function Icon({id,size=18}){
    const paths=ICON_PATHS[id];
    if(!paths)return <span style={{width:size,height:size,display:'inline-flex'}}/>;
    return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" style={{display:'block',opacity:.9}}>{paths}</svg>;
  }

  function Sidebar({onNavigate}){
    const {page,setPage,data,sidebarCollapsed,toggleSidebar,tradeMode}=useApp();
    const go=(id)=>{setPage(id);onNavigate&&onNavigate();};
    const collapsed=sidebarCollapsed;
    const BrandMark=({size=28})=><div style={{width:size,height:size,borderRadius:9,color:'var(--brand)',display:'flex',alignItems:'center',justifyContent:'center',fontWeight:700,fontFamily:'ui-monospace,monospace',fontSize:12,background:'var(--brand-soft)',letterSpacing:'-.02em'}}>KQ</div>;
    return <nav style={{padding:collapsed?'18px 10px 14px':'22px 14px 18px',display:'flex',flexDirection:'column',height:'100%',position:'relative'}}>
      {/* Floating collapse toggle — vertically centered on the sidebar right edge */}
      <button
        onClick={toggleSidebar}
        title={collapsed?'展开侧栏':'收起侧栏'}
        className="kq-press"
        style={{
          position:'absolute',
          right:-11,
          top:'50%',
          transform:'translateY(-50%)',
          width:22,height:22,
          borderRadius:'50%',
          background:'var(--surface)',
          color:'var(--ink-2)',
          cursor:'pointer',
          fontSize:13,
          display:'flex',
          alignItems:'center',
          justifyContent:'center',
          boxShadow:'var(--shadow-card)',
          zIndex:5,
          lineHeight:1,
          border:'none',
          transition:'background .15s,color .15s,box-shadow .15s'
        }}
        onMouseEnter={e=>{e.currentTarget.style.background='var(--brand-soft)';e.currentTarget.style.color='var(--brand)';}}
        onMouseLeave={e=>{e.currentTarget.style.background='var(--surface)';e.currentTarget.style.color='var(--ink-2)';}}
      >{collapsed?'»':'«'}</button>

      {/* Brand at top */}
      <div style={{display:'flex',alignItems:'center',gap:10,padding:collapsed?'0 0 4px':'0 4px 0 6px',marginBottom:collapsed?16:20,justifyContent:collapsed?'center':'flex-start'}}>
        <BrandMark size={collapsed?30:28}/>
        {!collapsed&&<div style={{lineHeight:1.15}}>
          <div style={{fontWeight:700,fontSize:14,letterSpacing:'-.01em',color:'var(--ink)'}}>KwikQuant</div>
          <div style={{fontSize:9,color:'var(--ink-3)',letterSpacing:'.1em',textTransform:'uppercase',marginTop:2}}>AI Native Quant</div>
        </div>}
      </div>

      {/* Scrollable nav region */}
      <div className="kq-sidebar-scroll" style={{flex:1,minHeight:0,overflowY:'auto',overflowX:'hidden',paddingRight:collapsed?0:4,marginRight:collapsed?0:-4}}>
        {NAV.map((group,gidx)=>(
          <div key={group.group} style={{marginTop:gidx===0?0:14}}>
            {!collapsed&&<div style={{fontSize:10,color:'var(--ink-3)',letterSpacing:'.08em',textTransform:'uppercase',fontWeight:700,padding:'0 8px 8px'}}>{group.group}</div>}
            <div style={{display:'flex',flexDirection:'column',gap:collapsed?6:3}}>
              {group.items.map(item=>{
                const active=page===item.id;
                const iconOnly=collapsed;
                return (
                <div key={item.id} className={`kq-nav-item ${active?'active':''}`} onClick={()=>go(item.id)} title={collapsed?item.label+': '+item.sub:undefined}
                  style={iconOnly?{flexDirection:'column',padding:'8px 4px',gap:4,position:'relative',alignItems:'center',justifyContent:'center'}:undefined}>
                  <Icon id={item.id} size={iconOnly?18:18}/>
                  {!iconOnly&&<div style={{flex:1,display:'flex',flexDirection:'column',lineHeight:1.2,minWidth:0}}>
                    <span>{item.label}</span>
                    <span style={{fontSize:10,color:active?'var(--brand)':'var(--ink-3)',opacity:.85}}>{item.id==='trade'?(tradeMode==='LIVE'?'LIVE 实盘':'PAPER 模拟'):item.sub}</span>
                  </div>}
                  {iconOnly&&<span style={{fontSize:10,fontWeight:500,color:active?'var(--brand)':'var(--ink-2)',lineHeight:1,marginTop:1}}>{item.short}</span>}
                  {!iconOnly&&item.id==='trade'&&(tradeMode==='LIVE'?<span className="kq-live-badge">LIVE</span>:<Chip tone="up" style={{padding:'1px 6px',fontSize:9}}>PAPER</Chip>)}
                  {iconOnly&&item.id==='trade'&&<span style={{position:'absolute',top:4,right:2,width:6,height:6,borderRadius:'50%',background:tradeMode==='LIVE'?'var(--brand)':'var(--up)',boxShadow:tradeMode==='LIVE'?'0 0 6px var(--brand)':undefined}}/>}
                  {iconOnly&&active&&<span style={{position:'absolute',left:0,top:6,bottom:6,width:3,borderRadius:2,background:'var(--brand)'}}/>}
                </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      {!collapsed&&<div style={{padding:'12px 14px',borderRadius:12,background:'var(--surface-2)',marginTop:14}}>
        <div style={{display:'flex',alignItems:'center',justifyContent:'space-between'}}>
          <span style={{fontSize:11,color:'var(--ink-3)'}}>运行中策略</span>
          <span style={{fontSize:12,fontWeight:700,color:'var(--up)'}}>{data.strategies.filter(s=>s.status==='running').length}</span>
        </div>
        <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginTop:6}}>
          <span style={{fontSize:11,color:'var(--ink-3)'}}>总资产</span>
          <span className="kq-mono-row" style={{fontSize:12,fontWeight:700}}>$ {ui.fmt(data.accounts.reduce((a,b)=>a+b.equity,0),2)}</span>
        </div>
      </div>}
      {collapsed&&<div style={{display:'flex',flexDirection:'column',alignItems:'center',gap:10,padding:'8px 0'}}>
        <div title="运行中策略" style={{display:'flex',flexDirection:'column',alignItems:'center',gap:0}}>
          <span style={{fontSize:9,color:'var(--ink-3)',letterSpacing:'.06em',textTransform:'uppercase'}}>RUN</span>
          <span style={{fontSize:12,fontWeight:700,color:'var(--up)'}}>{data.strategies.filter(s=>s.status==='running').length}</span>
        </div>
        <div title="总资产" style={{display:'flex',flexDirection:'column',alignItems:'center',gap:0}}>
          <span style={{fontSize:9,color:'var(--ink-3)',letterSpacing:'.06em',textTransform:'uppercase'}}>EQ</span>
          <span className="kq-mono-row" style={{fontSize:11,fontWeight:700}}>{(data.accounts.reduce((a,b)=>a+b.equity,0)/1000).toFixed(1)}k</span>
        </div>
      </div>}
    </nav>;
  }

  function Topbar(){
    const {theme,toggleTheme,setNotifOpen,notifOpen,data,setPage,mobileNavOpen,setMobileNavOpen,setCmdOpen}=useApp();
    const unread=data.notifications.filter(n=>n.unread).length;
    const {useEffect}=React;
    useEffect(()=>{
      const onKey=(e)=>{
        if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='k'){
          e.preventDefault();
          setCmdOpen(true);
        }
      };
      window.addEventListener('keydown',onKey);
      return()=>window.removeEventListener('keydown',onKey);
    },[setCmdOpen]);
    const openCmd=()=>setCmdOpen(true);
    return <header style={{height:60,display:'flex',alignItems:'center',justifyContent:'space-between',padding:'0 24px',background:'var(--canvas)',position:'sticky',top:0,zIndex:20,backdropFilter:'blur(8px)'}}>
      <div style={{display:'flex',alignItems:'center',gap:14}}>
        <button onClick={()=>setMobileNavOpen(!mobileNavOpen)} className="kq-press" style={{display:'none',background:'none',border:'none',color:'var(--ink)',cursor:'pointer',fontSize:20}} id="kq-hamburger">≡</button>
        <div style={{fontSize:13,color:'var(--ink-3)'}}>KwikQuant</div>
        <div style={{color:'var(--ink-3)'}}>/</div>
        <div style={{fontSize:14,fontWeight:600}}>{PAGE_MAP[useApp().page]}</div>
      </div>

      <div style={{display:'flex',alignItems:'center',gap:8}}>
        <button onClick={openCmd} className="kq-press" title="打开命令面板 ⌘K" style={{display:'flex',alignItems:'center',gap:8,width:280,height:36,padding:'0 12px',background:'var(--surface-2)',border:'1px solid var(--hair)',borderRadius:10,cursor:'pointer',color:'var(--ink-3)',fontSize:13,textAlign:'left'}}>
          <span style={{fontSize:13}}>⌕</span>
          <span style={{flex:1,color:'var(--ink-3)'}}>搜索策略 / 跳转页面 / 命令…</span>
          <kbd style={{fontFamily:'ui-monospace,monospace',fontSize:10,color:'var(--ink-3)',background:'var(--surface)',border:'1px solid var(--hair)',borderRadius:5,padding:'1px 5px',fontWeight:600}}>⌘K</kbd>
        </button>

        <button onClick={toggleTheme} className="kq-icon-btn kq-press" title="切换深/浅主题" style={{padding:'8px 10px',display:'flex',alignItems:'center',gap:6,fontSize:13}}>
          {theme==='dark'?'☀':'☾'}
          <span style={{fontSize:11,color:'var(--ink-3)'}}>{theme==='dark'?'浅色':'深色'}</span>
        </button>

        <button onClick={()=>setNotifOpen(!notifOpen)} className="kq-icon-btn kq-press" title="通知" style={{position:'relative',padding:'8px 10px',fontSize:14}}>
          ◔
          {unread>0&&<span style={{position:'absolute',top:2,right:2,background:'var(--brand)',color:'#fff',fontSize:9,fontWeight:700,borderRadius:'50%',width:16,height:16,display:'flex',alignItems:'center',justifyContent:'center'}}>{unread}</span>}
        </button>

        <div style={{display:'flex',alignItems:'center',gap:8,padding:'4px 10px 4px 6px',borderRadius:12,cursor:'pointer',background:'var(--surface-2)'}} onClick={()=>setPage('settings')}>
          <div style={{width:24,height:24,borderRadius:'50%',background:'var(--brand)',color:'#fff',display:'flex',alignItems:'center',justifyContent:'center',fontSize:11,fontWeight:700}}>D</div>
          <div style={{lineHeight:1.1}}>
            <div style={{fontSize:12,fontWeight:600}}>demo@kwikquant.io</div>
            <div style={{fontSize:10,color:'var(--ink-3)'}}>Pro · 全部账户</div>
          </div>
        </div>
      </div>
    </header>;
  }

  function NotifDrawer(){
    const {notifOpen,setNotifOpen,data}=useApp();
    const {pushToast}=useApp();
    const items=data.notifications;
    const ICONS={risk:'⛨',fill:'✓',cancel:'×',strat_start:'▶',strat_stop:'■',strat_error:'⚠'};
    const TONES={risk:'down',fill:'up',cancel:'default',strat_start:'up',strat_stop:'warn',strat_error:'down'};
    return <>
      <div className={`kq-drawer ${notifOpen?'open':''}`}>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',padding:'16px 18px',borderBottom:'1px solid var(--hair)'}}>
          <div>
            <div style={{fontSize:15,fontWeight:700}}>通知</div>
            <div style={{fontSize:11,color:'var(--ink-3)'}}>实时推送 · {items.filter(n=>n.unread).length} 条未读</div>
          </div>
          <button onClick={()=>setNotifOpen(false)} className="kq-icon-btn kq-press" title="关闭" style={{fontSize:18,padding:'4px 8px',lineHeight:1}}>×</button>
        </div>
        <div style={{padding:'8px 12px',overflow:'auto'}}>
          <div style={{display:'flex',gap:6,marginBottom:10}}>
            <button className="kq-tab active">全部</button>
            <button className="kq-tab">未读</button>
            <button className="kq-tab">风控</button>
            <button className="kq-tab">策略</button>
          </div>
          {items.map(n=>(
            <div key={n.id} style={{padding:'12px 10px',borderBottom:'1px solid var(--hair)',display:'flex',gap:10,opacity:n.unread?1:.55}}>
              <div style={{width:30,height:30,borderRadius:8,background:'var(--surface-2)',display:'flex',alignItems:'center',justifyContent:'center',color:TONES[n.type]==='down'?'var(--down)':TONES[n.type]==='up'?'var(--up)':'var(--warn)',fontWeight:700,fontSize:14,flexShrink:0}}>{ICONS[n.type]}</div>
              <div style={{flex:1,minWidth:0}}>
                <div style={{display:'flex',justifyContent:'space-between',alignItems:'baseline'}}>
                  <div style={{fontSize:13,fontWeight:600}}>{n.title}</div>
                  <div style={{fontSize:10,color:'var(--ink-3)'}}>{n.ts}</div>
                </div>
                <div style={{fontSize:12,color:'var(--ink-2)',marginTop:2,lineHeight:1.4}}>{n.body}</div>
              </div>
              {n.unread&&<div style={{width:6,height:6,borderRadius:'50%',background:'var(--brand)',marginTop:6,flexShrink:0}}/>}
            </div>
          ))}
        </div>
        <div style={{padding:'12px 18px',borderTop:'1px solid var(--hair)',display:'flex',gap:8,position:'absolute',bottom:0,left:0,right:0,background:'var(--surface)'}}>
          <button className="kq-btn-ghost kq-press" style={{flex:1,padding:'8px'}} onClick={()=>pushToast({title:'已全部标记为已读',tone:'up'})}>全部已读</button>
          <button className="kq-btn-ghost kq-press" style={{flex:1,padding:'8px'}} onClick={()=>{setNotifOpen(false);useApp().setPage('settings');}}>偏好</button>
        </div>
      </div>
      {notifOpen&&<div onClick={()=>setNotifOpen(false)} style={{position:'fixed',inset:0,background:'rgba(0,0,0,.3)',zIndex:55}}/>}
    </>;
  }

  function AppLayout({children}){
    const {mobileNavOpen,setMobileNavOpen,sidebarCollapsed,cmdOpen,setCmdOpen,setPage,toggleTheme,setNotifOpen,pushToast}=useApp();
    const ui=window.__SHAPE__.ui;
    const navItems=NAV.flatMap(g=>g.items);
    const commands=[
      ...navItems.map(n=>({id:'go-'+n.id,label:'跳转：'+n.label,icon:n.icon,group:'导航',action:()=>setPage(n.id)})),
      {id:'act-theme',label:'切换深 / 浅主题',icon:'☾',group:'操作',hint:'T',action:()=>toggleTheme()},
      {id:'act-notif',label:'打开通知抽屉',icon:'◔',group:'操作',action:()=>setNotifOpen(true)},
      {id:'act-newstrat',label:'新建策略（跳转工作台）',icon:'+',group:'操作',action:()=>{setPage('strategy');pushToast({title:'新建策略',body:'从草稿开始 · AI 流式辅助',tone:'brand'});}},
      {id:'act-backtest',label:'提交新回测',icon:'∿',group:'操作',action:()=>{setPage('backtest');pushToast({title:'回测',body:'选择策略与周期后提交',tone:'brand'});}},
      {id:'act-stop',label:'紧急停止 · 高风险',icon:'⛨',group:'操作',action:()=>{setPage('risk');pushToast({title:'紧急停止',body:'将拦截所有 LIVE 下单',tone:'down'});}},
    ];
    return <div style={{display:'flex',minHeight:'100vh',background:'var(--canvas)'}}>
      <aside style={{width:sidebarCollapsed?64:248,flexShrink:0,background:'var(--surface)',position:'sticky',top:0,height:'100vh',overflow:'visible',transition:'width .2s ease',boxShadow:'inset -1px 0 0 var(--canvas)'}} className="kq-desktop-nav">
        <Sidebar/>
      </aside>
      {/* mobile drawer */}
      {mobileNavOpen&&<div style={{position:'fixed',inset:0,background:'rgba(0,0,0,.4)',zIndex:40,display:'block'}} className="kq-mobile-overlay" onClick={()=>setMobileNavOpen(false)}>
        <div onClick={e=>e.stopPropagation()} style={{position:'absolute',left:0,top:0,bottom:0,width:280,background:'var(--surface)'}}>
          <Sidebar onNavigate={()=>setMobileNavOpen(false)}/>
        </div>
      </div>}
      <div style={{flex:1,minWidth:0,display:'flex',flexDirection:'column'}}>
        <Topbar/>
        <main style={{flex:1,padding:'28px 32px',maxWidth:'100%'}}>
          <div style={{maxWidth:1400,margin:'0 auto'}}>
            {children}
          </div>
        </main>
      </div>
      <NotifDrawer/>
      <ui.CommandPalette open={cmdOpen} onClose={()=>setCmdOpen(false)} commands={commands}/>
    </div>;
  }

  window.__SHAPE__.layout={AppLayout};
})();
