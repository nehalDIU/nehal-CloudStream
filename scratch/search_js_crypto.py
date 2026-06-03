with open("scratch/india.js", "r", encoding="utf-8") as f:
    content = f.read()

import re

keywords = ["AES", "CBC", "CryptoJS", "encrypt", "decrypt", "pad", "mode", "key", "iv"]

for kw in keywords:
    matches = list(re.finditer(re.escape(kw), content, re.IGNORECASE))
    print(f"Keyword '{kw}': Found {len(matches)} matches.")
    if matches:
        # Print a few snippets around the matches
        for m in matches[:3]:
            start = max(0, m.start() - 100)
            end = min(len(content), m.end() + 100)
            print(f"  Match at {m.start()}:")
            print(content[start:end])
            print("-" * 40)
