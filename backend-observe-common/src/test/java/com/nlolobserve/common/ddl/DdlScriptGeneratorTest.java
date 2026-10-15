package com.nlolobserve.common.ddl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T03 验收：scripts/databend/001-init.sql 由 DdlScripts 生成（单一事实来源 → 文件产物），
 * 仓库内文件与 DdlScripts.initScript() 保持同步。
 */
class DdlScriptGeneratorTest {

    @Test
    void generateWritesInitScriptCreatingParentDirs(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("nested/dir/001-init.sql");
        DdlScriptGenerator.generate(out);
        assertEquals(DdlScripts.initScript(), Files.readString(out));
    }

    @Test
    void mainWritesScriptToPathGivenAsArg(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("001-init.sql");
        DdlScriptGenerator.main(new String[] {out.toString()});
        assertEquals(DdlScripts.initScript(), Files.readString(out));
    }

    @Test
    void repoInitSqlExistsAndIsInSyncWithDdlScripts() throws IOException {
        Path sql = TestRepoPaths.repoRoot().resolve(DdlScriptGenerator.DEFAULT_RELATIVE_PATH);
        assertTrue(Files.exists(sql),
                "缺少 " + DdlScriptGenerator.DEFAULT_RELATIVE_PATH + "：运行 DdlScriptGenerator 生成后提交");
        assertEquals(DdlScripts.initScript(), Files.readString(sql),
                "001-init.sql 与 DdlScripts.initScript() 不同步：重新运行 DdlScriptGenerator 生成");
    }
}
