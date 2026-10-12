package com.datn.exam;

import com.datn.exam.client.*;
import com.datn.exam.dto.BlueprintDto.*;
import com.datn.exam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","question-bank.internal-token=test-qb"})
@AutoConfigureMockMvc
class BlueprintTest {
    @Autowired BlueprintService blueprints;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @MockBean QuestionBankClient bank;@MockBean SessionClient sessions;
    UUID teacher;Map<String,List<UUID>> pools;Set<UUID> eligible;
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    static List<UUID> ids(int n){return IntStream.range(0,n).mapToObj(i->UUID.randomUUID()).toList();}
    String typeOf(UUID id){return pools.entrySet().stream().filter(e->e.getValue().contains(id)).map(Map.Entry::getKey).findFirst().orElse("MULTIPLE_CHOICE");}

    @BeforeEach void setup(){
        teacher=UUID.randomUUID();pools=Map.of("MULTIPLE_CHOICE",ids(5),"ESSAY",ids(2));
        eligible=new HashSet<>();pools.values().forEach(eligible::addAll);
        // Provider stub per contracts/question-bank-api.md: random eligible ids for the row filter, never an excluded id.
        when(bank.eligibleRandom(any(),eq("test-qb"),eq(teacher))).thenAnswer(call->{
            QuestionBankClient.EligibleRandomRequest r=call.getArgument(0);
            var pool=new ArrayList<>(pools.getOrDefault(r.questionType()==null?"MULTIPLE_CHOICE":r.questionType(),List.of()));
            pool.removeAll(r.excludeQuestionIds());Collections.shuffle(pool);
            return new QuestionBankClient.EligibleRandom(pool.subList(0,Math.min(r.count(),pool.size())),r.count(),pool.size());});
        when(bank.eligible(anyList(),eq("test-qb"),eq(teacher),eq(true),eq(false))).thenAnswer(call->{
            List<UUID> requested=call.getArgument(0);
            return requested.stream().filter(eligible::contains).map(id->new QuestionBankClient.Question(id,2L,"ACTIVE",typeOf(id),"Q "+id,List.of(),null,true,"PERSONAL",null)).toList();});
    }
    Row row(String type,int count,String points,List<UUID> pins){return new Row(null,null,type,null,count,new BigDecimal(points),pins);}

