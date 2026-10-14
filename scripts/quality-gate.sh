#!/bin/bash
# 质量门统一入口：自动识别项目类型，输出 JSON 报告。交互式 hook 与 CI 共用。
# 用法: quality-gate.sh [--scope backend|frontend|all] [--thresholds <yaml>] [--stub-dir <dir>]
set -u

SCOPE="all"; THRESHOLDS_FILE=""; STUB_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --scope) SCOPE="$2"; shift 2 ;;
    --thresholds) THRESHOLDS_FILE="$2"; shift 2 ;;
    --stub-dir) STUB_DIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

REPORT='{"overall":"fail","scope":"'"$SCOPE"'","project_type":"none","warnings":[],"checks":[],"coverage":null}'
emit() { # emit <json-report> <exit-code>  —— 只允许这一个 stdout 输出点
  echo "$1"
  exit "$2"
}

# --scope 值校验（Task 11 评审裁决）：仅 backend|frontend|all 合法，其余报错退出
case "$SCOPE" in
  backend|frontend|all) ;;
  *) emit '{"overall":"fail","scope":"'"$SCOPE"'","project_type":"none","warnings":["无效的 --scope 值（仅支持 backend|frontend|all）"],"checks":[],"coverage":null}' 2 ;;
esac

EXTRA_WARNINGS=""   # load_thresholds 填充，run_backend/run_frontend 注入其 warnings

load_thresholds() {
  # 读取 YAML 里形如 "backend_coverage: 80" 的行；文件缺失 → 内置默认值 + EXTRA_WARNINGS
  local file="$1"
  COV_BACKEND=80; COV_FRONTEND=70
  EXTRA_WARNINGS=""
  # 未显式指定时，按项目内常见位置查找（README/手册承诺"复制到项目根后生效"）
  if [ -z "$file" ]; then
    if [ -f quality-gate.yaml ]; then file=quality-gate.yaml
    elif [ -f templates/config/quality-gate.yaml ]; then file=templates/config/quality-gate.yaml
    else return; fi
  fi
  if [ ! -f "$file" ]; then
    EXTRA_WARNINGS='"阈值文件缺失，使用默认值(Java 80/Vue 70)"'
    return
  fi
  local b f
  b=$(sed -n 's/^ *backend_coverage: *\([0-9][0-9]*\).*/\1/p' "$file" | head -1)
  f=$(sed -n 's/^ *frontend_coverage: *\([0-9][0-9]*\).*/\1/p' "$file" | head -1)
  [ -n "$b" ] && COV_BACKEND="$b"
  [ -n "$f" ] && COV_FRONTEND="$f"
}

# 工具解析：stub-dir 优先
resolve() { # resolve mvn -> 路径或空
  if [ -n "$STUB_DIR" ] && [ -x "$STUB_DIR/$1" ]; then echo "$STUB_DIR/$1"
  else command -v "$1" 2>/dev/null || true; fi
}

# 项目本地工具链引导：非登录 shell（自动化代理/CI 容器）可能缺 mvn/java 的 PATH。
# 仅当 PATH 无 mvn 且存在 scripts/quality-gate.env.sh 时 source 之——只补工具位置，
# 不改变任何门的阈值与判定口径；环境本身有 mvn 时该文件不会被触发。
if [ -z "$(command -v mvn 2>/dev/null)" ] && [ -f "$(dirname "$0")/quality-gate.env.sh" ]; then
  . "$(dirname "$0")/quality-gate.env.sh"
fi

# 捕获门函数输出；函数内部 emit（如 mvn 未安装）在 $() 子 shell 中退出，此处按原退出码转发
capture() { # capture <func>  -> 设置 REPORT 并按需 exit
  REPORT=$("$@"); local rc=$?
  [ "$rc" -eq 0 ] || emit "$REPORT" "$rc"
}

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# 参数解析后、任何门运行前加载阈值（须在 run_backend/run_frontend 调用之前）
load_thresholds "$THRESHOLDS_FILE"

HAS_POM=0; [ -f pom.xml ] && HAS_POM=1
HAS_NPM=0; [ -f package.json ] && HAS_NPM=1

if [ "$HAS_POM" -eq 0 ] && [ "$HAS_NPM" -eq 0 ]; then
  emit "$REPORT" 2
fi

