package com.wc.fantasy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wc.fantasy.model.Player;
import com.wc.fantasy.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FifaScraperService {

    private final PlayerRepository playerRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FIFA_PLAYERS_URL = "https://play.fifa.com/json/fantasy/players.json";

    public record SyncResult(int matched, int unmatched, List<String> unmatchedNames) {}

    public SyncResult syncPrices() {
        String json = fetch(FIFA_PLAYERS_URL);
        if (json == null) throw new RuntimeException("Could not fetch FIFA player prices");

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse FIFA JSON: " + e.getMessage());
        }

        List<Player> allPlayers = playerRepo.findAll();
        Map<String, Player> byFifaName  = new HashMap<>();
        Map<String, Player> byLastName  = new HashMap<>();
        Map<String, Player> byNormFull  = new HashMap<>();

        for (Player p : allPlayers) {
            if (p.getFifaPlayerName() != null) {
                byFifaName.put(normalize(p.getFifaPlayerName()), p);
            }
            byNormFull.put(normalize(p.getName()), p);
            String last = lastName(normalize(p.getName()));
            if (last.length() > 2) byLastName.putIfAbsent(last, p);
        }

        int matched = 0, unmatched = 0;
        List<String> unmatchedNames = new ArrayList<>();

        // FIFA endpoint returns a flat JSON array
        JsonNode players;
        if (root.isArray()) {
            players = root;
        } else {
            // fallback: find the first array field
            players = root.path("players");
            if (!players.isArray()) {
                Iterator<String> fields = root.fieldNames();
                players = root; // default
                while (fields.hasNext()) {
                    JsonNode candidate = root.path(fields.next());
                    if (candidate.isArray() && candidate.size() > 0) { players = candidate; break; }
                }
            }
        }

        for (JsonNode entry : players) {
            // FIFA JSON uses knownName (e.g. "Mbappé"), else firstName + lastName
            String knownName  = entry.path("knownName").asText("").trim();
            String firstName  = entry.path("firstName").asText("").trim();
            String lastName   = entry.path("lastName").asText("").trim();
            String fifaName   = !knownName.isEmpty() ? knownName
                              : (!firstName.isEmpty() && !lastName.isEmpty()) ? (firstName + " " + lastName)
                              : (!lastName.isEmpty() ? lastName : "");
            if (fifaName.isEmpty()) continue;

            // price is in millions in the FIFA JSON (e.g. 4.9 = $4.9m)
            double rawPrice = entry.path("price").asDouble(0);
            if (rawPrice <= 0) continue;
            BigDecimal price = BigDecimal.valueOf(Math.round(rawPrice * 1_000_000));

            Player player = resolvePlayer(fifaName, byFifaName, byNormFull, byLastName);
            if (player == null) {
                unmatched++;
                unmatchedNames.add(fifaName);
                log.debug("No match for FIFA player: {}", fifaName);
                continue;
            }

            player.setFifaPlayerName(fifaName);
            player.setPrice(price);
            playerRepo.save(player);
            matched++;
        }

        log.info("FIFA price sync: {} matched, {} unmatched", matched, unmatched);
        return new SyncResult(matched, unmatched, unmatchedNames);
    }

    private Player resolvePlayer(String fifaName,
                                  Map<String, Player> byFifaName,
                                  Map<String, Player> byNormFull,
                                  Map<String, Player> byLastName) {
        String norm = normalize(fifaName);

        // 1. Exact fifa_player_name match
        if (byFifaName.containsKey(norm)) return byFifaName.get(norm);

        // 2. Exact full name match
        if (byNormFull.containsKey(norm)) return byNormFull.get(norm);

        // 3. Last name match (e.g. FIFA "Mbappe" vs ESPN "Kylian Mbappe")
        String last = lastName(norm);
        if (last.length() > 2 && byLastName.containsKey(last)) return byLastName.get(last);

        // 4. Partial contains — FIFA may use short name like "Vini Jr." vs "Vinicius Junior"
        for (Map.Entry<String, Player> e : byNormFull.entrySet()) {
            if (e.getKey().contains(last) || norm.contains(lastName(e.getKey()))) {
                return e.getValue();
            }
        }

        return null;
    }

    private String normalize(String name) {
        return name.toLowerCase()
                .replaceAll("[áàâãä]", "a").replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i").replaceAll("[óòôõö]", "o")
                .replaceAll("[úùûü]", "u").replaceAll("[ç]", "c")
                .replaceAll("[ñ]", "n").replaceAll("[šś]", "s")
                .replaceAll("[žź]", "z").replaceAll("[čć]", "c")
                .replaceAll("[đ]", "d").replaceAll("[ř]", "r")
                .replaceAll("[^a-z\\s]", "").trim();
    }

    private String lastName(String normalized) {
        String[] parts = normalized.split("\\s+");
        return parts[parts.length - 1];
    }

    private String fetch(String url) {
        try {
            WebClient client = WebClient.builder()
                    .defaultHeader("User-Agent", "Mozilla/5.0")
                    .defaultHeader("Accept", "application/json")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                    .build();
            return client.get().uri(url).retrieve().bodyToMono(String.class).block();
        } catch (Exception e) {
            log.error("FIFA fetch failed: {}", e.getMessage());
            return null;
        }
    }
}
