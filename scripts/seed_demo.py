#!/usr/bin/env python3
"""
Dữ liệu demo cho toàn bộ hệ thống EXAMINI.

Tạo qua API thật (đi qua gateway), nên mọi service đều có dữ liệu khớp nhau:
  - 4 giáo viên, 24 học sinh (2 lớp), hồ sơ cá nhân
  - 2 tổ chức (lớp 12A1, 12A2) với thành viên đã nhận lời mời
  - 4 môn, ~54 câu hỏi đủ mọi dạng (trắc nghiệm, nhiều đáp án, đúng/sai nhiều ý, trả lời ngắn,
    tự luận, nói, câu có hình, bài đọc dùng chung), một số câu đưa lên ngân hàng công khai
  - 9 đề thi ở mọi trạng thái: đã thi xong + công bố điểm, đang chờ chấm tự luận, đang mở (luyện tập,
    mã tham gia), đã lên lịch, bản nháp
  - bài làm của học sinh (mỗi em một "năng lực" riêng nên phổ điểm và độ phân biệt câu hỏi có ý nghĩa),
    chấm tự động + giáo viên chấm tự luận, công bố kết quả, thông báo
Sau đó lùi ngày các kỳ thi đã diễn ra về 2–26 ngày trước (qua `docker exec` vào PostgreSQL) để các biểu đồ
theo thời gian (Tiến bộ, Thống kê hệ thống) có dữ liệu trải đều. Bỏ bước này bằng --no-backdate.

Dùng:
  docker compose up -d                      # đợi mọi container healthy
  python3 scripts/seed_demo.py              # mặc định gọi http://localhost:${WEB_PORT}/api
  python3 scripts/seed_demo.py --base http://localhost:8080   # gọi thẳng gateway

Chạy lại khi đã có dữ liệu demo thì script dừng, không tạo trùng.
Mọi tài khoản demo dùng chung mật khẩu (in ra cuối) — CHỈ dùng cho môi trường demo/phát triển.
"""
import argparse
import json
import os
import random
import struct
import subprocess
import sys
import time
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = 'examini.demo'
PASSWORD = 'Examini@2026'
SCHOOL = 'THPT Nguyễn Du'
rng = random.Random(2026)


def read_env():
    env = {}
    p = ROOT / '.env'
    if p.exists():
        for line in p.read_text(encoding='utf-8').splitlines():
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.split('#')[0].strip().strip('"').strip("'")
    env.update({k: v for k, v in os.environ.items() if k.startswith(('AUTH_BOOTSTRAP_', 'POSTGRES_', 'WEB_PORT'))})
    return env


ENV = read_env()


class Api:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.tokens = {}

    def call(self, path, who=None, method='GET', body=None, ok=(200, 201, 202, 204), raw=None, ctype='application/json'):
        headers = {'Content-Type': ctype}
        if who:
            headers['Authorization'] = 'Bearer ' + self.tokens[who]
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else (b'' if method in ('POST', 'PUT', 'PATCH') else None))
        for attempt in range(6):
            try:
                r = urlopen(Request(self.base + path, data=data, headers=headers, method=method), timeout=180)
                code, payload = r.status, r.read()
            except HTTPError as e:
                code, payload = e.code, e.read()
                # 429/502/503 are always safe to retry; other 5xx only for reads and answer saves (they overwrite).
                if code == 429 or (code in (502, 503) and attempt < 5) or (code >= 500 and attempt < 3 and (method == 'GET' or '/answers' in path)):
                    time.sleep(int(e.headers.get('Retry-After') or 2))
                    continue
            except URLError as e:
                if attempt < 5:
                    time.sleep(2)
                    continue
                raise SystemExit(f'Không kết nối được {self.base}: {e}')
            break
        if code not in ok:
            raise RuntimeError(f'{method} {path} → {code}: {payload[:400].decode(errors="replace")}')
        return json.loads(payload) if payload else None

    def login(self, who, email, password):
        res = self.call('/auth/login', method='POST', body={'email': email, 'password': password})
        self.tokens[who] = res['accessToken']
        return res


# ───────────────────────────── people ─────────────────────────────
TEACHERS = {
    'toan': ('gv.toan', 'Trần Thu Hà', 'Giáo viên Toán, chủ nhiệm lớp 12A1.'),
    'anh': ('gv.anh', 'Nguyễn Hoàng Lan', 'Giáo viên Tiếng Anh, chủ nhiệm lớp 12A2.'),
    'ly': ('gv.ly', 'Lê Minh Quân', 'Giáo viên Vật lý.'),
    'hoa': ('gv.hoa', 'Phạm Văn Hùng', 'Giáo viên Hóa học.'),
}
STUDENT_NAMES = [
    'Nguyễn Văn An', 'Trần Thị Bích', 'Lê Hoàng Cường', 'Phạm Minh Đức', 'Hoàng Thị Hà', 'Vũ Quốc Huy',
    'Đặng Thu Hương', 'Bùi Gia Khánh', 'Đỗ Ngọc Lan', 'Hồ Đức Long', 'Ngô Thị Mai', 'Dương Văn Nam',
    'Lý Bảo Ngọc', 'Phan Thanh Phong', 'Trịnh Mỹ Quyên', 'Võ Đình Sơn', 'Đinh Thị Thảo', 'Mai Xuân Thành',
    'Tạ Minh Trang', 'Lương Anh Tuấn', 'Cao Thị Uyên', 'Kiều Văn Việt', 'Châu Ngọc Xuân', 'Quách Hải Yến',
]


def email(local):
    return f'{local}@{DOMAIN}'


# ───────────────────────────── question content ─────────────────────────────
def _arranged(content, pairs):
    """Stable per-question order of (option, correct) pairs, so the key is not always A (same order on every run)."""
    order = list(range(len(pairs)))
    random.Random(zlib.crc32(content.encode())).shuffle(order)
    return [pairs[i] for i in order]


def mc(content, opts, correct=0, diff='EASY', explanation=None, **extra):
    return dict(type='MULTIPLE_CHOICE', content=content, options=_arranged(content, [(o, i == correct) for i, o in enumerate(opts)]),
                diff=diff, explanation=explanation, **extra)


def mr(content, opts, correct, diff='MEDIUM', explanation=None):
    return dict(type='MULTIPLE_RESPONSE', content=content, options=_arranged(content, [(o, i in correct) for i, o in enumerate(opts)]),
                diff=diff, explanation=explanation)


