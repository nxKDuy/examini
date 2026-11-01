package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PassageShuffleTest {
    static ExamClient.Question q(String id, String passage) {
        return new ExamClient.Question(UUID.nameUUIDFromBytes(id.getBytes()), 1, "MULTIPLE_CHOICE", id, List.of(), BigDecimal.ONE, 0,
                passage == null ? null : Map.of("passage", Map.of("key", passage)));
    }

    @Test void passageQuestionsStayTogetherInOrderWhileEverythingElseMoves() {
        var list = List.of(q("1", null), q("r1", "A"), q("2", null), q("r2", "A"), q("3", null), q("l1", "B"), q("l2", "B"), q("r3", "A"));
        var seen = new HashSet<List<String>>();
        for (int seed = 0; seed < 40; seed++) {
            var out = SessionService.shuffleKeepingPassages(list, new Random(seed)).stream().map(ExamClient.Question::content).toList();
            assertEquals(8, out.size());
            int a = out.indexOf("r1");
            assertEquals(List.of("r1", "r2", "r3"), out.subList(a, a + 3), "passage A stays one block in its order");
            int b = out.indexOf("l1");
            assertEquals("l2", out.get(b + 1));
            seen.add(out);
        }
        assertTrue(seen.size() > 5, "the order really changes between attempts");
    }
}
