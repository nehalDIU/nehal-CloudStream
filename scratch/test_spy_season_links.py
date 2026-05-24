import urllib.request
import re

url = "https://aniwatch.co.at/anime/spy-x-family/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Let's search for any links containing "spy-x-family" or "season"
    links = re.findall(r'<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>', html, re.DOTALL)
    print("\nLinks containing spy-x-family or season:")
    for href, text in links:
        href_lower = href.lower()
        text_clean = re.sub('<[^<]+?>', '', text).strip()
        if "spy-x-family" in href_lower or "season" in href_lower or "season" in text_clean.lower():
            print(f"  Href: {href} | Text: {text_clean}")
            
except Exception as e:
    print("Error:", e)
