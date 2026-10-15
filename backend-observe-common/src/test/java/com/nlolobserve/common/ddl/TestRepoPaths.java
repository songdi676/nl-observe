package com.nlolobserve.common.ddl;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试辅助：从 surefire 工作目录（模块目录）向上定位仓库根（含根 pom.xml 与 scripts/ 的目录）。
 */
final class TestRepoPaths {

    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("scripts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到仓库根（自 " + System.getProperty("user.dir") + " 向上）");
    }

    private TestRepoPaths() {
    }
}