def tfg(content, statements, diff='MEDIUM', explanation=None, **extra):
    return dict(type='TRUE_FALSE_GROUP', content=content, options=statements, diff=diff, explanation=explanation, **extra)


def sa(content, answer, wrong, diff='EASY', explanation=None):
    return dict(type='SHORT_ANSWER', content=content, expected=answer, wrong=wrong, diff=diff, explanation=explanation)


def essay(content, rubric, answers, diff='HARD', **extra):
    return dict(type='ESSAY', content=content, expected=rubric, answers=answers, diff=diff, **extra)


PASSAGE_EN = ('More and more cities around the world are trying to become greener. In Singapore, many buildings are '
              'covered with plants that help to cool the air and reduce energy use. Copenhagen has built over 400 '
              'kilometres of cycle lanes, and today almost half of its residents cycle to work or school every day. '
              'In Vietnam, Da Nang has planted thousands of trees along its streets and opened several public parks '
              'by the river. Experts say that green spaces do not only make cities more beautiful; they also improve '
              "people's health by lowering stress and encouraging outdoor activities. However, creating green cities "
              'is not cheap, and local governments must work closely with residents to keep these projects going.')

Q = {
    # ── Toán: Hàm số ──
    'T1': ('toan', 'Hàm số', mc('Hàm số y = x³ − 3x + 1 đồng biến trên khoảng nào sau đây?', ['(1; +∞)', '(−1; 1)', '(−∞; 1)', '(0; 2)'],
                               explanation="y' = 3x² − 3 > 0 khi x < −1 hoặc x > 1.")),
    'T2': ('toan', 'Hàm số', mc('Giá trị cực đại của hàm số y = x³ − 3x + 1 là', ['3', '−1', '1', '5'],
                               explanation='Hàm số đạt cực đại tại x = −1, y(−1) = 3.')),
    'T3': ('toan', 'Hàm số', mc('Giá trị lớn nhất của hàm số y = −x² + 4x trên đoạn [0; 3] là', ['4', '3', '0', '5'], diff='MEDIUM',
                               explanation='Đỉnh parabol tại x = 2 thuộc [0; 3], y(2) = 4.')),
    'T4': ('toan', 'Hàm số', mc('Đường tiệm cận đứng của đồ thị hàm số y = (2x + 1)/(x − 1) là', ['x = 1', 'x = 2', 'y = 2', 'x = −1'])),
    'T5': ('toan', 'Hàm số', mc('Đường tiệm cận ngang của đồ thị hàm số y = (2x + 1)/(x − 1) là', ['y = 2', 'y = 1', 'x = 1', 'y = −1'])),
    'T6': ('toan', 'Hàm số', mc('Số giao điểm của đồ thị hàm số y = x³ − 3x với trục hoành là', ['3', '1', '2', '0'], diff='MEDIUM',
                               explanation='x³ − 3x = 0 ⇔ x = 0 hoặc x = ±√3.')),
    'T7': ('toan', 'Hàm số', mc('Tìm m để hàm số y = x³ − 3mx² + 3x + 1 đồng biến trên ℝ.', ['−1 ≤ m ≤ 1', 'm ≥ 1', 'm ≤ −1', '−1 < m < 1'], diff='HARD',
                               explanation="y' = 3x² − 6mx + 3 ≥ 0 với mọi x ⇔ Δ' = 9m² − 9 ≤ 0.")),
    'T8': ('toan', 'Hàm số', mc('Đường cong trong hình là đồ thị của hàm số nào?', ['y = x² − 2x − 3', 'y = −x² + 2x + 3', 'y = x² + 2x − 3', 'y = x² − 3'],
                               diff='MEDIUM', image='parabola', explanation='Đồ thị cắt trục hoành tại x = −1, x = 3 và có đỉnh (1; −4).')),
    'T9': ('toan', 'Hàm số', mr('Những hàm số nào sau đây đồng biến trên ℝ?', ['y = x³ + x', 'y = 2ˣ', 'y = x²', 'y = −x + 1', 'y = eˣ'], {0, 1, 4})),
    'T10': ('toan', 'Hàm số', tfg('Cho hàm số f(x) = x³ − 3x² + 2. Mỗi khẳng định sau đúng hay sai?',
                                  [("f'(x) = 3x² − 6x", True), ('Hàm số đạt cực tiểu tại x = 0', False),
                                   ('Giá trị cực tiểu của hàm số bằng −2', True), ('Đồ thị cắt trục tung tại điểm (0; 2)', True)], diff='HARD')),
    'T11': ('toan', 'Hàm số', sa('Tính đạo hàm của hàm số y = x³ tại x = 2 (chỉ ghi số).', '12', ['6', '8', '4'], diff='MEDIUM')),
    'T12': ('toan', 'Hàm số', essay('Khảo sát sự biến thiên và vẽ đồ thị hàm số y = x³ − 3x + 1. Trình bày đủ các bước.',
                                    'Tập xác định ℝ; y\' = 3x² − 3; cực đại (−1; 3), cực tiểu (1; −1); bảng biến thiên; đồ thị đi qua (0; 1).', 'khaosat')),
    # ── Toán: Mũ – Logarit ──
    'T13': ('toan', 'Mũ – Logarit', mc('log₂ 8 bằng', ['3', '4', '2', '8'])),
    'T14': ('toan', 'Mũ – Logarit', mc('Nghiệm của phương trình 2ˣ = 16 là', ['x = 4', 'x = 8', 'x = 2', 'x = 3'])),
    'T15': ('toan', 'Mũ – Logarit', mc('Tập xác định của hàm số y = log₃(x − 2) là', ['(2; +∞)', '[2; +∞)', 'ℝ', '(0; +∞)'], diff='MEDIUM')),
    'T16': ('toan', 'Mũ – Logarit', mc('Tập nghiệm của bất phương trình log₂ x < 3 là', ['(0; 8)', '(−∞; 8)', '(8; +∞)', '(0; 6)'], diff='MEDIUM',
                                       explanation='Điều kiện x > 0; log₂ x < 3 ⇔ x < 8.')),
    'T17': ('toan', 'Mũ – Logarit', mc('Tổng các nghiệm của phương trình 4ˣ − 3·2ˣ + 2 = 0 là', ['1', '3', '2', '0'], diff='HARD',
                                       explanation='Đặt t = 2ˣ > 0: t² − 3t + 2 = 0 ⇒ t = 1 hoặc t = 2 ⇒ x = 0 hoặc x = 1.')),
    'T18': ('toan', 'Mũ – Logarit', mr('Với mọi số thực dương a, b, khẳng định nào sau đây đúng?',
                                       ['ln(ab) = ln a + ln b', 'ln(a/b) = ln a − ln b', 'ln(a + b) = ln a · ln b', 'ln(a²) = 2 ln a'], {0, 1, 3})),
    'T19': ('toan', 'Mũ – Logarit', sa('Tính log₁₀ 1000 (chỉ ghi số).', '3', ['2', '100', '30'])),
    'T20': ('toan', 'Mũ – Logarit', tfg('Cho hàm số y = 2ˣ. Mỗi khẳng định sau đúng hay sai?',
                                        [('Tập xác định của hàm số là ℝ', True), ('Hàm số nghịch biến trên ℝ', False),
                                         ('Đồ thị hàm số luôn nằm phía trên trục hoành', True), ('Đồ thị hàm số đi qua điểm (0; 2)', False)])),
    # ── Tiếng Anh ──
    'A1': ('anh', 'Reading', mc('What is the passage mainly about?', ['Efforts by cities to become greener', 'Traffic problems in Copenhagen',
                                                                     'The history of Singapore', 'The cost of public parks'], passage='green')),
    'A2': ('anh', 'Reading', mc('According to the passage, about how many Copenhagen residents cycle to work or school every day?',
                                ['Almost half', 'Over 400', 'About a quarter', 'All of them'], passage='green')),
    'A3': ('anh', 'Reading', mc('The word "residents" in the passage is closest in meaning to', ['people who live in a place', 'tourists', 'office workers', 'government officials'],
                                diff='MEDIUM', passage='green')),
    'A4': ('anh', 'Reading', mc('Which of the following is NOT mentioned as a benefit of green spaces?', ['Higher house prices', 'Lower stress', 'More outdoor activities', 'Cooler air'],
                                diff='HARD', passage='green')),
    'A5': ('anh', 'Reading', tfg('Decide whether each statement is true or false according to the passage.',
                                 [('Plants on buildings in Singapore help to reduce energy use.', True), ('Da Nang has opened several public parks by the sea.', False),
                                  ('Creating green cities is cheap.', False), ('Local governments need to work with residents.', True)], passage='green')),
    'A6': ('anh', 'Grammar', mc('If I ___ you, I would study harder for the exam.', ['were', 'am', 'will be', 'been'])),
    'A7': ('anh', 'Grammar', mc('She has lived in Hanoi ___ 2015.', ['since', 'for', 'in', 'at'])),
    'A8': ('anh', 'Grammar', mc('The book ___ I borrowed from you is very interesting.', ['which', 'who', 'whose', 'where'], diff='MEDIUM')),
    'A9': ('anh', 'Grammar', mc('By the time we arrived at the cinema, the film ___.', ['had already started', 'already starts', 'has already started', 'was already start'], diff='MEDIUM')),
    'A10': ('anh', 'Grammar', mc('He suggested ___ to the museum on Sunday.', ['going', 'to go', 'go', 'gone'], diff='MEDIUM')),
    'A11': ('anh', 'Grammar', mc('Not until he got home ___ that he had lost his keys.', ['did he realize', 'he realized', 'he did realize', 'realized he'], diff='HARD',
                                 explanation='"Not until" đứng đầu câu → đảo ngữ ở mệnh đề chính.')),
    'A12': ('anh', 'Grammar', mr('Which sentences are grammatically correct?', ["I'm used to getting up early.", 'She enjoys to read novels.',
                                                                                 'They have been friends since ten years.', 'We look forward to hearing from you.'], {0, 3})),
    'A13': ('anh', 'Grammar', sa("Write the past participle of the verb 'write'.", 'written', ['wrote', 'writed', 'writen'])),
    'A14': ('anh', 'Writing', essay('Write a paragraph (120–150 words) about the benefits of learning a foreign language.',
                                    'Clear topic sentence; 2–3 benefits with examples; linking words; conclusion; accurate grammar and vocabulary.', 'language',
                                    minWords=80, maxWords=200)),
    'A15': ('anh', 'Speaking', dict(type='SPEAKING', content='Talk about your favourite place in your hometown. You should say where it is, what you can do there and why you like it.',
                                    expected='Fluency, pronunciation, vocabulary range, coherent answer covering all three points.', diff='MEDIUM', maxRecordSeconds=90)),
    # ── Vật lý ──
    'L1': ('ly', 'Dao động cơ', mc('Chu kì con lắc lò xo T = 2π√(m/k). Khi tăng khối lượng m lên 4 lần thì chu kì', ['tăng 2 lần', 'tăng 4 lần', 'giảm 2 lần', 'không đổi'])),
    'L2': ('ly', 'Dao động cơ', mc('Đơn vị của tần số dao động là', ['Hz', 's', 'rad', 'm/s'])),
    'L3': ('ly', 'Dao động cơ', mc('Vật dao động điều hòa theo phương trình x = 5cos(4πt + π/3) cm. Biên độ dao động là', ['5 cm', '4π cm', '10 cm', 'π/3 cm'])),
    'L4': ('ly', 'Dao động cơ', mc('Vật dao động điều hòa theo phương trình x = 5cos(4πt + π/3) cm. Tần số dao động là', ['2 Hz', '4 Hz', '4π Hz', '0,5 Hz'], diff='MEDIUM',
                                   explanation='ω = 4π rad/s ⇒ f = ω/2π = 2 Hz.')),
    'L5': ('ly', 'Dao động cơ', mc('Con lắc đơn dài 1 m dao động tại nơi có g = π² m/s². Chu kì dao động là', ['2 s', '1 s', 'π s', '4 s'], diff='MEDIUM')),
    'L6': ('ly', 'Sóng cơ', mc('Một sóng cơ có tần số 50 Hz, tốc độ truyền 20 m/s. Bước sóng bằng', ['40 cm', '2,5 m', '1000 m', '4 cm'], diff='HARD',
                               explanation='λ = v/f = 20/50 = 0,4 m.')),
    'L7': ('ly', 'Sóng cơ', mc('Sóng âm không truyền được trong môi trường nào?', ['Chân không', 'Nước', 'Thép', 'Không khí'])),
    'L8': ('ly', 'Dao động cơ', mc('Trong dao động điều hòa, gia tốc của vật luôn', ['ngược dấu với li độ', 'cùng dấu với li độ', 'cùng dấu với vận tốc', 'bằng 0 ở vị trí biên'], diff='MEDIUM')),
    'L9': ('ly', 'Dao động cơ', tfg('Một vật dao động điều hòa với biên độ A và tần số góc ω. Mỗi khẳng định sau đúng hay sai?',
                                    [('Tốc độ cực đại của vật bằng ωA', True), ('Độ lớn gia tốc cực đại bằng ω²A', True),
                                     ('Tại vị trí cân bằng, gia tốc có độ lớn cực đại', False), ('Cơ năng của vật tỉ lệ với A²', True)])),
    'L10': ('ly', 'Dao động cơ', sa('Một dao động có tần số 4 Hz. Chu kì dao động là bao nhiêu giây? (ghi số thập phân, ví dụ 0,5)', '0,25', ['4', '0,4', '2,5'], diff='MEDIUM')),
    'L11': ('ly', 'Dao động cơ', essay('Nêu định nghĩa dao động điều hòa. Viết phương trình li độ và giải thích ý nghĩa các đại lượng trong phương trình.',
                                       'Định nghĩa đúng; x = A cos(ωt + φ); giải thích A, ω, φ, (ωt + φ).', 'dinhnghia', diff='MEDIUM')),
    'L12': ('ly', 'Dao động cơ', essay('Một con lắc lò xo có k = 100 N/m, vật nặng m = 100 g. Tính chu kì và tần số dao động (lấy π² = 10).',
                                       'T = 2π√(m/k) = 0,2 s; f = 1/T = 5 Hz; trình bày đổi đơn vị m = 0,1 kg.', 'conlac')),
    # ── Hóa học ──
    'H1': ('hoa', 'Este – Lipit', mc('Công thức tổng quát của este no, đơn chức, mạch hở là', ['CₙH₂ₙO₂ (n ≥ 2)', 'CₙH₂ₙ₊₂O₂', 'CₙH₂ₙO (n ≥ 1)', 'CₙH₂ₙ₋₂O₂'])),
    'H2': ('hoa', 'Este – Lipit', mc('Phản ứng thủy phân este trong môi trường kiềm được gọi là phản ứng', ['xà phòng hóa', 'este hóa', 'trùng hợp', 'hiđrat hóa'])),
    'H3': ('hoa', 'Este – Lipit', mc('Etyl axetat có công thức là', ['CH₃COOC₂H₅', 'HCOOC₂H₅', 'C₂H₅COOCH₃', 'CH₃COOCH₃'], diff='MEDIUM')),
    'H4': ('hoa', 'Este – Lipit', mc('Chất béo là trieste của glixerol với', ['axit béo', 'axit axetic', 'ancol etylic', 'axit vô cơ'])),
    'H5': ('hoa', 'Este – Lipit', mc('Số đồng phân este ứng với công thức phân tử C₃H₆O₂ là', ['2', '3', '1', '4'], diff='MEDIUM',
                                     explanation='HCOOC₂H₅ và CH₃COOCH₃.')),
    'H6': ('hoa', 'Este – Lipit', mc('Xà phòng hóa hoàn toàn 8,8 g etyl axetat bằng dung dịch NaOH vừa đủ, thu được m gam muối. Giá trị của m là',
                                     ['8,2', '9,6', '6,8', '4,6'], diff='HARD', explanation='n = 8,8/88 = 0,1 mol ⇒ m(CH₃COONa) = 0,1 × 82 = 8,2 g.')),
    'H7': ('hoa', 'Este – Lipit', tfg('Về chất béo, mỗi khẳng định sau đúng hay sai?',
                                      [('Chất béo không tan trong nước', True), ('Dầu thực vật thường ở thể lỏng vì chứa nhiều gốc axit béo không no', True),
                                       ('Thủy phân chất béo luôn thu được etanol', False), ('Mỡ động vật chủ yếu chứa gốc axit béo no', True)])),
}

