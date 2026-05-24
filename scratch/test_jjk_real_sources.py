import urllib.request
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
    'X-Requested-With': 'XMLHttpRequest',
    'Referer': 'https://megaplay.buzz/'
}

url = "https://megaplay.buzz/stream/getSources?id=15703"
req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        content = response.read().decode('utf-8')
        parsed = json.loads(content)
        
        if parsed.get("tracks"):
            # Find English subtitle
            eng_track = None
            for track in parsed["tracks"]:
                if track.get("label") == "English":
                    eng_track = track
                    break
            
            if eng_track:
                sub_url = eng_track["file"]
                print("\nDownloading English subtitle from:", sub_url)
                sub_req = urllib.request.Request(sub_url, headers=headers)
                with urllib.request.urlopen(sub_req) as sub_resp:
                    sub_content = sub_resp.read().decode('utf-8')
                    print("\nFirst 30 subtitle lines:")
                    for line in sub_content.splitlines()[:50]:
                        clean_line = line.encode('ascii', 'ignore').decode('ascii')
                        print(clean_line)
            else:
                print("No English subtitle found")
                    
except Exception as e:
    print("Error:", e)
