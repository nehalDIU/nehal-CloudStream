import zipfile
import json

apk_path = "scratch/castle.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    # 1. Read assets/supplierconfig.json
    try:
        data = z.read("assets/supplierconfig.json").decode('utf-8')
        print("=== assets/supplierconfig.json ===")
        print(data[:2000])
        print("=" * 30)
    except Exception as e:
        print("Failed to read supplierconfig.json:", e)

    # 2. Check other json files in assets
    for name in z.namelist():
        if name.startswith("assets/") and name.endswith(".json"):
            if "supplierconfig" not in name:
                try:
                    data = z.read(name).decode('utf-8')
                    print(f"\n=== {name} ===")
                    print(data[:500])
                except Exception as e:
                    pass
