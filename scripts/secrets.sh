#!/usr/bin/env bash
# Khoá bí mật của hệ thống (.env) được lưu trên repo dưới dạng mã hoá: .env.enc (AES-256, PBKDF2).
# Chỉ ai có mật khẩu giải mã mới tạo lại được .env để chạy; máy quét khoá của GitHub/Google/Groq không đọc được.
#
#   ./scripts/secrets.sh unlock   giải mã .env.enc → .env   (dùng trước khi chạy)
#   ./scripts/secrets.sh lock     mã hoá .env → .env.enc    (sau khi sửa .env, rồi commit .env.enc)
#
# Mật khẩu lấy theo thứ tự: biến EXAMINI_SECRETS_KEY → file .env.key (không commit) → hỏi khi chạy.
# Nhập lần đầu sẽ được hỏi có lưu vào .env.key không, để những lần sau tự chạy.
set -euo pipefail
cd "$(dirname "$0")/.."

OPENSSL=${OPENSSL:-openssl}
if ! command -v "$OPENSSL" >/dev/null 2>&1; then
  for c in "/c/Program Files/Git/usr/bin/openssl.exe" "/mingw64/bin/openssl"; do [ -x "$c" ] && OPENSSL="$c" && break; done
fi
command -v "$OPENSSL" >/dev/null 2>&1 || [ -x "$OPENSSL" ] || { echo "Cần openssl (có sẵn trên macOS/Linux và trong Git for Windows)." >&2; exit 1; }
ARGS=(enc -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -a -A)

passfile() {
  if [ -n "${EXAMINI_SECRETS_KEY:-}" ]; then
    local t; t=$(mktemp); printf '%s' "$EXAMINI_SECRETS_KEY" > "$t"; echo "$t"; return
  fi
  if [ -s .env.key ]; then echo ".env.key"; return; fi
  local p; read -rsp "Mật khẩu giải mã khoá EXAMINI: " p; echo >&2
  [ -n "$p" ] || { echo "Chưa nhập mật khẩu." >&2; exit 1; }
  local t; t=$(mktemp); printf '%s' "$p" > "$t"
  if [ -t 0 ]; then
    read -rp "Lưu mật khẩu vào .env.key trên máy này (không đưa lên git)? [Y/n] " a
    case "${a:-y}" in [Yy]*) cp "$t" .env.key; chmod 600 .env.key ;; esac
  fi
  echo "$t"
}

case "${1:-}" in
  unlock)
    [ -f .env.enc ] || { echo "Không có .env.enc." >&2; exit 1; }
    pf=$(passfile)
    if ! "$OPENSSL" "${ARGS[@]}" -d -in .env.enc -out .env.tmp -pass "file:$pf" 2>/dev/null; then
      rm -f .env.tmp; [ "$pf" = ".env.key" ] || rm -f "$pf"
      echo "Sai mật khẩu hoặc .env.enc bị hỏng." >&2; exit 1
    fi
    [ "$pf" = ".env.key" ] || rm -f "$pf"
    if [ -f .env ] && ! cmp -s .env .env.tmp; then cp .env ".env.bak.$(date +%Y%m%d%H%M%S)"; echo "Đã sao lưu .env cũ."; fi
    mv .env.tmp .env; chmod 600 .env
    echo "Đã giải mã → .env"
    ;;
  lock)
    [ -f .env ] || { echo "Không có .env để mã hoá." >&2; exit 1; }
    pf=$(passfile)
    "$OPENSSL" "${ARGS[@]}" -salt -in .env -out .env.enc -pass "file:$pf"
    [ "$pf" = ".env.key" ] || rm -f "$pf"
    echo "Đã mã hoá → .env.enc (commit file này; .env và .env.key không được commit)"
    ;;
  *)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