# ---- Java（Maven）门 ----
run_backend() {
  local MVN; MVN=$(resolve mvn)
  if [ -z "$MVN" ]; then emit '{"overall":"fail","scope":"backend","project_type":"maven","warnings":["mvn 未安装"],"checks":[],"coverage":null}' 2; fi

  local checks='[]' cs_status=pass cs_detail="0 违规" cs_sug='[]'
  local sb_status=pass sb_detail="0 高危/中危" sb_sug='[]'
  local cov_status=pass cov_line=0 cov_threshold=${COV_BACKEND:-80} cov_hint=""
  local dep_status=pass dep_detail="无高危漏洞" dep_sug='[]'

  if ! "$MVN" checkstyle:check >/dev/null 2>&1; then
    cs_status=fail; cs_detail="checkstyle 违规（阿里 P3C）"; cs_sug='["运行 mvn checkstyle:check 查看违规明细并修复"]'
  fi
  if ! "$MVN" spotbugs:check >/dev/null 2>&1; then
    sb_status=fail; sb_detail="spotbugs 违规（高危/中危未清零）"; sb_sug='["运行 mvn spotbugs:check 查看明细并修复"]'
  fi
  # jacoco 行覆盖：从 target/site/jacoco/jacoco.csv 解析（缺文件按失败处理）
  # 列语义（JaCoCo CSV 标准布局 GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
  # BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...）：$8=LINE_MISSED $9=LINE_COVERED
  # （首行为表头，NR>1 跳过）
  if [ -f target/site/jacoco/jacoco.csv ]; then
    cov_line=$(awk -F',' 'NR>1 { im+=$8; ic+=$9 } END { if (ic+im>0) printf "%.1f", 100*ic/(ic+im); else print 0 }' target/site/jacoco/jacoco.csv)
    if ! awk -v l="$cov_line" -v t="$cov_threshold" 'BEGIN { exit !(l >= t) }'; then
      cov_status=fail; cov_hint="行覆盖率 ${cov_line}% 低于阈值 ${cov_threshold}%，补充单元测试后运行 mvn test jacoco:report 再检查"
    fi
  else
    cov_status=fail; cov_hint="缺 target/site/jacoco/jacoco.csv，先运行 mvn test jacoco:report 生成覆盖率报告"
  fi
  # Dependency-Check：与 CI 模板同命令（mvn dependency-check:check），保证"同命令同阈值"口径。
  # pom.xml 未配置插件时 mvn 报错退出非 0 → 门失败（安全门从严，指引中说明配置方法）
  if ! "$MVN" dependency-check:check >/dev/null 2>&1; then
    dep_status=fail; dep_detail="依赖漏洞扫描未通过或未配置"; dep_sug='["运行 mvn dependency-check:check 查看明细；若报插件缺失，在 pom.xml 配置 org.owasp:dependency-check-maven 插件"]'
  fi

  local overall=pass
  [ "$cs_status" = fail ] || [ "$sb_status" = fail ] || [ "$cov_status" = fail ] || [ "$dep_status" = fail ] && overall=fail

  checks=$(printf '[{"name":"checkstyle","status":"%s","details":"%s","suggestions":%s},{"name":"spotbugs","status":"%s","details":"%s","suggestions":%s},{"name":"dependency-check","status":"%s","details":"%s","suggestions":%s}]' \
    "$cs_status" "$(json_escape "$cs_detail")" "$cs_sug" \
    "$sb_status" "$(json_escape "$sb_detail")" "$sb_sug" \
    "$dep_status" "$(json_escape "$dep_detail")" "$dep_sug")
  printf '{"overall":"%s","scope":"backend","project_type":"maven","warnings":[%s],"checks":%s,"coverage":{"line":%s,"threshold":%s,"status":"%s","hint":"%s"}}' \
    "$overall" "${EXTRA_WARNINGS:-}" "$checks" "$cov_line" "$cov_threshold" "$cov_status" "$(json_escape "$cov_hint")"
}

