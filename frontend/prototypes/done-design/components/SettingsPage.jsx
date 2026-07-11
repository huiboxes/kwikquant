(function(){
  const {useState}=React;
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const {Card,SectionTitle,Chip,Modal,fmt}=ui;

  const EVENT_TYPES=[
    {id:'risk_reject',label:'风控拒绝',def:true},
    {id:'order_filled',label:'订单成交',def:true},
    {id:'order_canceled',label:'订单撤销',def:false},
    {id:'strat_start',label:'策略启动',def:true},
    {id:'strat_stop',label:'策略停止',def:false},
    {id:'strat_error',label:'策略异常',def:true},
  ];
  const CHANNELS=[{id:'ws',label:'站内',def:true},{id:'email',label:'邮件',def:true},{id:'telegram',label:'Telegram',def:false},{id:'webhook',label:'Webhook',def:false}];

  function LlmKeyCard({k}){
    const {pushToast}=useApp();
    const [show,setShow]=useState(false);
    return <Card>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start'}}>
        <div style={{flex:1}}>
          <div style={{display:'flex',alignItems:'center',gap:8}}>
            <strong style={{fontSize:14}}>{k.label}</strong>
            <Chip tone={k.provider.includes('OpenAI')?'info':'brand'}>{k.provider}</Chip>
            {k.active&&<Chip tone="up">● 启用</Chip>}
          </div>
          <div style={{display:'flex',gap:10,marginTop:8,fontSize:11,color:'var(--ink-3)'}}>
            <span>API key <span className="kq-mono-row" style={{color:'var(--ink-2)'}}>{k.masked}</span></span>
            <span>添加于 {k.added}</span>
          </div>
        </div>
        <div style={{display:'flex',gap:6}}>
          <button onClick={()=>pushToast({title:'key 已轮换',body:'旧 key 失效',tone:'info'})} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:11}}>轮换</button>
          <button onClick={()=>pushToast({title:'已删除',body:k.label,tone:'warn'})} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:11,color:'var(--down)'}}>删除</button>
        </div>
      </div>
    </Card>;
  }

  function McpTokenCard({t}){
    const {pushToast}=useApp();
    const [showReveal,setShowReveal]=useState(false);
    return <Card>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'flex-start'}}>
        <div style={{flex:1}}>
          <div style={{display:'flex',alignItems:'center',gap:8}}>
            <strong style={{fontSize:14}}>{t.name}</strong>
            {t.active&&<Chip tone="up">● 有效</Chip>}
          </div>
          <div style={{display:'flex',gap:6,marginTop:8,flexWrap:'wrap'}}>
            {t.scopes.map(s=><Chip key={s} style={{fontSize:10,padding:'1px 8px'}}>{s}</Chip>)}
          </div>
          <div style={{marginTop:8,padding:'8px 10px',borderRadius:8,background:'var(--surface-2)',fontSize:11,color:'var(--ink-2)'}}>
            <div style={{display:'flex',justifyContent:'space-between'}}>
              <span>明文 token</span><span className="kq-mono-row">{showReveal?'kq_live_xxxxxxxxxxxx_'+t.id:'••••••••••••••••'}</span>
            </div>
          </div>
          <div style={{fontSize:10,color:'var(--ink-3)',marginTop:6}}>创建 {t.created} · 上次使用 {t.lastUsed}</div>
        </div>
        <div style={{display:'flex',flexDirection:'column',gap:6}}>
          <button onClick={()=>setShowReveal(s=>!s)} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:11}}>{showReveal?'隐藏':'显示'}</button>
          <button onClick={()=>pushToast({title:'token 已吊销',body:t.name,tone:'down'})} className="kq-btn-ghost kq-press" style={{padding:'5px 10px',fontSize:11,color:'var(--down)'}}>吊销</button>
        </div>
      </div>
      {showReveal&&<div style={{marginTop:10,padding:10,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:11,color:'var(--brand-ink)',lineHeight:1.55}}>
        ⚠ MCP token 明文仅签发时显示一次。请立即复制到你的 AI agent 配置，关闭后将无法再次查看。
      </div>}
    </Card>;
  }

  function SettingsPage(){
    const {data,pushToast}=useApp();
    const [tab,setTab]=useState('llm');
    const [showAddLlm,setShowAddLlm]=useState(false);
    const [showAddMcp,setShowAddMcp]=useState(false);
    const [showMcpReveal,setShowMcpReveal]=useState(false);
    const [newMcpToken,setNewMcpToken]=useState('');

    const tabs=[
      {id:'llm',label:'LLM API Key'},
      {id:'mcp',label:'MCP 令牌'},
      {id:'notif',label:'通知偏好'},
      {id:'account',label:'账户与密码'},
    ];

    const revealMcp=()=>{
      setShowAddMcp(false);
      const tok='kq_mcp_'+Math.random().toString(36).slice(2,8)+Math.random().toString(36).slice(2,8);
      setNewMcpToken(tok);
      setShowMcpReveal(true);
    };

    return <div style={{display:'flex',flexDirection:'column',gap:18}}>
      <div>
        <h1 style={{fontSize:24,fontWeight:700,letterSpacing:'-.015em',margin:0}}>设置</h1>
        <p style={{fontSize:13,color:'var(--ink-2)',marginTop:6}}>管理 AI 密钥 · MCP 令牌 · 通知偏好 · 密码</p>
      </div>

      <div style={{display:'flex',gap:4,borderBottom:'1px solid var(--hair)'}}>
        {tabs.map(t=><button key={t.id} onClick={()=>setTab(t.id)} className="kq-press" style={{padding:'10px 16px',fontSize:13,fontWeight:600,background:'none',border:'none',borderBottom:tab===t.id?'2px solid var(--brand)':'2px solid transparent',color:tab===t.id?'var(--brand)':'var(--ink-3)',cursor:'pointer',marginBottom:-1}}>{t.label}</button>)}
      </div>

      {tab==='llm'&&<>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
          <SectionTitle title="LLM API Keys" sub="多 provider · 加密存储 · 仅露末 4 位"/>
          <button className="kq-btn-primary kq-press" onClick={()=>setShowAddLlm(true)}>+ 添加 Key</button>
        </div>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          {data.llmKeys.map(k=><LlmKeyCard key={k.id} k={k}/>)}
        </div>
      </>}

      {tab==='mcp'&&<>
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
          <SectionTitle title="MCP 令牌" sub="给 AI agent 用 · 明文仅签发时显示一次"/>
          <button className="kq-btn-primary kq-press" onClick={()=>setShowAddMcp(true)}>+ 签发令牌</button>
        </div>
        <Card style={{background:'var(--brand-soft)',border:'1px solid var(--brand)'}}>
          <div style={{display:'flex',gap:12,alignItems:'flex-start'}}>
            <div style={{width:32,height:32,borderRadius:8,background:'var(--brand)',color:'#fff',display:'flex',alignItems:'center',justifyContent:'center',fontWeight:700,flexShrink:0}}>AI</div>
            <div style={{fontSize:12,color:'var(--brand-ink)',lineHeight:1.6}}>
              <strong>MCP agent 能代你</strong> · 查账户 / 查行情 / 下单 / 撤单 / 查持仓 / 跑回测 / 启停策略。
              <strong>高风险操作需二次确认</strong>：紧急停止、启动实盘交易 — UI 会有强确认流程。
            </div>
          </div>
        </Card>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          {data.mcpTokens.map(t=><McpTokenCard key={t.id} t={t}/>)}
        </div>
      </>}

      {tab==='notif'&&<>
        <SectionTitle title="通知偏好" sub="按事件类型 × 渠道启停 · 无记录 = 默认推送 · 关闭 = 不推"/>
        <Card pad={0} style={{overflow:'hidden'}}>
          <table style={{width:'100%',fontSize:12,borderCollapse:'collapse'}}>
            <thead><tr style={{textAlign:'left',fontSize:10,color:'var(--ink-3)',textTransform:'uppercase',letterSpacing:'.04em'}}>
              <th style={{padding:'12px 16px',borderBottom:'1px solid var(--hair)'}}>事件类型</th>
              {CHANNELS.map(c=><th key={c.id} style={{padding:'12px 16px',borderBottom:'1px solid var(--hair)',textAlign:'center'}}>{c.label}</th>)}
            </tr></thead>
            <tbody>
              {EVENT_TYPES.map(ev=>(
                <tr key={ev.id}>
                  <td style={{padding:'12px 16px',borderBottom:'1px solid var(--hair)',fontWeight:600}}>{ev.label}</td>
                  {CHANNELS.map(c=>(
                    <td key={c.id} style={{padding:'12px 16px',borderBottom:'1px solid var(--hair)',textAlign:'center'}}>
                      <input type="checkbox" defaultChecked={ev.def&&c.def} style={{accentColor:'var(--brand)',transform:'scale(1.3)'}} onChange={e=>pushToast({title:`${ev.label} / ${c.label} 已${e.target.checked?'启用':'关闭'}`,tone:e.target.checked?'up':'warn'})}/>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </>}

      {tab==='account'&&<>
        <SectionTitle title="账户与密码" sub="修改登录密码"/>
        <Card style={{maxWidth:480}}>
          <div style={{display:'flex',flexDirection:'column',gap:14}}>
            <div>
              <label className="kq-label">当前密码</label>
              <input className="kq-input" type="password" defaultValue="••••••••"/>
            </div>
            <div>
              <label className="kq-label">新密码</label>
              <input className="kq-input" type="password" placeholder="至少 8 位，含字母数字"/>
            </div>
            <div>
              <label className="kq-label">确认新密码</label>
              <input className="kq-input" type="password" placeholder="再输入一次"/>
            </div>
            <button className="kq-btn-primary kq-press" style={{alignSelf:'flex-start'}} onClick={()=>pushToast({title:'密码已更新',tone:'up'})}>更新密码</button>
          </div>
        </Card>
        <Card style={{maxWidth:480,marginTop:14}}>
          <SectionTitle title="会话" sub="当前登录设备"/>
          <div style={{display:'flex',flexDirection:'column',gap:8,fontSize:12}}>
            <div style={{display:'flex',justifyContent:'space-between',padding:'8px 10px',borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)'}}>
              <div><strong>当前会话</strong><div style={{fontSize:10,color:'var(--ink-3)'}}>Chrome · macOS · 2026-07-09 14:02</div></div>
              <Chip tone="up">在线</Chip>
            </div>
            <div style={{display:'flex',justifyContent:'space-between',padding:'8px 10px',borderRadius:8,background:'var(--surface-2)'}}>
              <div><strong>Cursor Agent</strong><div style={{fontSize:10,color:'var(--ink-3)'}}>MCP token · 2小时前</div></div>
              <button className="kq-btn-ghost kq-press" style={{padding:'3px 8px',fontSize:11,color:'var(--down)'}}>吊销</button>
            </div>
          </div>
        </Card>
      </>}

      {/* Add LLM modal */}
      <Modal open={showAddLlm} onClose={()=>setShowAddLlm(false)} title="添加 LLM API Key" width={480}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowAddLlm(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={()=>{setShowAddLlm(false);pushToast({title:'key 已加密保存',body:'仅露末 4 位',tone:'up'});}}>保存</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div>
            <label className="kq-label">Provider</label>
            <select className="kq-input"><option>OpenAI</option><option>Anthropic</option><option>OpenAI 兼容 (DeepSeek 等)</option></select>
          </div>
          <div>
            <label className="kq-label">标签</label>
            <input className="kq-input" placeholder="例：gpt-5 风格策略"/>
          </div>
          <div>
            <label className="kq-label">API Key</label>
            <input className="kq-input" type="password" placeholder="sk-..."/>
          </div>
          <div style={{padding:10,borderRadius:8,background:'var(--surface-2)',border:'1px dashed var(--hair)',fontSize:11,color:'var(--ink-3)',lineHeight:1.5}}>
            ⚠ API key 加密存储，UI 永远不会展示明文。LLM 原始错误会被脱敏，不透传。
          </div>
        </div>
      </Modal>

      {/* Add MCP modal */}
      <Modal open={showAddMcp} onClose={()=>setShowAddMcp(false)} title="签发 MCP 令牌" width={520}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowAddMcp(false)}>取消</button><button className="kq-btn-primary kq-press" onClick={revealMcp}>签发并显示</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div>
            <label className="kq-label">Agent 名称</label>
            <input className="kq-input" defaultValue="My AI Agent"/>
          </div>
          <div>
            <label className="kq-label">权限范围 (scopes)</label>
            <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:6,fontSize:12}}>
              {['read_market','read_account','read_position','place_order','cancel_order','run_backtest','start_strategy','stop_strategy','emergency_stop','start_live'].map(s=>(
                <label key={s} style={{display:'flex',gap:6,alignItems:'center',padding:'6px 10px',borderRadius:6,background:'var(--surface-2)'}}>
                  <input type="checkbox" defaultChecked={s.startsWith('read')} style={{accentColor:'var(--brand)'}}/>
                  <span className="kq-mono-row" style={{fontSize:11}}>{s}</span>
                  {(s==='emergency_stop'||s==='start_live')&&<span style={{color:'var(--down)',fontSize:10}}>·高风险</span>}
                </label>
              ))}
            </div>
          </div>
          <div style={{padding:10,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)',fontSize:11,color:'var(--brand-ink)',lineHeight:1.55}}>
            ⚠ <strong>明文 token 仅签发时显示一次</strong>。关闭后将永远无法再次查看。高风险操作（紧急停止、启动实盘）会触发二次确认流程。
          </div>
        </div>
      </Modal>

      {/* MCP reveal modal */}
      <Modal open={showMcpReveal} onClose={()=>setShowMcpReveal(false)} title="⚠ MCP 令牌已签发" width={520}
        footer={<><button className="kq-btn-ghost kq-press" onClick={()=>setShowMcpReveal(false)}>我已保存</button></>}>
        <div style={{display:'flex',flexDirection:'column',gap:12}}>
          <div style={{padding:14,borderRadius:8,background:'var(--brand-soft)',border:'1px solid var(--brand)'}}>
            <div style={{fontSize:13,fontWeight:700,color:'var(--brand-ink)'}}>请立即复制 · 现在不存下来就再也看不到了</div>
            <div style={{fontSize:11,color:'var(--brand-ink)',marginTop:4,lineHeight:1.5}}>明文 token 只在签发时显示这一次，关闭后无法再次查看。</div>
          </div>
          <div style={{padding:14,borderRadius:8,background:'var(--surface-2)',border:'1px solid var(--hair)'}}>
            <div className="kq-label">Token (明文)</div>
            <div className="kq-mono-row" style={{fontSize:14,fontWeight:700,wordBreak:'break-all',color:'var(--brand)'}}>{newMcpToken}</div>
          </div>
          <button className="kq-btn-primary kq-press" onClick={()=>{navigator.clipboard&&navigator.clipboard.writeText(newMcpToken);pushToast({title:'已复制到剪贴板',tone:'up'});}}>复制 Token</button>
        </div>
      </Modal>
    </div>;
  }

  window.__SHAPE__.pages=window.__SHAPE__.pages||{};
  window.__SHAPE__.pages.SettingsPage=SettingsPage;
})();
