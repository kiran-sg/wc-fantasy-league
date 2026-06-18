"""Scrape World Cup 2026 matches and load into wcfantasy.matches table."""
import json
import urllib.request
from datetime import datetime, timezone
import psycopg2

DB_CONFIG = {"host": "localhost", "port": 5432, "dbname": "wcfantasy", "user": "postgres", "password": "postgres"}
TEAMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.teams.json"
STADIUMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.stadiums.json"
MATCHES_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.matches.json"

OFFICIAL_UTC = {
    "1": "2026-06-11T19:00", "2": "2026-06-12T02:00", "3": "2026-06-12T19:00",
    "4": "2026-06-13T01:00", "5": "2026-06-13T04:00", "6": "2026-06-13T19:00",
    "7": "2026-06-13T22:00", "8": "2026-06-14T01:00", "9": "2026-06-14T17:00",
    "10": "2026-06-14T20:00", "11": "2026-06-14T23:00", "12": "2026-06-15T02:00",
    "13": "2026-06-15T16:00", "14": "2026-06-15T19:00", "15": "2026-06-15T22:00",
    "16": "2026-06-16T01:00", "17": "2026-06-16T04:00", "18": "2026-06-16T19:00",
    "19": "2026-06-16T22:00", "20": "2026-06-17T01:00", "21": "2026-06-17T17:00",
    "22": "2026-06-17T20:00", "23": "2026-06-17T23:00", "24": "2026-06-18T02:00",
    "25": "2026-06-18T16:00", "26": "2026-06-18T19:00", "27": "2026-06-18T22:00",
    "28": "2026-06-19T01:00", "29": "2026-06-19T04:00", "30": "2026-06-19T19:00",
    "31": "2026-06-19T22:00", "32": "2026-06-20T01:00", "33": "2026-06-20T04:00",
    "34": "2026-06-20T17:00", "35": "2026-06-20T20:00", "36": "2026-06-21T00:00",
    "37": "2026-06-21T16:00", "38": "2026-06-21T19:00", "39": "2026-06-21T22:00",
    "40": "2026-06-22T01:00", "41": "2026-06-22T17:00", "42": "2026-06-22T21:00",
    "43": "2026-06-23T00:00", "44": "2026-06-23T03:00", "45": "2026-06-23T17:00",
    "46": "2026-06-23T20:00", "47": "2026-06-23T23:00", "48": "2026-06-24T02:00",
    "49": "2026-06-24T19:00", "50": "2026-06-24T19:00", "51": "2026-06-24T22:00",
    "52": "2026-06-24T22:00", "53": "2026-06-25T01:00", "54": "2026-06-25T01:00",
    "55": "2026-06-25T20:00", "56": "2026-06-25T20:00", "57": "2026-06-25T23:00",
    "58": "2026-06-25T23:00", "59": "2026-06-26T02:00", "60": "2026-06-26T02:00",
    "61": "2026-06-26T19:00", "62": "2026-06-26T19:00", "63": "2026-06-27T00:00",
    "64": "2026-06-27T00:00", "65": "2026-06-27T03:00", "66": "2026-06-27T03:00",
    "67": "2026-06-27T21:00", "68": "2026-06-27T21:00", "69": "2026-06-27T23:30",
    "70": "2026-06-27T23:30", "71": "2026-06-28T02:00", "72": "2026-06-28T02:00",
}


def fetch_json(url):
    with urllib.request.urlopen(url) as resp:
        return json.loads(resp.read())


def main():
    teams_data = fetch_json(TEAMS_URL)
    teams_by_id = {t["id"]: t["fifa_code"] for t in teams_data}
    stadiums = {s["id"]: s["name_en"] for s in fetch_json(STADIUMS_URL)}
    matches = fetch_json(MATCHES_URL)

    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    cur.execute("""
        CREATE TABLE IF NOT EXISTS matches (
            id BIGSERIAL PRIMARY KEY,
            team_a_id BIGINT REFERENCES teams(id),
            team_b_id BIGINT REFERENCES teams(id),
            match_time TIMESTAMP,
            venue VARCHAR(255),
            stage VARCHAR(20),
            status VARCHAR(20),
            score_a INTEGER,
            score_b INTEGER
        )
    """)

    # Build code->id lookup from DB
    cur.execute("SELECT id, code FROM teams")
    code_to_id = {row[1]: row[0] for row in cur.fetchall()}

    inserted = 0
    for m in matches:
        team_a_code = teams_by_id.get(m["home_team_id"])
        team_b_code = teams_by_id.get(m["away_team_id"])
        team_a_id = code_to_id.get(team_a_code)
        team_b_id = code_to_id.get(team_b_code)

        if not team_a_id or not team_b_id:
            continue

        utc_str = OFFICIAL_UTC.get(m["id"])
        match_time = datetime.strptime(utc_str, "%Y-%m-%dT%H:%M").replace(tzinfo=timezone.utc) if utc_str else None

        venue = stadiums.get(m["stadium_id"], "TBD")
        stage = "GROUP"
        status = "UPCOMING"

        cur.execute("""
            INSERT INTO matches (team_a_id, team_b_id, match_time, venue, stage, status)
            VALUES (%s, %s, %s, %s, %s, %s)
        """, (team_a_id, team_b_id, match_time, venue, stage, status))
        inserted += 1

    conn.commit()
    print(f"Loaded {inserted} matches into matches table")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
