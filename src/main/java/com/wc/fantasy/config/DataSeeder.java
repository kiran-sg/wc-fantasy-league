package com.wc.fantasy.config;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.MatchSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final TeamRepository teamRepo;
    private final UserRepository userRepo;
    private final PlayerRepository playerRepo;
    private final MatchRepository matchRepo;
    private final MatchSyncService matchSyncService;
    private final RoundConfigRepository roundConfigRepo;
    private final com.wc.fantasy.service.DataSyncService dataSyncService;

    @Override
    public void run(String... args) {
        seedRoundConfig();
        if (teamRepo.count() > 0) {
            // Matches already exist — back-fill any roundStart that is still null
            dataSyncService.refreshRoundStarts();
            return;
        }

        // Try sync from prediction API, fallback to manual seed
        try {
            matchSyncService.syncMatchesFromPredictionApi();
            log.info("Synced matches from prediction API");
        } catch (Exception e) {
            log.warn("Could not sync from prediction API, seeding demo data: {}", e.getMessage());
            seedDemoData();
        }

        // After matches are in, fill roundStart for each stage
        dataSyncService.refreshRoundStarts();

        // Create demo user
        if (userRepo.findByUsername("demo").isEmpty()) {
            AppUser demo = new AppUser();
            demo.setUsername("demo");
            demo.setDisplayName("Demo User");
            userRepo.save(demo);
        }
    }

    // For each round_config row, set roundStart = earliest matchTime of that stage (if not already set)
    private void backfillRoundStarts() {
        for (RoundConfig rc : roundConfigRepo.findAll()) {
            if (rc.getRoundStart() != null) continue; // already set — don't overwrite manual edits
            matchRepo.findAll().stream()
                    .filter(m -> rc.getStage().equalsIgnoreCase(m.getStage()) && m.getMatchTime() != null)
                    .map(Match::getMatchTime)
                    .min(LocalDateTime::compareTo)
                    .ifPresent(earliest -> {
                        rc.setRoundStart(earliest);
                        roundConfigRepo.save(rc);
                        log.info("Set roundStart={} for stage={}", earliest, rc.getStage());
                    });
        }
    }

    private void seedRoundConfig() {
        // Upsert — only insert rows that don't already exist, so manual edits are preserved
        seedConfigIfAbsent("GROUP", 999, 3, 12, 23, "Asia/Kolkata");
        seedConfigIfAbsent("R32",   4,  3, 12, 19, "Asia/Kolkata");
        seedConfigIfAbsent("R16",   4,  4, 12, 19, "Asia/Kolkata");
        seedConfigIfAbsent("QF",    4,  5, 12, 19, "Asia/Kolkata");
        seedConfigIfAbsent("SF",    5,  6, 12, 19, "Asia/Kolkata");
        seedConfigIfAbsent("FINAL", 6,  8, 12, 19, "Asia/Kolkata");
        log.info("Round config seeded");
    }

    private void seedConfigIfAbsent(String stage, int freeTransfers, int countryLimit,
                                     int openHour, int closeHour, String tz) {
        if (roundConfigRepo.existsById(stage)) return;
        RoundConfig rc = new RoundConfig();
        rc.setStage(stage);
        rc.setFreeTransfers(freeTransfers);
        rc.setCountryLimit(countryLimit);
        rc.setWindowOpenHour(openHour);
        rc.setWindowCloseHour(closeHour);
        rc.setWindowTimezone(tz);
        roundConfigRepo.save(rc);
    }

    private void seedDemoData() {
        // Create teams
        Team brazil = createTeam("Brazil", "BRA", "A");
        Team germany = createTeam("Germany", "GER", "A");
        Team argentina = createTeam("Argentina", "ARG", "B");
        Team france = createTeam("France", "FRA", "B");
        Team spain = createTeam("Spain", "SPA", "C");
        Team england = createTeam("England", "ENG", "C");

        // Create players
        seedPlayers(brazil, List.of("Alisson", "Marquinhos", "Militao", "Alex Sandro", "Casemiro", "Paqueta", "Raphinha", "Vinicius Jr", "Rodrygo", "Richarlison", "Neymar", "Danilo", "Thiago Silva"));
        seedPlayers(germany, List.of("Neuer", "Rudiger", "Sule", "Raum", "Kimmich", "Gundogan", "Musiala", "Sane", "Havertz", "Gnabry", "Muller", "Goretzka", "Schlotterbeck"));
        seedPlayers(argentina, List.of("E. Martinez", "Romero", "Otamendi", "Acuna", "De Paul", "Fernandez", "Mac Allister", "Di Maria", "Messi", "Alvarez", "Lo Celso", "Molina", "Tagliafico"));
        seedPlayers(france, List.of("Lloris", "Varane", "Upamecano", "T. Hernandez", "Tchouameni", "Rabiot", "Griezmann", "Dembele", "Mbappe", "Giroud", "Kolo Muani", "Kounde", "Camavinga"));
        seedPlayers(spain, List.of("Unai Simon", "Carvajal", "Laporte", "Alba", "Pedri", "Busquets", "Gavi", "Olmo", "Torres", "Morata", "Williams", "Rodri", "Balde"));
        seedPlayers(england, List.of("Pickford", "Walker", "Stones", "Maguire", "Shaw", "Rice", "Bellingham", "Mount", "Saka", "Kane", "Foden", "Trippier", "Henderson"));

        // Create matches (set in future so they are UPCOMING)
        createMatch(brazil, germany, LocalDateTime.now().plusDays(1), "MetLife Stadium [#1]", "GROUP");
        createMatch(argentina, france, LocalDateTime.now().plusDays(2), "AT&T Stadium [#2]", "GROUP");
        createMatch(spain, england, LocalDateTime.now().plusDays(3), "Rose Bowl [#3]", "GROUP");
        createMatch(brazil, argentina, LocalDateTime.now().plusDays(5), "MetLife Stadium [#4]", "GROUP");
        createMatch(germany, france, LocalDateTime.now().plusDays(6), "SoFi Stadium [#5]", "GROUP");
        createMatch(spain, brazil, LocalDateTime.now().plusDays(8), "Azteca Stadium [#6]", "GROUP");

        log.info("Seeded 6 teams, players, and 6 matches");
    }

    private Team createTeam(String name, String code, String group) {
        Team t = new Team();
        t.setName(name);
        t.setCode(code);
        t.setGroup(group);
        return teamRepo.save(t);
    }

    private void seedPlayers(Team team, List<String> names) {
        String[] positions = {"GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "FWD", "FWD", "FWD", "MID", "DEF"};
        // Prices in millions: GK=5.5, DEF=6, MID=8, FWD=9.5 — star players (index 8-10) get premium
        java.math.BigDecimal[] basePrices = {
            bd(5_500_000), bd(6_000_000), bd(6_000_000), bd(6_000_000), bd(6_500_000),
            bd(8_000_000), bd(8_000_000), bd(8_500_000), bd(9_500_000), bd(9_000_000),
            bd(8_500_000), bd(7_500_000), bd(6_000_000)
        };
        for (int i = 0; i < names.size(); i++) {
            Player p = new Player();
            p.setName(names.get(i));
            p.setPosition(positions[i % positions.length]);
            p.setTeam(team);
            p.setJerseyNumber(i + 1);
            p.setPrice(basePrices[i % basePrices.length]);
            playerRepo.save(p);
        }
    }

    private java.math.BigDecimal bd(long val) {
        return java.math.BigDecimal.valueOf(val);
    }

    private void createMatch(Team teamA, Team teamB, LocalDateTime time, String venue, String stage) {
        Match m = new Match();
        m.setTeamA(teamA);
        m.setTeamB(teamB);
        m.setMatchTime(time);
        m.setVenue(venue);
        m.setStage(stage);
        m.setStatus("UPCOMING");
        matchRepo.save(m);
    }
}
