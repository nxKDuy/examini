"""Real Phase 1 API/MQ validation against an isolated development stack.
Creates test accounts, questions, exams and sessions; does not remove test data.
Requires configured bootstrap Admin credentials in .env/environment.
Run with PHASE1_GATEWAY_URL pointing to the intended Gateway.
Credentials/tokens stay in a chmod-0600 local /tmp artifact; never print them.
Browser release validation is a separate real UI check, not a fixture test.
"""
import os
import json,time,secrets,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from concurrent.futures import ThreadPoolExecutor
ENV={}
for line in (Path(__file__).resolve().parents[1] / '.env').read_text().splitlines():
 if line and not line.startswith('#') and '=' in line:
  k,v=line.split('=',1); ENV[k]=v.strip().strip('\"').strip("'")
ENV.update({k: os.environ[k] for k in ['AUTH_BOOTSTRAP_EMAIL', 'AUTH_BOOTSTRAP_PASSWORD'] if k in os.environ})
base=os.environ.get('PHASE1_GATEWAY_URL', 'http://127.0.0.1:8080')
accounts={}; passed=[]
def api(path,actor=None,method='GET',body=None,expect=200,headers=None):
 h={'Content-Type':'application/json'}
 if actor: h['Authorization']='Bearer '+accounts[actor]['accessToken']
 h.update(headers or {})
 req=Request(base+path,data=json.dumps(body).encode() if body is not None else (b'' if method in ['POST','PUT'] else None),headers=h,method=method)
 try:
  r=urlopen(req,timeout=25); code,raw=r.status,r.read()
 except HTTPError as e: code,raw=e.code,e.read()
 assert code in ([expect] if isinstance(expect,int) else expect),f'{method} {path}: expected {expect}, got {code}; {raw[:350].decode(errors="replace")}'
 return json.loads(raw) if raw else None

def ok(s): passed.append(s); print('PASS',s,flush=True)
def register(name,persona):
 email=f'{name}-{uuid.uuid4().hex[:10]}@runtime.example.test'; password=secrets.token_urlsafe(24)
 body={'email':email,'password':password,'fullName':'Runtime '+name,'accountType':'PERSONAL','persona':persona}
 api('/auth/register',method='POST',body=body,expect=201)
 accounts[name]=api('/auth/login',method='POST',body={'email':email,'password':password})
 accounts[name].update(email=email,password=password)
 me=api('/auth/me',name); accounts[name]['id']=me['id']
 assert me['persona']==persona and me['systemPrivilege']=='NONE'
 return body
for name,persona in [('teacher','TEACHER'),('manager','TEACHER'),('proctor','TEACHER'),('unrelated','TEACHER'),('student','STUDENT'),('student2','STUDENT'),('student3','STUDENT')]: register(name,persona)
accounts['admin']=api('/auth/login',method='POST',body={'email':ENV['AUTH_BOOTSTRAP_EMAIL'],'password':ENV['AUTH_BOOTSTRAP_PASSWORD']})
accounts['admin'].update(email=ENV['AUTH_BOOTSTRAP_EMAIL'],password=ENV['AUTH_BOOTSTRAP_PASSWORD'])
assert api('/auth/me','admin')['systemPrivilege']=='ADMIN'
p=Path('/tmp/examini-runtime-accounts.json');p.write_text(json.dumps(accounts));p.chmod(0o600)
ok('Gateway-backed Student/Teacher/Admin real registration, login and me; TEACHER != ADMIN')
api('/auth/login',method='POST',body={'email':'admin@datn.local','password':'Admin@123'},expect=401)
ok('Legacy known seed password disabled')
for role in ['ADMIN','SYSTEM_ADMIN']:
 api('/auth/register',method='POST',body={'email':uuid.uuid4().hex+'@runtime.example.test','password':secrets.token_urlsafe(24),'fullName':'Attack','role':role},expect=[400,403])
