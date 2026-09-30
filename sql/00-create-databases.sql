-- 创建五个独立 schema；各服务启动时执行自有 schema.sql，space-service 另载入幂等演示数据。
-- 用有 CREATE DATABASE 权限的 MySQL 账户执行。
CREATE DATABASE IF NOT EXISTS parking_space CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS parking_access CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS parking_billing CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS parking_pass CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS parking_analytics CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
