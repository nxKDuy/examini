"""Run Phase 2 HTTP and Phase 1/MQ regression on disposable isolated Docker resources.
Prerequisite: Maven verify/packages for exam-service, exam-session-service, grading-service.
No host ports, foreign databases or production containers are used. No tokens are printed.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

root=Path(__file__).resolve().parents[3]
prefix="examini-integrity-"+uuid.uuid4().hex[:10]
owned=[]

def docker(*args):
    result=subprocess.run(["docker",*args],text=True,capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

def start(suffix,*args):
    name=prefix+"-"+suffix
    docker("run","-d","--name",name,*args)
    owned.append(name)
    return name

for service in ["exam-service","exam-session-service","grading-service"]:
    assert (root/service/"target"/(service+"-1.0.0-SNAPSHOT.jar")).is_file(),"Run Maven verify first"

try:
    with tempfile.TemporaryDirectory(prefix="examini-integrity-") as directory:
        temp=Path(directory)
        fixture=dict(jwtSecret=os.urandom(48).hex(),examToken=os.urandom(32).hex(),
                     qbToken=os.urandom(32).hex(),questionId=str(uuid.uuid4()))
        (temp/"fixture.json").write_text(json.dumps(fixture));(temp/"fixture.json").chmod(0o600)
        env=temp/"runtime.env"
        env.write_text("JWT_SECRET="+fixture["jwtSecret"]+"\nEXAM_INTERNAL_TOKEN="+fixture["examToken"]+
                       "\nQB_INTERNAL_TOKEN="+fixture["qbToken"]+"\nSPRING_DATASOURCE_USERNAME=postgres\nSPRING_DATASOURCE_PASSWORD=\nEUREKA_CLIENT_ENABLED=false\nSPRING_CLOUD_DISCOVERY_ENABLED=false\n")
        env.chmod(0o600)
        pg=start("pg","-e","POSTGRES_HOST_AUTH_METHOD=trust","postgres:16-alpine")
        for _ in range(60):
            ready=subprocess.run(["docker","exec",pg,"pg_isready","-U","postgres"],capture_output=True)
            if ready.returncode==0: break
            time.sleep(1)
        else: raise RuntimeError("Disposable PostgreSQL never became ready")
        for db in ["exam_runtime","session_runtime","grading_runtime"]:
            docker("exec",pg,"createdb","-U","postgres",db)
        network=["--network","container:"+pg]
        mounts=["-v",str(root)+":/workspace:ro"]
        fixture_mount=["-v",str(temp)+":/fixture:ro"]
        start("rabbit",*network,"rabbitmq:3.13-alpine")
        start("provider",*network,*mounts,*fixture_mount,"python:3.12-alpine","python",
              "/workspace/exam-session-service/src/test/phase2_http_e2e.py","/fixture/fixture.json","--provider")
        for suffix,service,port,db,urls in [
            ("exam","exam-service",28083,"exam_runtime",["--question-bank.url=http://localhost:18082","--exam-session.url=http://localhost:28084"]),
            ("session","exam-session-service",28084,"session_runtime",["--exam-service.url=http://localhost:28083"]),
            ("grading","grading-service",28085,"grading_runtime",["--exam-service.url=http://localhost:28083","--exam-session.url=http://localhost:28084"])]:
            start(suffix,*network,"--env-file",str(env),*mounts,"maven:3.9-eclipse-temurin-21",
                  "java","-jar",f"/workspace/{service}/target/{service}-1.0.0-SNAPSHOT.jar",
                  f"--server.port={port}",f"--spring.datasource.url=jdbc:postgresql://localhost:5432/{db}",*urls)
        print(docker("run","--rm",*network,*mounts,*fixture_mount,"python:3.12-alpine","python",
                     "/workspace/exam-session-service/src/test/phase2_http_e2e.py","/fixture/fixture.json"))
finally:
    for name in reversed(owned):
        subprocess.run(["docker","rm","-f",name],capture_output=True)
