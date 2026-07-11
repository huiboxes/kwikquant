(function(){
  const {useMemo,useState}=React;
  const useApp=window.__SHAPE__.context.useApp;

  function fmt(n,dp=2){
    if(typeof n!=='number')return '-';
    return n.toLocaleString('en-US',{minimumFractionDigits:dp,maximumFractionDigits:dp});
  }
  function fmtSigned(n,dp=2){
    const s=n>=0?'+':'';
    return s+fmt(n,dp);
  }
  function chgColor(v){return v>=0?'var(--up)':'var(--down)';}
  function chgArrow(v){return v>=0?'▲':'▼';}

  function Chip({children,tone,style}){
    const tones={default:{},brand:{background:'var(--brand-soft)',color:'var(--brand)',borderColor:'var(--brand)'},
      up:{background:'rgba(43,162,152,.12)',color:'var(--up)',borderColor:'var(--up)'},
      down:{background:'rgba(246,57,105,.12)',color:'var(--down)',borderColor:'var(--down)'},
      warn:{background:'rgba(224,160,67,.14)',color:'var(--warn)',borderColor:'var(--warn)'},
      info:{background:'rgba(91,168,232,.14)',color:'var(--info)',borderColor:'var(--info)'}};
    const t=tones[tone]||tones.default;
    return <span className="kq-chip" style={{...t,...style}}>{children}</span>;
  }

  function StatusDot({status}){
    const map={running:'var(--up)',paused:'var(--warn)',stopped:'var(--ink-3)',draft:'var(--info)',COMPLETED:'var(--up)',RUNNING:'var(--brand)',FAILED:'var(--down)',PENDING:'var(--warn)'};
    const color=map[status]||'var(--ink-3)';
    const live=status==='running'||status==='RUNNING';
    return <span className={`kq-status-dot ${live?'kq-status-dot-live':''}`} style={{background:color,boxShadow:`0 0 8px ${color}`,color}}/>;
  }

  function StrategyStatusBadge({status}){
    const map={
      running:{label:'运行中',tone:'up'},
      paused:{label:'已暂停',tone:'warn'},
      stopped:{label:'已停止',tone:'default'},
      draft:{label:'草稿',tone:'info'},
    };
    const m=map[status]||{label:status,tone:'default'};
    return <Chip tone={m.tone}><StatusDot status={status}/>{m.label}</Chip>;
  }

  function BacktestStatusBadge({status}){
    const map={COMPLETED:{label:'已完成',tone:'up'},RUNNING:{label:'运行中',tone:'brand'},FAILED:{label:'失败',tone:'down'},PENDING:{label:'待处理',tone:'warn'}};
    const m=map[status]||{label:status,tone:'default'};
    return <Chip tone={m.tone}><StatusDot status={status}/>{m.label}</Chip>;
  }

  function OrderStatusBadge({status}){
    const map={
      NEW:{label:'新建',tone:'info'},
      PENDING:{label:'待提交',tone:'warn'},
      SUBMITTED:{label:'已提交',tone:'info'},
      PARTIALLY_FILLED:{label:'部分成交',tone:'warn'},
      FILLED:{label:'全部成交',tone:'up'},
      PENDING_CANCEL:{label:'待撤销',tone:'warn'},
      CANCELED:{label:'已撤销',tone:'default'},
      REJECTED:{label:'被拒',tone:'down'},
      EXPIRED:{label:'过期',tone:'default'},
    };
    const m=map[status]||{label:status,tone:'default'};
    return <Chip tone={m.tone}>{m.label}</Chip>;
  }

  function Modal({open,onClose,title,children,footer,width}){
    if(!open)return null;
    return <div className="kq-modal-backdrop" onClick={onClose}>
      <div className="kq-card kq-modal-panel" style={{width:width||480,maxWidth:'94vw',maxHeight:'92vh',overflow:'auto'}} onClick={e=>e.stopPropagation()}>
        <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',padding:'16px 20px',borderBottom:'1px solid var(--hair)'}}>
          <div style={{fontSize:16,fontWeight:600}}>{title}</div>
          <button onClick={onClose} className="kq-icon-btn kq-press" title="关闭" style={{fontSize:20,lineHeight:1,padding:'4px 8px'}}>×</button>
        </div>
        <div style={{padding:20}}>{children}</div>
        {footer&&<div style={{padding:'14px 20px',borderTop:'1px solid var(--hair)',display:'flex',gap:10,justifyContent:'flex-end'}}>{footer}</div>}
      </div>
    </div>;
  }

  function Card({children,className,style,pad}){
    return <div className={`kq-card ${className||''}`} style={{padding:pad===undefined?20:pad,...style}}>{children}</div>;
  }

  function SectionTitle({title,sub,right,icon}){
    return <div style={{display:'flex',alignItems:'flex-end',justifyContent:'space-between',marginBottom:14,flexWrap:'wrap',gap:10}}>
      <div>
        <div style={{display:'flex',alignItems:'center',gap:8}}>
          {icon}
          <div style={{fontSize:17,fontWeight:700,letterSpacing:'-.01em'}}>{title}</div>
        </div>
        {sub&&<div style={{fontSize:12,color:'var(--ink-3)',marginTop:3}}>{sub}</div>}
      </div>
      {right}
    </div>;
  }

  function EquityCurve({data,width,height,color,showArea}){
    const W=width||740,H=height||220;
    const min=Math.min(...data.map(d=>d[1])),max=Math.max(...data.map(d=>d[1]));
    const padL=44,padR=14,padT=14,padB=22;
    const xs=d=>padL+(d[0]/(data.length-1))*(W-padL-padR);
    const ys=v=>padT+(1-(v-min)/(max-min||1))*(H-padT-padB);
    const line=data.map((d,i)=>`${i===0?'M':'L'} ${xs(d).toFixed(1)} ${ys(d[1]).toFixed(1)}`).join(' ');
    const area=`${line} L ${xs(data[data.length-1]).toFixed(1)} ${H-padB} L ${xs(data[0]).toFixed(1)} ${H-padB} Z`;
    const c=color||'var(--up)';
    const gid='eg'+Math.random().toString(36).slice(2,8);
    const glowId=gid+'g';
    const gridYs=[0,0.25,0.5,0.75,1].map(p=>padT+p*(H-padT-padB));
    const fmtShort=n=>Math.abs(n)>=1000?(n/1000).toFixed(1)+'k':n.toFixed(0);
    const lastX=xs(data[data.length-1]),lastY=ys(data[data.length-1][1]);
    return <svg width={W} height={H} style={{display:'block'}}>
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={c} stopOpacity="0.32"/>
          <stop offset="60%" stopColor={c} stopOpacity="0.08"/>
          <stop offset="100%" stopColor={c} stopOpacity="0"/>
        </linearGradient>
        <filter id={glowId} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" result="b"/>
          <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
      </defs>
      {gridYs.map((y,i)=><g key={i}>
        <line x1={padL} x2={W-padR} y1={y} y2={y} stroke="var(--hair)" strokeDasharray="2 4" strokeWidth="1" opacity={i===0||i===gridYs.length-1?1:0.6}/>
        <text x={padL-8} y={y+3} fontSize="9" fill="var(--ink-3)" textAnchor="end" className="kq-mono-row">{fmtShort(i===0?max:i===gridYs.length-1?min:min+(max-min)*(1-i/4))}</text>
      </g>)}
      {showArea!==false&&<path d={area} fill={`url(#${gid})`} stroke="none"/>}
      <path d={line} fill="none" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" filter={`url(#${glowId})`}/>
      <circle cx={lastX} cy={lastY} r="4" fill={c} opacity="0.25">
        <animate attributeName="r" values="4;9;4" dur="2s" repeatCount="indefinite"/>
        <animate attributeName="opacity" values="0.35;0;0.35" dur="2s" repeatCount="indefinite"/>
      </circle>
      <circle cx={lastX} cy={lastY} r="3.5" fill={c} stroke="var(--surface)" strokeWidth="1.5"/>
    </svg>;
  }

  function Candles({data,width,height}){
    const W=width||740,H=height||260;
    const hasVol=data.every(d=>typeof d.v==='number');
    const padT=10,padB=18;
    const mainH=hasVol?Math.round(H*0.74):H-padT-padB;
    const volTop=padT+mainH+Math.round(H*0.06);
    const volH=hasVol?H-volTop-padB:0;
    const allH=Math.max(...data.map(d=>d.h)),allL=Math.min(...data.map(d=>d.l));
    const maxVol=hasVol?Math.max(...data.map(d=>d.v)):0;
    const cw=(W-20)/data.length;
    const ys=v=>padT+(1-(v-allL)/(allH-allL||1))*mainH;
    const yv=v=>volTop+(1-v/(maxVol||1))*volH;
    const gridYs=[0,0.25,0.5,0.75,1].map(p=>padT+p*mainH);
    return <svg width={W} height={H} style={{display:'block'}}>
      {gridYs.map((y,i)=><line key={i} x1="10" x2={W-10} y1={y} y2={y} stroke="var(--hair)" strokeDasharray="2 4" strokeWidth="1" opacity={i===0||i===gridYs.length-1?0.8:0.4}/>)}
      {hasVol&&<line x1="10" x2={W-10} y1={volTop-1} y2={volTop-1} stroke="var(--hair)" strokeWidth="1" opacity="0.6"/>}
      {data.map((d,i)=>{
        const x=10+i*cw+cw/2;
        const up=d.c>=d.o;
        const c=up?'var(--up)':'var(--down)';
        const yO=ys(d.o),yC=ys(d.c),yH=ys(d.h),yL=ys(d.l);
        const top=Math.min(yO,yC),bh=Math.abs(yC-yO)||1;
        return <g key={i}>
          <line x1={x} x2={x} y1={yH} y2={yL} stroke={c} strokeWidth="1"/>
          <rect x={x-Math.max(2,cw/2-1.5)} y={top} width={Math.max(3,cw-3)} height={bh} fill={c} rx="1"/>
          {hasVol&&<rect x={x-Math.max(2,cw/2-1.5)} y={yv(d.v)} width={Math.max(3,cw-3)} height={Math.max(1,volTop+volH-yv(d.v))} fill={c} opacity="0.4" rx="1"/>}
        </g>;
      })}
      {hasVol&&<text x={W-12} y={volTop+10} fontSize="9" fill="var(--ink-3)" textAnchor="end" className="kq-mono-row">VOL</text>}
      <line x1="10" x2={W-10} y1={ys(allL)} y2={ys(allL)} stroke="var(--hair)" strokeDasharray="2 3"/>
    </svg>;
  }

  function Sparkline({data,width,height,color}){
    const W=width||80,H=height||24;
    const min=Math.min(...data),max=Math.max(...data);
    const xs=(i)=>(i/(data.length-1))*W;
    const ys=v=>(1-(v-min)/(max-min||1))*H;
    const line=data.map((v,i)=>`${i===0?'M':'L'} ${xs(i).toFixed(1)} ${ys(v).toFixed(1)}`).join(' ');
    const c=color||(data[data.length-1]>=data[0]?'var(--up)':'var(--down)');
    const gid='sp'+Math.random().toString(36).slice(2,8);
    const area=`${line} L ${W} ${H} L 0 ${H} Z`;
    const upDir=data[data.length-1]>=data[0];
    return <svg width={W} height={H} style={{display:'block',overflow:'visible'}}>
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={c} stopOpacity="0.28"/>
          <stop offset="100%" stopColor={c} stopOpacity="0"/>
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gid})`} stroke="none"/>
      <path d={line} fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
      <circle cx={xs(data.length-1)} cy={ys(data[data.length-1])} r="2" fill={c}>
        <animate attributeName="opacity" values="1;0.4;1" dur="1.8s" repeatCount="indefinite"/>
      </circle>
    </svg>;
  }

  function Ticker({base,chg,dp}){
    // simulate price flicker
    const {tickerTick}=useApp();
    const flick=Math.sin(tickerTick*1.7+base)*0.0008;
    const v=base*(1+flick);
    return <span className="kq-mono-row" style={{color:chg>=0?'var(--up)':'var(--down)'}}>{fmt(v,dp||2)}</span>;
  }

  function LivePrice({symbol,base,chg,dp}){
    const {tickerTick}=useApp();
    const {useEffect,useRef}=React;
    const seed=useMemo(()=>Math.random()*10,[symbol]);
    const flick=Math.sin(tickerTick*1.3+seed)*0.0012;
    const v=base*(1+flick);
    const up=flick>=0;
    const wrapRef=useRef(null);
    const prevUp=useRef(up);
    useEffect(()=>{
      if(prevUp.current!==up&&wrapRef.current){
        const el=wrapRef.current;
        el.classList.remove('kq-flash');
        void el.offsetWidth;
        el.classList.add('kq-flash');
        prevUp.current=up;
      }
    },[up]);
    return <span ref={wrapRef} className="kq-mono-row" style={{color:up?'var(--up)':'var(--down)',transition:'color .2s',borderRadius:6,padding:'1px 4px',display:'inline-block'}}>{fmt(v,dp||2)}</span>;
  }

  function Stat({label,value,sub,tone,mono}){
    const {useEffect,useRef,useState}=React;
    const t={up:{color:'var(--up)'},down:{color:'var(--down)'},brand:{color:'var(--brand)'},default:{}};
    const raw=value==null?'':String(value);
    const m=raw.match(/^([+\-]?)([\d,]*\.?\d+)(.*)$/);
    const canCount=m&&/[0-9]/.test(raw);
    const targetNum=canCount?parseFloat(m[2].replace(/,/g,'')):0;
    const prefix=canCount?m[1]:'';
    const suffix=canCount?m[3]:'';
    const dp=canCount?(m[2].split('.')[1]||'').length:0;
    const [shown,setShown]=useState(canCount?0:raw);
    const rafRef=useRef(null);
    useEffect(()=>{
      if(!canCount)return;
      const dur=600;
      const t0=performance.now();
      const tick=(now)=>{
        const p=Math.min(1,(now-t0)/dur);
        const e=1-Math.pow(1-p,3);
        setShown(targetNum*e);
        if(p<1)rafRef.current=requestAnimationFrame(tick);
      };
      rafRef.current=requestAnimationFrame(tick);
      return()=>{if(rafRef.current)cancelAnimationFrame(rafRef.current);};
    // eslint-disable-next-line
    },[targetNum]);
    const display=canCount?prefix+shown.toLocaleString('en-US',{minimumFractionDigits:dp,maximumFractionDigits:dp})+suffix:raw;
    return <div>
      <div style={{fontSize:11,color:'var(--ink-3)',fontWeight:600,letterSpacing:'.05em',textTransform:'uppercase'}}>{label}</div>
      <div className={mono?'kq-mono-row':''} style={{fontSize:22,fontWeight:700,marginTop:4,letterSpacing:'-.01em',...(t[tone]||t.default)}}>{display}</div>
      {sub&&<div style={{fontSize:11,color:'var(--ink-3)',marginTop:2}}>{sub}</div>}
    </div>;
  }

  function EmptyState({title,sub,action,illustration}){
    const def=<svg width="64" height="56" viewBox="0 0 64 56" fill="none" aria-hidden="true">
      <rect x="14" y="10" width="40" height="30" rx="4" stroke="var(--ink-3)" strokeWidth="1.4" opacity="0.3" fill="var(--surface)"/>
      <rect x="9" y="15" width="40" height="30" rx="4" stroke="var(--ink-3)" strokeWidth="1.4" opacity="0.5" fill="var(--surface-2)"/>
      <rect x="4" y="20" width="40" height="30" rx="4" stroke="var(--ink-3)" strokeWidth="1.6" opacity="0.85" fill="var(--surface)"/>
      <line x1="11" y1="29" x2="28" y2="29" stroke="var(--ink-3)" strokeWidth="1.3" opacity="0.5"/>
      <line x1="11" y1="34" x2="24" y2="34" stroke="var(--ink-3)" strokeWidth="1.3" opacity="0.35"/>
      <circle cx="44" cy="42" r="7" stroke="var(--brand)" strokeWidth="1.8" opacity="0.85" fill="var(--surface)"/>
      <line x1="49.5" y1="47.5" x2="55" y2="53" stroke="var(--brand)" strokeWidth="2.2" strokeLinecap="round"/>
    </svg>;
    return <div style={{textAlign:'center',padding:'48px 20px',color:'var(--ink-3)'}}>
      <div style={{marginBottom:14,display:'flex',justifyContent:'center'}}>{illustration||def}</div>
      <div style={{fontSize:14,fontWeight:600,color:'var(--ink-2)'}}>{title}</div>
      {sub&&<div style={{fontSize:12,marginTop:4}}>{sub}</div>}
      {action&&<div style={{marginTop:14}}>{action}</div>}
    </div>;
  }

  function Toasts(){
    const {toasts}=useApp();
    return <div className="kq-toast">
      {toasts.map(t=>(
        <div key={t.id} className="kq-toast-item" style={{borderLeftColor:t.tone==='up'?'var(--up)':t.tone==='down'?'var(--down)':t.tone==='warn'?'var(--warn)':'var(--brand)'}}>
          <div style={{fontSize:13,fontWeight:600}}>{t.title}</div>
          {t.body&&<div style={{fontSize:12,color:'var(--ink-2)',marginTop:2}}>{t.body}</div>}
        </div>
      ))}
    </div>;
  }

  function Heatmap({data,rowLabels,colLabels,cellW=64,cellH=34,gap=3,fmtVal}){
    const rows=data.length,cols=data[0].length;
    const maxAbs=Math.max(...data.flat().map(v=>Math.abs(v)))||1;
    const rowAvgs=data.map(r=>r.reduce((a,b)=>a+b,0)/cols);
    const colAvgs=data[0].map((_,c)=>data.reduce((a,r)=>a+r[c],0)/rows);
    const xFor=c=>gap+c*(cellW+gap);
    const yFor=r=>gap+r*(cellH+gap);
    const labelW=cellW*0.6;
    const rowLabelX=gap;
    const colLabelY=gap;
    const W=cols*(cellW+gap)+gap+labelW+gap;
    const H=rows*(cellH+gap)+gap+16;
    const fv=fmtVal||(v=>(v>=0?'+':'')+v.toFixed(1)+'%');
    const cellText=(v,i)=>i>=0.55?'#fff':'var(--ink)';
    return <svg width={W} height={H} style={{display:'block',fontFamily:'inherit'}}>
      {colLabels.map((lab,c)=><text key={'ch-'+c} x={xFor(c)+cellW/2+labelW+gap} y={colLabelY+9} textAnchor="middle" fontSize="9.5" fill="var(--ink-3)" fontWeight="600" letterSpacing=".04em">{lab}</text>)}
      {data.map((row,r)=>row.map((v,c)=>{
        const i=Math.min(0.88,Math.abs(v)/maxAbs*0.88);
        const up=v>=0;
        const x=xFor(c)+labelW+gap, y=yFor(r);
        return <g key={r+'-'+c}>
          <rect x={x} y={y} width={cellW} height={cellH} rx="4" fill={up?'var(--up)':'var(--down)'} fillOpacity={0.12+i*0.78} stroke="var(--hair)" strokeWidth="0.5"/>
          <text x={x+cellW/2} y={y+cellH/2+3.5} textAnchor="middle" fontSize="11" fontFamily="ui-monospace,monospace" fill={cellText(v,i)} fontWeight="600">{fv(v)}</text>
        </g>;
      }))}
      {rowLabels.map((lab,r)=><text key={'rl-'+r} x={rowLabelX} y={yFor(r)+cellH/2+3.5} fontSize="11.5" fill="var(--ink-2)" fontWeight="600">{lab}</text>)}
      {rowAvgs.map((v,r)=>{
        const i=Math.min(0.88,Math.abs(v)/maxAbs*0.88);
        const up=v>=0;
        const x=xFor(cols)+labelW+gap, y=yFor(r);
        return <g key={'ra-'+r}>
          <rect x={x} y={y} width={labelW} height={cellH} rx="4" fill={up?'var(--up)':'var(--down)'} fillOpacity={0.12+i*0.78} stroke="var(--hair)" strokeWidth="0.5"/>
          <text x={x+labelW/2} y={y+cellH/2+3.5} textAnchor="middle" fontSize="10" fontFamily="ui-monospace,monospace" fill={cellText(v,i)} fontWeight="600">{fv(v)}</text>
        </g>;
      })}
    </svg>;
  }

  function CommandPalette({open,onClose,commands}){
    const {useEffect,useMemo,useState}=React;
    const [q,setQ]=useState('');
    const [sel,setSel]=useState(0);
    const filtered=useMemo(()=>{
      if(!q.trim())return commands;
      const lq=q.toLowerCase().trim();
      return commands.filter(c=>c.label.toLowerCase().includes(lq)||c.id.toLowerCase().includes(lq));
    },[q,commands]);
    useEffect(()=>{setSel(0)},[q]);
    useEffect(()=>{if(!open)setQ('');},[open]);
    useEffect(()=>{
      if(!open)return;
      const onKey=(e)=>{
        if(e.key==='Escape'){e.preventDefault();onClose();}
        else if(e.key==='ArrowDown'){e.preventDefault();setSel(s=>Math.min(s+1,filtered.length-1));}
        else if(e.key==='ArrowUp'){e.preventDefault();setSel(s=>Math.max(s-1,0));}
        else if(e.key==='Enter'&&filtered[sel]){e.preventDefault();const c=filtered[sel];onClose();c.action();}
      };
      window.addEventListener('keydown',onKey);
      return()=>window.removeEventListener('keydown',onKey);
    },[open,filtered,sel,onClose]);
    if(!open)return null;
    return <div className="kq-modal-backdrop kq-cmd-backdrop" onClick={onClose} style={{alignItems:'flex-start',paddingTop:'14vh'}}>
      <div className="kq-card kq-modal-panel kq-cmd-panel" onClick={e=>e.stopPropagation()} style={{width:560,maxWidth:'94vw',padding:0,overflow:'hidden'}}>
        <div style={{display:'flex',alignItems:'center',gap:10,padding:'14px 18px',borderBottom:'1px solid var(--hair)'}}>
          <span style={{color:'var(--ink-3)',fontSize:16}}>⌕</span>
          <input autoFocus value={q} onChange={e=>setQ(e.target.value)} placeholder="搜索页面 / 命令…" style={{flex:1,background:'transparent',border:'none',outline:'none',color:'var(--ink)',fontSize:15,fontWeight:500}}/>
          <kbd style={{fontFamily:'ui-monospace,monospace',fontSize:10,color:'var(--ink-3)',background:'var(--surface-2)',border:'1px solid var(--hair)',borderRadius:5,padding:'1px 6px',fontWeight:600}}>ESC</kbd>
        </div>
        <div style={{maxHeight:'48vh',overflowY:'auto',padding:'6px'}}>
          {filtered.length===0&&<div style={{padding:'20px',textAlign:'center',color:'var(--ink-3)',fontSize:13}}>没有匹配的命令</div>}
          {filtered.map((c,i)=><div key={c.id} onClick={()=>{onClose();c.action();}} onMouseEnter={()=>setSel(i)} style={{display:'flex',alignItems:'center',gap:12,padding:'10px 12px',borderRadius:8,cursor:'pointer',background:i===sel?'var(--surface-2)':'transparent',color:i===sel?'var(--brand)':'var(--ink)'}}>
            <span style={{width:22,textAlign:'center',color:i===sel?'var(--brand)':'var(--ink-3)',fontSize:14}}>{c.icon}</span>
            <div style={{flex:1,fontSize:13,fontWeight:500}}>{c.label}</div>
            {c.group&&<span style={{fontSize:10,color:'var(--ink-3)',letterSpacing:'.04em',textTransform:'uppercase'}}>{c.group}</span>}
            {c.hint&&<kbd style={{fontFamily:'ui-monospace,monospace',fontSize:10,color:'var(--ink-3)',background:'var(--surface-2)',border:'1px solid var(--hair)',borderRadius:4,padding:'1px 5px',fontWeight:600}}>{c.hint}</kbd>}
          </div>)}
        </div>
        <div style={{padding:'8px 14px',borderTop:'1px solid var(--hair)',display:'flex',justifyContent:'space-between',fontSize:10,color:'var(--ink-3)'}}>
          <span>↑↓ 选择 · Enter 执行</span>
          <span>KwikQuant · ⌘K</span>
        </div>
      </div>
    </div>;
  }

  window.__SHAPE__.ui={
    fmt,fmtSigned,chgColor,chgArrow,
    Chip,StatusDot,StrategyStatusBadge,BacktestStatusBadge,OrderStatusBadge,
    Modal,Card,SectionTitle,EquityCurve,Candles,Sparkline,Ticker,LivePrice,Stat,EmptyState,Toasts,Heatmap,CommandPalette
  };
})();
