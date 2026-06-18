"""Sync World Cup 2026 data via API endpoints."""
import json
import sys
import urllib.request

if len(sys.argv) < 2:
    print("Usage: python sync_data.py <API_BASE_URL>")
    print("  e.g: python sync_data.py http://localhost:8081/api")
    print("  e.g: python sync_data.py https://wc-fantasy-league-production.up.railway.app/api")
    sys.exit(1)

api_base = sys.argv[1].rstrip("/")
print(f"Syncing to: {api_base}")


def post_json(url, data=None):
    body = json.dumps(data).encode("utf-8") if data else b""
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read())


def get_json(url):
    req = urllib.request.Request(url, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read())


print("Syncing all data (teams + matches + players)...")
try:
    result = get_json(f"{api_base}/sync/all")
    print(f"Done! {result}")
except Exception as e:
    print(f"sync-all failed: {e}")
    print("Trying individual syncs...")
    try:
        r = get_json(f"{api_base}/sync/teams")
        print(f"Teams: {r}")
    except Exception as e2:
        print(f"sync-teams failed: {e2}")
    try:
        r = get_json(f"{api_base}/sync/matches")
        print(f"Matches: {r}")
    except Exception as e2:
        print(f"sync-matches failed: {e2}")
    try:
        r = get_json(f"{api_base}/sync/players")
        print(f"Players: {r}")
    except Exception as e2:
        print(f"sync-players failed: {e2}")

print("Done!")
