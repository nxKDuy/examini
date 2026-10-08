package com.datn.exam;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Plain Flyway test (no Spring context): legacy participant rows must survive the stateful-participant migration, twice-run safe. */
class ParticipantMigrationTest {
    static final String URL="jdbc:postgresql://localhost:5432/exam_legacy";

    @Test void legacyParticipantsBecomeActiveRowsAndMigrationIsRepeatable()throws Exception {
        var ds=new DriverManagerDataSource(URL,"postgres","");
        Flyway older=Flyway.configure().dataSource(ds).cleanDisabled(false).locations("classpath:db/migration").target("3").load();
        older.clean();older.migrate();
        var jdbc=new JdbcTemplate(ds);
        UUID exam=UUID.randomUUID(),user=UUID.randomUUID();
        jdbc.update("insert into exams(id,code,title,duration_minutes,status,created_by) values(?,?,?,?,?,?)",exam,"LEG"+exam.toString().substring(0,6),"Legacy",30,"DRAFT",UUID.randomUUID());
        jdbc.update("insert into exam_participants(exam_id,user_id,source) values(?,?,?)",exam,user,"ASSIGNED");
        Flyway latest=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        latest.migrate();
        var row=jdbc.queryForMap("select status,source,extra_attempts,created_at from exam_participants where exam_id=? and user_id=?",exam,user);
        assertEquals("ACTIVE",row.get("status"));assertEquals("ASSIGNED",row.get("source"));assertEquals(0,row.get("extra_attempts"));assertNotNull(row.get("created_at"));
        assertEquals(0,latest.migrate().migrationsExecuted); // second run is a no-op
        assertThrows(Exception.class,()->jdbc.update("update exam_participants set status='BOGUS' where exam_id=?",exam));
        assertThrows(Exception.class,()->jdbc.update("insert into exam_attempt_grants(id,exam_id,user_id,quantity,reason,actor_user_id,request_id,created_at) values(?,?,?,0,'r',?,'req-00000001',now())",UUID.randomUUID(),exam,user,UUID.randomUUID()));
    }
}
