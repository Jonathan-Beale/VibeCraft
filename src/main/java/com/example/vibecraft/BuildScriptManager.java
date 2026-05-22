package com.example.vibecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;

public class BuildScriptManager {

    private final File serverDir;
    private final String serverPluginsDir;

    public BuildScriptManager(File serverDir, String serverPluginsDir) {
        this.serverDir = serverDir;
        this.serverPluginsDir = serverPluginsDir;
    }

    public File getServerDir() { return serverDir; }

    /** Regenerates all per-plugin scripts and build-all.bat from the current path list. */
    public void regenerate(List<String> pluginPaths) {
        for (String path : pluginPaths) {
            writePluginScript(new File(path));
        }
        writeAllScript(pluginPaths);
    }

    public void addPlugin(File projectDir) {
        writePluginScript(projectDir);
    }

    // TODO: Linux code path is dead — investigate or remove
    private void writePluginScript(File projectDir) {
        String name = projectDir.getName();
        String dataDir = serverPluginsDir + name + "\\";
        File script = new File(serverDir, "build-" + name + ".bat");
        try (PrintWriter w = new PrintWriter(new FileWriter(script))) {
            w.println("@echo off");
            w.println("echo [VibeCraft] Building " + name + "...");
            w.println("cd /d \"" + projectDir.getAbsolutePath() + "\"");
            w.println("call gradlew.bat build");
            w.println("if %errorlevel% neq 0 ( echo [VibeCraft] Build FAILED & exit /b 1 )");
            // Copy all jars from build/libs except sources/javadoc
            w.println("for %%f in (build\\libs\\*.jar) do (");
            w.println("  echo %%f | findstr /V /i \"sources javadoc\" >nul");
            w.println("  if not errorlevel 1 copy /Y \"%%f\" \"" + serverPluginsDir + "\"");
            w.println(")");
            // Recursively mirror src/main/resources → plugin data dir, excluding plugin.yml
            // robocopy exits 0-7 for success (0=nothing to do, 1=copied, etc.), 8+ = error
            w.println("if exist \"src\\main\\resources\" (");
            w.println("  robocopy \"src\\main\\resources\" \"" + dataDir + "\" /E /XF plugin.yml >nul");
            w.println("  if %errorlevel% gtr 7 ( echo [VibeCraft] Resource copy FAILED & exit /b 1 )");
            w.println(")");
            w.println("echo [VibeCraft] Deployed " + name + ".");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeAllScript(List<String> pluginPaths) {
        File script = new File(serverDir, "build-all.bat");
        try (PrintWriter w = new PrintWriter(new FileWriter(script))) {
            w.println("@echo off");
            w.println("echo [VibeCraft] Building all plugins...");
            for (String path : pluginPaths) {
                String name = new File(path).getName();
                w.println("call \"" + serverDir.getAbsolutePath() + "\\build-" + name + ".bat\"");
                w.println("if %errorlevel% neq 0 ( echo [VibeCraft] " + name + " failed & exit /b 1 )");
            }
            w.println("echo [VibeCraft] All plugins built and deployed.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
