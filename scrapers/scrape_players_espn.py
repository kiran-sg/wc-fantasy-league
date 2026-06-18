"""Scrape World Cup 2026 players from ESPN team squad pages into wcfantasy DB."""
import re
import urllib.request
import psycopg2

DB_CONFIG = {"host": "localhost", "port": 5432, "dbname": "wcfantasy", "user": "postgres", "password": "postgres"}

# ESPN team IDs mapped to our team codes
ESPN_TEAMS = {
    "660": "USA", "624": "ALG", "202": "ARG", "628": "AUS", "474": "AUT",
    "459": "BEL", "204": "BRA", "2850": "COD", "208": "COL", "477": "CRO",
    "1524": "CUW", "447": "CZE", "496": "ECU", "467": "EGY", "448": "ENG",
    "463": "FRA", "481": "GER", "4469": "GHA", "2836": "HTI", "471": "IRN",
    "2851": "IRQ", "2802": "CIV", "489": "JPN", "2917": "JOR", "461": "MEX",
    "782": "MAR", "469": "NED", "5765": "NZL", "498": "NOR", "2659": "PAN",
    "2656": "PAR", "482": "POR", "5765": "NZL", "7835": "QAT", "3377": "KSA",
    "2818": "SEN", "4697": "CPV", "457": "SCO", "2668": "RSA",
    "452": "KOR", "449": "ESP", "3372": "SWE", "468": "SUI", "2413": "TUN",
    "484": "TUR", "2570": "UZB", "225": "URU", "483": "SWE",
    "4538": "BIH", "3378": "CAN", "448": "ENG", "463": "FRA",
}

# Simpler: get all teams from the WC teams page and scrape each
TEAMS_URL = "https://www.espn.com/soccer/teams/_/league/fifa.world"
SQUAD_URL = "https://www.espn.com/soccer/team/squad/_/id/{}"

POS_MAP = {"goalkeeper": "GK", "defender": "DEF", "midfielder": "MID", "forward": "FWD"}


def get_espn_team_ids():
    """Get all team IDs from ESPN's WC teams page."""
    req = urllib.request.Request(TEAMS_URL, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode()
    # Pattern: /soccer/team/_/id/XXX/team-name
    teams = {}
    for match in re.finditer(r'/soccer/team/_/id/(\d+)/([a-z-]+)', html):
        tid, slug = match.group(1), match.group(2)
        name = slug.replace("-", " ").title()
        teams[tid] = name
    return teams


def get_squad(team_id):
    """Get players from ESPN squad page using embedded JSON data."""
    url = SQUAD_URL.format(team_id)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            html = resp.read().decode()
    except Exception as e:
        print(f"  Failed to fetch squad for team {team_id}: {e}")
        return []

    pos_map = {"G": "GK", "D": "DEF", "M": "MID", "F": "FWD"}
    players = []
    seen = set()

    for m in re.finditer(r'"name":"([^"]+)","href":"https://www\.espn\.com/soccer/player/_/id/(\d+)/[^"]*"[^}]*"position":"([GDMF])"', html):
        name, pid, pos = m.group(1), m.group(2), m.group(3)
        if pid not in seen:
            seen.add(pid)
            players.append((name, pos_map.get(pos, "MID")))

    return players


def main():
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    # Get team code -> id mapping from DB
    cur.execute("SELECT id, code, name FROM teams")
    db_teams = {row[2].lower(): (row[0], row[1]) for row in cur.fetchall()}

    # Get ESPN team IDs
    espn_teams = get_espn_team_ids()
    print(f"Found {len(espn_teams)} teams on ESPN")

    cur.execute("DELETE FROM match_player_stats")
    cur.execute("DELETE FROM squad_players")
    cur.execute("DELETE FROM user_squads")
    cur.execute("DELETE FROM players")

    total = 0
    for espn_id, espn_name in espn_teams.items():
        # Match ESPN team name to DB team
        team_id = None
        espn_lower = espn_name.lower()
        for db_name, (tid, code) in db_teams.items():
            if espn_lower == db_name or espn_lower in db_name or db_name in espn_lower:
                team_id = tid
                break
        # Handle special name mismatches
        if not team_id:
            aliases = {
                "congo dr": "democratic republic of the congo",
                "curacao": "curaçao",
                "czechia": "czech republic",
                "bosnia herzegovina": "bosnia and herzegovina",
                "turkiye": "turkey",
            }
            mapped = aliases.get(espn_lower)
            if mapped and mapped in db_teams:
                team_id = db_teams[mapped][0]

        if not team_id:
            print(f"  Skipping {espn_name} - no DB match")
            continue

        players = get_squad(espn_id)
        if not players:
            continue

        for name, pos in players:
            cur.execute(
                "INSERT INTO players (name, position, team_id) VALUES (%s, %s, %s)",
                (name, pos, team_id)
            )
            total += 1

        print(f"  {espn_name}: {len(players)} players")

    conn.commit()
    print(f"\nLoaded {total} players from ESPN into players table")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