ESSAY_ANSWERS = {
    'khaosat': [
        "Tập xác định D = ℝ. y' = 3x² − 3, y' = 0 ⇔ x = ±1. Hàm số đồng biến trên (−∞; −1) và (1; +∞), nghịch biến trên (−1; 1). "
        "Cực đại tại x = −1, y = 3; cực tiểu tại x = 1, y = −1. Giới hạn: x → ±∞ thì y → ±∞. Lập bảng biến thiên. Đồ thị đi qua (0; 1), đối xứng qua điểm uốn (0; 1).",
        "D = ℝ, y' = 3x² − 3 = 0 khi x = 1 hoặc x = −1. Hàm số có cực đại y = 3 và cực tiểu y = −1. Vẽ đồ thị qua các điểm (−1; 3), (0; 1), (1; −1).",
        "y' = 3x² − 3. Hàm số đồng biến trên ℝ nên đồ thị đi lên.",
    ],
    'language': [
        "Learning a foreign language brings many benefits. First, it opens up better job opportunities, because many international companies need staff who can "
        "communicate with foreign partners. Second, it helps us understand other cultures through films, books and music in their original language. For example, "
        "after learning English I can watch documentaries and read news from around the world. Moreover, studies show that learning a language improves memory "
        "and problem-solving skills. In conclusion, a foreign language is not only a useful tool but also a way to grow as a person.",
        "Learning a foreign language is very useful. It help us to find a good job and travel to many country. We can talk with foreigner and make friends. "
        "I think everyone should learn English because it is international language.",
        "Foreign language is good. I like English. It is important for job.",
    ],
    'dinhnghia': [
        "Dao động điều hòa là dao động trong đó li độ của vật là một hàm cosin (hay sin) của thời gian. Phương trình: x = A cos(ωt + φ), trong đó A là biên độ (li độ cực đại, A > 0), "
        "ω là tần số góc (rad/s), φ là pha ban đầu tại t = 0, (ωt + φ) là pha dao động tại thời điểm t.",
        "Dao động điều hòa là dao động lặp đi lặp lại quanh vị trí cân bằng. x = A cos(ωt + φ) với A là biên độ, ω là tần số góc, φ là pha ban đầu.",
        "Là dao động của con lắc. x = A cos ωt.",
    ],
    'conlac': [
        "Đổi m = 100 g = 0,1 kg. T = 2π√(m/k) = 2π√(0,1/100) = 2π√0,001. Với π² = 10: T = √(4π² · 0,001) = √0,04 = 0,2 s. Tần số f = 1/T = 5 Hz.",
        "T = 2π√(m/k) = 2π√(0,1/100) ≈ 0,2 s, f = 1/T = 5 Hz.",
        "T = 2π√(100/100) = 2π ≈ 6,28 s, f = 0,16 Hz.",
    ],
}


