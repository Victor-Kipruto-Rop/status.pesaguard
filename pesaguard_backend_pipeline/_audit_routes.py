"""Audit helper: list Flask routes lacking @require_auth within 6 lines."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "api/dashboard_app.py"
lines = open(path, encoding="utf-8").read().splitlines()
route_re = re.compile(r'@app\.(route|get|post|put|delete)\(\s*["\']([^"\']+)')
gap = []
for i, line in enumerate(lines):
    m = route_re.search(line)
    if not m:
        continue
    has_auth = False
    for j in range(i + 1, min(i + 7, len(lines))):
        if re.match(r'\s*def ', lines[j]):
            break
        if "require_auth" in lines[j]:
            has_auth = True
            break
    if not has_auth:
        gap.append(f"{i+1}: {m.group(2)}")
print(f"total routes checked: {sum(1 for l in lines if route_re.search(l))}")
print(f"routes without nearby @require_auth: {len(gap)}")
for g in gap:
    print(g)
