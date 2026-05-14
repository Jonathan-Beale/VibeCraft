package com.example.vibecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Collects UI schema fragments from installed plugins and merges them into a
 * single schema sent to the VibeCraft mod.
 */
public final class PluginUIRegistry {

    private final PluginManager pluginManager;

    public PluginUIRegistry(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public JsonObject buildMergedSchema(File vibecraftDataFolder) {
        JsonObject merged = readSchema(new File(vibecraftDataFolder, "ui/main.json"));
        if (merged == null) {
            merged = new JsonObject();
        }

        JsonArray mergedScreens = ensureArray(merged, "screens");
        JsonArray mergedOverlays = ensureArray(merged, "overlays");

        for (Plugin p : pluginManager.getPlugins()) {
            if (!p.isEnabled()) continue;
            if ("VibeCraft".equalsIgnoreCase(p.getName())) continue;

            File schemaFile = new File(p.getDataFolder(), "ui/main.json");
            JsonObject pluginSchema = readSchema(schemaFile);
            if (pluginSchema == null) continue;

            appendArrayWithPlugin(mergedScreens, pluginSchema.getAsJsonArray("screens"), p.getName());
            appendArrayWithPlugin(mergedOverlays, pluginSchema.getAsJsonArray("overlays"), p.getName());
        }

        return merged;
    }

    private static JsonObject readSchema(File schemaFile) {
        if (!schemaFile.exists()) return null;
        try {
            String raw = Files.readString(schemaFile.toPath(), StandardCharsets.UTF_8);
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray ensureArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            JsonArray arr = new JsonArray();
            obj.add(key, arr);
            return arr;
        }
        return obj.getAsJsonArray(key);
    }

    private static void appendArrayWithPlugin(JsonArray target, JsonArray source, String pluginName) {
        if (source == null) return;
        for (JsonElement e : source) {
            if (e.isJsonObject()) {
                JsonObject obj = e.getAsJsonObject();
                obj.addProperty("plugin", pluginName);
                target.add(obj);
            }
        }
    }
}
