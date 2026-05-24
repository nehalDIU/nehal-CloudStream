import urllib.request
import re
import urllib.parse

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

def clean_title(title):
    t = title.lower()
    t = re.sub(r'(?:season|ss)\s*\d+', '', t)
    t = re.sub(r'\d+(?:st|nd|rd|th)\s*season', '', t)
    t = re.sub(r'\s*\(dub\)', '', t)
    t = re.sub(r'\s*\(sub\)', '', t)
    t = re.sub(r'[^a-z0-9\s]', '', t)
    return " ".join(t.split())

def parse_season_number(title):
    t = title.lower()
    m = re.search(r'(?:season|ss)\s*(\d+)', t)
    if m:
        return int(m.group(1))
    m = re.search(r'(\d+)(?:st|nd|rd|th)\s*season', t)
    if m:
        return int(m.group(1))
    return 1

# Let's search for "spy x family"
query = "spy x family"
search_url = f"https://aniwatch.co.at/?s={urllib.parse.quote(query)}"

print(f"Searching: {search_url}")
req = urllib.request.Request(search_url, headers=headers)
try:
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Check what items exist
    # Let's search for film-name and film-poster links
    matches = []
    target_clean = clean_title(query)
    
    # The links look like: <a href="URL" class="..." title="TITLE">
    # Let's find any a tags inside elements with flw-item class, or just parse h2/h3 film-name or similar
    # In searchResponseBuilder: item.selectFirst(".film-name a, a.dynamic-name")
    # Let's search for all href/title patterns inside film-name links
    film_names = re.findall(r'<h[23][^>]*class="[^"]*film-name[^"]*"[^>]*>\s*<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>', html, re.DOTALL | re.IGNORECASE)
    if not film_names:
        # Fallback regex for dynamic-name or other patterns
        film_names = re.findall(r'<a[^>]+href="([^"]*)"[^>]+class="[^"]*dynamic-name[^"]*"[^>]*>(.*?)</a>', html, re.DOTALL | re.IGNORECASE)
        
    print(f"Found {len(film_names)} films in search results.")
    
    for href, title in film_names:
        # strip HTML tags if any
        title = re.sub(r'<[^<]+?>', '', title).strip()
        title_clean = clean_title(title)
        if title_clean == target_clean:
            season = parse_season_number(title)
            matches.append({
                'title': title,
                'url': href,
                'season': season
            })
            print(f"  [MATCH] '{title}' -> Cleaned: '{title_clean}' -> Season {season} | URL: {href}")
        else:
            print(f"  [SKIP] '{title}' -> Cleaned: '{title_clean}' (Target: '{target_clean}')")
            
    # Sort matches by season
    matches.sort(key=lambda x: x['season'])
    
    print("\nGrouped & Sorted Seasons:")
    for m in matches:
        print(f"  Season {m['season']}: {m['title']} | URL: {m['url']}")
        
except Exception as e:
    print("Error:", e)
