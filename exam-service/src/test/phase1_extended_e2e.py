"""Real provider mutation, objective grading, timeout and browser fixtures.
Runs phase1_api_e2e first; uses only public/internal HTTP APIs, no DB edits.
"""
import runpy
from pathlib import Path
import json, time, uuid
ctx=runpy.run_path(str(Path(__file__).with_name('phase1_api_e2e.py')))
globals().update({k:v for k,v in ctx.items() if not k.startswith('__')})
admin=str(uuid.uuid4()); tokens[admin]=signed(admin,'ADMIN')
questions=[]
for kind in ['TRUE_FALSE','SHORT_ANSWER','ESSAY']:
    body={'categoryId':fixture['categoryId'],'content':'Isolated '+kind,'questionType':kind,'difficultyLevel':'EASY'}
    if kind=='TRUE_FALSE':body['options']=[{'content':'True','correct':True,'displayOrder':0},{'content':'False','correct':False,'displayOrder':1}]
    if kind=='SHORT_ANSWER':body['expectedAnswer']='Café  Test'
    q=api(18082,'/questions',owner,'POST',body,expect=201)
    api(18082,f"/questions/{q['id']}/submit-review",owner,'POST',{'questionVersion':1})
    q=api(18082,f"/questions/{q['id']}/review",admin,'POST',{'questionVersion':1,'decision':'APPROVED','reviewNote':'Isolated test fixture'})
    questions.append(q)
qs=[{'questionId':q['id'],'weight':2} for q in questions]
created=api(28083,'/exams/manual',owner,'POST',{'title':'Browser and immutable snapshot E2E','durationMinutes':1,'type':'EXAM','passingScore':2,'questions':qs})
ext_exam=created['id']
api(28083,f'/exams/{ext_exam}/policy',owner,'PUT',{'accessMode':'PUBLIC','maxParticipants':10,'maxConcurrentSessions':5,'attemptLimit':2})
api(28083,f'/exams/{ext_exam}/publish',owner,'PUT')
# Provider owns this mutation: exercise its public author API only.
changed=api(18082,f"/questions/{questions[0]['id']}",owner,'PUT',{'questionVersion':1,'content':'Changed provider version two'})
assert changed['questionVersion']==2 and changed['status']=='DRAFT'
api(28083,f'/exams/{ext_exam}/open',owner,'PUT')
api(28083,f'/exams/{ext_exam}/join',student,'POST')
s=api(28084,f'/sessions/start?examId={ext_exam}',student,'POST')
assert s['questions'][0]['questionVersion']==1 and s['questions'][0]['content']=='Isolated TRUE_FALSE'
step('Provider edit creates DRAFT v2; published Exam and session keep original APPROVED v1')
for q,answer in [(questions[0],next(o['id'] for o in questions[0]['options'] if o['correct'])),(questions[1],'  Cafe\u0301\u2003 Test  '),(questions[2],'An essay requiring human review')]:
    api(28084,f"/sessions/{s['sessionId']}/answers?questionId={q['id']}",student,'POST',{'content':answer})
api(28084,f"/sessions/{s['sessionId']}/submit",student,'POST')
for _ in range(60):
    r=api(28085,f"/grades/session/{s['sessionId']}",student)
    if r['status']=='NEEDS_REVIEW':break
    time.sleep(.5)
else:raise AssertionError('Essay failure review state not reached')
assert r['score'] is None and r['totalScore']==6
sub=next(x for x in api(28085,f'/submissions?examId={ext_exam}',owner)['content'] if x['studentId']==student)
detail=api(28085,f"/submissions/{sub['id']}",owner)
assert detail['score']==4 and detail['status']=='NEEDS_REVIEW'
awarded={a['questionId']:a['earnedPoints'] for a in detail['answers']}
assert all(awarded[q['id']]==2 for q in questions[:2])
step('Real TRUE_FALSE / Unicode SHORT_ANSWER grading; unavailable Essay AI preserves objective points and requires review')
api(28085,f"/submissions/{sub['id']}/grade",owner,'PUT',{'score':5,'feedback':'Human-reviewed essay'})
assert api(28085,f"/grades/session/{s['sessionId']}",student)['score']==5
step('OWNER manually resolves review; final Student Result uses reviewed score')
# Another attempt times out via persisted server time; no database mutation.
s2=api(28084,f'/sessions/start?examId={ext_exam}',student,'POST')
assert s2['sessionId']!=s['sessionId'] and s2['attemptNumber']==2
api(28084,f"/sessions/{s2['sessionId']}/answers?questionId={questions[1]['id']}",student,'POST',{'content':'café Test'})
# Keep a different participant and exam OPEN for browser join/start/answer/resume/submit.
browser_student=str(uuid.uuid4()); tokens[browser_student]=signed(browser_student,'STUDENT')
fixture.update(examId=ext_exam,activeStudentId=browser_student,testTokens=tokens)
path=Path('/tmp/examini-et-phase1-browser-fixture.json');path.write_text(json.dumps(fixture));path.chmod(0o600)
for role,user in [('teacher',owner),('student',browser_student),('manager',manager),('proctor',proctor)]:
    state={'state':{'accessToken':tokens[user],'refreshToken':None,'user':{'id':user,'email':role+'@isolated.example','fullName':role,'role':'STUDENT' if role=='student' else 'TEACHER'}},'version':0}
    p=Path('/tmp/examini-et-browser-'+role+'.js');p.write_text('localStorage.setItem("exam-auth",'+json.dumps(json.dumps(state))+'); void 0;');p.chmod(0o600)
print('Browser fixture ready; waiting for real one-minute server expiry',flush=True)
while time.time()<__import__('datetime').datetime.fromisoformat(__import__('re').sub(r'\.\d+', '', s2['expiresAt']).replace('Z','+00:00')).timestamp()+1:time.sleep(1)
expired=api(28084,f"/sessions/{s2['sessionId']}",student)
assert expired['status']=='AUTO_SUBMITTED'
api(28084,f"/sessions/{s2['sessionId']}/answers?questionId={questions[1]['id']}",student,'POST',{'content':'Café Test'},expect=409)
api(28084,f'/sessions/start?examId={ext_exam}',student,'POST',expect=409)
for _ in range(40):
    r=api(28085,f"/grades/session/{s2['sessionId']}",student)
    if r['status']=='GRADED':break
    time.sleep(.5)
assert r['score']==0
step('Real server timeout AUTO_SUBMITTED / late answer rejected / attempt limit / case-sensitive wrong answer grading')
Path('/tmp/examini-et-phase1-extended-e2e-result.json').write_text(json.dumps({'passed':steps,'examId':ext_exam,'environment':'isolated provider API and three services, real MQ/DB; synthetic identities'},indent=2))
