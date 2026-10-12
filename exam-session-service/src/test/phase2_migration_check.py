"""Upgrade verification on a disposable task-owned PostgreSQL container only.
Usage: python3 exam-session-service/src/test/phase2_migration_check.py <owned-container>
Creates two uniquely named disposable databases, applies committed Phase 1 schemas,
adds representative active/historical data, upgrades and checks preservation.
"""
from pathlib import Path
import subprocess
import sys
import uuid

container = sys.argv[1]
root = Path(__file__).resolve().parents[3]

def command(*args, sql=None):
    result = subprocess.run(["docker", "exec", "-i", container, *args], input=sql,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

for domain, old_versions, new_version in [("exam-service", [1, 2, 3], 4),
                                           ("exam-session-service", [1, 2], 3)]:
    db = "integrity_upgrade_" + uuid.uuid4().hex
    command("createdb", "-U", "postgres", db)
    try:
        def sql(body):
            return command("psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-U", "postgres", "-d", db, sql=body)
        migrations = root / domain / "src/main/resources/db/migration"
        for version in old_versions:
            sql(next(migrations.glob(f"V{version}__*.sql")).read_text())
        if domain == "exam-service":
            sql("INSERT INTO exams(code,title,duration_minutes,status,created_by,require_fullscreen,block_tab_switch) VALUES ('UPGRADE','Existing published exam',10,'PUBLISHED',gen_random_uuid(),true,true);")
            table = "exams"
        else:
            sql("INSERT INTO exam_sessions(exam_id,user_id,start_time,status,expires_at,attempt_number,exam_title) VALUES (gen_random_uuid(),gen_random_uuid(),now(),'IN_PROGRESS',now()+interval '10 minutes',1,'Active attempt'), (gen_random_uuid(),gen_random_uuid(),now(),'SUBMITTED',now(),1,'Historical attempt');")
            table = "exam_sessions"
        before = sql(f"SELECT id::text || ':' || status FROM {table} ORDER BY id")
        sql(next(migrations.glob(f"V{new_version}__*.sql")).read_text())
        assert before == sql(f"SELECT id::text || ':' || status FROM {table} ORDER BY id")
        assert sql(f"SELECT bool_and(integrity_policy->>'warningPolicy'='LOG_ONLY' AND integrity_policy->>'monitorTabSwitch'='false' AND integrity_policy->>'requireFullscreen'='false') FROM {table}") == "t"
        print(f"PASS {domain}: Phase 1 upgrade preserves identities/status and disables new monitors")
    finally:
        command("dropdb", "-U", "postgres", db)
