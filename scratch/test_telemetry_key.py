import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

# Fetch home page
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

# Key/IV from telemetry
key_bytes = b"u1tDxubl2IZ946Ly"
iv_bytes = b"u1tDxubl2IZ946Ly"

try:
    cipher = AES.new(key_bytes, AES.MODE_CBC, iv_bytes)
    decrypted = cipher.decrypt(encrypted_data)
    decrypted_unpadded = unpad(decrypted, 16)
    decrypted_str = decrypted_unpadded.decode('utf-8')
    print("SUCCESS with telemetry key/IV!")
    print("Decrypted sample:", decrypted_str[:200])
except Exception as e:
    print("Failed with telemetry key/IV:", e)
