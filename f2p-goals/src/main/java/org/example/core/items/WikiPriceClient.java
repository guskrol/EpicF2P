package org.example.core.items;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiPriceClient {
    private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String USER_AGENT = "EpicF2P price lookup (https://github.com/guskrol/EpicF2P)";
    private static final long MAPPING_CACHE_MILLIS = 12L * 60L * 60L * 1000L;
    private static final long LATEST_CACHE_MILLIS = 60_000L;

    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern LATEST_ITEM_PATTERN = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{([^{}]*)}");

    private static Map<String, Integer> itemIdsByName = new HashMap<>();
    private static Map<Integer, Price> latestPricesById = new HashMap<>();
    private static long mappingFetchedAt;
    private static long latestFetchedAt;

    private WikiPriceClient() {
    }

    public static Price latest(String itemName) {
        int itemId = itemIdFor(itemName);
        if (itemId <= 0) {
            return null;
        }
        return latestPrices().get(itemId);
    }

    private static synchronized int itemIdFor(String itemName) {
        refreshMappingIfNeeded();
        Integer itemId = itemIdsByName.get(normalize(itemName));
        return itemId == null ? -1 : itemId;
    }

    private static synchronized Map<Integer, Price> latestPrices() {
        refreshLatestIfNeeded();
        return latestPricesById;
    }

    private static void refreshMappingIfNeeded() {
        long now = System.currentTimeMillis();
        if (!itemIdsByName.isEmpty() && now - mappingFetchedAt < MAPPING_CACHE_MILLIS) {
            return;
        }

        try {
            Map<String, Integer> parsed = parseMapping(fetch(MAPPING_URL));
            if (!parsed.isEmpty()) {
                itemIdsByName = parsed;
                mappingFetchedAt = now;
            }
        } catch (RuntimeException ignored) {
            // Keep stale cache when the Wiki is unreachable.
        }
    }

    private static void refreshLatestIfNeeded() {
        long now = System.currentTimeMillis();
        if (!latestPricesById.isEmpty() && now - latestFetchedAt < LATEST_CACHE_MILLIS) {
            return;
        }

        try {
            Map<Integer, Price> parsed = parseLatest(fetch(LATEST_URL));
            if (!parsed.isEmpty()) {
                latestPricesById = parsed;
                latestFetchedAt = now;
            }
        } catch (RuntimeException ignored) {
            // Keep stale cache when the Wiki is unreachable.
        }
    }

    private static String fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Wiki price API returned HTTP " + status);
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("Could not fetch Wiki price API", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static Map<String, Integer> parseMapping(String json) {
        Map<String, Integer> result = new HashMap<>();
        Matcher matcher = OBJECT_PATTERN.matcher(json == null ? "" : json);
        while (matcher.find()) {
            String object = matcher.group(1);
            Long id = longField(object, "id");
            String name = stringField(object, "name");
            if (id != null && id > 0 && name != null && !name.isBlank()) {
                result.put(normalize(unescapeJson(name)), id.intValue());
            }
        }
        return result;
    }

    private static Map<Integer, Price> parseLatest(String json) {
        Map<Integer, Price> result = new HashMap<>();
        Matcher matcher = LATEST_ITEM_PATTERN.matcher(json == null ? "" : json);
        while (matcher.find()) {
            int itemId = Integer.parseInt(matcher.group(1));
            String object = matcher.group(2);
            result.put(itemId, new Price(
                    positiveInt(longField(object, "high")),
                    positiveInt(longField(object, "low")),
                    positiveLong(longField(object, "highTime")),
                    positiveLong(longField(object, "lowTime"))
            ));
        }
        return result;
    }

    private static String stringField(String object, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(object);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Long longField(String object, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
                + "\"\\s*:\\s*(null|\\d+)").matcher(object);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

    private static int positiveInt(Long value) {
        return value == null || value <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static long positiveLong(Long value) {
        return value == null || value <= 0L ? 0L : value;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/");
    }

    private static String normalize(String itemName) {
        if (itemName == null) {
            return "";
        }
        return itemName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static final class Price {
        private final int high;
        private final int low;
        private final long highTime;
        private final long lowTime;

        private Price(int high, int low, long highTime, long lowTime) {
            this.high = high;
            this.low = low;
            this.highTime = highTime;
            this.lowTime = lowTime;
        }

        public int buyReference() {
            return high > 0 ? high : low;
        }

        public int sellReference() {
            return low > 0 ? low : high;
        }

        public int high() {
            return high;
        }

        public int low() {
            return low;
        }

        public long highTime() {
            return highTime;
        }

        public long lowTime() {
            return lowTime;
        }
    }
}
