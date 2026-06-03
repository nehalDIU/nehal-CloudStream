import urllib.request
import urllib.parse
import json

def normalize_path(path):
    if not path:
        return "/"
    normalized = path if path.startswith("/") else f"/{path}"
    if not normalized.endswith("/"):
        normalized += "/"
    return normalized

def direct_children(items, parent_href):
    parent = normalize_path(parent_href)
    direct = []
    for item in items:
        href = item.get("href", "")
        if not href.startswith(parent) or href == parent:
            continue
        relative = href[len(parent):]
        clean = relative.strip("/")
        if not clean or "/" in clean:
            continue
        direct.append(item)
    return direct

def probe():
    url = "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/"
    headers = {
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    }
    
    data = {
        "action": "get",
        "items[href]": "/DHAKA-FLIX-7/English%20Movies/%282025%29/A%20Minecraft%20Movie%20%282025%29%20720p%20%5BDual%20Audio%5D/",
        "items[what]": "1"
    }
    
    parent_path = data["items[href]"]
    encoded_data = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(url, data=encoded_data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as response:
            res_body = response.read().decode("utf-8")
            parsed = json.loads(res_body)
            all_items = parsed.get("items", [])
            items = direct_children(all_items, parent_path)
            print(f"\n--- Movie Folder ({parent_path}): {len(items)} direct items ---")
            
            for i, it in enumerate(items):
                print(f"{i+1}: href='{it.get('href')}', size={it.get('size')}")
    except Exception as e:
        print("Error querying movie folder:", e)

if __name__ == "__main__":
    probe()
