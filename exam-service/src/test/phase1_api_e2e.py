"""Isolated real API/MQ smoke test. Pass a restricted synthetic fixture JSON path.
Requires separately launched Exam/Session/Grading and the provider's isolated API.
Never prints credentials or answer keys; never touches any database.
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from concurrent.futures import ThreadPoolExecutor

fixture = json.loads(Path(sys.argv[1]).read_text())
def signed(user, role):
    def encode(data):
        return base64.urlsafe_b64encode(json.dumps(data, separators=(',', ':')).encode()).rstrip(b'=')
    body = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode({'sub': str(user), 'role': role, 'type': 'access', 'exp': int(time.time()) + 3600})
    return (body + b'.' + base64.urlsafe_b64encode(hmac.new(fixture['jwtSecret'].encode(), body, hashlib.sha256).digest()).rstrip(b'=')).decode()
owner = fixture['ownerId']
manager, proctor, student, other = [str(uuid.uuid4()) for _ in range(4)]
tokens = {owner: signed(owner, 'TEACHER'), manager: signed(manager, 'TEACHER'), proctor: signed(proctor, 'TEACHER'), student: signed(student, 'STUDENT'), other: signed(other, 'STUDENT')}
steps = []
def api(port, path, actor=None, method='GET', body=None, expect=200, internal=False):
    headers = {'Content-Type': 'application/json'}
    if actor: headers['Authorization'] = 'Bearer ' + tokens[actor]
    if internal: headers['X-Service-Token'] = fixture['examInternalToken']
    req = Request(f'http://127.0.0.1:{port}{path}', data=json.dumps(body).encode() if body is not None else (b'' if method in ['POST', 'PUT'] else None), headers=headers, method=method)
    try:
        response = urlopen(req, timeout=15)
        code, raw = response.status, response.read()
    except HTTPError as failure:
        code, raw = failure.code, failure.read()
    assert code == expect, f'{method} {path}: expected {expect}, got {code}'
    return json.loads(raw) if raw else None

def step(name):
    steps.append(name)
    print('PASS', name, flush=True)

automatic=api(28083,'/exams/auto',owner,'POST',{'title':'Isolated approved auto generation','durationMinutes':10,'categoryId':fixture['categoryId'],'easyCount':1,'mediumCount':0,'hardCount':0})
assert automatic['status']=='DRAFT' and len(automatic['questionIds'])==1
api(28083,f"/exams/{automatic['id']}/publish",owner,'PUT')
step('Real provider APPROVED random auto generation / frozen publish')
qid = fixture['questionId']
created = api(28083, '/exams/manual', owner, 'POST', {'title': 'Phase 1 isolated E2E', 'durationMinutes': 10, 'type': 'EXAM', 'passingScore': 1, 'questions': [{'questionId': qid, 'weight': 2.5}]})
exam = created['id']
assert created['ownerUserId'] == owner and created['status'] == 'DRAFT'
step('Create Exam / creator becomes sole OWNER')
for actor, role in [(manager, 'MANAGER'), (proctor, 'PROCTOR')]:
    api(28083, f'/exams/{exam}/members', owner, 'PUT', {'userId': actor, 'role': role})
    api(28083, f'/exams/{exam}/members/accept', actor, 'POST')
assert api(28083, '/exams', manager)['totalElements'] >= 1
api(28083, f'/exams/{exam}/members', manager, 'PUT', {'userId': manager, 'role': 'MANAGER'}, expect=403)
api(28083, f'/exams/{exam}/open', proctor, 'PUT', expect=403)
api(28083, f'/exams/{exam}/open', owner, 'PUT', expect=409)
step('MANAGER/PROCTOR invitations, lists and backend forbidden actions')
api(28083, f'/exams/{exam}/policy', manager, 'PUT', {'accessMode': 'PUBLIC', 'maxParticipants': 10, 'maxConcurrentSessions': 1, 'attemptLimit': 2})
api(28083, f'/exams/{exam}/policy', proctor, 'PUT', {'accessMode': 'PUBLIC', 'maxParticipants': 10, 'maxConcurrentSessions': 1, 'attemptLimit': 2}, expect=403)
api(28083, f'/exams/{exam}/policy', owner, 'PUT', {'accessMode': 'PUBLIC', 'maxParticipants': 200, 'maxConcurrentSessions': 101, 'attemptLimit': 1}, expect=400)
step('Policy and platform capacity configured / enforced')
api(28083, f'/exams/{exam}/publish', manager, 'PUT')
api(28084, f'/sessions/start?examId={exam}', student, 'POST', expect=409)
api(28083, f'/exams/{exam}/policy', owner, 'PUT', {'accessMode': 'PUBLIC', 'maxParticipants': 10, 'maxConcurrentSessions': 1, 'attemptLimit': 2}, expect=409)
api(28083, f'/exams/{exam}/open', manager, 'PUT')
api(28083, f'/exams/{exam}/internal/grading', owner, expect=401)
api(28083, f'/exams/{exam}/%69nternal/grading', owner, expect=401)
api(28083, f'/exams/{exam}/internal/grading', owner, internal=True, expect=401)
import http.client
connection=http.client.HTTPConnection('127.0.0.1',28083)
connection.putrequest('GET','/exams')
connection.putheader('Authorization','Bearer '+tokens[owner])
connection.putheader('Authorization','Bearer '+tokens[owner])
connection.endheaders()
assert connection.getresponse().status==401
connection.close()
step('Mapped encoded internal routes, mixed credentials and duplicate Authorization headers reject browser access')
step('Real APPROVED provider publish / immutable points / distinct OPEN')
assert any(e['id'] == exam for e in api(28083, '/exams/public', student))
api(28083, '/exams/public', expect=401)
for actor in [student, other]:
    api(28083, f'/exams/{exam}/join', actor, 'POST')
    api(28083, f'/exams/{exam}/preflight', actor, 'POST')
    assert api(28083, f'/exams/{exam}/lobby', actor)['questionIds'] == [qid]
assert api(28084, f'/sessions/exam/{exam}/monitor', proctor) == []
step('Discover / authenticated join / eligibility / Lobby / preflight consume no slot')
# Two real HTTP starts race against a single DB-serialized slot.
def start(actor):
    try: return actor, api(28084, f'/sessions/start?examId={exam}', actor, 'POST')
    except AssertionError as error:
        assert 'got 409' in str(error)
        return actor, None
with ThreadPoolExecutor(2) as pool: starts = list(pool.map(start, [student, other]))
winners = [(actor, result) for actor, result in starts if result]
assert len(winners) == 1
active, session = winners[0]
loser = other if active == student else student
sid = session['sessionId']
def safe_keys(value):
    if isinstance(value, dict):
        assert not {'correct','expectedAnswer','rubric','gradingKey'}.intersection(value)
        for item in value.values(): safe_keys(item)
    elif isinstance(value,list):
        for item in value: safe_keys(item)
safe_keys(session)
assert session['questions'][0]['questionVersion'] == fixture['questionVersion']
step('Race-safe capacity: exactly one Start wins; safe frozen session snapshot')
api(28084, f'/sessions/{sid}/answers?questionId={qid}', active, 'POST', {'content': fixture['correctOptionId']})
restored = api(28084, f'/sessions/{sid}', active)
assert restored['answers'][qid] == fixture['correctOptionId']
assert api(28084, f'/sessions/start?examId={exam}', active, 'POST')['sessionId'] == sid
api(28084, f'/sessions/{sid}/answers?questionId={uuid.uuid4()}', active, 'POST', {'content': 'bad'}, expect=400)
step('Answer persistence / refresh-resume / question membership validation')
api(28083, f'/exams/{exam}/close', manager, 'PUT')
api(28084, f'/sessions/start?examId={exam}', loser, 'POST', expect=409)
assert api(28084, f'/sessions/start?examId={exam}', active, 'POST')['sessionId'] == sid
api(28084, f'/sessions/{sid}/answers?questionId={qid}', active, 'POST', {'content': fixture['correctOptionId']})
assert api(28084, f'/sessions/exam/{exam}/monitor', proctor)[0]['status'] == 'IN_PROGRESS'
api(28085, f'/submissions?examId={exam}', proctor, expect=403)
step('CLOSE prevents new starts but active session continues / PROCTOR monitor-only')
api(28084, f'/sessions/{sid}/submit', active, 'POST')
api(28084, f'/sessions/{sid}/submit', active, 'POST')
api(28084, f'/sessions/{sid}/answers?questionId={qid}', active, 'POST', {'content': ''}, expect=409)
step('Idempotent manual Submit / terminal answer rejection')
for _ in range(40):
    result = api(28085, f'/grades/session/{sid}', active)
    if result['status'] == 'GRADED': break
    time.sleep(.5)
else: raise AssertionError('Real MQ grading did not finish')
assert result['score'] == 2.5 and result['totalScore'] == 2.5
api(28085, f'/grades/session/{sid}', loser, expect=403)
assert len(api(28085, f'/submissions?examId={exam}', manager)['content']) == 1
assert api(28084, f'/sessions/exam/{exam}/monitor', owner)[0]['status'] == 'SUBMITTED'
step('Real RabbitMQ/outbox grading / one weighted result / teacher submissions / student ownership')
# Return only fixture navigation identities; auth remains in a restricted file.
fixture['examId'], fixture['sessionId'], fixture['activeStudentId'] = exam, sid, active
fixture['managerId'], fixture['proctorId'] = manager, proctor
fixture['testTokens'] = tokens
path = Path('/tmp/examini-et-phase1-browser-fixture.json')
path.write_text(json.dumps(fixture)); path.chmod(0o600)
Path('/tmp/examini-et-phase1-api-e2e-result.json').write_text(json.dumps({'passed': steps, 'examId': exam, 'sessionId': sid, 'environment': 'isolated real provider + three real services + PostgreSQL + RabbitMQ; synthetic test identities, no shared deployment'}, indent=2))
print('API/MQ E2E passed; browser fixture saved with restricted permissions')
