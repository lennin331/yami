
# Yet Another Miner  on the Internet

 
Search aggregator with caching and mining utilities, suggestively with faster and relevant data frames.
 
### Architecture
 
```mermaid
flowchart TD
  UI["Search Page"]
 
    subgraph APP[" "]
        C["Controller"] --> S["Search Service"]
         ~~~ E
        S -->|" cache miss "| SC["Scraper"]
        SC --> P["Clean, Dedupe, Rank"]
        S -.->|" enqueue "| M["Background Miner"]
    end
 
    UI --> C
    SC --> E["Search Engines"]
    S <-->|"cache hit"| DB[("PostgreSQL<br/>cached and mined data")]
    P --> DB
    M <--> DB 

    style APP fill:none, stroke-dasharray: 5 5
    style DB fill:#e2e8f0, stroke:#475569, color:#0f172a
```
 
- New query goes to the scraper, which fetches from [mentioned](https://example.com) search engines.
- Results are ranked and stored in postgreSQL.
- The background miner keeps drilling down the stored data, branching until met the cache quote.
- Follow-up queries are answered from the database first given the new scraped clump gets a lower ranking.

**Stack:** Java, Spring Boot, Maven, Hibernate, PostgreSQL, Jsoup, Playwright

> To Add:  Ranking mechanism, Anti Bot routes, Testing tools.

### Docs
- `mvn compile exec:java`
