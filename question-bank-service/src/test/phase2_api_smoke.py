"""Isolated loopback-only Phase 2 HTTP smoke; uses synthetic signed identities.
Run against the packaged service configured with the test-only JWT key below.
No production credentials, foreign databases or third-party packages are used.
"""
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('QB_PHASE2_URL', 'http://127.0.0.1:18092').rstrip('/')
assert urllib.parse.urlparse(BASE).hostname in ('127.0.0.1', 'localhost'), 'Isolated loopback required'
KEY = b'test-only-jwt-key-32-bytes-or-longer-never-production'


def token(user, role, privilege='NONE'):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    body = enc({'alg': 'HS256'}) + b'.' + enc({'sub': user, 'role': role, 'systemPrivilege': privilege,
                                             'type': 'access', 'exp': int(time.time()) + 600})
    return (body + b'.' + base64.urlsafe_b64encode(hmac.new(KEY, body, hashlib.sha256).digest()).rstrip(b'=')).decode()


def api(method, path, bearer, body=None, status=200, extra_headers=None):
    headers = {'Authorization': 'Bearer ' + bearer, 'Content-Type': 'application/json'}
    headers.update(extra_headers or {})
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == status, (method, path, response.status, status, response.read().decode())
        raw = response.read()
        return json.loads(raw) if raw else None


def safe(value):
    if isinstance(value, dict):
        assert not {'correct', 'expectedAnswer', 'sourceExcerpt'} & value.keys(), value.keys()
        for nested in value.values():
            safe(nested)
    elif isinstance(value, list):
        for nested in value:
            safe(nested)


owner = str(uuid.uuid4())
teacher = token(owner, 'TEACHER')
admin = token(str(uuid.uuid4()), 'ADMIN', 'ADMIN')
student = token(str(uuid.uuid4()), 'STUDENT')
subject = api('GET', '/subjects', teacher)[0]['id']
category = api('GET', '/categories?subjectId=' + subject, teacher)[0]['id']
request = {'categoryId': category, 'content': 'HTTP Phase 2 immutable source', 'questionType': 'MULTIPLE_CHOICE',
           'difficultyLevel': 'EASY', 'options': [{'content': 'Yes', 'correct': True, 'displayOrder': 0},
                                                {'content': 'No', 'correct': False, 'displayOrder': 1}]}
q = api('POST', '/questions', teacher, request, 201)
assert q['status'] == 'DRAFT' and q['questionVersion'] == 1
api('POST', '/questions/' + q['id'] + '/contributions', teacher, {'sourceQuestionVersion': 1}, 409)
api('POST', '/questions/' + q['id'] + '/submit-review', teacher, {'questionVersion': 1})
api('POST', '/questions/' + q['id'] + '/review', admin, {'questionVersion': 1, 'decision': 'APPROVED', 'reviewNote': 'Source review'})
contribution = api('POST', '/questions/' + q['id'] + '/contributions', teacher, {'sourceQuestionVersion': 1}, 201)
assert contribution['status'] == 'PUBLIC_PENDING'
safe(contribution)
api('POST', '/public-contributions/' + contribution['id'] + '/review', teacher, {'decision': 'APPROVED'}, 403)
api('GET', '/public-contributions/' + contribution['id'] + '?includeAnswers=true', teacher, status=403)
keys = api('GET', '/public-contributions/' + contribution['id'] + '?includeAnswers=true', admin)
assert keys['question']['options'][0]['correct'] is True
api('POST', '/public-contributions/' + contribution['id'] + '/review', admin, {'decision': 'APPROVED', 'reviewNote': 'Independent public verification'})
api('PUT', '/questions/' + q['id'], teacher, {'content': 'New private content', 'questionVersion': 1})
api('DELETE', '/questions/' + q['id'] + '?questionVersion=2', teacher, status=204)
p = api('GET', '/public-questions?sourceOwnerUserId=' + owner, student)['content'][0]
safe(p)
assert p['sourceQuestionVersion'] == 1 and p['question']['content'] == request['content'] and p['id'] != q['id']
api('POST', '/public-questions/' + p['id'] + '/fork', student, {'sourceQuestionVersion': 1}, 403)
fork = api('POST', '/public-questions/' + p['id'] + '/fork', teacher, {'sourceQuestionVersion': 1}, 201)
f = fork['question']
assert f['status'] == 'DRAFT' and f['creationMethod'] == 'COPIED' and f['questionVersion'] == 1
assert f['options'][0]['id'] != q['options'][0]['id']
lineage = api('GET', '/questions/' + f['id'] + '/lineage', teacher)
assert lineage['sourcePublicQuestionId'] == p['id'] and lineage['sourceQuestionId'] == q['id'] and lineage['sourceQuestionVersion'] == 1
api('POST', '/questions/batch', teacher, [f['id']], 409)
api('POST', '/questions/' + f['id'] + '/submit-review', teacher, {'questionVersion': 1})
api('POST', '/questions/' + f['id'] + '/review', admin, {'questionVersion': 1, 'decision': 'APPROVED', 'reviewNote': 'Copied personal review'})
assert api('GET', '/questions/random?categoryId=' + category + '&difficulty=EASY&limit=200', teacher) == [f['id']]
safe(api('POST', '/questions/batch', teacher, [f['id']]))
api('POST', '/question-banks', teacher, {'type': 'SYSTEM', 'name': 'Spoof'}, 403, {'X-System-Privilege': 'ADMIN'})
system = api('POST', '/question-banks', admin, {'type': 'SYSTEM', 'name': 'HTTP System'}, 201)
sys_q = api('POST', '/question-banks/' + system['id'] + '/questions', admin, dict(request, scope='SYSTEM'), 201)['question']
api('GET', '/questions/' + sys_q['id'], teacher, status=404)
api('POST', '/questions/' + sys_q['id'] + '/submit-review', admin, {'questionVersion': 1})
api('POST', '/questions/' + sys_q['id'] + '/review', admin, {'questionVersion': 1, 'decision': 'APPROVED'})
api('POST', '/questions/batch', admin, [sys_q['id']], 409)
safe(api('GET', '/question-banks/' + system['id'] + '/questions', teacher))
org = api('POST', '/question-banks', admin, {'type': 'ORGANIZATION', 'name': 'HTTP Organization reference', 'organizationId': str(uuid.uuid4())}, 201)
assert org['status'] == 'PENDING_AUTHORIZATION'
api('POST', '/question-banks/' + org['id'] + '/questions', admin, dict(request, scope='ORGANIZATION'), 503)
print('PASS: actual HTTP personal/public verification/frozen snapshot/fork lineage/system privilege/organization fail-closed/Exam eligibility')