b={'email':uuid.uuid4().hex+'@runtime.example.test','password':secrets.token_urlsafe(24),'fullName':'Injection','persona':'TEACHER','systemPrivilege':'ADMIN','permissions':'*','roles':['ADMIN']}
accounts['injection']=api('/auth/register',method='POST',body=b,expect=201)
assert api('/auth/me','injection')['systemPrivilege']=='NONE'
spoof={'X-User-Role':'ADMIN','X-User-Persona':'TEACHER','X-System-Privilege':'ADMIN','X-User-Id':str(uuid.uuid4())}
assert api('/auth/me','student',headers=spoof)['persona']=='STUDENT'
api('/exams',headers={'Authorization':'Bearer '+accounts['student']['refreshToken']},expect=401)
api('/exams',headers=spoof,expect=401)
ok('ADMIN/SYSTEM_ADMIN registration rejected; injected privilege ignored; headers spoof and refresh misuse rejected')
subjects=api('/subjects','teacher'); subject=subjects[0] if isinstance(subjects,list) else subjects['content'][0]
categories=api('/categories?subjectId='+subject['id'],'teacher'); category=categories[0] if isinstance(categories,list) else categories['content'][0]
cid=category['id']; questions=[]
for typ in ['MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY']:
 b={'categoryId':cid,'content':'Runtime original '+typ,'questionType':typ,'difficultyLevel':'EASY'}
 if typ in ['MULTIPLE_CHOICE','TRUE_FALSE']: b['options']=[{'content':'Correct','correct':True,'displayOrder':0},{'content':'Wrong','correct':False,'displayOrder':1}]
 else: b['expectedAnswer']='runtime answer' if typ=='SHORT_ANSWER' else 'Explain clearly'
 q=api('/questions','teacher','POST',b,201); assert q['status']=='DRAFT'
 api('/questions/'+q['id'],'unrelated',expect=404)
 api('/questions/'+q['id'],'student',expect=404,headers=spoof)
 q=api('/questions/'+q['id']+'/submit-review','teacher','POST',{'questionVersion':q['questionVersion']})
 api('/questions/'+q['id']+'/review','teacher','POST',{'questionVersion':q['questionVersion'],'decision':'APPROVED'},expect=403)
 q=api('/questions/'+q['id']+'/review','admin','POST',{'questionVersion':q['questionVersion'],'decision':'APPROVED','reviewNote':'Runtime release validation'})
 assert q['status']=='APPROVED'
 assert api('/questions/'+q['id']+'/reviews','admin')
 assert api('/questions/'+q['id']+'/versions','teacher')
 questions.append(q)
ok('Question lifecycle DRAFT -> PENDING_REVIEW -> APPROVED, owner boundary, Admin review, versions/audit')
ids=api('/questions/random?categoryId='+cid+'&difficulty=EASY&limit=1','teacher'); assert len(ids)==1
safe=api('/questions/batch','teacher','POST',[q['id'] for q in questions])
def safe_keys(x):
 if isinstance(x,dict):
  assert not {'correct','expectedAnswer','gradingKey','rubric'}.intersection(x)
  for v in x.values():safe_keys(v)
 elif isinstance(x,list):
  for v in x:safe_keys(v)
safe_keys(safe)
api('/questions/random?categoryId='+cid+'&difficulty=EASY&status=DRAFT','teacher',expect=400)
ok('APPROVED-only random/batch, version-bearing candidate-safe DTO without answer keys')
auto=api('/exams/auto','teacher','POST',{'title':'Runtime approved auto-generation','durationMinutes':2,'categoryId':cid,'easyCount':1,'mediumCount':0,'hardCount':0})
assert auto['status']=='DRAFT' and len(auto['questionIds'])==1
api('/exams/'+auto['id']+'/publish','teacher','PUT')
ok('Real Exam internal approved random/batch auto-generation and frozen Publish')
# Publish negative lifecycle gates.
for status in ['DRAFT','PENDING_REVIEW','REJECTED']:
 q=api('/questions','teacher','POST',{'categoryId':cid,'content':'Blocked '+status,'questionType':'SHORT_ANSWER','difficultyLevel':'EASY','expectedAnswer':'blocked'},201)
 if status!='DRAFT': q=api('/questions/'+q['id']+'/submit-review','teacher','POST',{'questionVersion':q['questionVersion']})
 if status=='REJECTED':q=api('/questions/'+q['id']+'/review','admin','POST',{'questionVersion':q['questionVersion'],'decision':'REJECTED','reviewNote':'Test rejection'})
 e=api('/exams/manual','teacher','POST',{'title':'Ineligible '+status,'durationMinutes':1,'questions':[{'questionId':q['id'],'weight':1}]})
 api('/exams/'+e['id']+'/publish','teacher','PUT',expect=[400,409])
ok('DRAFT/PENDING_REVIEW/REJECTED questions cannot publish')
e=api('/exams/manual','teacher','POST',{'title':'Runtime Phase 1 objective','durationMinutes':2,'type':'EXAM','passingScore':1,'questions':[{'questionId':q['id'],'weight':2} for q in questions[:3]]})
eid=e['id']; assert e['ownerUserId']==accounts['teacher']['id']
for actor,role in [('manager','MANAGER'),('proctor','PROCTOR')]:
 api('/exams/'+eid+'/members','teacher','PUT',{'userId':accounts[actor]['id'],'role':role})
 api('/exams/'+eid+'/members/accept',actor,'POST')
