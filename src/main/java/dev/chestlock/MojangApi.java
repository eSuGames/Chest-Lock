package dev.chestlock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mojang API による MCID の存在確認。
 * 判定できなかった場合は Result として null を返す。
 */
public final class MojangApi {

    /** exists=true で存在する。どちらでもない「判定不能」は null で表す。 */
    public record Result(boolean exists, UUID uuid) {
    }

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private MojangApi() {
    }

    /**
     * MCID が Minecraft に存在するかを非同期で確認する。
     * 戻り値の Result が null の場合は判定不能（オフライン等）。
     */
    public static CompletableFuture<Result> lookup(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Matcher matcher = ID_PATTERN.matcher(response.body());
                    UUID uuid = matcher.find() ? fromUndashed(matcher.group(1)) : null;
                    return new Result(true, uuid);
                }
                if (response.statusCode() == 404) {
                    return new Result(false, null);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static UUID fromUndashed(String id) {
        try {
            return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                    + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
