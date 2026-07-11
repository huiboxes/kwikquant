(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,Modal,fmt}=ui;

  function RuleCard({rule}){
    const {pushToast}=useApp();
    const [enabled,setEnabled]=useState(rule.enabled);
    return <Card>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start'}}>
        <div style={{flex:1}}>
          <div style={{display:'flex',alignItems:'center',gap:8}}>
            <div style={{width:32,height:32,borderRadius:8,background:'var(--brand-soft)',color:'var(--brand)',display:'flex',alignItems:'center',justifyContent:'center',fontWeight:700,fontFamily:'ui-monospace,monospace',fontSize:13}}>{rule.id.split('_')[0]}</div>
            <div>
              <div style={{fontSize:14,fontWeight:700}}>{rule.label}</div>
              <div style={{fontSize:10,color:'var(--ink-3)',fontFamily:'ui-monospace,monospace'}}>{rule.id}</div>
            </div>
          </div>
          <div style={{fontSize:12,color:'var(--ink-2)',marginTop:10,lineHeight:1.5}}>{rule.desc}</div>
          <div style={{marginTop:10,padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
            <div style={{fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>当前阈值</div>
            <div className="kq-mono-row" style={{fontSize:16,fontWeight:700,color:'var(--brand)',marginTop:2}}>{rule.value}</div>
          </div>
          <div style={{marginTop:8,fontSize:11,color:'var(--ink-3)',lineHeight:1.5}}>
            · 拒绝原因脱敏：只告知"被哪条规则拒"，不告知阈值<br/>
            · 无规则 = 放行；风控服务挂了开仓 fail-closed
          </div>
        </div>
        <label style={{display:'flex',flexDirection:'column',alignItems:'center',gap:4,cursor:'pointer'}}>
          <div onClick={()=>{setEnabled(!enabled);pushToast({title:`${rule.label} ${!enabled?'已启用':'已停用'}`,tone:!enabled?'up':'warn'});}} className="kq-press" style={{width:44,height:24,borderRadius:12,background:enabled?'var(--brand)':'var(--surface-3)',position:'relative',transition:'all .2s'}}>
            <div style={{position:'absolute',top:2,left:enabled?22:2,width:20,height:20,borderRadius:'50%',background:'#fff',transition:'all .2s',boxShadow:'0 1px 3px rgba(0,0,0,.2)'}}/>
          </div>
          <span style={{fontSize:10,color:'var(--ink-3)'}}>{enabled?'ON':'OFF'}</span>
        </label>
      </div>
    </Card>;
  }

  function AuditTable(){
    const {data}=useApp();
    return <Card>
      <SectionTitle title="决策审计" sub="每次风控决策的脱敏日志" right={<button className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:12}}>↓ 导出</button>}/>
      <div style={{overflow:'auto'}}>
        <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}} className="kq-mono-row">
          <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>时间</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>规则</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>决策</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>详情</th>
            <th style={{padding:'8px 12px',borderBottom:'1px solid var(--hair)'}}>账户</th>
          </tr></thead>
          <tbody>
            {data.riskAudit.map((a,i)=><tr key={i}>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{a.ts}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}><Chip>{a.rule}</Chip></td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>
                <span style={{display:'inline-flex',alignItems:'center',gap:4,padding:'2px 8px',borderRadius:6,fontSize:11,fontWeight:700,background:a.action==='放行'?'rgba(43,162,152,.15)':'rgba(246,57,105,.15)',color:a.action==='放行'?'var(--up)':'var(--down)'}}>
                  {a.action==='放行'?'✓':'✕'} {a.action}
                </span>
              </td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)',color:'var(--ink-2)'}}>{a.detail}</td>
              <td style={{padding:'10px 12px',borderBottom:'1px solid var(--hair)'}}>{a.acc.includes('paper')?<span className="kq-paper-badge">PAPER</span>:<span className="kq-live-badge">LIVE</span>}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </Card>;
  }

  function RiskPage(){
    const {data,pushToast}=useApp();
    const [showStop,setShowStop]=useState(false);
    const [showStopConfirm,setShowStopConfirm]=useState(false);
    const running=data.strategies.filter(s=>s.status==='running');

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap',gap:14}}>
        <div>
          <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>风控</h1>
          <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>下单前自动检查 · 防超额 / 防暴仓 / 防滥用</p>
        </div>
        <div style={{display:'flex',gap:8}}>
          <button className="kq-btn-ghost kq-press" onClick={()=>setShowStop(true)}>⏹ 紧急停止</button>
          <button className="kq-btn-primary kq-press" onClick={()=>pushToast({title:'规则已保存',tone:'up'})}>保存规则</button>
        </div>
      </div>

      {/* Behavior banner */}
      <Card style={{background:'var(--surface-2)',border:'1px dashed var(--hair)'}}>
        <div style={{display:'flex',gap:14,alignItems:'flex-start'}}>
          <div style={{width:36,height:36,borderRadius:8,background:'var(--brand-soft)',color:'var(--brand)',display:'flex',alignItems:'center',justifyContent:'center',fontSize:18,fontWeight:700,flexShrink:0}}>ⓘ</div>
          <div style={{fontSize:12,color:'var(--ink-2)',lineHeight:1.6}}>
            <strong style={{color:'var(--ink)'}}>风控行为</strong> · 拒绝不是 HTTP 错误，而是业务结果（HTTP 200 + 业务码 4105），UI 需读响应体判断而非状态码。
            拒绝原因脱敏：只告知用户"被哪条规则拒"，不告知阈值具体多少（防探测）。
            <strong style={{color:'var(--warn)'}}>风控服务挂了：</strong>平仓单放行 + 审计；开仓单直接拒（fail-closed）。
          </div>
        </div>
      </Card>

      {/* Rules */}
      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',gap:14}} className="kq-rule-grid">
        {data.riskRules.map(r=><RuleCard key={r.id} rule={r}/>)}
      </div>

      <AuditTable/>

      {/* Emergency stop modal */}
      <Modal open={showStop} onClose={()=>setShowStop(false)} title="紧急停止 · 高风险操作" width={520}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowStop(false)}>取消</button><button onClick={()=>{setShowStop(false);setShowStopConfirm(true);}} className="kq-press" style={{padding:'9px 16px',borderRadius:10,fontWeight:700,fontSize:13,background:'var(--down)',color:'#fff',border:'none',cursor:'pointer'}}>下一步 →</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div style={{padding:14,borderRadius:8,background:'rgba(246,57,105,.1)',border:'1px solid var(--down)'}}>
            <div style={{fontSize:13,fontWeight:700,color:'var(--down)'}}>⚠ 紧急停止会停掉所有运行中策略</div>
            <div style={{fontSize:11,color:'var(--ink-2)',marginTop:4,lineHeight:1.5}}>部分策略可能因 Worker 通信失败而无法停止，失败列表会暴露给你。</div>
          </div>
          <div style={{padding:14,borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)'}}>
            <div style={{fontSize:12,color:'var(--ink-3)'}}>将停止以下 {running.length} 个运行中策略：</div>
            <div style={{display:'flex',flexDirection:'column',gap:6,marginTop:8}}>
              {running.map(s=><div key={s.id} style={{display:'flex',justifyContent:'space-between',fontSize:12}}>
                <span>{s.name}</span><span style={{color:'var(--ink-3)'}}>{s.symbol}</span>
              </div>)}
            </div>
          </div>
        </div>
      </Modal>

      <Modal open={showStopConfirm} onClose={()=>setShowStopConfirm(false)} title="二次确认" width={440}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowStopConfirm(false)}>取消</button><button onClick={()=>{setShowStopConfirm(false);pushToast({title:'紧急停止已执行',body:'3 个策略已停止 · 1 个失败',tone:'warn'});}} className="kq-press" style={{padding:'9px 16px',borderRadius:10,fontWeight:700,fontSize:13,background:'var(--down)',color:'#fff',border:'none',cursor:'pointer'}}>确认停止全部</button></>}>
        <div style={{padding:14,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:12,color:'var(--brand-ink)',lineHeight:1.55}}>
          <strong>这是 MCP agent 高风险操作的二次确认流程。</strong><br/>
          输入"STOP"以确认停止所有运行中策略。失败列表会在通知中暴露。
        </div>
        <input className="kq-input" style={{marginTop:12}} placeholder="输入 STOP 确认"/>
      </Modal>

      <style>{`@media(max-width:900px){.kq-rule-grid{grid-template-columns:1fr !important}}`}</style>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.RiskPage=RiskPage;
})();
