import urllib.request
import json
import base64

# First, query sources
url = "https://megaplay.buzz/stream/getSources?id=107257"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
    'X-Requested-With': 'XMLHttpRequest',
    'Referer': 'https://megaplay.buzz/'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        content = response.read().decode('utf-8')
        parsed = json.loads(content)
        print("Frieren Sources JSON:")
        print(json.dumps(parsed, indent=2))
        
        # Download first track subtitle
        if parsed.get("tracks"):
            sub_url = parsed["tracks"][0]["file"]
            print("\nDownloading Frieren subtitle from:", sub_url)
            sub_req = urllib.request.Request(sub_url, headers=headers)
            with urllib.request.urlopen(sub_req) as sub_resp:
                sub_content = sub_resp.read().decode('utf-8')
                print("\nFirst 30 lines of Frieren subtitle:")
                for line in sub_content.splitlines()[:40]:
                    print(line)
                    
except Exception as e:
    print("Error:", e)
