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

    @Override
    public void run(String... args) {
        if (teamRepo.count() > 0) return;

        // Try sync from prediction API, fallback to manual seed
        try {
            matchSyncService.syncMatchesFromPredictionApi();
            log.info("Synced matches from prediction API");
        } catch (Exception e) {
            log.warn("Could not sync from prediction API, seeding demo data: {}", e.getMessage());
            seedDemoData();
        }

        // Create demo user
        if (userRepo.findByUsername("demo").isEmpty()) {
            AppUser demo = new AppUser();
            demo.setUsername("demo");
            demo.setDisplayName("Demo User");
            userRepo.save(demo);
        }
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
        for (int i = 0; i < names.size(); i++) {
            Player p = new Player();
            p.setName(names.get(i));
            p.setPosition(positions[i % positions.length]);
            p.setTeam(team);
            p.setJerseyNumber(i + 1);
            playerRepo.save(p);
        }
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
