(function(){
  const {useApp}=window.__SHAPE__.context;
  const ui=window.__SHAPE__.ui;
  const pages=window.__SHAPE__.pages;
  const {Provider}=window.__SHAPE__.context;
  const {AppLayout}=window.__SHAPE__.layout;

  function TradePage(){return <pages.TradingPage/>;}

  const PAGES={
    dashboard:pages.DashboardPage,
    strategy:pages.StrategyPage,
    backtest:pages.BacktestPage,
    trade:TradePage,
    risk:pages.RiskPage,
    portfolio:pages.PortfolioPage,
    market:pages.MarketPage,
    history:pages.HistoryPage,
    settings:pages.SettingsPage,
  };

  function Shell(){
    const {authed,page}=useApp();
    if(!authed){
      return <pages.LoginPage/>;
    }
    const Page=PAGES[page]||pages.DashboardPage;
    return <AppLayout>
      <div key={page} className="kq-page-enter">
        <Page/>
      </div>
      <ui.Toasts/>
      {/* mobile hamburger visibility */}
      <style>{`
        @media(max-width:900px){
          .kq-desktop-nav{display:none !important}
          #kq-hamburger{display:block !important}
        }
      `}</style>
    </AppLayout>;
  }

  function App(){
    return <Provider>
      <Shell/>
    </Provider>;
  }

  window.__SHAPE__.App=App;

  // Mount after Babel finishes compiling all scripts
  function tryMount(){
    const root=document.getElementById('root');
    if(!root||!window.__SHAPE__.App||!window.__SHAPE__.layout||!window.__SHAPE__.context)return setTimeout(tryMount,30);
    if(window.__SHAPE__._mounted)return;
    window.__SHAPE__._mounted=true;
    ReactDOM.render(React.createElement(App),root);
  }
  if(document.readyState==='loading'){
    document.addEventListener('DOMContentLoaded',tryMount);
  }else{
    tryMount();
  }
})();
