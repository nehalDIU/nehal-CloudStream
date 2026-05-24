import urllib.request
import re

url_detail = "https://aniwatch.co.at/anime/spy-x-family/"
url_watch = "https://aniwatch.co.at/spy-x-family-episode-1-english-subbed/"

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

def inspect_seasons(url, label):
    print(f"\n=== Inspecting {label} ({url}) ===")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            html = response.read().decode('utf-8')
            
        # Look for season-related keywords, class names, divs
        # E.g. class="os-list", os-item, class="seasons", data-id, etc.
        seasons_matches = re.findall(r'<div[^>]+class="[^"]*season[^"]*"[^>]*>.*?</div>', html, re.IGNORECASE | re.DOTALL)
        print(f"Found {len(seasons_matches)} elements matching class 'season'")
        
        # Search for links containing "/anime/" or "-season-" or similar patterns inside seasons-block
        seasons_block = re.findall(r'<div[^>]+class="[^"]*seasons-block[^"]*"[^>]*>(.*?)<div[^>]+class="ss-list"[^>]*>', html, re.DOTALL | re.IGNORECASE)
        if seasons_block:
            print("Found seasons-block HTML:")
            clean = seasons_block[0].encode('ascii', 'ignore').decode('ascii')
            print(clean[:2000])
        else:
            # Let's print any div containing id="detail-ss-list" or class="detail-seasons"
            ss_list = re.findall(r'<div[^>]+id="detail-ss-list"[^>]*>(.*?)</div>\s*</div>', html, re.DOTALL | re.IGNORECASE)
            if ss_list:
                print("Found detail-ss-list HTML:")
                print(ss_list[0].encode('ascii', 'ignore').decode('ascii')[:2000])
            else:
                # Find all links containing /anime/
                links = re.findall(r'<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>', html, re.DOTALL)
                print("Checking links that might be other seasons:")
                for href, text in links:
                    if "season" in href.lower() or "season" in text.lower():
                        print(f"  Href: {href} | Text: {text.strip()}")
                        
    except Exception as e:
        print("Error:", e)

inspect_seasons(url_detail, "Detail Page")
inspect_seasons(url_watch, "Watch Page")
