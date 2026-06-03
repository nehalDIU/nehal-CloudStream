import urllib.request
import json
import base64
import zlib
import gzip

# 1. Fetch live home page
print("Fetching home page...")
req_home = urllib.request.Request(
    "https://api.hlowb.com/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req_home) as response:
    home_body = response.read().decode().strip()

# Parse ciphertext
if home_body.startswith("{"):
    home_data = json.loads(home_body)
    ciphertext_b64 = home_data.get("data", "")
else:
    if home_body.startswith('"') and home_body.endswith('"'):
        home_body = home_body[1:-1]
    ciphertext_b64 = home_body

encrypted_data = base64.b64decode(ciphertext_b64)

print("Attempting direct UTF-8 decoding...")
try:
    print("Direct UTF-8:", encrypted_data[:100].decode('utf-8'))
except Exception as e:
    print("Direct UTF-8 failed:", e)

print("\nAttempting zlib decompression...")
try:
    print("zlib decompressed:", zlib.decompress(encrypted_data)[:100].decode('utf-8'))
except Exception as e:
    print("zlib failed:", e)

print("\nAttempting gzip decompression...")
try:
    print("gzip decompressed:", gzip.decompress(encrypted_data)[:100].decode('utf-8'))
except Exception as e:
    print("gzip failed:", e)

print("\nAttempting raw deflate decompression...")
try:
    print("deflate decompressed:", zlib.decompress(encrypted_data, -15)[:100].decode('utf-8'))
except Exception as e:
    print("deflate failed:", e)
