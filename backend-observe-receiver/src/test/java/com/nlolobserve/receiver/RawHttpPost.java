package com.nlolobserve.receiver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T06 测试脚手架：原始 socket 的 HTTP/1.1 POST（Content-Length 口径，Connection: close）。
 *
 * 为何不用 java.net.http.HttpClient 发送超大 body：服务端按 Content-Length 预检拒绝（413）
 * 且不再读取 body 时，HttpClient 会阻塞在 body 写入上（连接未被排空 → TCP 窗口耗尽 →
 * send() 永久挂起，实测挂死整个 surefire JVM）。本工具把 body 写入放到独立写线程
 * （分块写入并受 stop 标志约束，响应已读回后停止写入，减少 RST 竞态），主线程并发读响应
 * （跳过响应头、soTimeout 兜底、状态行之后容忍连接被服务端关闭导致的 ECONNRESET）。
 */
final class RawHttpPost {

    private RawHttpPost() {
        // 测试脚手架，禁止实例化
    }

    /** 响应：状态码 + 响应体（已剥离响应头）。 */
    record Result(int status, String body) {
    }

    /**
     * 向指定端口/路径 POST 给定 body（Content-Length 语义），解析并返回状态码与响应体。
     *
     * @throws IOException            连接失败、状态行前读超时，或响应在状态行前被截断
     * @throws InterruptedException 等待写线程收尾被中断
     */
    static Result post(int port, String path, byte[] body) throws IOException, InterruptedException {
        String headers = "POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] head = headers.getBytes(StandardCharsets.US_ASCII);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<IOException> readError = new AtomicReference<>();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            Thread writer = new Thread(() -> {
                try {
                    OutputStream out = socket.getOutputStream();
                    out.write(head);
                    // 分块写入：响应读回后（stop）或连接被服务端关闭（IOException）即停止
                    int chunk = 256 * 1024;
                    for (int off = 0; off < body.length && !stop.get(); off += chunk) {
                        out.write(body, off, Math.min(chunk, body.length - off));
                    }
                    out.flush();
                } catch (IOException e) {
                    // 服务端预检拒绝（413/403/429）后提前断开连接——预期内，忽略
                }
            });
            writer.setDaemon(true);
            writer.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                throw new IOException("服务端在响应前关闭了连接：" + path);
            }
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            // 跳过响应头（到空行为止），只保留响应体
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // header
            }
            StringBuilder bodyBuf = new StringBuilder();
            try {
                while ((line = reader.readLine()) != null) {
                    bodyBuf.append(line);
                }
            } catch (IOException e) {
                // 状态行已读回后服务端关闭连接（Connection: close + 未读体 → RST）：
                // 已读到的响应体视为完整（拒绝响应体极小，与状态行同段送达）
                readError.set(e);
            }
            stop.set(true);
            // try-with-resources 关闭 socket，解除写线程可能阻塞的写入
            writer.join(5_000);
            if (readError.get() != null && bodyBuf.length() == 0) {
                // 响应体完全丢失：无法验证统一错误码，按 IO 失败暴露
                throw new IOException("响应体在连接断开中丢失：" + path, readError.get());
            }
            return new Result(status, bodyBuf.toString());
        }
    }
}
