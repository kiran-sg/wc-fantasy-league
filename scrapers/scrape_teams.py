"""Scrape World Cup 2026 teams and load into wcfantasy.teams table."""
import json
import urllib.request
import psycopg2

DB_CONFIG = {"host": "localhost", "port": 5432, "dbname": "wcfantasy", "user": "postgres", "password": "postgres"}
TEAMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.teams.json"

# Map group IDs from the JSON data
GROUP_MAP = {
    1: "A", 2: "B", 3: "C", 4: "D", 5: "E", 6: "F",
    7: "G", 8: "H", 9: "I", 10: "J", 11: "K", 12: "L"
}


def main():
    with urllib.request.urlopen(TEAMS_URL) as resp:
        teams = json.loads(resp.read())

    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    cur.execute("""
        CREATE TABLE IF NOT EXISTS teams (
            id BIGSERIAL PRIMARY KEY,
            name VARCHAR(255),
            code VARCHAR(10) UNIQUE,
            team_group VARCHAR(10),
            flag_url VARCHAR(500)
        )
    """)

    for t in teams:
        group = GROUP_MAP.get(t.get("group_id"), None)
        cur.execute("""
            INSERT INTO teams (name, code, team_group, flag_url)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name, team_group = EXCLUDED.team_group, flag_url = EXCLUDED.flag_url
        """, (t["name_en"], t["fifa_code"], group, t.get("flag")))

    conn.commit()
    print(f"Loaded {len(teams)} teams into teams table")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
