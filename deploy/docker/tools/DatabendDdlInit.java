/**
 * Databend DDL 初始化（deploy/docker 一次性 init 服务）：
 * 先 CREATE DATABASE IF NOT EXISTS observe（001-init.sql 无建库语句，
 * 而 storage/query 的 JDBC URL 指向 /observe 库），再经 HTTP 查询 API
 * 逐条执行 DDL 文件（去行注释、按 ';' 切分）。任一语句报错即非零退出。
 *
 * 运行：build.sh 用宿主 JDK 预编译到 tools/classes/，容器内
 *      java -cp /app DatabendDdlInit（temurin 17-jre 无 jdk.compiler，不能源码直跑）
 * 环境变量：DATABEND_HTTP（默认 http://databend:8000）、DATABEND_USER（默认 root）、
 *          DATABEND_PASSWORD（默认空）、DDL_FILE（默认 /sql/001-init.sql）
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabendDdlInit {

    private static final Pattern ERR_FIELD = Pattern.compile("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static void main(String[] args) throws Exception {
        String base = env("DATABEND_HTTP", "http://databend:8000");
        String user = env("DATABEND_USER", "root");
        String pass = env("DATABEND_PASSWORD", "");
        String ddlFile = env("DDL_FILE", "/sql/001-init.sql");

        HttpClient http = HttpClient.newHttpClient();
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());

        execute(http, base, auth, "CREATE DATABASE IF NOT EXISTS observe");

        String ddl = Files.readString(Path.of(ddlFile));
        StringBuilder sb = new StringBuilder();
        for (String line : ddl.split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        for (String part : sb.toString().split(";")) {
            String sql = part.trim();
            if (sql.isEmpty()) {
                continue;
            }
            // /v1/query 单请求只执行一条语句（多语句会被截断）且新会话默认库是
            // default——表名必须显式限定 observe.，否则落进 default 库
            String qualified = sql.replaceFirst(
                    "(?i)^(CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+)(\\w+)", "$1observe.$2");
            execute(http, base, auth, qualified);
        }
        System.out.println("DDL init done: " + ddlFile);
    }

    private static void execute(HttpClient http, String base, String auth, String sql) throws Exception {
        String url = base.replaceAll("/$", "") + "/v1/query";
        // Databend 查询 API 要求 JSON 体 {"sql": "..."}（text/plain 会 415）
        String body = "{\"sql\":\"" + jsonEscape(sql) + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            fail("HTTP " + resp.statusCode() + " 执行: " + firstLine(sql), resp.body());
        }
        String respBody = resp.body() == null ? "" : resp.body();
        Matcher m = ERR_FIELD.matcher(respBody);
        if (m.find() && !m.group(1).isEmpty()) {
            fail("Databend 错误执行: " + firstLine(sql), m.group(1));
        }
        System.out.println("OK: " + firstLine(sql));
    }

    private static String firstLine(String s) {
        return s.lines().findFirst().orElse(s);
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? dflt : v;
    }

    private static void fail(String msg, String detail) {
        System.err.println(msg + " -> " + (detail == null ? "" : detail));
        System.exit(1);
    }
}
