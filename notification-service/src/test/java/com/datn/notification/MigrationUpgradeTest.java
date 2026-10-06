package com.datn.notification;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Upgrades a database that already ran V1 (with inbox data) to V2 on real PostgreSQL; V1 rows and checksum stay intact. */
class MigrationUpgradeTest {
    static final String URL=System.getenv().getOrDefault("TEST_DB_URL","jdbc:postgresql://localhost:5432/notification_test");
    static final String USER=System.getenv().getOrDefault("TEST_DB_USER","postgres"),PASSWORD=System.getenv().getOrDefault("TEST_DB_PASSWORD","");

    @Test void existingV1DatabaseUpgradesAdditively()throws Exception{
        String admin=URL.substring(0,URL.lastIndexOf('/')+1)+"postgres",db="notification_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,8);
        try(Connection c=DriverManager.getConnection(admin,USER,PASSWORD);Statement s=c.createStatement()){s.execute("CREATE DATABASE "+db);}
        String url=URL.substring(0,URL.lastIndexOf('/')+1)+db;
        try{
            Flyway.configure().dataSource(url,USER,PASSWORD).locations("classpath:db/migration").target("1").load().migrate();
            UUID id=UUID.randomUUID(),event=UUID.randomUUID(),user=UUID.randomUUID();
            try(Connection c=DriverManager.getConnection(url,USER,PASSWORD);PreparedStatement p=c.prepareStatement(
                "INSERT INTO notifications(id,recipient_user_id,type,title,message,source_event_id,source_event_type) VALUES(?,?,'EXAM_ASSIGNED','t','m',?,'exam.participant.assigned')")){
                p.setObject(1,id);p.setObject(2,user);p.setObject(3,event);p.executeUpdate();
            }
            var result=Flyway.configure().dataSource(url,USER,PASSWORD).locations("classpath:db/migration").load().migrate();
            assertEquals("2",result.targetSchemaVersion);
            // validate() also fails on duplicate versions or edited checksums.
            Flyway.configure().dataSource(url,USER,PASSWORD).locations("classpath:db/migration").load().validate();
            try(Connection c=DriverManager.getConnection(url,USER,PASSWORD);Statement s=c.createStatement()){
                var r=s.executeQuery("SELECT recipient_user_id,read_at FROM notifications WHERE id='"+id+"'");assertTrue(r.next());assertEquals(user,r.getObject(1));assertNull(r.getObject(2));
                r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('exam_schedule_state','scheduling_jobs','push_subscriptions','notification_deliveries')");
                r.next();assertEquals(4,r.getInt(1));
                r=s.executeQuery("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success");r.next();assertEquals("1,2",r.getString(1));
            }
        }finally{
            try(Connection c=DriverManager.getConnection(admin,USER,PASSWORD);Statement s=c.createStatement()){s.execute("DROP DATABASE IF EXISTS "+db+" WITH (FORCE)");}
        }
    }
}
