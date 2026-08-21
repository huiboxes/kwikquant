"""kwikquant-worker runtime。

内部包(不发 PyPI,Worker 镜像预装)。函数式策略契约(on_bar(bar, ctx),见 strategy.py)
+ event_loop(回测本地撮合/runner WS 驱动)+ worker_server 入口 + data_loader + backtest.matching。
依赖 :mod:`kwikquant` SDK。
"""

__version__ = "0.1.0"
