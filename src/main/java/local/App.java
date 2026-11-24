import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class App {

   // record SearchResult(String title, String url, String snippet) {}
 
    private static final Scraper SCRAPER = new Scraper();
    private static List<SearchResult> performSearch(String query) {
       if (query.isBlank()) return List.of();
       return SCRAPER.search(query);
}; 
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Serve home page
        server.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                serveResource(exchange, "/public/index.html", "text/html");
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });

        // Serve CSS
        server.createContext("/style.css", exchange -> 
            serveResource(exchange, "/public/style.css", "text/css")
        );

        // Handle search queries
        server.createContext("/search", App::handleSearch);

        //server.setExecutor(null); //replaced
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Search UI running at http://localhost:8080");
    }

    private static void handleSearch(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String query = "";
        if (rawQuery != null && rawQuery.startsWith("q=")) {
            query = URLDecoder.decode(rawQuery.substring(2), StandardCharsets.UTF_8);
        }

        // Replace this mock list with your scraper / database / index lookup
        List<SearchResult> results = performSearch(query);

        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>%s - Search</title>
                <link rel="stylesheet" href="/style.css">
            </head>
            <body>
                <main class="container">
                    <form action="/search" method="GET" class="search-form">
                        <input type="text" name="q" value="%s" required>
                        <button type="submit">Search</button>
                    </form>
            """.formatted(escapeHtml(query), escapeHtml(query)));

        if (results.isEmpty()) {
            html.append("<p>No results found.</p>");
        } else {
            for (SearchResult r : results) {
                html.append("""
                    <div class="result-item">
                        <a class="result-title" href="%s">%s</a>
                        <div class="result-url">%s <span class="result-engine">[%s]</span></div>
                        <p class="result-snippet">%s</p>
                    </div>
                    """.formatted(
                        escapeHtml(r.url()),
                        escapeHtml(r.title()),
                        escapeHtml(r.url()),
                        escapeHtml(r.snippet())
                    ));
            }
        }

        html.append("</main></body></html>");

        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static List<SearchResult> performSearch(String query) {
        // Stub implementation — wire your backend search/mining logic here
        return List.of(
            new SearchResult(
                "Result for: " + query,
                "https://example.org/docs",
                "Deterministic match retrieved from local index or upstream aggregator."
            ),
            new SearchResult(
                "ArchWiki - Suckless & Minimalist Tools",
                "https://wiki.archlinux.org",
                "Documentation for lightweight, terminal-first software and window managers."
            )
        );
    }

    private static void serveResource(HttpExchange exchange, String path, String contentType) throws IOException {
        try (InputStream is = App.class.getResourceAsStream(path)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
