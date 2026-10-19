#!/usr/bin/env bash
# 构建演示栈的全部宿主侧产物：
#   1) 三个后端 fat-jar（mvn package -DskipTests，compose 以 ro 卷挂载）
#   2) web-ui/dist（compose web-ui 服务 nginx 伺服）
#   3) demo-host/dist（compose demo-host 服务 nginx 伺服）
# 用法：bash deploy/docker/build.sh   （仓库任意位置执行均可）
set -euo pipefail
cd "$(dirname "$0")/../.."

# Java/Maven 环境兜底（本机已装则直接用 PATH）
if ! command -v mvn >/dev/null 2>&1; then
  export JAVA_HOME="${JAVA_HOME:-/home/nlai/tools/jdk-17.0.20.1+1}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v mvn >/dev/null 2>&1; then
  export PATH="/home/nlai/tools/apache-maven-3.9.16/bin:$PATH"
fi

echo "==> [0/3] Databend DDL 初始化工具（javac 预编译，jre 镜像不能源码直跑）"
javac -d deploy/docker/tools/classes deploy/docker/tools/DatabendDdlInit.java

echo "==> [1/3] 后端 fat-jars（mvn package -DskipTests）"
mvn -q package -DskipTests

echo "==> [2/3] web-ui dist"
(cd web-ui && [ -d node_modules ] || npm ci --no-audit --no-fund >/dev/null; npm run build)

echo "==> [3/3] demo-host dist"
(cd deploy/docker/demo-host && [ -d node_modules ] || npm install --no-audit --no-fund >/dev/null; npm run build)

echo "构建完成：三 fat-jar + web-ui/dist + demo-host/dist"
