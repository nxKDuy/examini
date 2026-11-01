package com.datn.questionbank.service.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrieverTest {
    @Test void teacherMaterialIsUsedAsIsWhenShortAndNeverTriggersWebSearch() {
        var wiki = mock(WikipediaClient.class);
        var k = new KnowledgeRetriever(wiki, true).retrieve("phân số", "Phân số a/b có b khác 0. Hai phân số bằng nhau khi tích chéo bằng nhau.", "vi", true);
        assertEquals("[1] Tài liệu của giáo viên: Phân số a/b có b khác 0. Hai phân số bằng nhau khi tích chéo bằng nhau.", k.text());
        assertEquals(List.of(new KnowledgeRetriever.Source("Tài liệu của giáo viên", null)), k.sources());
        verifyNoInteractions(wiki);
        assertNull(new KnowledgeRetriever(wiki, true).retrieve("x", null, "vi", false).text(), "web only when asked");
    }

    @Test void longMaterialKeepsTheMostRelevantPassagesWithinBudgetInOriginalOrder() {
        var sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("Đoạn ").append(i).append(" nói về thời tiết, mưa nắng và khí hậu nhiệt đới gió mùa ở nhiều vùng. ")
                .append(i % 10 == 0 ? "Quang hợp là quá trình cây xanh dùng ánh sáng để tạo chất hữu cơ từ khí cacbonic và nước. " : "")
                .append("\n\n");
        var passages = KnowledgeRetriever.split("doc", sb.toString(), 0);
        assertTrue(passages.stream().allMatch(p -> p.text().length() <= KnowledgeRetriever.PASSAGE_CHARS * 2));
        var chosen = KnowledgeRetriever.select("quang hợp cây xanh", passages);
        assertTrue(chosen.stream().mapToInt(p -> p.text().length()).sum() <= KnowledgeRetriever.BUDGET_CHARS);
        assertTrue(chosen.stream().filter(p -> p.text().contains("Quang hợp")).count() >= 3, "relevant passages win");
        var orders = chosen.stream().map(KnowledgeRetriever.Passage::order).toList();
        assertEquals(orders.stream().sorted().toList(), orders);
    }

    @Test void webWarningsAreKeptAndEmptyResultsGiveNoKnowledge() {
        var wiki = mock(WikipediaClient.class);
        when(wiki.search("abc", "en")).thenReturn(new WikipediaClient.Search(List.of(), "Không truy cập được Wikipedia"));
        var k = new KnowledgeRetriever(wiki, true).retrieve("abc", "  ", "en", true);
        assertNull(k.text());
        assertEquals(List.of("Không truy cập được Wikipedia"), k.warnings());
    }
}
