# 质量门工具链引导（仅由 scripts/quality-gate.sh 在 PATH 缺 mvn 时 source）。
# 本机 JDK17/Maven 安装于 ~/tools（见提交 1803042 的环境说明），非登录 shell 不会自动加入 PATH。
# 注意：本文件只定位工具，不改变任何质量门的阈值或判定；CI 环境自带 mvn 时不会被触发。
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.20.1+1}"
export MAVEN_HOME="$HOME/tools/apache-maven-3.9.16"
case ":$PATH:" in
  *":$MAVEN_HOME/bin:"*) ;;
  *) PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH" ;;
esac
