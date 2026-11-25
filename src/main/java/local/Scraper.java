package local;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.HttpStatusException;

public class Scraper {

    private static final String UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int FETCH_TIMEOUT_MS = 6000;
    private static final int PER_ENGINE_LIMIT = 10;

    private record Engine(String name, String template) {}

    // CSS selectors per engine: item container, link, snippet
    private record Selectors(String item, String link, String snippet) {}

    private static final Map<String, Selectors> SELECTORS = Map.of(
        "duckduckgo", new Selectors("div.result", "a.result__a", ".result__snippet"),
        "google",     new Selectors("div.g", "a:has(h3)", "div.VwiC3b"),
        "yandex",     new Selectors("li.serp-item", "a.OrganicTitle-Link", ".OrganicText")
    );

    private final List<Engine> engines = loadEngines();
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "scraper");
        t.setDaemon(true);
        return t;
    });

    public List<SearchResult> search(String query) {
        List<Future<List<SearchResult>>> futures = new ArrayList<>();
        for (Engine e : engines) {
            futures.add(pool.submit(() -> scrape(e, query)));
        }

        List<List<SearchResult>> perEngine = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                perEngine.add(futures.get(i).get(FETCH_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS));
            } catch (Exception ex) {
                System.err.println("[scraper] " + engines.get(i).name() + " failed: " + ex.getMessage());
                perEngine.add(List.of());
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            String name = engines.get(i).name();
            try {
                List<SearchResult> got = futures.get(i).get(FETCH_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
                System.out.println("[scraper] " + name + " -> " + got.size() + " results");
                perEngine.add(got);
            } catch (Exception ex) {
                System.err.println("[scraper] " + name + " failed: " + ex.getMessage());
                perEngine.add(List.of());
            }
        }

        // Interleave engines (1st of each, then 2nd of each...) and dedupe by URL
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (int rank = 0; rank < PER_ENGINE_LIMIT; rank++) {
            for (List<SearchResult> list : perEngine) {
                if (rank < list.size()) {
                    merged.putIfAbsent(list.get(rank).url(), list.get(rank));
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<SearchResult> scrape(Engine e, String query) throws Exception {
        if (e.name().equals("serpapi")) return scrapeSerpApi(e, query);
        Selectors sel = SELECTORS.get(e.name());
        if (sel == null) return List.of();

        String url = e.template().replace("{q}", URLEncoder.encode(query, StandardCharsets.UTF_8));
        Document doc = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(FETCH_TIMEOUT_MS)
            .get();

        List<SearchResult> out = new ArrayList<>();
        for (Element item : doc.select(sel.item())) {
            Element a = item.selectFirst(sel.link());
            if (a == null) continue;

            String href = unwrapRedirect(a.absUrl("href"));
            if (!href.startsWith("http://") && !href.startsWith("https://")) continue;

            Element h3 = a.selectFirst("h3");
            String title = (h3 != null ? h3.text() : a.text()).trim();
            if (title.isEmpty()) continue;

            Element snip = item.selectFirst(sel.snippet());
            out.add(new SearchResult(title, href, snip != null ? snip.text().trim() : "", e.name()));
            if (out.size() >= PER_ENGINE_LIMIT) break;
        }
        return out;
    }
   // <- end of scrape(), line 103: don't add this, just paste below it

    private List<SearchResult> scrapeSerpApi(Engine e, String query) throws Exception {
        String key = Config.get("API_KEY");   // use System.getenv("SERPAPI_KEY") if you skipped Config.java
        if (key == null || key.isBlank()) {
            System.err.println("[scraper] serpapi skipped: API_KEY not set");
            return List.of();
        }

        String url = e.template()
            .replace("{q}", URLEncoder.encode(query, StandardCharsets.UTF_8))
            .replace("{key}", URLEncoder.encode(key, StandardCharsets.UTF_8));

        String body;
        try {
            body = Jsoup.connect(url)
                .userAgent(UA)
                .timeout(FETCH_TIMEOUT_MS)
                .ignoreContentType(true)   // response is JSON, not HTML
                .execute()
                .body();
        } catch (HttpStatusException ex) {
            // Don't rethrow the original: its message contains the URL, and so the API key
            throw new java.io.IOException("serpapi HTTP " + ex.getStatusCode());
        }

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error")) {
            throw new java.io.IOException("serpapi error: " + root.get("error").getAsString());
        }
        if (!root.has("organic_results")) return List.of();

        List<SearchResult> out = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("organic_results");
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("link") || !o.has("title")) continue;
            String link = o.get("link").getAsString();
            if (!link.startsWith("http://") && !link.startsWith("https://")) continue;
            String snippet = o.has("snippet") ? o.get("snippet").getAsString() : "";
            out.add(new SearchResult(o.get("title").getAsString(), link, snippet, "serpapi"));
            if (out.size() >= PER_ENGINE_LIMIT) break;
        }
        return out;
    }

    // DuckDuckGo wraps links as https://duckduckgo.com/l/?uddg=<encoded-real-url>&...
    private static String unwrapRedirect(String href) {
        int i = href.indexOf("uddg=");
        if (i < 0) return href;
        String v = href.substring(i + 5);
        int amp = v.indexOf('&');
        if (amp >= 0) v = v.substring(0, amp);
        return URLDecoder.decode(v, StandardCharsets.UTF_8);
    }

    private static List<Engine> loadEngines() {
        List<Engine> list = new ArrayList<>();
        try (InputStream is = Scraper.class.getResourceAsStream("/search_domain.txt")) {
            if (is == null) throw new IllegalStateException("search_domain.txt not on classpath");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int bar = line.indexOf('|');
                if (bar < 0) continue;
                list.add(new Engine(line.substring(0, bar).trim().toLowerCase(), line.substring(bar + 1).trim()));
            }
        } catch (Exception ex) {
            throw new RuntimeException("Could not load engines", ex);
        }
        return list;
    }
}
