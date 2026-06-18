"""Scrape World Cup 2026 players from Wikipedia and load into wcfantasy.players table."""
import json
import re
import urllib.request
import psycopg2

DB_CONFIG = {"host": "localhost", "port": 5432, "dbname": "wcfantasy", "user": "postgres", "password": "postgres"}
API_URL = "https://en.wikipedia.org/w/api.php?action=parse&page=2026_FIFA_World_Cup_squads&prop=wikitext&format=json"
POS_MAP = {"GK": "GK", "DF": "DEF", "MF": "MID", "FW": "FWD"}

WIKI_TO_TEAM = {
    "Czech Republic": "Czech Republic", "South Korea": "South Korea",
    "Bosnia and Herzegovina": "Bosnia and Herzegovina",
    "United States": "United States", "Curaçao": "Curaçao",
    "Ivory Coast": "Ivory Coast", "New Zealand": "New Zealand",
    "Cape Verde": "Cape Verde", "Saudi Arabia": "Saudi Arabia",
    "DR Congo": "Democratic Republic of the Congo",
}

TEAMS = {
    "Mexico", "South Africa", "South Korea", "Czech Republic", "Canada",
    "Bosnia and Herzegovina", "Qatar", "Switzerland", "Brazil", "Morocco",
    "Haiti", "Scotland", "United States", "Paraguay", "Australia", "Turkey",
    "Germany", "Curaçao", "Ivory Coast", "Ecuador", "Netherlands", "Japan",
    "Sweden", "Tunisia", "Belgium", "Egypt", "Iran", "New Zealand", "Spain",
    "Cape Verde", "Saudi Arabia", "Uruguay", "France", "Senegal", "Iraq",
    "Norway", "Argentina", "Algeria", "Austria", "Jordan", "Portugal",
    "Democratic Republic of the Congo", "Uzbekistan", "Colombia", "England",
    "Croatia", "Ghana", "Panama"
}


def main():
    req = urllib.request.Request(API_URL, headers={"User-Agent": "WCFantasyBot/1.0"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())

    wikitext = data["parse"]["wikitext"]["*"]
    players = []
    current_team = None

    for line in wikitext.split("\n"):
        header_match = re.match(r"^===\s*(.+?)\s*===$", line)
        if header_match:
            header = header_match.group(1)
            team_name = WIKI_TO_TEAM.get(header, header)
            current_team = team_name if team_name in TEAMS else None
            continue

        if not current_team:
            continue

        if "nat fs" in line and "|pos=" in line and "|name=" in line:
            pos_match = re.search(r"\|pos=(\w+)", line)
            no_match = re.search(r"\|no=(\d+)", line)
            name_match = re.search(r"\|name=\[\[(?:[^\]|]*\|)?([^\]]+)\]\]", line)
            if not name_match:
                name_match = re.search(r"\|name=\[\[([^\]|]+)", line)
            if not name_match:
                name_match = re.search(r"\|name=([^|{}]+)", line)
            if pos_match and name_match:
                pos = POS_MAP.get(pos_match.group(1), pos_match.group(1))
                name = name_match.group(1).strip()
                jersey = int(no_match.group(1)) if no_match else None
                if name:
                    players.append((name, current_team, pos, jersey))

    # Deduplicate
    seen = set()
    unique = []
    for p in players:
        key = (p[0], p[1])
        if key not in seen:
            seen.add(key)
            unique.append(p)

    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    cur.execute("""
        CREATE TABLE IF NOT EXISTS players (
            id BIGSERIAL PRIMARY KEY,
            name VARCHAR(255),
            position VARCHAR(10),
            team_id BIGINT REFERENCES teams(id),
            jersey_number INTEGER,
            photo_url VARCHAR(500)
        )
    """)

    # Build team name->id lookup
    cur.execute("SELECT id, name FROM teams")
    team_to_id = {row[1]: row[0] for row in cur.fetchall()}

    cur.execute("DELETE FROM players")

    inserted = 0
    for name, team, pos, jersey in unique:
        team_id = team_to_id.get(team)
        if not team_id:
            continue
        cur.execute("""
            INSERT INTO players (name, position, team_id, jersey_number)
            VALUES (%s, %s, %s, %s)
        """, (name, pos, team_id, jersey))
        inserted += 1

    conn.commit()
    print(f"Loaded {inserted} players into players table")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
