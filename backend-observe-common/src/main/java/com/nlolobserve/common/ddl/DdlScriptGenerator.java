package com.nlolobserve.common.ddl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 建库脚本生成器（T03）：以 {@link DdlScripts#initScript()} 为唯一事实来源，
 * 生成/同步 scripts/databend/001-init.sql（运维在 Databend 侧执行该脚本建表）。
 * <p>
 * 用法（仓库根目录）：
 * <pre>mvn -q -pl backend-observe-common compile \
 *   && java -cp backend-observe-common/target/classes \
 *        com.nlolobserve.common.ddl.DdlScriptGenerator [输出路径]</pre>
 * 缺省输出路径为仓库根的 {@value #DEFAULT_RELATIVE_PATH}。
 */
public final class DdlScriptGenerator {

    /** 缺省输出路径（相对仓库根） */
    public static final String DEFAULT_RELATIVE_PATH = "scripts/databend/001-init.sql";

    /**
     * 将完整初始化脚本写入指定文件（必要时创建父目录）。
     *
     * @param outputFile 输出文件路径
     * @return 写入的文件路径
     * @throws IOException 写文件失败
     */
    public static Path generate(Path outputFile) throws IOException {
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputFile, DdlScripts.initScript(), StandardCharsets.UTF_8);
        return outputFile;
    }

    /**
     * 命令行入口：args[0] 为输出路径（可选，缺省 {@value #DEFAULT_RELATIVE_PATH}）。
     *
     * @param args 输出路径（可选）
     * @throws IOException 写文件失败
     */
    public static void main(String[] args) throws IOException {
        Path out = args.length > 0 ? Path.of(args[0]) : Path.of(DEFAULT_RELATIVE_PATH);
        generate(out);
        System.out.println("generated: " + out.toAbsolutePath());
    }

    private DdlScriptGenerator() {
    }
}
