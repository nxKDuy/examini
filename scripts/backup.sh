#!/usr/bin/env bash
# Sao lưu toàn bộ dữ liệu EXAMINI đang chạy bằng docker compose:
#   - mọi database PostgreSQL (pg_dump định dạng custom, mỗi DB một file)
#   - file đính kèm: ảnh/âm thanh câu hỏi, bài nói của thí sinh, ảnh giám sát camera
# Dùng:   ./scripts/backup.sh [thư_mục_đích]      (mặc định ./backups/<thời gian>)
# Khôi phục (xem cuối file): ./scripts/backup.sh --restore ./backups/<thời gian>
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
PGUSER="${POSTGRES_USER:-datn}"
PG=datn-postgres
VOLUMES=(question_media:question-bank-service:/data/question-media
         answer_audio:exam-session-service:/data/answer-audio
         camera_data:exam-session-service:/data/camera)

dbs() { docker exec "$PG" psql -U "$PGUSER" -d postgres -Atc "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'"; }

if [ "${1:-}" = "--restore" ]; then
  SRC="${2:?Cần thư mục bản sao lưu}"
  read -r -p "Khôi phục từ $SRC sẽ GHI ĐÈ dữ liệu hiện tại. Gõ YES để tiếp tục: " ok
  [ "$ok" = "YES" ] || { echo "Đã huỷ."; exit 1; }
  for f in "$SRC"/db/*.dump; do
    db=$(basename "$f" .dump)
    docker exec "$PG" psql -U "$PGUSER" -d postgres -qc "CREATE DATABASE \"$db\"" 2>/dev/null || true
    docker exec -i "$PG" pg_restore -U "$PGUSER" -d "$db" --clean --if-exists --no-owner < "$f"
    echo "✓ database $db"
  done
  for v in "${VOLUMES[@]}"; do
    IFS=: read -r name svc dir <<<"$v"
    [ -f "$SRC/files/$name.tar.gz" ] || continue
    docker compose exec -T -u 0 "$svc" sh -c "mkdir -p $dir && tar -xzf - -C $dir" < "$SRC/files/$name.tar.gz"
    echo "✓ $name"
  done
  echo "Xong. Nên khởi động lại các service: docker compose restart"
  exit 0
fi

OUT="${1:-./backups/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT/db" "$OUT/files"
for db in $(dbs); do
  docker exec "$PG" pg_dump -U "$PGUSER" -Fc "$db" > "$OUT/db/$db.dump"
  echo "✓ database $db ($(du -h "$OUT/db/$db.dump" | cut -f1))"
done
for v in "${VOLUMES[@]}"; do
  IFS=: read -r name svc dir <<<"$v"
  if docker compose exec -T "$svc" test -d "$dir" 2>/dev/null; then
    docker compose exec -T "$svc" tar -czf - -C "$dir" . > "$OUT/files/$name.tar.gz"
    echo "✓ $name ($(du -h "$OUT/files/$name.tar.gz" | cut -f1))"
  fi
done
echo "Đã sao lưu vào $OUT"
# Giữ 14 bản gần nhất khi dùng thư mục mặc định (chạy hằng ngày bằng cron: 0 2 * * * /đường/dẫn/scripts/backup.sh)
[ -z "${1:-}" ] && ls -1dt ./backups/*/ 2>/dev/null | tail -n +15 | xargs -r rm -rf
