# 前端结构（【AI-辅助】）

`App.vue` 只负责导航、服务状态和页面容器。`pages/` 中的七个页面分别展示总览、车位、预约月卡、入场寻车、充电、结算和报表；页面通过显式的 `workspace` 属性读取状态并调用操作。跨页状态及业务流程集中在 `composables/useParkingWorkspace.js`，网络请求和幂等键只由 `api/client.js` 处理。`components/` 保存不调用 API 的车位图、车流图和图标。`style.css` 按顺序引入 `styles/` 的外壳、可视化、业务表单与响应式规则。`main.js` 只注册页面实际使用的 Element Plus 组件，避免打包整个组件库。

后端仍是金额、权益和状态的权威来源。页面只格式化“分”为元，按 API 返回的真实车位和车流作图；立体车位是示意图，不是机场建筑数字孪生。

在本目录执行 `npm ci`、`npm test`、`npm run build`。开发时运行 `npm run dev`，Vite 将 `/api` 代理到 `PARKING_GATEWAY_URL`（默认 `http://127.0.0.1:18080`）。在 Docker 中访问前端 `:8080`，或经网关 `:18080/`；`FRONTEND_SERVICE_URI` 控制网关静态页面路由。
