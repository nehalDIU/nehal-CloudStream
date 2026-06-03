import zipfile
import re

apk_path = "scratch/castle.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    try:
        dex_data = z.read("classes.dex")
        print(f"Extracted classes.dex of size {len(dex_data)} bytes")
        
        # Find all ASCII strings of length 4 to 100
        strings = re.findall(b"[a-zA-Z0-9_/.:?=&-]{4,100}", dex_data)
        unique_strings = sorted(list(set(strings)))
        
        print(f"Found {len(unique_strings)} unique ASCII strings.")
        
        keywords = [b"hlowb", b"hbzws", b"htocy", b"category", b"getVideo", b"getSecurityKey", b"suffix", b"api"]
        for s in unique_strings:
            for kw in keywords:
                if kw in s:
                    print("Match:", s.decode('ascii', errors='ignore'))
                    break
    except Exception as e:
        print("Failed to inspect classes.dex:", e)
