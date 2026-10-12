"""Real Exam/Session/Grading HTTP smoke test on isolated task-owned services.
Usage: python3 phase2_http_e2e.py <restricted synthetic fixture JSON> [--provider]
Provider mode serves a fixed APPROVED fixture via the public Question Bank contract;
it is test data, not provider implementation or access to provider persistence.
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

fixture = json.loads(Path(sys.argv[1]).read_text())
if "--provider" in sys.argv:
    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            if not self.path.startswith("/questions/batch?") or self.headers.get("X-Service-Token") != fixture["qbToken"]:
                self.send_error(403); return
            ids = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            data = [{"id":fixture["questionId"],"questionVersion":1,"status":"APPROVED",
                     "questionType":"SHORT_ANSWER","content":"Compute 2 + 2",
                     "options":[],"expectedAnswer":"4"}] if ids == [fixture["questionId"]] else []
            body = json.dumps(data).encode()
            self.send_response(200); self.send_header("Content-Type","application/json")
            self.end_headers(); self.wfile.write(body)
    HTTPServer(("0.0.0.0",18082),Provider).serve_forever()
    sys.exit()

def bearer(actor, role):
    def part(data): return base64.urlsafe_b64encode(json.dumps(data,separators=(",",":")).encode()).rstrip(b"=")
    body = part({"alg":"HS256"}) + b"." + part({"sub":actor,"role":role,"type":"access","exp":int(time.time())+600})
    return "Bearer " + (body+b"."+base64.urlsafe_b64encode(hmac.new(fixture["jwtSecret"].encode(),body,hashlib.sha256).digest()).rstrip(b"=")).decode()

def call(port,path,actor,role="TEACHER",method="GET",body=None,expected=200):
    headers={"Authorization":bearer(actor,role),"Content-Type":"application/json"}
    req=Request(f"http://127.0.0.1:{port}{path}",headers=headers,method=method,
                data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(req,timeout=10) as response:
            assert response.status==expected
            data=response.read(); return json.loads(data) if data else None
    except HTTPError as error:
        assert error.code==expected,(path,error.code,error.read().decode())
        return None

owner,manager,proctor,student,other=[str(uuid.uuid4()) for _ in range(5)]
for port,path in [(28083,"/exams"),(28084,"/sessions/missing"),(28085,"/grading")]:
    for attempt in range(60):
        try:
            with urlopen(f"http://127.0.0.1:{port}{path}",timeout=1): pass
            break
        except HTTPError: break
        except (URLError,TimeoutError): time.sleep(1)
    else: raise AssertionError(f"Service {port} never became ready")

exam=call(28083,"/exams/manual",owner,method="POST",body={"title":"Phase 2 HTTP integrity",
    "durationMinutes":10,"type":"EXAM","questions":[{"questionId":fixture["questionId"],"weight":5}]})["id"]
for actor,role in [(manager,"MANAGER"),(proctor,"PROCTOR")]:
    call(28083,f"/exams/{exam}/members",owner,method="PUT",body={"userId":actor,"role":role})
    call(28083,f"/exams/{exam}/members/accept",actor,method="POST")
policy=dict(monitorTabSwitch=True,monitorWindowBlur=True,requireFullscreen=True,
            monitorCopy=True,monitorPaste=True,monitorNetwork=True,warningPolicy="WARN_ONLY")
call(28083,f"/exams/{exam}/integrity/policy",manager,method="PUT",body=policy)
call(28083,f"/exams/{exam}/participants/{student}",manager,method="PUT")
call(28083,f"/exams/{exam}/publish",manager,method="PUT")
call(28083,f"/exams/{exam}/integrity/policy",owner,method="PUT",body=policy,expected=409)
call(28083,f"/exams/{exam}/open",owner,method="PUT")
assert call(28083,f"/exams/{exam}/integrity/policy",student,"STUDENT")==policy
started=call(28084,f"/sessions/start?examId={exam}",student,"STUDENT",method="POST")
sid=started["sessionId"]
assert call(28084,f"/sessions/{sid}/integrity/policy",student,"STUDENT")==policy
assert "expectedAnswer" not in json.dumps(started) and "correct" not in json.dumps(started)
event={"clientEventId":str(uuid.uuid4()),"type":"TAB_SWITCH","occurredAt":(datetime.now(timezone.utc)+timedelta(days=8)).isoformat()}
call(28084,f"/sessions/{sid}/integrity/events",student,"STUDENT",method="POST",body=event,expected=400)
event.pop("occurredAt")
receipt=call(28084,f"/sessions/{sid}/integrity/events",student,"STUDENT",method="POST",body=event)
retry=call(28084,f"/sessions/{sid}/integrity/events",student,"STUDENT",method="POST",body=event)
assert receipt["event"]["id"]==retry["event"]["id"] and retry["duplicate"]
call(28084,f"/sessions/{sid}/integrity/events",other,"STUDENT",method="POST",body=event,expected=403)
for actor in [owner,manager,proctor]:
    summary=call(28084,f"/sessions/{sid}/integrity/summary",actor)
    assert summary["counts"]["TAB_SWITCH"]==1 and summary["totalEvents"]==1
    assert call(28084,f"/sessions/exam/{exam}/integrity/events",actor)["totalElements"]==1
call(28084,f"/sessions/{sid}/integrity/summary",other,expected=403)
call(28084,f"/sessions/{sid}/integrity/summary",student,"STUDENT",expected=403)
call(28083,f"/exams/{exam}/close",manager,method="PUT")
assert call(28084,f"/sessions/start?examId={exam}",student,"STUDENT",method="POST")["sessionId"]==sid
call(28084,f"/sessions/{sid}/answers?questionId={fixture['questionId']}",student,"STUDENT",method="POST",body={"content":"4"})
assert call(28084,f"/sessions/{sid}",student,"STUDENT")["answers"][fixture["questionId"]]=="4"
call(28084,f"/sessions/{sid}/submit",student,"STUDENT",method="POST")
call(28084,f"/sessions/{sid}/submit",student,"STUDENT",method="POST")
new_event={"clientEventId":str(uuid.uuid4()),"type":"PASTE"}
call(28084,f"/sessions/{sid}/integrity/events",student,"STUDENT",method="POST",body=new_event,expected=409)
assert call(28084,f"/sessions/{sid}/integrity/events",student,"STUDENT",method="POST",body=event)["duplicate"]
for attempt in range(30):
    result=call(28085,f"/grades/session/{sid}",student,"STUDENT")
    if result["status"]=="GRADED": break
    time.sleep(1)
else: raise AssertionError("RabbitMQ grading never completed")
assert result["score"]==5
print("PASS real HTTP: policy transfer/freeze, authenticated ingestion, retry, staff review, foreign/student denial")
print("PASS Phase 1 runtime regression: frozen safe snapshot, close/resume, answer persistence, duplicate submit, RabbitMQ grading/result score 5")
