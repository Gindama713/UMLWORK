# 建模源文件

本目录存放可编辑的 PlantUML 源文件。当前 01～17 与服务依赖图均为【AI-辅助】实施草案，覆盖总体用例、领域模型、两类状态图、三个核心用例的 SSD/设计顺序图、跨服务活动图、组件图、部署图、通信图、五个服务的设计类图、独立数据库 ER 概念图和出场结算的进程视图顺序图（17）。15 号源文件用五组 `@startuml` / `@enduml` 分别生成 access、billing、space、pass、analytics 的设计类图，避免宽幅合图在报告中无法阅读。

2026-10-07 已用本机 PlantUML 1.2024.3 对全部 18 个 `.puml` 源文件执行 `-checkonly`，并从同一源文件生成 `rendered/` 下的 22 张 PNG（15 号一份源产生五张图）；实际打开 15 号的五张类图和 17 号进程图核对文字与连线。负责人仍需逐图审核业务语义，补报告图题、正文引用和“模型—代码—测试”解释；图中任何与代码或数据库不一致的文字先修订源文件，再更新报告。

2026-10-09 图 13 已改为 Ubuntu VM Compose 默认栈：Nginx、gateway、五个业务容器、MySQL 五库和 Redis；默认固定地址路由。VM 未部署 Nacos profile，Sentinel 未接入。用同一 PlantUML 1.2024.3 执行 `-checkonly` 和重新渲染 `13-deployment.png`，并打开 PNG 检查布局。Windows 经该 VM 网关完成烟测，容器内部状态以 VM 侧验收记录为准。

2026-10-08 已将 analytics 的可选 Redis 报表缓存同步到组件图 12、部署图 13 和设计类图 15，并重新检查语法、渲染及打开对应 PNG；MySQL 仍是报表权威存储。

在项目根目录可用 `java -jar plantuml.jar -checkonly "models/*.puml"` 检查语法，再用 `java -jar plantuml.jar -charset UTF-8 -tpng -o rendered "models/*.puml"` 重建图片。`plantuml.jar` 可使用本机 VS Code PlantUML 扩展附带的版本；图片是源文件的派生产物。
