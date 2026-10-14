package com.nlolobserve.poc.receiver;

import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T01 验收 #1/#2（Plan A 探针）：确认 io.mishmash.opentelemetry:collector-embedded:1.2.1
 * 在 classpath 上、JDK17 可加载，并导出其公开 API（类/方法签名），
 * 用于确认「导出失败可观测钩子」（回调/listener/异常计数点）。
 *
 * 该探针不依赖未知 API 的编译期符号：通过 classpath 扫描 + 反射打印签名，
 * 结果写入 target/collector-embedded-api.txt 供 poc-notes.md 结论引用。
 * 若 jar 不可解析（本环境 Maven Central 不可达），测试跳过并在报告中标注。
 */
class CollectorEmbeddedProbeTest {

    @Test
    void probeCollectorEmbeddedApiSurface() throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        Enumeration<URL> urls = cl.getResources("");
        Path out = Path.of("target");
        Files.createDirectories(out);

        StringBuilder report = new StringBuilder();
        boolean found = false;
        while (urls.hasMoreElements()) {
            URL u = urls.nextElement();
            if (!"file".equals(u.getProtocol())) {
                continue;
            }
            Path dir = Path.of(u.getPath());
            List<Path> jars;
            try (var s = Files.list(dir)) {
                jars = s.filter(p -> p.getFileName().toString().contains("collector-embedded"))
                        .collect(Collectors.toList());
            }
            for (Path jar : jars) {
                found = true;
                try (JarFile jf = new JarFile(jar.toFile())) {
                    var entries = jf.stream()
                            .filter(e -> e.getName().endsWith(".class"))
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith("io/mishmash"))
                            .collect(Collectors.toList());
                    report.append("JAR: ").append(jar).append('\n');
                    report.append("classes(io/mishmash): ").append(entries.size()).append('\n');
                    for (String name : entries) {
                        String cn = name.substring(0, name.length() - 6).replace('/', '.');
                        try {
                            Class<?> c = Class.forName(cn, false, cl);
                            report.append("== ").append(cn).append('\n');
                            for (var m : c.getDeclaredMethods()) {
                                report.append("   ").append(m).append('\n');
                            }
                            for (var ctor : c.getDeclaredConstructors()) {
                                report.append("   ctor ").append(ctor).append('\n');
                            }
                        } catch (Throwable t) {
                            report.append("== ").append(cn).append(" LOAD_FAIL: ").append(t).append('\n');
                        }
                    }
                }
            }
        }
        // 兜底：maven 依赖 jar 在 ~/.m2 而非 classpath 根目录列举时
        if (!found) {
            Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository",
                    "io", "mishmash", "opentelemetry", "collector-embedded");
            assumeTrue(Files.isDirectory(m2),
                    "collector-embedded jar 不在 classpath 且 ~/.m2 无该坐标 —— 本环境 Maven Central 不可达，探针待有仓库环境重跑");
            try (var s = Files.walk(m2, 3)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".jar")).collect(Collectors.toList())) {
                    found = true;
                    report.append("M2 JAR: ").append(p).append('\n');
                }
            }
        }
        assertThat(found).as("应能找到 collector-embedded jar").isTrue();
        Files.writeString(out.resolve("collector-embedded-api.txt"), report.toString());
    }
}
