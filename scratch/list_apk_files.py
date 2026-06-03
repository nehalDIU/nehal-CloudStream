import zipfile

apk_path = "scratch/castle.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    for name in z.namelist():
        if name.endswith(".dex") or "assets/" in name or "lib/" in name:
            if "/" not in name or name.count("/") <= 2:
                print(name)