api('/exams/'+eid+'/policy','manager','PUT',{'accessMode':'PUBLIC','maxParticipants':2,'maxConcurrentSessions':1,'attemptLimit':1})
for actor in ['proctor','unrelated']:
 for action in ['publish','open','close']:api('/exams/'+eid+'/'+action,actor,'PUT',expect=403)
api('/exams/'+eid+'/publish','manager','PUT')
api('/exams/'+eid+'/open','manager','PUT')
ok('Exam sole OWNER; accepted MANAGER/PROCTOR; manager publish/open; Proctor/unrelated teacher lifecycle denied')
q=questions[0]; old_content=q['content']; old_version=q['questionVersion']
updated=api('/questions/'+q['id'],'teacher','PUT',{'content':'Runtime CHANGED after publish','questionVersion':old_version})
assert updated['questionVersion']>old_version
for a in ['student','student2']:api('/exams/'+eid+'/join',a,'POST')
api('/exams/'+eid+'/join','student3','POST',expect=409)
for a in ['student','student2']:
 api('/exams/'+eid+'/preflight',a,'POST'); api('/exams/'+eid+'/lobby',a)
def start(a):
 try:return a,api('/sessions/start?examId='+eid,a,'POST')
 except AssertionError as err:
  assert 'got 409' in str(err); return a,None
with ThreadPoolExecutor(2) as pool: starts=list(pool.map(start,['student','student2']))
winners=[(a,s) for a,s in starts if s]; assert len(winners)==1
active,s=winners[0]; loser='student2' if active=='student' else 'student'; sid=s['sessionId']
safe_keys(s); assert s['questions'][0]['content']==old_content and s['questions'][0]['questionVersion']==old_version
ok('PUBLIC join/lobby/preflight, maxParticipants=2; racing starts obey maxConcurrentSessions=1; immutable safe snapshot')
answers={}
for q in questions[:3]:
 answers[q['id']]=next(o['id'] for o in q['options'] if o['correct']) if q['options'] else '  runtime answer  '
 api('/sessions/'+sid+'/answers?questionId='+q['id'],active,'POST',{'content':answers[q['id']]})
restored=api('/sessions/'+sid,active)
assert restored['answers']==answers
assert api('/sessions/start?examId='+eid,active,'POST')['sessionId']==sid
from datetime import datetime
import re
def timestamp(v): return datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',v).replace('Z','+00:00'))
assert abs((timestamp(restored['expiresAt'])-timestamp(s['expiresAt'])).total_seconds()) <= 0.000001
assert api('/sessions/exam/'+eid+'/monitor','teacher')[0]['startedAt']
ok('Persisted answers, same-session resume, server startedAt/expiresAt preserved')
api('/exams/'+eid+'/close','manager','PUT')
api('/sessions/start?examId='+eid,loser,'POST',expect=409)
api('/sessions/'+sid+'/answers?questionId='+questions[0]['id'],active,'POST',{'content':answers[questions[0]['id']]})
api('/sessions/'+sid+'/submit',active,'POST');api('/sessions/'+sid+'/submit',active,'POST')
api('/sessions/'+sid+'/answers?questionId='+questions[0]['id'],active,'POST',{'content':'mutate'},expect=409)
ok('CLOSED rejects new sessions; active finishes; duplicate submit idempotent; terminal answers reject mutation')
for _ in range(60):
 result=api('/grades/session/'+sid,active)
 if result['status']=='GRADED':break
 time.sleep(1)