# ---- Vue 门 ----
run_frontend() {
  local NPM; NPM=$(resolve npm)
  if [ -z "$NPM" ]; then emit '{"overall":"fail","scope":"frontend","project_type":"vue","warnings":["npm 未安装"],"checks":[],"coverage":null}' 2; fi

  # vue-tsc 解析：stub/PATH → npx 本地依赖回退（Vite+Vue 项目 vue-tsc 通常为本地 devDependency）。
  # 两者都不可用 → tsc 门记 fail（不得报 pass——与 mvn/npm 缺失口径一致，选 fail 而非 exit 2
  # 以保持单侧报告结构，checks 中明确给出"vue-tsc 不可用"详情）
  local VTS="" VTS_NPX="" NPX_BIN=""
  VTS=$(resolve vue-tsc)
  if [ -z "$VTS" ]; then
    if [ -n "$STUB_DIR" ] && [ -x "$STUB_DIR/npx" ]; then NPX_BIN="$STUB_DIR/npx"
    else NPX_BIN=$(command -v npx 2>/dev/null) || true; fi
    if [ -n "$NPX_BIN" ] && "$NPX_BIN" --no-install vue-tsc --version >/dev/null 2>&1; then
      VTS="vue-tsc"; VTS_NPX="$NPX_BIN"
    fi
  fi

  local lint_status=pass lint_detail="0 error" lint_sug='[]'
  local tsc_status=pass tsc_detail="类型检查" tsc_sug='[]'
  local audit_status=pass audit_detail="无高危" audit_sug='[]'
  local cov_status=pass cov_threshold=${COV_FRONTEND:-70} cov_hint=""
  local cov_line=0
  if [ -z "$VTS" ]; then tsc_status=fail; tsc_detail="vue-tsc 不可用"; tsc_sug='["npm i -D vue-tsc 安装后重跑"]'; fi
  if ! "$NPM" run lint >/dev/null 2>&1; then
    lint_status=fail; lint_detail="ESLint 违规"; lint_sug='["运行 npm run lint 查看明细并修复"]'
  fi
  if [ -n "$VTS" ]; then
    if [ -n "$VTS_NPX" ]; then
      "$VTS_NPX" --no-install vue-tsc --noEmit >/dev/null 2>&1 || { tsc_status=fail; tsc_detail="类型检查未通过"; tsc_sug='["运行 npx vue-tsc --noEmit 查看明细并修复"]'; }
    else
      "$VTS" --noEmit >/dev/null 2>&1 || { tsc_status=fail; tsc_detail="类型检查未通过"; tsc_sug='["运行 vue-tsc --noEmit 查看明细并修复"]'; }
    fi
  fi
  if ! "$NPM" audit --audit-level=high >/dev/null 2>&1; then
    audit_status=fail; audit_detail="存在高危依赖漏洞"; audit_sug='["运行 npm audit 查看明细并升级依赖"]'
  fi
  # Vitest 覆盖率：从 coverage/coverage-summary.json 读取 lines.pct
  if [ -f coverage/coverage-summary.json ]; then
    cov_line=$(node -e 'const s=JSON.parse(require("fs").readFileSync("coverage/coverage-summary.json","utf8")).total.lines.pct; process.stdout.write(String(Math.round(s*10)/10))' 2>/dev/null || echo 0)
    if ! awk -v l="$cov_line" -v t="$cov_threshold" 'BEGIN { exit !(l >= t) }'; then
      cov_status=fail; cov_hint="行覆盖率 ${cov_line}% 低于阈值 ${cov_threshold}%，补充单元测试后运行 npx vitest run --coverage 再检查"
    fi
  else
    cov_status=fail; cov_hint="缺 coverage/coverage-summary.json，先运行 npx vitest run --coverage 生成报告（需配置 json-summary reporter）"
  fi

  local overall=pass
  [ "$lint_status" = fail ] || [ "$tsc_status" = fail ] || [ "$audit_status" = fail ] || [ "$cov_status" = fail ] && overall=fail

  printf '{"overall":"%s","scope":"frontend","project_type":"vue","warnings":[%s],"checks":[{"name":"eslint","status":"%s","details":"%s","suggestions":%s},{"name":"vue-tsc","status":"%s","details":"%s","suggestions":%s},{"name":"npm-audit","status":"%s","details":"%s","suggestions":%s}],"coverage":{"line":%s,"threshold":%s,"status":"%s","hint":"%s"}}' \
    "$overall" "${EXTRA_WARNINGS:-}" "$lint_status" "$(json_escape "$lint_detail")" "$lint_sug" "$tsc_status" "$(json_escape "$tsc_detail")" "$tsc_sug" "$audit_status" "$(json_escape "$audit_detail")" "$audit_sug" "$cov_line" "$cov_threshold" "$cov_status" "$(json_escape "$cov_hint")"
}

case "$SCOPE" in
  backend)
    [ "$HAS_POM" -eq 1 ] || emit '{"overall":"fail","scope":"backend","project_type":"none","warnings":["当前目录无 pom.xml"],"checks":[],"coverage":null}' 2
    capture run_backend ;;
  frontend)
    [ "$HAS_NPM" -eq 1 ] || emit '{"overall":"fail","scope":"frontend","project_type":"none","warnings":["当前目录无 package.json"],"checks":[],"coverage":null}' 2
    capture run_frontend ;;
  all)
    case "$HAS_POM$HAS_NPM" in
      10) capture run_backend ;;
      01) capture run_frontend ;;
      11) # 前后端同仓：两侧都跑，合并输出（见 Interfaces 的 both 形态）
          B=$(run_backend); B_RC=$?
          F=$(run_frontend); F_RC=$?
          B_OK=$(echo "$B" | grep -q '"overall":"pass"' && echo 1 || echo 0)
          F_OK=$(echo "$F" | grep -q '"overall":"pass"' && echo 1 || echo 0)
          OV=fail; [ "$B_OK" = 1 ] && [ "$F_OK" = 1 ] && OV=pass
          REPORT=$(printf '{"overall":"%s","scope":"all","project_type":"both","warnings":[],"backend":%s,"frontend":%s}' "$OV" "$B" "$F")
          # 子门环境/配置错误（内部 emit exit 2）在 $() 中被捕获为 JSON，此处按契约转发退出码 2；
          # 与单侧路径 capture 的 rc 转发一致（emit 输出的 JSON 必为 overall=fail，故合并报告保持 overall=fail）
          if [ "$B_RC" -eq 2 ] || [ "$F_RC" -eq 2 ]; then emit "$REPORT" 2; fi ;;
      00) emit "$REPORT" 2 ;;
    esac ;;
esac

# 按 overall 决定退出码（默认值保证非 fail 即 0）
echo "$REPORT" | grep -q '"overall":"fail"' && emit "$REPORT" 1 || emit "$REPORT" 0
