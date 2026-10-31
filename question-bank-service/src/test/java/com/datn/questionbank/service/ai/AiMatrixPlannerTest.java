package com.datn.questionbank.service.ai;

import com.datn.questionbank.security.QuestionActor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiMatrixPlannerTest {
    final UUID ham = UUID.randomUUID(), log = UUID.randomUUID();
    final QuestionActor teacher = new QuestionActor(UUID.randomUUID(), "TEACHER", false);
    final AiMatrixPlanner.Request req = new AiMatrixPlanner.Request("Đề 15 phút: 6 câu dễ về hàm số, 3 câu trung bình về logarit, thang điểm 10",
            List.of(new AiMatrixPlanner.Topic(ham, "Hàm số", Map.of("EASY", 4, "MEDIUM", 9)), new AiMatrixPlanner.Topic(log, "Logarit", Map.of("MEDIUM", 5))), null);

    @Test void onlyListedTopicsAndLevelsSurviveAndShortfallsAreNoted() {
        var prompt = new AtomicReference<String>();
        var planner = new AiMatrixPlanner((s, u) -> { prompt.set(u); return Optional.of("""
                {"cells":[{"categoryId":"%s","level":"easy","count":6},{"categoryId":"%s","level":"MEDIUM","count":3},
                          {"categoryId":"%s","level":"EASY","count":9},{"categoryId":"%s","level":"EXPERT","count":2},{"categoryId":"%s","level":"HARD","count":0}],
                 "points":{"EASY":1.11111,"MEDIUM":1.11,"HARD":-1},"note":"6 câu dễ hàm số, 3 câu trung bình logarit."}
                """.formatted(ham, log, UUID.randomUUID(), ham, log)); }, null);
        var plan = planner.plan(req, teacher);
        assertTrue(prompt.get().contains(ham + " | Hàm số | 4/9/0"), prompt.get());
        assertEquals(List.of(new AiMatrixPlanner.Cell(ham, "EASY", 6), new AiMatrixPlanner.Cell(log, "MEDIUM", 3)), plan.cells(), "unknown topic, bad level and zero counts dropped");
        assertEquals(Map.of("EASY", 1.11, "MEDIUM", 1.11), plan.points(), "invalid points dropped, others rounded");
        assertEquals("6 câu dễ hàm số, 3 câu trung bình logarit.", plan.notes().getFirst());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("Hàm số") && n.contains("4/6")), plan.notes().toString());
    }

    @Test void refusesWhenNothingUsableOrNoAi() {
        var nothing = new AiMatrixPlanner((s, u) -> Optional.of("{\"cells\":[]}"), null);
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> nothing.plan(req, teacher)).getStatusCode().value());
        var offline = new AiMatrixPlanner((s, u) -> Optional.empty(), null);
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> offline.plan(req, teacher)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> offline.plan(new AiMatrixPlanner.Request(" ", req.topics(), null), teacher)).getStatusCode().value());
    }
}
