package dev.yoshiro.versioneye;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Posts an update summary to a Discord webhook. Blocking; must be called
 * off the main server thread.
 */
final class DiscordWebhook {

    private static final int EMBED_COLOR = 0xFFAA00;

    private final ApiHttp http;

    DiscordWebhook(ApiHttp http) {
        this.http = http;
    }

    void sendUpdates(String webhookUrl, List<UpdateResult> outdated)
            throws IOException, InterruptedException {
        StringBuilder description = new StringBuilder();
        for (UpdateResult r : outdated) {
            if (!description.isEmpty()) {
                description.append('\n');
            }
            description.append("**").append(r.pluginName()).append("** ")
                    .append(r.installedVersion()).append(" -> ").append(r.latestVersion())
                    .append(" ([").append(r.source()).append("](").append(r.projectUrl()).append("))");
        }

        JsonObject embed = new JsonObject();
        embed.addProperty("title", outdated.size() == 1
                ? "1 plugin update available"
                : outdated.size() + " plugin updates available");
        embed.addProperty("description", description.toString());
        embed.addProperty("color", EMBED_COLOR);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "VersionEye");
        payload.add("embeds", embeds);

        // Not idempotent: a retried webhook post could announce twice.
        http.post(webhookUrl, payload.toString(), false);
    }
}
