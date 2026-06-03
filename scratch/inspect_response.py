import urllib.request
import json
import base64

# Fetch security key
print("Fetching security key...")
req = urllib.request.Request(
    "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req) as response:
    key_data = json.loads(response.read().decode())
    print("Security Key Response:", key_data)
    api_key_b64 = key_data["data"]

# Fetch home page
print("\nFetching home page...")
req_home = urllib.request.Request(
    "https://api.hlowb.com/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req_home) as response:
    home_body = response.read().decode().strip()
    print("Home body length:", len(home_body))
    print("Home body preview:", home_body[:200])

# Parse ciphertext
if home_body.startswith("{"):
    home_data = json.loads(home_body)
    ciphertext_b64 = home_data.get("data", "")
else:
    # If the response is a raw string
    if home_body.startswith('"') and home_body.endswith('"'):
        home_body = home_body[1:-1]
    ciphertext_b64 = home_body

print("Ciphertext B64 length:", len(ciphertext_b64))

# Let's inspect the decoded bytes
try:
    decoded = base64.b64decode(ciphertext_b64)
    print("Base64 decoded bytes count:", len(decoded))
    print("First 16 bytes (hex):", decoded[:16].hex())
except Exception as e:
    print("Base64 decoding failed:", e)