# ───────────────────────────── tiny PNG for the graph question ─────────────────────────────
def parabola_png(w=320, h=320):
    """y = x² − 2x − 3 on [−3, 5] × [−5, 7], grid + axes, no external libraries."""
    px = [[(255, 255, 255)] * w for _ in range(h)]
    x0, x1, y0, y1 = -3, 5, -5, 7
    sx = lambda x: int((x - x0) / (x1 - x0) * (w - 1))
    sy = lambda y: int((y1 - y) / (y1 - y0) * (h - 1))

    def dot(x, y, c, r=0):
        for dx in range(-r, r + 1):
            for dy in range(-r, r + 1):
                if 0 <= x + dx < w and 0 <= y + dy < h:
                    px[y + dy][x + dx] = c
    for gx in range(x0, x1 + 1):
        for yy in range(h):
            dot(sx(gx), yy, (226, 230, 238))
    for gy in range(y0, y1 + 1):
        for xx in range(w):
            dot(xx, sy(gy), (226, 230, 238))
    for yy in range(h):
        dot(sx(0), yy, (60, 60, 60))
    for xx in range(w):
        dot(xx, sy(0), (60, 60, 60))
    steps = 2000
    for i in range(steps + 1):
        x = x0 + (x1 - x0) * i / steps
        y = x * x - 2 * x - 3
        if y0 <= y <= y1:
            dot(sx(x), sy(y), (51, 85, 230), 1)
    for x, y in [(-1, 0), (3, 0), (1, -4), (0, -3)]:
        dot(sx(x), sy(y), (232, 89, 12), 3)
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in px)
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def multipart(field, filename, content, ctype):
    boundary = 'examini' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f'Content-Type: {ctype}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return body, f'multipart/form-data; boundary={boundary}'


