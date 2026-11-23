# 三角洲行动 · 浏览游戏模块反编译源码

`browse反编译.html` 是 `https://dfc.lza11111.com/browse` 页面「浏览游戏」模块的反编译源码浏览页。

- 路由：`/browse`
- 入口模块：`Y-x8muZd.js`（导出 `component`）
- 依赖模块：42 个（Vite 打包产物，经 Prettier 格式化）
- 单文件自包含：左侧文件树、文件名过滤、上/下一个切换、JS 语法高亮，离线可直接打开

来源站点为 Vite + React SPA；从 `index-DdGw8vbq.js` 的 `__vite__mapDeps` 还原出该路由的完整依赖集合。

## 在线访问

- 官网：https://lb.czyz.eu.cc/browse反编译.html （需在 Cloudflare DNS 将 `lb` CNAME 到 `wangbin100608-eng.github.io`）
- GitHub Pages：https://wangbin100608-eng.github.io/df-browse-decompiled/browse反编译.html
