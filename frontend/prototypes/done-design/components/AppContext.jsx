(function(){
  const {useState,useEffect,useCallback,useRef,createContext,useContext,memo}=React;
  const Ctx=createContext(null);

  // ---- Mock data ----
  const strategies=[
    {id:'stg-01',name:'BTC Trend Rider',symbol:'BTC/USDT',exchange:'BINANCE',timeframe:'15m',status:'running',version:'v1.3.2',desc:'双均线突破 + ATR 止损',pnl:+12.43,author:'you',updated:'2分钟前',lines:142},
    {id:'stg-02',name:'ETH Mean Reversion',symbol:'ETH/USDT',exchange:'BINANCE',timeframe:'5m',status:'paused',version:'v0.4.1',desc:'Z-score 反转，布林带过滤',pnl:-2.18,author:'you',updated:'1小时前',lines:98},
    {id:'stg-03',name:'Grid Scalper',symbol:'SOL/USDT',exchange:'OKX',timeframe:'1m',status:'stopped',version:'v2.0.0',desc:'网格挂单 + 资金费率套利',pnl:+4.91,author:'you',updated:'昨天',lines:210},
    {id:'stg-04',name:'Funding Arb',symbol:'BTC/USDT-PERP',exchange:'BITGET',timeframe:'1h',status:'draft',version:'草稿',desc:'资金费率套利，未发布',pnl:0,author:'you',updated:'3天前',lines:67},
  ];

  const accounts=[
    {id:'acc-paper',label:'PAPER · 主模拟盘',exchange:'BINANCE',market:'PERP',isPaper:true,balance:100000.00,frozen:18420.50,refExchange:'BINANCE',equity:118420.50},
    {id:'acc-live-1',label:'实盘 · 主账户',exchange:'BINANCE',market:'PERP',isPaper:false,balance:4283.91,frozen:612.40,refExchange:null,equity:4896.31},
    {id:'acc-live-2',label:'实盘 · OKX 副账户',exchange:'OKX',market:'SPOT',isPaper:false,balance:1240.18,frozen:0,refExchange:null,equity:1240.18},
  ];

  const positions=[
    {acc:'acc-paper',symbol:'BTC/USDT',side:'LONG',qty:0.42,avg:61200,uPnl:+184.20,rPnl:+620.00},
    {acc:'acc-paper',symbol:'ETH/USDT',side:'SHORT',qty:3.1,avg:3120,uPnl:-42.10,rPnl:+85.00},
    {acc:'acc-live-1',symbol:'SOL/USDT',side:'LONG',qty:20,avg:142.5,uPnl:+24.80,rPnl:0},
  ];

  const orders=[
    {id:'o-9001',acc:'acc-paper',symbol:'BTC/USDT',type:'LIMIT',side:'BUY',price:61200,qty:0.42,status:'FILLED',ts:'14:02:18'},
    {id:'o-9002',acc:'acc-paper',symbol:'ETH/USDT',type:'STOP_LIMIT',side:'SELL',price:3250,qty:3.1,status:'NEW',ts:'14:01:55'},
    {id:'o-9003',acc:'acc-live-1',symbol:'SOL/USDT',type:'MARKET',side:'BUY',price:142.6,qty:20,status:'PARTIALLY_FILLED',filled:8,ts:'13:58:42'},
    {id:'o-9004',acc:'acc-live-1',symbol:'BTC/USDT',type:'LIMIT',side:'SELL',price:65000,qty:0.05,status:'CANCELED',ts:'13:42:10'},
    {id:'o-9005',acc:'acc-paper',symbol:'BTC/USDT',type:'TRAILING_STOP',side:'SELL',qty:0.42,trailPct:1.5,status:'NEW',ts:'13:30:00'},
  ];

  const backtests=[
    {id:'bt-2201',strategy:'BTC Trend Rider',period:'2025-01 → 2026-06',status:'COMPLETED',ret:+58.4,sharpe:2.31,maxDD:-9.8,winRate:62,trades:184,profitFactor:1.84,avgHold:'3h 12m',ts:'今天 10:42'},
    {id:'bt-2202',strategy:'ETH Mean Reversion',period:'2025-01 → 2026-06',status:'COMPLETED',ret:+22.1,sharpe:1.42,maxDD:-14.2,winRate:54,trades:312,profitFactor:1.31,avgHold:'45m',ts:'昨天 18:20'},
    {id:'bt-2203',strategy:'Grid Scalper',period:'2025-03 → 2026-06',status:'COMPLETED',ret:+41.2,sharpe:1.88,maxDD:-7.1,winRate:71,trades:1284,profitFactor:2.02,avgHold:'12m',ts:'3天前'},
    {id:'bt-2204',strategy:'BTC Trend Rider',period:'2024-01 → 2024-12',status:'COMPLETED',ret:+38.1,sharpe:1.96,maxDD:-12.4,winRate:58,trades:142,profitFactor:1.62,avgHold:'3h 08m',ts:'1周前'},
    {id:'bt-2205',strategy:'Funding Arb',period:'2025-06 → 2026-06',status:'RUNNING',progress:64,ts:'运行中'},
  ];

  const equityCurve=[
    [0,100000],[30,101200],[60,99500],[90,102800],[120,108400],[150,106200],[180,112800],[210,115000],[240,109800],[270,118000],[300,122400],[330,121000],[360,128400],[390,131200],[420,127800],[450,138200],[480,142000],[510,140200],[540,148200],[570,152400],[600,158420]
  ];

  const candles=(function(){
    const out=[];let p=60000;for(let i=0;i<60;i++){const o=p;const h=o+(Math.random()*800-200);const l=o-Math.random()*700;const c=l+Math.random()*(h-l);p=c;out.push({o,h,l,c,v:Math.random()*100+20});}
    return out;
  })();

  const tickers=[
    {symbol:'BTC/USDT',last:61420.50,chg:+2.34,high:62100,low:59800,vol:'1.2B',stale:false},
    {symbol:'ETH/USDT',last:3142.18,chg:-1.12,high:3198,low:3102,vol:'680M',stale:false},
    {symbol:'SOL/USDT',last:142.60,chg:+5.81,high:148.2,low:134.1,vol:'320M',stale:false},
    {symbol:'BNB/USDT',last:584.20,chg:+0.42,high:592,low:578,vol:'180M',stale:false},
    {symbol:'XRP/USDT',last:0.5241,chg:-2.18,high:0.551,low:0.518,vol:'420M',stale:false},
    {symbol:'DOGE/USDT',last:0.1248,chg:+8.42,high:0.131,low:0.115,vol:'210M',stale:true},
    {symbol:'ADA/USDT',last:0.3821,chg:-0.82,high:0.392,low:0.378,vol:'95M',stale:false},
    {symbol:'AVAX/USDT',last:24.18,chg:+3.12,high:25.1,low:23.6,vol:'140M',stale:false},
  ];

  const notifications=[
    {id:'n1',type:'risk',title:'风控拦截',body:'订单 o-9006 触发单笔限额，已被拒绝',ts:'2分钟前',unread:true},
    {id:'n2',type:'fill',title:'订单成交',body:'BTC/USDT BUY 0.42 @ 61200 已全部成交',ts:'5分钟前',unread:true},
    {id:'n3',type:'cancel',title:'订单撤销',body:'o-9004 已撤销',ts:'22分钟前',unread:false},
    {id:'n4',type:'strat_start',title:'策略启动',body:'BTC Trend Rider v1.3.2 已启动',ts:'1小时前',unread:false},
    {id:'n5',type:'strat_error',title:'策略异常',body:'Grid Scalper 撮合超时，已自动停止',ts:'昨天',unread:false},
  ];

  const riskRules=[
    {id:'MAX_NOTIONAL',label:'单笔限额',enabled:true,value:'5,000 USDT',desc:'单笔下单金额上限'},
    {id:'DAILY_LOSS_LIMIT',label:'日亏限额',enabled:true,value:'-2,500 USDT',desc:'当日已实现亏损上限'},
    {id:'ORDER_FREQUENCY',label:'下单频率',enabled:false,value:'30 / min',desc:'每分钟下单次数上限'},
  ];

  const riskAudit=[
    {ts:'14:02:18',rule:'MAX_NOTIONAL',action:'放行',detail:'BTC/USDT BUY 0.42 @ 61200 = 2568 USDT',acc:'acc-paper'},
    {ts:'14:01:55',rule:'DAILY_LOSS_LIMIT',action:'放行',detail:'ETH/USDT SHORT 3.1',acc:'acc-paper'},
    {ts:'13:58:42',rule:'MAX_NOTIONAL',action:'拒绝',detail:'SOL/USDT BUY 80 @ 142.6 = 11408 USDT',acc:'acc-live-1'},
    {ts:'13:42:10',rule:'MAX_NOTIONAL',action:'放行',detail:'BTC/USDT SELL 0.05',acc:'acc-live-1'},
    {ts:'13:30:00',rule:'ORDER_FREQUENCY',action:'放行',detail:'TRAILING_STOP 挂单',acc:'acc-paper'},
  ];

  const strategies_code=`// BTC Trend Rider v1.3.2
// 双均线突破 + ATR 止损
const { zma, atr } = require('kwikquant/indicators');

async function onBar(bar, ctx) {
  const fast = await zma(ctx.close, 9);
  const slow = await zma(ctx.close, 21);
  const a = await atr(ctx.high, ctx.low, ctx.close, 14);

  if (fast[0] > slow[0] && fast[1] <= slow[1]) {
    await ctx.order({
      side: 'BUY',
      type: 'MARKET',
      qty: ctx.riskQty(0.02, a[0]),
      stop: bar.close - a[0] * 1.5,
      takeProfit: bar.close + a[0] * 3,
    });
  }

  if (fast[0] < slow[0]) {
    await ctx.flatten();
  }
}

module.exports = { onBar, onInit, onTick };`;

  const llmKeys=[
    {id:'k1',provider:'OpenAI',label:'gpt-5 风格策略',masked:'...a1b2',added:'2026-05-12',active:true},
    {id:'k2',provider:'Anthropic',label:'Claude Sonnet 编码',masked:'...7c8d',added:'2026-06-01',active:true},
    {id:'k3',provider:'OpenAI 兼容',label:'DeepSeek V3',masked:'...9e0',added:'2026-06-20',active:false},
  ];

  const mcpTokens=[
    {id:'t1',name:'Cursor Agent',scopes:['read_market','place_order','run_backtest'],created:'2026-06-10',lastUsed:'2小时前',active:true},
    {id:'t2',name:'Claude Code',scopes:['read_market','read_position','read_account'],created:'2026-06-22',lastUsed:'昨天',active:true},
  ];

  const trades=[
    {ts:'2026-07-09 14:02:18',acc:'PAPER',symbol:'BTC/USDT',side:'BUY',qty:0.42,price:61200,fee:6.12,pnl:0},
    {ts:'2026-07-09 13:58:42',acc:'LIVE',symbol:'SOL/USDT',side:'BUY',qty:8,price:142.60,fee:0.34,pnl:0},
    {ts:'2026-07-09 13:30:00',acc:'PAPER',symbol:'ETH/USDT',side:'SELL',qty:2.0,price:3142.18,fee:3.14,pnl:+24.40},
    {ts:'2026-07-08 22:18:09',acc:'PAPER',symbol:'BTC/USDT',side:'SELL',qty:0.1,price:60800,fee:3.04,pnl:+180.00},
    {ts:'2026-07-08 18:42:30',acc:'LIVE',symbol:'BTC/USDT',side:'BUY',qty:0.05,price:59800,fee:1.50,pnl:0},
    {ts:'2026-07-08 10:22:11',acc:'PAPER',symbol:'SOL/USDT',side:'SELL',qty:12,price:138.20,fee:0.66,pnl:-42.10},
  ];

  function Provider({children}){
    const [theme,setThemeState]=useState(()=>{
      try{return localStorage.getItem('kq-theme')||'dark';}catch(e){return 'dark';}
    });
    const [authed,setAuthed]=useState(false);
    const [page,setPage]=useState('dashboard');
    const [mobileNavOpen,setMobileNavOpen]=useState(false);
    const [notifOpen,setNotifOpen]=useState(false);
    const [toasts,setToasts]=useState([]);
    const [tickerTick,setTickerTick]=useState(0);
    const [sidebarCollapsed,setSidebarCollapsed]=useState(()=>{
      try{return localStorage.getItem('kq-sidebar-collapsed')==='1';}catch(e){return false;}
    });
    const [cmdOpen,setCmdOpen]=useState(false);
    const [tradeMode,setTradeMode]=useState('PAPER');
    const [liveConfirmedThisSession,setLiveConfirmedThisSession]=useState(false);

    useEffect(()=>{
      document.documentElement.setAttribute('data-theme',theme);
    },[theme]);

    useEffect(()=>{
      const h=()=>setThemeState(document.documentElement.getAttribute('data-theme'));
      window.addEventListener('kq-theme-change',h);
      return ()=>window.removeEventListener('kq-theme-change',h);
    },[]);

    // ticker heartbeat for price flicker
    useEffect(()=>{
      if(!authed)return;
      const t=setInterval(()=>setTickerTick(v=>v+1),1800);
      return ()=>clearInterval(t);
    },[authed]);

    const toggleTheme=useCallback(()=>{
      setThemeState(t=>{
        const n=t==='dark'?'light':'dark';
        try{localStorage.setItem('kq-theme',n);}catch(e){}
        document.documentElement.setAttribute('data-theme',n);
        return n;
      });
    },[]);

    const pushToast=useCallback((t)=>{
      const id='ts-'+Date.now()+Math.random();
      setToasts(s=>[...s,{...t,id}]);
      setTimeout(()=>setToasts(s=>s.filter(x=>x.id!==id)),3800);
    },[]);

    const toggleSidebar=useCallback(()=>{
      setSidebarCollapsed(c=>{
        const n=!c;
        try{localStorage.setItem('kq-sidebar-collapsed',n?'1':'0');}catch(e){}
        return n;
      });
    },[]);

    const api={
      theme,toggleTheme,
      authed,setAuthed,
      page,setPage,
      mobileNavOpen,setMobileNavOpen,
      notifOpen,setNotifOpen,
      toasts,pushToast,
      tickerTick,
      sidebarCollapsed,toggleSidebar,
      cmdOpen,setCmdOpen,
      tradeMode,setTradeMode,
      liveConfirmedThisSession,setLiveConfirmedThisSession,
      data:{strategies,accounts,positions,orders,backtests,equityCurve,candles,tickers,notifications,riskRules,riskAudit,strategies_code,llmKeys,mcpTokens,trades}
    };
    return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
  }

  function useApp(){const c=useContext(Ctx);if(!c)throw new Error('no ctx');return c;}

  window.__SHAPE__=window.__SHAPE__||{};
  window.__SHAPE__.context={Provider,useApp};
})();
