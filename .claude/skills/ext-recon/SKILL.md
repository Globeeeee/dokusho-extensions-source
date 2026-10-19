---
name: ext-recon
description: Scout a novel site before writing an extension - fetch listing, search, book and chapter pages, find selectors, pagination, Cloudflare, JS rendering, paywall markers, and write a recon note. Use when Qasim names a site, says recon, inspect site, check if we can support, or before /ext-new.
---

# Site recon

Read-only. No extension code yet. Output is a short note that `/ext-new` consumes.

## Do

1. Fetch into the scratchpad, never into context:
   `curl -sL -A "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile" -o $S/home.html -w "%{http_code} %{url_effective}\n" <baseUrl>`
   Repeat for: a popular/ranking page, a search URL, one book page, one chapter page.
2. Classify each response: 200 HTML · Cloudflare challenge (`Just a moment`, `cf-mitigated`) ·
   Turnstile · JS app (empty body, `__NEXT_DATA__`, `window.__NUXT__`) · JSON API.
3. From the HTML find with grep: book card selector, title, cover, URL pattern · pagination
   (`?page=`, `rel=next`, AJAX endpoint) · chapter list container and whether it paginates ·
   chapter content container · junk inside it (ads, watermark spans, footers) · paywall or
   login markers (`locked`, `premium`, `coins`, 401/403).
4. Check robots.txt and rate: note `Crawl-delay` if present; propose `rateLimit`.
5. Write `docs/recon/<domain>.md`:

```markdown
# <domain> (YYYY-MM-DD)
Reach: 200 plain | Cloudflare | JS | API
Listing: <url pattern> · card `<sel>` · title `<sel>` · cover `<sel>` · next `<sel>`
Search: <url pattern>
Book: title `<sel>` · author · status · genres · description · chapters `<sel>` (paginated? how)
Chapter: content `<sel>` · strip `<sel, sel>` · title `<sel>`
Account: none | login needed for <what> · locked marker `<sel>`
Rate: rateLimit(<n>, <period>)
Theme fit: standalone | <existing theme> | new theme candidate with <other domain>
Blockers: ...
```

6. Report the note path, a go / no-go, and the theme fit.

## Do not

- Fetch more than one chapter per site, or hammer a site
- Fetch anything behind a login
- Paste raw HTML or long listings into the chat
- Start writing the extension
