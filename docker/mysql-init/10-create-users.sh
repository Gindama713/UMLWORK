#!/bin/bash
# 为每个服务创建只能访问自有 schema 的账号（README 建议；避免应用用 root 连库）
# 由 mysql 官方镜像的 docker-entrypoint 在首次初始化时执行，密码来自 .env 注入的环境变量
set -euo pipefail

declare -A SCHEMA=(
  [SPACE]=parking_space
  [ACCESS]=parking_access
  [BILLING]=parking_billing
  [PASS]=parking_pass
  [ANALYTICS]=parking_analytics
)
declare -A SEEN_USER=()

for svc in SPACE ACCESS BILLING PASS ANALYTICS; do
  user_var="${svc}_DB_USER"
  pw_var="${svc}_DB_PASSWORD"
  user="${!user_var:?缺少 $user_var}"
  pw="${!pw_var:?缺少 $pw_var}"
  db="${SCHEMA[$svc]}"
  if [[ ! "$user" =~ ^[A-Za-z_][A-Za-z0-9_]*$ || -n "${SEEN_USER[$user]+x}" ]]; then
    echo "[init] $user_var 必须是唯一的英文字母/数字/下划线账号" >&2
    exit 1
  fi
  SEEN_USER[$user]=1
  if [[ ! "$pw" =~ ^[A-Za-z0-9._@%+=:-]+$ ]]; then
    echo "[init] $pw_var 含不能安全写入初始化 SQL 的字符；请使用字母、数字或 ._@%+=:-" >&2
    exit 1
  fi
  echo "[init] 创建账号 ${user} → 仅授权 ${db}.*"
  mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${pw}';
ALTER USER '${user}'@'%' IDENTIFIED BY '${pw}';
GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${user}'@'%';
FLUSH PRIVILEGES;
SQL
done
echo "[init] 五个服务账号创建完成"
