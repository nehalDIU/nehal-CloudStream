import re

with open("scratch/strings_ascii.txt", "r", encoding="utf-8") as f:
    strings = [line.strip() for line in f if line.strip()]

candidates = []
for s in strings:
    # Look for alphanumeric / special character strings between 4 and 45 characters
    if 4 <= len(s) <= 45:
        # Filter out common standard library/framework strings, java package names, etc.
        if not re.match(r'^(java|kotlin|android|com/|org/|Ljava|Lkotlin|Landroid|Lcom)', s):
            if not s.startswith("[") and not s.endswith(";"):
                candidates.append(s)

unique_candidates = sorted(list(set(candidates)))
with open("scratch/candidates.txt", "w", encoding="utf-8") as out:
    for c in unique_candidates:
        out.println = out.write(c + "\n")

print(f"Saved {len(unique_candidates)} unique candidates to scratch/candidates.txt")
