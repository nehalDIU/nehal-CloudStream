import zipfile
import re

apk_path = "scratch/castle.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    try:
        dex_data = z.read("classes.dex")
        # In DEX files, class descriptors start with L and end with ; e.g. Lcom/external/castle/MainActivity;
        classes = re.findall(b"L[a-zA-Z0-9_/]+;", dex_data)
        unique_classes = sorted(list(set(classes)))
        
        print(f"Found {len(unique_classes)} classes in classes.dex")
        
        # Look for Castle-related classes
        castle_classes = [c.decode('ascii', errors='ignore') for c in unique_classes if b"castle" in c or b"external" in c]
        print(f"Found {len(castle_classes)} Castle-related classes:")
        for c in castle_classes[:50]:
            print("  ", c)
            
    except Exception as e:
        print("Error:", e)
