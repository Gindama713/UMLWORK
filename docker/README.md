# 容器编排说明

本目录与项目根的 `docker-compose.yml` 一起，把「机场智慧停车与出行服务平台」的
6 个 Spring 服务 + MySQL + Redis + 前端打包成一套可复现的容器编排。

## 拓扑

| 服务 | 容器名 | 宿主端口 | 镜像 |
|---|---|---|---|
| gateway | umlwork-gateway | **18080** | 自建（Dockerfile.service） |
| space-service | umlwork-space | 内部 18081 | 自建 |
| access-service | umlwork-access | 内部 18082 | 自建 |
| billing-service | umlwork-billing | 内部 18083 | 自建 |
| pass-service | umlwork-pass | 内部 18084 | 自建 |
| analytics-service | umlwork-analytics | 内部 18085 | 自建 |
| frontend | umlwork-frontend | **8080** | nginx:1.27-alpine，复制预构建 dist |
| mysql | umlwork-mysql | 3306 | mysql:8.4 |
| redis | umlwork-redis | 内部 6379 | redis:7-alpine |
| nacos（可选 profile） | umlwork-nacos | 8848 / 8081 | nacos/nacos-server:v3.1.1 |

对外访问：网关 `http://<VM_IP>:18080`，前端 `http://<VM_IP>:8080`。

## 使用步骤

```bash
# 1) 准备密码（.env 已在 .gitignore 中；参考 .env.example）
cp .env.example .env && vi .env

# 2) 准备预构建产物（compose 只做“瘦运行镜像”，不在容器里编译）
#    jar  ：项目根执行  .\mvnw.cmd -DskipTests clean package（Windows）
#    前端 ：cd frontend && npm ci && npm run build           → 产出 frontend/dist
#
#    ⚠️ 实测本 VM 访问 Maven/通用 HTTPS 的上限约 190KB/s，Maven 有效速率低到 31KB/s
#       （依赖全量下载约 4.5 小时）；而 宿主机→VM 走 VMnet8 本地链路实测 98MB/s。
#       所以预构建产物应在宿主机完成后传入，而不是在 VM 里下载。

# 3) 构建并启动
docker compose up -d --build

# 4) 查看健康状态
docker compose ps
curl -s localhost:18080/actuator/health
```

## 关键设计说明

1. **jar 与镜像分离**：`Dockerfile.service` 只承载已构建好的 jar，不做编译。
   好处是 `compose build` 不会重复跑 Maven；代价是必须先在有依赖缓存的机器上编译 JAR。
   两步之间缺失 jar 时，镜像构建会**立刻失败**（`COPY` 断言），不会拖到运行期。

2. **数据库初始化分三层**：
   - `sql/00-create-databases.sql` → 建 5 个库（挂在 `/docker-entrypoint-initdb.d/`）
   - `docker/mysql-init/10-create-users.sh` → 建 5 个"仅能访问自有 schema"的账号
   - 各服务 `spring.sql.init.mode=always` → 每次服务启动时执行本服务的幂等建表脚本
   前两项仅在**数据卷为空时**执行一次，服务的 `schema.sql` 每次启动执行。因为 `schema.sql` 连不上库会直接导致启动失败，
   所以 compose 给 mysql 配了 healthcheck，业务服务 `depends_on: service_healthy`；
   初始化期间官方镜像跑的是 `skip-networking` 临时实例，因此那个 healthcheck 只在"真能连"时通过。

3. **默认不启动 Nacos**：项目交接记录已实测「Nacos 8848 未运行时六个服务 health 全 UP」，
   依据是 `spring.config.import: optional:nacos:...` + `NACOS_ENABLED` 默认 false，
   网关默认 profile 也用固定地址路由（`*_SERVICE_URI`）。
   可选 Nacos 容器尚未联调；单独启动该 profile 也不会自动切换网关和 Feign 的固定地址配置，不能据此声称服务发现已启用。

4. **前端必须靠 nginx 反代**：`App.vue` 全部请求相对路径 `/api/v1/...`，生产构建不含代理，
   所以 `docker/nginx.conf` 把 `/api/` 反代到 `gateway:18080`；没有这一段前端只会 404。

5. **内存敏感**：每个 Java 服务 `mem_limit: 512m` + `MaxRAMPercentage=55` + `SerialGC` +
   `TieredStopAtLevel=1`；MySQL 关 `performance_schema`、buffer pool 限 128M。
   运行时内存预算由六个 JVM、MySQL、Redis 和 Nginx 组成，具体占用以 VM 的 `docker stats` 为准。

## 已知未验证 / 注意事项

- **Nacos profile 未实测**：镜像 tag 与 3.x 控制台鉴权初始化流程都可能需要调整，默认不启用。
- **证据边界**：VM agent 报告 `docker compose up -d` 后六个 Java 服务健康；Windows 经 `scripts/smoke-gateway.ps1 -GatewayUrl http://192.168.125.129:18080` 实测退出码 0，11 项 PASS。Windows 没有 Docker CLI，未独立检查容器列表；新仓库文件与 VM 当前运行文件同步后还需重新构建复核。
- **MySQL 3306 对宿主暴露**：便于宿主机侧脚本核对；不需要可删掉 `ports` 那一行。
- 所有服务日志已限 `10m × 3`，避免长期运行撑爆磁盘。
- `.env` 含真实密码，**不要提交**；`.dockerignore` 已排除，避免被烤进镜像层。
