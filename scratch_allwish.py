import urllib.parse
import urllib.request
import json
import base64

def base64_url_safe(data: bytes, padding=True) -> str:
    encoded = base64.b64encode(data).decode('ascii')
    res = encoded.replace("+", "-").replace("/", "_")
    if not padding:
        res = res.rstrip("=")
    return res

def generate_episode_vrf(episode_id: str, padding=True) -> str:
    secret_key = "ysJhV6U27FVIjjuk"
    encoded_id = urllib.parse.quote(episode_id, safe='~()*!\'')
    key_codes = [ord(c) for c in secret_key]
    data_codes = [ord(c) for c in encoded_id]
    n = list(range(256))
    a = 0
    for o in range(256):
        a = (a + n[o] + key_codes[o % len(key_codes)]) % 256
        n[o], n[a] = n[a], n[o]
    out = []
    o = 0
    a = 0
    for r in range(len(data_codes)):
        o = (o + 1) % 256
        a = (a + n[o]) % 256
        n[o], n[a] = n[a], n[o]
        k = n[(n[o] + n[a]) % 256]
        out.append(data_codes[r] ^ k)
    step1 = bytes([x & 0xFF for x in out])
    base1 = base64_url_safe(step1, padding)
    step2_bytes = base1.encode('latin1')
    transformed = []
    for index, value in enumerate(step2_bytes):
        s = value
        idx_mod = index % 8
        if idx_mod == 1:
            s += 3
        elif idx_mod == 7:
            s += 5
        elif idx_mod == 2:
            s -= 4
        elif idx_mod == 4:
            s += -2
        elif idx_mod == 6:
            s += 4
        elif idx_mod == 0:
            s += -3
        elif idx_mod == 3:
            s += 2
        elif idx_mod == 5:
            s += 5
        transformed.append((s & 0xFF))
    transformed_bytes = bytes(transformed)
    base2 = base64_url_safe(transformed_bytes, padding)
    final_chars = []
    for c in base2:
        if 'A' <= c <= 'Z':
            final_chars.append(chr(ord('A') + (ord(c) - ord('A') + 13) % 26))
        elif 'a' <= c <= 'z':
            final_chars.append(chr(ord('a') + (ord(c) - ord('a') + 13) % 26))
        else:
            final_chars.append(c)
    return "".join(final_chars)

id_to_test = "1642"

# 1. Test WITH padding
vrf_with = generate_episode_vrf(id_to_test, padding=True)
url_with = f"https://all-wish.me/ajax/episode/list/{id_to_test}?vrf={vrf_with}"
print(f"VRF with padding: '{vrf_with}'")
req = urllib.request.Request(url_with, headers={'User-Agent': 'Mozilla/5.0', 'X-Requested-With': 'XMLHttpRequest'})
try:
    with urllib.request.urlopen(req) as resp:
        print("Success with padding! Status:", resp.status)
except Exception as e:
    print("Failed with padding:", e)

# 2. Test WITHOUT padding
vrf_without = generate_episode_vrf(id_to_test, padding=False)
url_without = f"https://all-wish.me/ajax/episode/list/{id_to_test}?vrf={vrf_without}"
print(f"VRF without padding: '{vrf_without}'")
req = urllib.request.Request(url_without, headers={'User-Agent': 'Mozilla/5.0', 'X-Requested-With': 'XMLHttpRequest'})
try:
    with urllib.request.urlopen(req) as resp:
        print("Success without padding! Status:", resp.status)
except Exception as e:
    print("Failed without padding:", e)