assert result['status']=='GRADED', 'RabbitMQ grading did not complete'
assert result['score']==6 and result['totalScore']==6, f'Objective scoring mismatch: {result}'
api('/grades/session/'+sid,loser,expect=403)
ok('Real RabbitMQ grading MC/TRUE_FALSE/SHORT_ANSWER; frozen key; real result score=6/6; result ownership')
accounts['_context']={'examId':eid,'sessionId':sid,'active':active,'questions':questions,'categoryId':cid,'passed':passed}
p=Path('/tmp/examini-runtime-accounts.json');p.write_text(json.dumps(accounts));p.chmod(0o600)
Path('/tmp/examini-runtime-api-result.json').write_text(json.dumps({'passed':passed,'examId':eid,'sessionId':sid},indent=2))
# Use all live accounts/tokens, all traffic through Gateway.
def question(typ):
 b={'categoryId':cid,'content':'Browser validation '+typ,'questionType':typ,'difficultyLevel':'EASY'}
 if typ in ['MULTIPLE_CHOICE','TRUE_FALSE']:b['options']=[{'content':'Right choice','correct':True,'displayOrder':0},{'content':'Wrong choice','correct':False,'displayOrder':1}]
 else:b['expectedAnswer']='frozen answer' if typ=='SHORT_ANSWER' else 'Explain your reasoning'
 q=api('/questions','teacher','POST',b,201)
 q=api('/questions/'+q['id']+'/submit-review','teacher','POST',{'questionVersion':q['questionVersion']})
 return api('/questions/'+q['id']+'/review','admin','POST',{'questionVersion':q['questionVersion'],'decision':'APPROVED'})
def exam(qs,title,duration=10):
 e=api('/exams/manual','teacher','POST',{'title':title,'durationMinutes':duration,'questions':[{'questionId':q['id'],'weight':1} for q in qs]})
 api('/exams/'+e['id']+'/policy','teacher','PUT',{'accessMode':'PUBLIC','maxParticipants':10,'maxConcurrentSessions':5,'attemptLimit':1})
 api('/exams/'+e['id']+'/publish','teacher','PUT');api('/exams/'+e['id']+'/open','teacher','PUT')
 return e['id']
qs=[question(t) for t in ['MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER']]
browser_exam=exam(qs,'Browser real Phase 1 exam')
# Frozen grading key survives live answer-key replacement.
q=qs[2]
api('/questions/'+q['id'],'teacher','PUT',{'questionVersion':q['questionVersion'],'content':'Live replacement','expectedAnswer':'new live answer'})
api('/exams/'+browser_exam+'/join','student','POST')
s=api('/sessions/start?examId='+browser_exam,'student','POST');sid=s['sessionId']
for q in qs:
 answer=next(o['id'] for o in q['options'] if not o['correct']) if q['options'] else 'frozen answer'
 api('/sessions/'+sid+'/answers?questionId='+q['id'],'student','POST',{'content':answer})
api('/sessions/'+sid+'/submit','student','POST')
for _ in range(40):
 r=api('/grades/session/'+sid,'student')
 if r['status']=='GRADED':break
 time.sleep(.5)
assert r['score']==1 and r['totalScore']==3
ok('Wrong MC/TF score zero; SHORT_ANSWER frozen key still scores after live key changed')
essay=question('ESSAY');ee=exam([essay],'Essay fallback runtime')
api('/exams/'+ee+'/join','student','POST');es=api('/sessions/start?examId='+ee,'student','POST')
api('/sessions/'+es['sessionId']+'/answers?questionId='+essay['id'],'student','POST',{'content':'My actual essay response'})
api('/sessions/'+es['sessionId']+'/submit','student','POST')
for _ in range(80):
 er=api('/grades/session/'+es['sessionId'],'student')
 if er['status']!='GRADING':break
 time.sleep(.5)
assert er['status']=='NEEDS_REVIEW' and er['score'] is None
ok('Unavailable external essay AI gracefully yields NEEDS_REVIEW, not published zero score')
tq=question('SHORT_ANSWER');te=exam([tq],'One minute timeout validation',1)
api('/exams/'+te+'/join','student3','POST');ts=api('/sessions/start?examId='+te,'student3','POST')
api('/sessions/'+ts['sessionId']+'/answers?questionId='+tq['id'],'student3','POST',{'content':'frozen answer','expiresAt':'2099-01-01T00:00:00Z'})
assert api('/sessions/'+ts['sessionId'],'student3')['expiresAt'][:19]==ts['expiresAt'][:19]
accounts['_browser']={'examId':browser_exam,'questions':qs,'timerSessionId':ts['sessionId'],'extraPassed':passed}
p=Path('/tmp/examini-runtime-accounts.json');p.write_text(json.dumps(accounts));p.chmod(0o600)
print('Timer running; real browser validation context ready',flush=True)
time.sleep(62)
assert api('/sessions/'+ts['sessionId'],'student3')['status']=='AUTO_SUBMITTED'
api('/sessions/'+ts['sessionId']+'/answers?questionId='+tq['id'],'student3','POST',{'content':'late'},expect=409)
ok('Server timeout AUTO_SUBMITTED; caller expiresAt cannot extend duration; late mutation denied')
Path('/tmp/examini-runtime-extra-result.json').write_text(json.dumps({'passed':passed},indent=2))
