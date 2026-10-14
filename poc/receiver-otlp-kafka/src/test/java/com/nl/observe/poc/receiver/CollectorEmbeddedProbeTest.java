package com.nl.observe.poc.receiver;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 扫描策略（修正版）：Maven 依赖 jar 不在 classpath 目录根列举中（cl.getResources("")
 * 只返回 target/classes 等目录），因此直接解析 java.class.path 中的 jar 条目；
 * 若 jar 未上 classpath（如 optional 依赖未参与打包），兜底扫 ~/.m2 并用
 * URLClassLoader 加载后反射 dump——两条路径都产出真实 API 签名到
 * target/collector-embedded-api.txt。jar 完全不可得时测试跳过（本环境 Maven Central 不可达）。
 */
class CollectorEmbeddedProbeTest {

    @Test
    void probeCollectorEmbeddedApiSurface() throws IOException {
        Path out = Path.of("target");
        Files.createDirectories(out);
        StringBuilder report = new StringBuilder();

        List<Path> jars = findCollectorJars();
        assumeTrue(!jars.isEmpty(),
                "collector-embedded jar 不在 classpath 且 ~/.m2 无该坐标 —— 本环境 Maven Central 不可达，探针待有仓库环境重跑");

        ClassLoader parent = getClass().getClassLoader();
        for (Path jar : jars) {
            report.append("JAR: ").append(jar).append('\n');
            dumpJar(jar, parent, report);
        }
        Files.writeString(out.resolve("collector-embedded-api.txt"), report.toString());
        assertThat(report).as("探针应至少 dump 一个 jar 的 API 面")
                .contains("classes(io/mishmash)");
    }

    /** 先扫 java.class.path，再兜底扫 ~/.m2 坐标目录，去重返回候选 jar。 */
    private List<Path> findCollectorJars() {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(
                System.getProperty("path.separator"))) {
            Path p = Path.of(entry);
            if (p.getFileName().toString().contains("collector-embedded")
                    && entry.endsWith(".jar") && Files.isRegularFile(p)) {
                jars.add(p);
            }
        }
        if (jars.isEmpty()) {
            Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository",
                    "io", "mishmash", "opentelemetry", "collector-embedded");
            if (Files.isDirectory(m2)) {
                try (var s = Files.walk(m2, 3)) {
                    jars = s.filter(p -> p.toString().endsWith(".jar"))
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    // 兜底扫描失败不致命：保持空列表，由 assumeTrue 跳过
                }
            }
        }
        return jars;
    }

    /** 枚举 jar 内 io/mishmash/** 类，用独立 URLClassLoader 加载并 dump 方法/构造器签名。 */
    private void dumpJar(Path jar, ClassLoader parent, StringBuilder report) throws IOException {
        List<String> entries;
        try (JarFile jf = new JarFile(jar.toFile())) {
            entries = jf.stream()
                    .filter(e -> e.getName().endsWith(".class"))
                    .map(JarEntry::getName)
                    .filter(n -> n.startsWith("io/mishmash"))
                    .collect(Collectors.toList());
        }
        report.append("classes(io/mishmash): ").append(entries.size()).append('\n');
        try (URLClassLoader ul = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, parent)) {
            for (String name : entries) {
                String cn = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(cn, false, ul);
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
