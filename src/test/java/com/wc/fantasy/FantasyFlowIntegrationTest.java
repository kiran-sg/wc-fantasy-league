package com.wc.fantasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FantasyFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    private String getToken() throws Exception {
        // Register or login
        MvcResult res = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "flowuser",
                        "password", "pass123",
                        "displayName", "Flow User"))))
                .andReturn();

        if (res.getResponse().getStatus() != 200) {
            res = mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "username", "flowuser",
                            "password", "pass123"))))
                    .andReturn();
        }
        Map<String, Object> body = mapper.readValue(res.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    @Test
    @Order(1)
    void getTeams_returnsSeededData() throws Exception {
        String token = getToken();
        mvc.perform(get("/api/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    @Order(2)
    void getPlayersByTeam_returnsPlayers() throws Exception {
        String token = getToken();
        // Team ID 1 should be Brazil from the seeder
        mvc.perform(get("/api/players/team/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(11))))
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].position").exists());
    }

    @Test
    @Order(3)
    void getMatches_returnsSeededMatches() throws Exception {
        String token = getToken();
        mvc.perform(get("/api/matches").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[0].status", is("UPCOMING")));
    }

    @Test
    @Order(4)
    void getMatchesByStatus_filtersCorrectly() throws Exception {
        String token = getToken();
        mvc.perform(get("/api/matches/status/UPCOMING").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));

        mvc.perform(get("/api/matches/status/COMPLETED").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(5)
    void saveSquad_with11Players_succeeds() throws Exception {
        String token = getToken();

        // Get user ID
        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "flowuser", "password", "pass123"))))
                .andReturn();
        Map<String, Object> loginBody = mapper.readValue(loginRes.getResponse().getContentAsString(), Map.class);
        int userId = (int) loginBody.get("userId");

        // Get players from both teams for match 1 (Brazil vs Germany)
        MvcResult playersRes = mvc.perform(get("/api/players/team/1")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        List<Map<String, Object>> teamAPlayers = mapper.readValue(
                playersRes.getResponse().getContentAsString(), List.class);

        MvcResult playersRes2 = mvc.perform(get("/api/players/team/2")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        List<Map<String, Object>> teamBPlayers = mapper.readValue(
                playersRes2.getResponse().getContentAsString(), List.class);

        // Pick 6 from team A and 5 from team B
        List<Integer> playerIds = new java.util.ArrayList<>();
        for (int i = 0; i < 6 && i < teamAPlayers.size(); i++)
            playerIds.add((int) teamAPlayers.get(i).get("id"));
        for (int i = 0; i < 5 && i < teamBPlayers.size(); i++)
            playerIds.add((int) teamBPlayers.get(i).get("id"));

        int captainId = playerIds.get(0);

        mvc.perform(post("/api/squads")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "userId", userId,
                        "matchId", 1,
                        "playerIds", playerIds,
                        "captainId", captainId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(11)))
                .andExpect(jsonPath("$.captain.id", is(captainId)));
    }

    @Test
    @Order(6)
    void saveSquad_withWrongCount_fails() throws Exception {
        String token = getToken();
        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "flowuser", "password", "pass123"))))
                .andReturn();
        Map<String, Object> loginBody = mapper.readValue(loginRes.getResponse().getContentAsString(), Map.class);
        int userId = (int) loginBody.get("userId");

        // Only 3 players - should fail
        mvc.perform(post("/api/squads")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "userId", userId,
                        "matchId", 2,
                        "playerIds", List.of(1, 2, 3),
                        "captainId", 1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    void getLeaderboard_returnsUsers() throws Exception {
        String token = getToken();
        mvc.perform(get("/api/leaderboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @Order(8)
    void getSquad_afterSaving_returnsIt() throws Exception {
        String token = getToken();
        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "flowuser", "password", "pass123"))))
                .andReturn();
        Map<String, Object> loginBody = mapper.readValue(loginRes.getResponse().getContentAsString(), Map.class);
        int userId = (int) loginBody.get("userId");

        mvc.perform(get("/api/squads/" + userId + "/1")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(11)));
    }
}