# ───────────────────────────── seeding ─────────────────────────────
def log(msg):
    print(msg, flush=True)


def seed(api, backdate=True):
    admin_email, admin_pw = ENV.get('AUTH_BOOTSTRAP_EMAIL'), ENV.get('AUTH_BOOTSTRAP_PASSWORD')
    if not admin_email or not admin_pw:
        raise SystemExit('Cần AUTH_BOOTSTRAP_EMAIL và AUTH_BOOTSTRAP_PASSWORD trong .env (tài khoản Admin).')
    api.login('admin', admin_email, admin_pw)
    try:
        api.call('/auth/login', method='POST', body={'email': email('gv.toan'), 'password': PASSWORD})
        log('Dữ liệu demo đã có sẵn (đăng nhập được gv.toan). Không tạo lại.')
        return None
    except RuntimeError:
        pass

    # 1. accounts
    log('• Tạo tài khoản giáo viên và học sinh…')
    people = {}

    def register(key, local, name, persona, bio, phone):
        api.call('/auth/register', method='POST', body={'email': email(local), 'password': PASSWORD, 'fullName': name,
                                                         'accountType': 'PERSONAL', 'persona': persona}, ok=(201,))
        api.login(key, email(local), PASSWORD)
        api.call('/auth/account', key, 'PATCH', {'fullName': name, 'phone': phone, 'school': SCHOOL, 'bio': bio})
        people[key] = dict(id=api.call('/auth/me', key)['id'], name=name, email=email(local))

    for i, (key, (local, name, bio)) in enumerate(TEACHERS.items()):
        register(key, local, name, 'TEACHER', bio, f'09{12345670 + i}')
    students = []
    for i, name in enumerate(STUDENT_NAMES, 1):
        key = f'hs{i:02d}'
        cls = '12A1' if i <= 12 else '12A2'
        register(key, key, name, 'STUDENT', f'Học sinh lớp {cls}.', f'03{88000000 + i}')
        people[key].update(cls=cls, ability=round(rng.uniform(0.38, 0.95), 2))
        students.append(key)
    a1 = [s for s in students if people[s]['cls'] == '12A1']
    a2 = [s for s in students if people[s]['cls'] == '12A2']

    # 2. organizations
    log('• Tạo tổ chức lớp 12A1, 12A2 và mời thành viên…')
    orgs = {}
    for cls, owner, members, extra in [('12A1', 'toan', a1, ['ly', 'hoa']), ('12A2', 'anh', a2, ['toan', 'ly'])]:
        org = api.call('/organizations', owner, 'POST', {'name': f'Lớp {cls} — {SCHOOL}', 'description': f'Lớp {cls}, năm học 2026–2027.'})
        for who in members + extra:
            inv = api.call(f"/organizations/{org['id']}/invitations", owner, 'POST',
                           {'email': people[who]['email'], 'role': 'ADMIN' if who in TEACHERS else 'MEMBER'})
            api.call(f"/organizations/invitations/{inv['invitation']['id'] if 'invitation' in inv else inv['id']}/accept", who, 'POST')
        orgs[cls] = org['id']

    # 3. subjects & categories (Admin keeps the shared taxonomy)
    log('• Tạo môn học, chủ đề và câu hỏi…')
    subjects = {s['name']: s['id'] for s in _items(api.call('/subjects', 'admin'))}
    cats = {}
    for subj, names in [('Toán học', ['Hàm số', 'Mũ – Logarit']), ('Tiếng Anh', ['Reading', 'Grammar', 'Writing', 'Speaking']),
                        ('Vật lý', ['Dao động cơ', 'Sóng cơ']), ('Hóa học', ['Este – Lipit'])]:
        if subj not in subjects:
            subjects[subj] = api.call('/subjects', 'admin', 'POST', {'name': subj, 'description': f'Môn {subj} THPT'}, ok=(200, 201))['id']
        existing = {c['name']: c['id'] for c in _items(api.call('/categories?subjectId=' + subjects[subj], 'admin'))}
        for n in names:
            cats[n] = existing.get(n) or api.call('/categories', 'admin', 'POST', {'subjectId': subjects[subj], 'name': n}, ok=(200, 201))['id']

    # 4. questions
    image_id = None
    passage_key = str(uuid.uuid4())
    qs = {}
    for code, (owner, cat, spec) in Q.items():
        body = {'categoryId': cats[cat], 'content': spec['content'], 'questionType': spec['type'], 'difficultyLevel': spec['diff'],
                'source': f'{SCHOOL} — dữ liệu demo'}
        if spec.get('explanation'):
            body['explanation'] = spec['explanation']
        if spec['type'] in ('MULTIPLE_CHOICE', 'MULTIPLE_RESPONSE', 'TRUE_FALSE_GROUP'):
            body['options'] = [{'content': c, 'correct': ok, 'displayOrder': i} for i, (c, ok) in enumerate(spec['options'])]
        else:
            body['expectedAnswer'] = spec['expected']
        pres = {}
        if spec.get('image'):
            if not image_id:
                raw, ctype = multipart('file', 'do-thi-parabol.png', parabola_png(), 'image/png')
                image_id = api.call('/question-media', owner, 'POST', raw=raw, ctype=ctype)['id']
            pres['media'] = [{'id': image_id, 'kind': 'IMAGE', 'name': 'do-thi-parabol.png'}]
        if spec.get('passage'):
            pres['passage'] = {'key': passage_key, 'title': 'Green cities', 'content': PASSAGE_EN}
        for k in ('minWords', 'maxWords', 'maxRecordSeconds'):
            if spec.get(k):
                pres[k] = spec[k]
        if pres:
            body['presentation'] = pres
        q = api.call('/questions', owner, 'POST', body, ok=(201,))
        q = api.call(f"/questions/{q['id']}/activate", owner, 'POST', {'questionVersion': q['questionVersion']})
        qs[code] = dict(q, code=code, spec=spec, owner=owner)
    for code in ['T1', 'T4', 'T13', 'T14', 'L2', 'L7', 'H2', 'A7']:
        q = qs[code]
        try:
            api.call(f"/questions/{q['id']}/publish-public", q['owner'], 'POST', {'sourceQuestionVersion': q['questionVersion']})
        except RuntimeError as e:
            log(f'  (bỏ qua đưa công khai {code}: {str(e)[:80]})')

    # 5. exams
    log('• Tạo đề thi, cho học sinh làm bài…')
    exams = []

    def make_exam(owner, title, codes, minutes, students_in, mode='ASSIGNED', etype='EXAM', attempts=1, description=None, result_policy=None):
        weights = {'ESSAY': 2, 'SPEAKING': 2}
        e = api.call('/exams/manual', owner, 'POST', {
            'title': title, 'description': description, 'durationMinutes': minutes, 'type': etype, 'passingScore': None,
            'shuffleQuestions': etype == 'EXAM', 'shuffleOptions': etype == 'EXAM', 'requireCam': False, 'requireMic': False,
            'blockTabSwitch': etype == 'EXAM', 'requireFullscreen': False,
            'questions': [{'questionId': qs[c]['id'], 'weight': weights.get(qs[c]['spec']['type'], 1)} for c in codes]})
        api.call(f"/exams/{e['id']}/policy", owner, 'PUT', {'accessMode': mode, 'maxParticipants': 100, 'maxConcurrentSessions': 100, 'attemptLimit': attempts})
        if result_policy:
            api.call(f"/exams/{e['id']}/result-policy", owner, 'PUT', result_policy)
        if students_in:
            api.call(f"/exams/{e['id']}/participants", owner, 'POST', {'userIds': [people[s]['id'] for s in students_in]})
        return dict(e, owner=owner, codes=codes)

    reveal = {'autoRelease': True, 'showScore': True, 'showQuestionBreakdown': True, 'showFeedback': True, 'showCorrectAnswers': True, 'showExplanation': True}
    plan = [
        # title, owner, codes, minutes, who, days ago, essays graded (fraction)
        ('Kiểm tra 15 phút — Hàm số (12A1)', 'toan', ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T8', 'T11', 'T9'], 15, a1, 26, 1),
        ('Kiểm tra giữa kỳ I — Toán 12', 'toan', ['T3', 'T6', 'T7', 'T9', 'T10', 'T13', 'T15', 'T16', 'T17', 'T18', 'T19', 'T20', 'T12'], 45, students, 19, 1),
        ('Thi thử THPT lần 1 — Tiếng Anh', 'anh', ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8', 'A9', 'A10', 'A11', 'A12', 'A13', 'A14'], 60, students, 12, 0.85),
        ('Kiểm tra 1 tiết — Dao động cơ (12A1)', 'ly', ['L1', 'L2', 'L3', 'L4', 'L5', 'L8', 'L9', 'L10', 'L11', 'L12'], 45, a1, 5, 0.5),
        ('Kiểm tra 15 phút — Mũ và Logarit', 'toan', ['T13', 'T14', 'T15', 'T16', 'T17', 'T18', 'T19', 'T20'], 15, students, 2, 1),
    ]
    sessions = []
    for title, owner, codes, minutes, who, days, graded in plan:
        e = make_exam(owner, title, codes, minutes, who, result_policy=reveal if owner == 'anh' else None,
                      description='Đề kiểm tra trên lớp, dữ liệu demo.')
        api.call(f"/exams/{e['id']}/publish", owner, 'PUT')
        api.call(f"/exams/{e['id']}/open", owner, 'PUT')
        takers = [s for s in who if rng.random() < 0.93]
        with ThreadPoolExecutor(8) as pool:
            done = list(pool.map(lambda s: take(api, e, s, people[s]['ability'], qs), takers))
        sessions += [(e, s, sid) for s, sid in done if sid]
        api.call(f"/exams/{e['id']}/close", owner, 'PUT', {})
        exams.append(dict(e, days=days, graded=graded, takers=len(takers)))
        log(f'  ✓ {title}: {len(takers)} bài nộp')

    # proctor colleague on the midterm (collaboration tab)
    mid = exams[1]
    api.call(f"/exams/{mid['id']}/members", 'toan', 'PUT', {'userId': people['ly']['id'], 'role': 'PROCTOR'})
    api.call(f"/exams/{mid['id']}/members/accept", 'ly', 'POST')

    log('• Đợi chấm tự động…')
    wait_grading(api, sessions)
    log('• Giáo viên chấm tự luận…')
    for e in exams:
        manual_grade(api, e, qs, people)

    # open / scheduled / draft exams
    log('• Đề đang mở, đã lên lịch và bản nháp…')
    practice = make_exam('hoa', 'Ôn tập Este — Lipit (luyện tập)', ['H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'H7'], 20, [], mode='PUBLIC', etype='PRACTICE', attempts=3,
                         description='Đề luyện tập công khai, làm lại tối đa 3 lần.', result_policy=reveal)
    api.call(f"/exams/{practice['id']}/publish", 'hoa', 'PUT')
    api.call(f"/exams/{practice['id']}/open", 'hoa', 'PUT')
    for s in rng.sample(students, 9):
        api.call(f"/exams/{practice['id']}/join", s, 'POST')
        take(api, practice, s, people[s]['ability'], qs)
    code_exam = make_exam('ly', 'Luyện tập — Sóng cơ (vào bằng mã)', ['L6', 'L7', 'L8', 'L10'], 15, [], mode='JOIN_CODE', etype='PRACTICE', attempts=2,
                          description='Nhập mã đề để tham gia.')
    api.call(f"/exams/{code_exam['id']}/publish", 'ly', 'PUT')
    api.call(f"/exams/{code_exam['id']}/open", 'ly', 'PUT')
    final = make_exam('toan', 'Kiểm tra cuối kỳ I — Toán 12', ['T1', 'T2', 'T5', 'T7', 'T8', 'T9', 'T10', 'T14', 'T16', 'T17', 'T18', 'T12'], 60, students,
                      description='Thi tập trung, có lịch tự mở và tự đóng.')
    start = (datetime.now(timezone(timedelta(hours=7))) + timedelta(days=6)).replace(hour=7, minute=30, second=0, microsecond=0)
    sched = api.call(f"/exams/{final['id']}/schedule", 'toan')
    api.call(f"/exams/{final['id']}/schedule", 'toan', 'PUT', {
        'startAt': start.isoformat(), 'endAt': (start + timedelta(minutes=90)).isoformat(), 'timezone': 'Asia/Ho_Chi_Minh',
        'deadlineRule': 'CAPPED_AT_END', 'autoOpen': True, 'autoClose': True, 'reminderOffsetsMinutes': [1440, 30],
        'expectedRevision': sched.get('scheduleRevision', 0), 'reason': 'Lịch thi cuối kỳ'})
    api.call(f"/exams/{final['id']}/publish", 'toan', 'PUT')
    make_exam('anh', 'Speaking & Writing — Unit 5 (bản nháp)', ['A15', 'A14'], 20, [], description='Đang soạn, chưa xuất bản.')
    for s in rng.sample(students, 10):
        try:
            api.call(f"/exams/{final['id']}/follow", s, 'PUT')
        except RuntimeError:
            pass

    if backdate:
        log('• Lùi ngày các kỳ thi đã diễn ra…')
        backdate_history(exams, people, qs, orgs)
    return dict(people=people, exams=exams, code=code_exam.get('code'))


def _items(x):
    return x if isinstance(x, list) else x.get('content') or x.get('items') or []


def take(api, exam, student, ability, qs):
    """One attempt with answers that follow the student's ability and the question difficulty."""
    try:
        api.call(f"/exams/{exam['id']}/preflight", student, 'POST', ok=(200, 201, 204, 404, 405))
        s = api.call(f"/sessions/start?examId={exam['id']}", student, 'POST')
    except RuntimeError as e:
        log(f'  ! {student} không vào được {exam["title"]}: {str(e)[:120]}')
        return student, None
    sid = s['sessionId']
    adjust = {'EASY': 0.15, 'MEDIUM': 0.0, 'HARD': -0.22}
    for code in exam['codes']:
        q = qs[code]
        spec = q['spec']
        p = max(0.05, min(0.97, ability + adjust[spec['diff']] + rng.uniform(-0.12, 0.12)))
        if spec['type'] != 'SPEAKING' and rng.random() < 0.04:
            continue  # left blank
        content = answer(q, p, ability)
        if content is not None:
            api.call(f"/sessions/{sid}/answers?questionId={q['id']}", student, 'POST', {'content': content})
    api.call(f'/sessions/{sid}/submit', student, 'POST')
    return student, sid


def answer(q, p, ability):
    spec, opts = q['spec'], q.get('options') or []
    right = rng.random() < p
    t = spec['type']
    if t == 'MULTIPLE_CHOICE':
        good = [o['id'] for o in opts if o['correct']]
        bad = [o['id'] for o in opts if not o['correct']]
        return good[0] if right else rng.choice(bad)
    if t == 'MULTIPLE_RESPONSE':
        good = [o['id'] for o in opts if o['correct']]
        if right:
            return json.dumps(good)
        pick = [o['id'] for o in opts if (o['correct'] and rng.random() < 0.7) or (not o['correct'] and rng.random() < 0.35)]
        return json.dumps(pick or [rng.choice(opts)['id']])
    if t == 'TRUE_FALSE_GROUP':
        return json.dumps({o['id']: (o['correct'] if rng.random() < p + 0.1 else not o['correct']) for o in opts})
    if t == 'SHORT_ANSWER':
        return spec['expected'] if right else rng.choice(spec['wrong'])
    if t == 'ESSAY':
        variants = ESSAY_ANSWERS[spec['answers']]
        return variants[0] if ability > 0.78 else variants[1] if ability > 0.55 else variants[2]
    return None  # SPEAKING needs a recording


def wait_grading(api, sessions, timeout=240):
    end = time.time() + timeout
    pending = list(sessions)
    while pending and time.time() < end:
        still = []
        for e, s, sid in pending:
            r = api.call(f'/grades/session/{sid}', s)
            if r.get('status') in ('SUBMITTED', 'GRADING', 'IN_PROGRESS'):
                still.append((e, s, sid))
        pending = still
        if pending:
            time.sleep(2)
    if pending:
        log(f'  ! {len(pending)} bài chưa chấm xong sau {timeout}s (RabbitMQ/grading?)')


def manual_grade(api, exam, qs, people):
    owner = exam['owner']
    page = api.call(f"/submissions?examId={exam['id']}&status=NEEDS_REVIEW&size=100", owner)
    subs = page.get('content', [])
    rng.shuffle(subs)
    keep = int(round(len(subs) * (1 - exam['graded'])))
    ability = {p['id']: p.get('ability', 0.6) for p in people.values()}
    for sub in subs[keep:]:
        d = api.call(f"/submissions/{sub['id']}", owner)
        items = []
        for it in d['items']:
            if not it.get('needsReview'):
                continue
            pv = float(it['pointValue'])
            a = ability.get(sub['studentId'], 0.6)
            score = round(min(pv, max(0, pv * (a + rng.uniform(-0.15, 0.1)))) * 4) / 4
            fb = ('Bài làm đầy đủ, lập luận chặt chẽ.' if score >= 0.8 * pv else
                  'Đúng hướng nhưng còn thiếu bước/ý, cần trình bày rõ hơn.' if score >= 0.5 * pv else
                  'Bài làm còn sơ sài, xem lại lý thuyết và làm thêm bài tương tự.')
            items.append({'questionId': it['questionId'], 'score': score, 'feedback': fb})
        if not items:
            continue
        d = api.call(f"/submissions/{sub['id']}/draft", owner, 'PUT', {'expectedRevision': d['resultRevision'], 'feedback': 'Cô/thầy đã chấm phần tự luận.', 'items': items})
        api.call(f"/submissions/{sub['id']}/finalize", owner, 'POST', {'expectedRevision': d['resultRevision']})
    if subs:
        log(f"  ✓ {exam['title']}: chấm {len(subs) - keep}/{len(subs)} bài tự luận" + (f', để lại {keep} bài chờ chấm' if keep else ''))


# ───────────────────────────── backdating (docker exec psql) ─────────────────────────────
def psql(db, sql):
    user = ENV.get('POSTGRES_USER') or 'datn'
    r = subprocess.run(['docker', 'exec', '-i', 'datn-postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-q', '-At', '-U', user, '-d', db],
                       input=sql, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f'psql {db}: {r.stderr.strip()[:300]}')
    return r.stdout.strip()


def ts_columns(db):
    rows = psql(db, """SELECT table_name, string_agg(column_name, ',') FILTER (WHERE data_type LIKE 'timestamp%'),
                       bool_or(column_name = 'exam_id'), bool_or(column_name = 'session_id'), bool_or(column_name = 'result_id')
                       FROM information_schema.columns WHERE table_schema = 'public' GROUP BY 1""")
    out = {}
    for line in rows.splitlines():
        t, cols, ex, se, re_ = line.split('|')
        if cols and not any(k in t for k in ('outbox', 'processed', 'flyway', 'scheduling_jobs', 'schedule_state', 'deliveries')):
            out[t] = (cols.split(','), ex == 't', se == 't', re_ == 't')
    return out


def shift_exam(db, tables, exam_id, days):
    stmts = []
    for t, (cols, has_exam, has_session, has_result) in tables.items():
        sets = ', '.join(f'{c} = {c} - interval \'{days} days\'' for c in cols)
        if t == 'exams':
            where = f"id = '{exam_id}'"
        elif has_exam:
            where = f"exam_id::text = '{exam_id}'"
        elif has_session and 'exam_sessions' in tables:
            where = f"session_id IN (SELECT id FROM exam_sessions WHERE exam_id::text = '{exam_id}')"
        elif has_result and 'exam_results' in tables:
            where = f"result_id IN (SELECT id FROM exam_results WHERE exam_id::text = '{exam_id}')"
        else:
            continue
        stmts.append(f'UPDATE {t} SET {sets} WHERE {where};')
    if stmts:
        psql(db, 'BEGIN;\n' + '\n'.join(stmts) + '\nCOMMIT;')


def backdate_history(exams, people, qs, orgs):
    if subprocess.run(['docker', 'inspect', 'datn-postgres'], capture_output=True).returncode:
        log('  ! Không thấy container datn-postgres — bỏ qua bước lùi ngày.')
        return
    # analytics consumes events asynchronously: wait until it has seen every submission
    for e in exams:
        for _ in range(60):
            n = psql('analytics_db', f"SELECT count(*) FROM exam_result_projection WHERE exam_id::text = '{e['id']}'")
            if int(n or 0) >= e['takers']:
                break
            time.sleep(2)
    time.sleep(3)
    dbs = {db: ts_columns(db) for db in ('exam_db', 'exam_session_db', 'grading_db', 'analytics_db')}
    for e in exams:
        for db, tables in dbs.items():
            shift_exam(db, tables, e['id'], e['days'])
        psql('exam_db', f"UPDATE exams SET created_at = created_at - interval '1 day' WHERE id = '{e['id']}';")
    ids = ','.join(f"'{p['id']}'" for p in people.values())
    psql('auth_db', f"""UPDATE users SET created_at = created_at - interval '29 days' - (interval '1 minute' * floor(random() * 1200)),
                               last_login_at = now() - (interval '1 hour' * floor(random() * 150)) WHERE id IN ({ids});""")
    qids = ','.join(f"'{q['id']}'" for q in qs.values())
    psql('question_bank_db', f"""UPDATE questions SET created_at = created_at - interval '28 days' - (interval '1 minute' * floor(random() * 1200)),
                                         updated_at = updated_at - interval '27 days' WHERE id IN ({qids});""")
    oids = ','.join(f"'{o}'" for o in orgs.values())
    psql('organization_db', f"""UPDATE organizations SET created_at = created_at - interval '28 days', updated_at = updated_at - interval '28 days' WHERE id IN ({oids});
                                UPDATE memberships SET joined_at = joined_at - interval '28 days', updated_at = updated_at - interval '28 days' WHERE organization_id IN ({oids});
                                UPDATE invitations SET created_at = created_at - interval '28 days', responded_at = responded_at - interval '28 days' WHERE organization_id IN ({oids});""")


def main():
    ap = argparse.ArgumentParser(description='Tạo dữ liệu demo cho EXAMINI qua API.')
    ap.add_argument('--base', default=f"http://localhost:{ENV.get('WEB_PORT') or '80'}/api", help='Địa chỉ API (mặc định qua nginx /api)')
    ap.add_argument('--no-backdate', action='store_true', help='Không lùi ngày kỳ thi cũ (không cần docker)')
    args = ap.parse_args()
    started = time.time()
    res = seed(Api(args.base), backdate=not args.no_backdate)
    if res is None:
        return
    log(f'\nXong sau {time.time() - started:.0f}s. Tài khoản demo (mật khẩu chung: {PASSWORD}):')
    for key, (local, name, _) in TEACHERS.items():
        log(f'  Giáo viên  {email(local):<26} {name}')
    log(f'  Học sinh   hs01@{DOMAIN} … hs24@{DOMAIN}  (hs01–hs12: lớp 12A1, hs13–hs24: lớp 12A2)')
    log(f"  Admin      {ENV.get('AUTH_BOOTSTRAP_EMAIL')} (mật khẩu trong .env)")
    if res.get('code'):
        log(f"  Mã đề 'Luyện tập — Sóng cơ': {res['code']}")


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as e:
        sys.exit(f'Lỗi: {e}')
