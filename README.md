# 机场智慧停车与出行服务平台

软件建模技术大作业，第 14 题。项目负责人：姜苏豪（软件工程 24201718）。

**当前状态：工程骨架与接口草案。**现有状态接口只证明服务和网关能运行，不代表十项业务已实现。计费、预约、月卡等规则仍需负责人确认，见 [决策记录](docs/decisions.md)。所有协作者先读 [项目统一契约](AGENTS.md)。

## 目录

| 路径 | 用途 |
|---|---|
| `gateway/` | API 网关，默认端口 18080 |
| `space-service/` | 车位与充电资源，默认端口 18081 |
| `access-service/` | 出入场与停车记录，默认端口 18082 |
| `billing-service/` | 计费、模拟支付与发票，默认端口 18083 |
| `pass-service/` | 预约与月卡，默认端口 18084 |
| `analytics-service/` | 运营统计，默认端口 18085 |
| `frontend/` | Vue 3 页面骨架 |
| `docs/` | 用例、接口、决策草案 |
| `models/` | 可编辑的 PlantUML 源文件 |
| `sql/` | 独立数据库初始化与后续建表脚本 |

## 技术版本

Java 21；Spring Boot 4.0.6；Spring Cloud 2025.1.1；Spring Cloud Alibaba 2025.1.0.0；MySQL；Vue 3；Vite 6；Element Plus 2。版本组合以根 `pom.xml` 为准。选择依据为 [Spring Cloud Alibaba 官方版本对应表](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)；依赖安装后仍需以实际集成运行验证。

## 本地启动

1. 安装 Java 21 并设置 `JAVA_HOME`。项目已包含 `mvnw.cmd`，首次使用时它会下载 Maven 3.8.5；MySQL 与 Nacos 在**当前骨架验证**中不是必需的，业务实现时需要安装和配置。
2. 在根目录运行 `.\mvnw.cmd -DskipTests package`。
3. 分别打开终端运行 `java -jar <模块名>/target/<模块名>-0.1.0-SNAPSHOT.jar`。先启动五个业务服务，再启动 `gateway`。默认使用本地固定地址路由；后续 Nacos 联调时设置 `NACOS_ENABLED=true`，并按实际部署配置网关目标 URI。
4. 浏览器访问 `http://localhost:18080/api/v1/spaces/status`，预期返回 `code=OK`、`service=space-service`。其余路径依次为 `/api/v1/access/status`、`/api/v1/billing/status`、`/api/v1/passes/status`、`/api/v1/analytics/status`。
5. 前端需要符合 Vite 6 要求的 Node.js 版本，在 `frontend/` 运行 `npm install`、`npm run dev`，打开终端显示的本地地址。前端通过网关读取上述状态接口。

> 当前执行环境无法连接 GitHub：命令行默认代理指向不可用的 `127.0.0.1:9`，绕过代理直连时连接被重置。因此尚未读取远端仓库内容。本地已初始化 Git 并设置 `origin` 地址；目前没有本地提交，也没有向远端推送。这些网络错误不能说明远端仓库不存在。

本机的首次骨架验证结果见 [验证记录](docs/bootstrap-verification.md)。

## 下一阶段

负责人审核 `docs/decisions.md` 的业务规则和 `docs/api-contract.md` 的接口草案；确认后冻结 v1 接口，再实现“入场—占位—出场—计费—支付—释放车位”业务链。报告与运行截图只能反映已经真实实现并验证的内容。