    @Test void rowsReportShortfallWithoutCrossRowBackfillOrDuplicates() {
        var excluded=pools.get("MULTIPLE_CHOICE").getFirst();
        var p=blueprints.preview(new PreviewRequest(List.of(row("MULTIPLE_CHOICE",3,"2",null),row("ESSAY",4,"5",null),row("MULTIPLE_CHOICE",3,"1",null)),false,null,List.of(excluded)),teacher);
        assertEquals(10,p.requestedQuestions());assertEquals(3,p.rows().get(0).picked());assertEquals(0,p.rows().get(0).shortfall());
        assertEquals(2,p.rows().get(1).picked());assertEquals(2,p.rows().get(1).shortfall());assertEquals(2,p.rows().get(1).available());
        assertEquals(1,p.rows().get(2).picked());assertEquals(2,p.rows().get(2).shortfall()); // only 4 MC left after exclusion, row 0 took 3
        assertTrue(p.rows().get(1).questions().stream().allMatch(q->"ESSAY".equals(q.questionType()))); // never filled from another row
        var all=p.questions().stream().map(Question::questionId).toList();
        assertEquals(all.size(),new HashSet<>(all).size());assertFalse(all.contains(excluded));
        assertEquals(new BigDecimal("17"),p.totalPoints());assertEquals(4,p.totalShortfall());assertFalse(p.complete());
    }
    @Test void regenerateOneRowKeepsPinnedDropsIneligibleAndSeedIsDeterministic() {
        var first=blueprints.preview(new PreviewRequest(List.of(row("MULTIPLE_CHOICE",3,"1",null),row("ESSAY",2,"1",null)),true,7L,null),teacher);
        var mc=first.rows().get(0).questions().stream().map(Question::questionId).toList();
        var essay=first.rows().get(1).questions().stream().map(Question::questionId).toList();
        var revoked=UUID.randomUUID(); // a pinned id the provider no longer confirms
        // Swap one MC question: pin the other two, exclude the removed one; ESSAY row is pinned as-is.
        var again=blueprints.preview(new PreviewRequest(List.of(row("MULTIPLE_CHOICE",3,"1",List.of(mc.get(0),mc.get(1))),
                row("ESSAY",2,"1",List.of(essay.get(0),revoked))),true,7L,List.of(mc.get(2))),teacher);
        var r0=again.rows().get(0).questions().stream().map(Question::questionId).toList();
        assertTrue(r0.containsAll(List.of(mc.get(0),mc.get(1))));assertFalse(r0.contains(mc.get(2)));assertEquals(3,r0.size());
        assertEquals(List.of(revoked),again.rows().get(1).droppedPinnedIds());
        assertEquals(Set.copyOf(essay),again.rows().get(1).questions().stream().map(Question::questionId).collect(Collectors.toSet())); // freed slot refilled in-row
        assertTrue(again.rows().get(0).questions().stream().filter(q->mc.subList(0,2).contains(q.questionId())).allMatch(Question::pinned));
        var pinnedAll=new PreviewRequest(List.of(row("MULTIPLE_CHOICE",3,"1",r0)),true,99L,null);
        assertEquals(blueprints.preview(pinnedAll,teacher).questions(),blueprints.preview(pinnedAll,teacher).questions());
        assertEquals(99L,blueprints.preview(pinnedAll,teacher).seed());
    }
    @Test void validationAuthorizationAndSaveTimeEligibilityRecheck()throws Exception {
        String body=json.writeValueAsString(new PreviewRequest(List.of(row("ESSAY",1,"1",null)),false,null,null));
        mvc.perform(post("/exams/blueprint/preview").header("Authorization",token(teacher,"STUDENT")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/exams/blueprint/preview").header("Authorization",token(teacher,"TEACHER")).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].picked").value(1));
        for(var bad:List.of(row("ESSAY",0,"1",null),row("ESSAY",1,"0",null),row("WEBCAM",1,"1",null),row("ESSAY",1,"1",List.of(UUID.randomUUID(),UUID.randomUUID()))))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->blueprints.preview(new PreviewRequest(List.of(bad),false,null,null),teacher)).getStatusCode().value());
        var dup=UUID.randomUUID();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->blueprints.preview(new PreviewRequest(List.of(row("ESSAY",1,"1",List.of(dup)),row("ESSAY",1,"1",List.of(dup))),false,null,null),teacher)).getStatusCode().value());
        UUID ok=pools.get("ESSAY").getFirst(),gone=UUID.randomUUID();
        String create="{\"title\":\"Blueprint\",\"durationMinutes\":10,\"type\":\"EXAM\",\"questions\":[{\"questionId\":\""+ok+"\",\"weight\":2},{\"questionId\":\""+gone+"\",\"weight\":1}]}";
        mvc.perform(post("/exams/manual").header("Authorization",token(teacher,"TEACHER")).contentType("application/json").content(create)).andExpect(status().isConflict());
        String valid="{\"title\":\"Blueprint\",\"durationMinutes\":10,\"type\":\"EXAM\",\"questions\":[{\"questionId\":\""+ok+"\",\"weight\":2}]}";
        var id=json.readTree(mvc.perform(post("/exams/manual").header("Authorization",token(teacher,"TEACHER")).contentType("application/json").content(valid)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(put("/exams/"+id).header("Authorization",token(teacher,"TEACHER")).contentType("application/json").content(create)).andExpect(status().isConflict());
        eligible.remove(ok); // already-saved questions are re-checked at Publish, not on every edit
        mvc.perform(put("/exams/"+id).header("Authorization",token(teacher,"TEACHER")).contentType("application/json").content(valid.replace("Blueprint","Renamed"))).andExpect(status().isOk());
    }
}
