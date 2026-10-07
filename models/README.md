# 建模源文件

本目录存放可编辑的 PlantUML 源文件。当前 01～16 与服务依赖图均为【AI-辅助】实施草案，覆盖总体用例、领域模型、两类状态图、三个核心用例的 SSD/设计顺序图、跨服务活动图、组件图、部署图、通信图、核心设计类图和独立数据库 ER 概念图。

已用本机 PlantUML 1.2024.3 对全部 17 个 `.puml` 文件执行 `-checkonly`，并从同一源文件生成 `rendered/` 下的 17 张 PNG。负责人仍需逐图审核业务语义，补报告图号、图题、正文引用和“模型—代码—测试”映射；图中任何与代码或数据库不一致的文字先修订源文件，再更新报告。13 号部署图在 2026-09-30 的本机 Nacos 3.1.1 联调后更新；不要把消息队列、Nacos 配置中心或未运行组件画成已验证部署。

在项目根目录可用 `java -jar plantuml.jar -checkonly "models/*.puml"` 检查语法，再用 `java -jar plantuml.jar -charset UTF-8 -tpng -o rendered "models/*.puml"` 重建图片。`plantuml.jar` 可使用本机 VS Code PlantUML 扩展附带的版本；图片是源文件的派生产物。
