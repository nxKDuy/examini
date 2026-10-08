package com.datn.examsession;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Plain Flyway test: existing sessions survive the invalidation columns and the repeatable migration is a no-op on re-run. */
class AttemptMigrationTest {
    @Test void legacySessionsStayValidAndConstraintRejectsInvalidationWithoutReason()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:postgresql://localhost:5432/session_legacy","postgres","");
        Flyway older=Flyway.configure().dataSource(ds).cleanDisabled(false).locations("classpath:db/migration").target("2").load();
        older.clean();older.migrate();
        var jdbc=new JdbcTemplate(ds);
        UUID id=UUID.randomUUID();
        jdbc.update("insert into exam_sessions(id,exam_id,user_id,start_time,status,expires_at,attempt_number) values(?,?,?,now(),'SUBMITTED',now(),1)",id,UUID.randomUUID(),UUID.randomUUID());
        Flyway latest=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        latest.migrate();
        var row=jdbc.queryForMap("select status,invalidated_at,invalidation_reason from exam_sessions where id=?",id);
        assertEquals("SUBMITTED",row.get("status"));assertNull(row.get("invalidated_at"));assertNull(row.get("invalidation_reason"));
        assertEquals(0,latest.migrate().migrationsExecuted);
        assertThrows(Exception.class,()->jdbc.update("update exam_sessions set invalidated_at=now() where id=?",id)); // reason is mandatory
        assertEquals(1,jdbc.update("update exam_sessions set invalidated_at=now(),invalidated_by=?,invalidation_reason='Public reason' where id=?",UUID.randomUUID(),id));
        assertEquals(0,jdbc.queryForObject("select count(*) from attempt_events",Integer.class));
    }
}
