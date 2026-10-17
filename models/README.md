# 建模源文件

本目录存放可编辑的 PlantUML 源文件。当前 01～18 与服务依赖图共 19 份源、34 张 PNG，均为【AI-辅助】实施草案，覆盖总体用例、领域模型、两类状态图、三个核心用例的 SSD/设计顺序图、跨服务活动图、组件图、部署图、通信图、五个服务的设计类图、五个独立 schema 的 ER 图和出场结算的进程视图顺序图（17）。18 号单独画两张可选扩展用例图及四张短顺序图，避免挤压必做总体用例图。11 号源文件分三张活动图（入场、出场准备、支付释放）；15 号与 16 号源文件各分五张设计类图和 ER 图。每张图在源文件中按 `@startuml` / `@enduml` 分段，避免报告插图缩小后难以阅读。

2026-10-17 EX-04 同步：13 号可选 DeepSeek 节点增加 analytics 连接；15 号 analytics 类图加入真实 `TrafficInsightService` 与 `TrafficInsight`；18 号新增 AI 报表解读用例及顺序图。全源 `-checkonly` 与渲染退出 0，已打开新增两张图及 analytics 类图查看；AI 只读取汇总统计，不改变 UC-10 报表或业务状态。

2026-10-17 扩展同步：13 号部署图以可选云节点标注 DeepSeek API；15 号设计类图加入图片识别、预估和推荐真实成员；16 号 ER 图加入 space 自有 `zone_walk_time` 演示表，明确没有与车位表的物理外键；18 号四张图说明 EX-01～EX-03。PlantUML 1.2024.3 全源语法检查退出 0，13/15/16/18 号重新渲染并打开核对。

2026-10-15 规范性修订：用例图删去未接入的外部识别设备及不成立的 include，用例语义说明留在文档中；领域模型的跨服务概念关系改为带多重性的关联，明确没有跨库外键；三张 SSD 与设计顺序图标出规约步骤，补激活条和真实控制器/仓储；跨服务活动图按入场、出场准备、支付释放拆成三张泳道图；组件图用提供/依赖接口；部署图逐个画出实际 Compose 进程、端口和五库，默认未启用的 Nacos 不画成节点；通信图改为与出场设计顺序图对应的对象级消息；16 号拆成五张 ER 图。access 与 analytics 设计类图只列实现中最关键的成员，其他真实成员仍可从源码查证；客户端接口与服务依赖见组件图和设计顺序图。PlantUML 1.2024.3 全源 `-checkonly` 与 28 张 PNG 渲染通过，已打开核心图核对可读性。UML 规范依据为课程报告模板第 7 节和 [OMG UML 2.5.1](https://www.omg.org/spec/UML/2.5.1)。

2026-10-11：图 10 的 pass→space 消息改成 `/internal/v1/spaces`；图 15 的 space 类图加入 `SpaceController.internalSpaces`。同日后续变更把预约定时到期标在图 04、pass 类图 15，把异常处理快照标在图 08、access 类图 15 和独立数据库 ER 图 16。PlantUML 1.2024.3 对全部源文件 `-checkonly`、重渲染成功，已打开修改过的 PNG 核对排版。

2026-10-07 已用本机 PlantUML 1.2024.3 对全部 18 个 `.puml` 源文件执行 `-checkonly`，并从同一源文件生成 `rendered/` 下的 22 张 PNG（15 号一份源产生五张图）；实际打开 15 号的五张类图和 17 号进程图核对文字与连线。负责人仍需逐图审核业务语义，补报告图题、正文引用和“模型—代码—测试”解释；图中任何与代码或数据库不一致的文字先修订源文件，再更新报告。

2026-10-09 图 13 已改为 Ubuntu VM Compose 默认栈：Nginx、gateway、五个业务容器、MySQL 五库和 Redis；默认固定地址路由。VM 未部署 Nacos profile，Sentinel 未接入。用同一 PlantUML 1.2024.3 执行 `-checkonly` 和重新渲染 `13-deployment.png`，并打开 PNG 检查布局。Windows 经该 VM 网关完成烟测，容器内部状态以 VM 侧验收记录为准。

2026-10-10 图 13 同步 Windows Docker Desktop 默认栈与新增的 gateway→frontend 页面静态路由。PlantUML 1.2024.3 对本图 `-checkonly`、渲染退出码 0，已打开 PNG 检查；五个业务服务依赖和数据库所有权没有改变。

2026-10-08 已将 analytics 的可选 Redis 报表缓存同步到组件图 12、部署图 13 和设计类图 15，并重新检查语法、渲染及打开对应 PNG；MySQL 仍是报表权威存储。

在项目根目录可用 `java -jar plantuml.jar -checkonly "models/*.puml"` 检查语法，再用 `java -jar plantuml.jar -charset UTF-8 -tpng -o rendered "models/*.puml"` 重建图片。`plantuml.jar` 可使用本机 VS Code PlantUML 扩展附带的版本；图片是源文件的派生产物。
