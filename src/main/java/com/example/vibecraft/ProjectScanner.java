package com.example.vibecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ProjectScanner {

    /**
     * Scans the workspace directory for subdirectories that look like Java plugin
     * projects (contain gradlew.bat or pom.xml), excluding the VibeCraft plugin itself.
     */
    public static List<File> findProjects(File workspaceDir, File selfDir) {
        List<File> results = new ArrayList<>();
        File[] children = workspaceDir.listFiles();
        if (children == null) return results;
        for (File f : children) {
            if (!f.isDirectory()) continue;
            if (f.equals(selfDir)) continue;
            if (new File(f, "gradlew.bat").exists() || new File(f, "pom.xml").exists()) {
                results.add(f);
            }
        }
        return results;
    }
}
