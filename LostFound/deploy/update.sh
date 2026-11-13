#!/bin/bash
# EC2(운영 환경)에서 쓰는 실행·업데이트 스크립트. 개발 문서 3.5, 6.3
# - 처음 실행: 네트워크·볼륨·DB 컨테이너를 만들고 앱을 띄운다
# - 그다음부터: Docker Hub에 새 이미지가 있으면 앱 컨테이너만 바꾼다 (DB와 데이터는 그대로)
# 사용법: 이 파일과 .env를 같은 폴더(~/lostfound)에 두고 ./update.sh   (cron으로 5분마다 실행)
DIR=$(cd "$(dirname "$0")" && pwd)
cd "$DIR" || exit 1
[ -f .env ] || { echo ".env 가 없습니다 ($DIR)"; exit 1; }
IMAGE=$(grep -E '^APP_IMAGE=' .env | tail -1 | cut -d= -f2- | tr -d '[:space:]"')
[ -n "$IMAGE" ] || { echo ".env 에 APP_IMAGE=도커허브아이디/lostfound:v6 를 적어 주세요"; exit 1; }

docker network inspect lostfound > /dev/null 2>&1 || docker network create lostfound > /dev/null
docker volume inspect lostfound-data > /dev/null 2>&1 || docker volume create lostfound-data > /dev/null
if ! docker inspect db > /dev/null 2>&1; then
  docker run -d --name db --network lostfound --restart unless-stopped \
    -e POSTGRES_DB=lostfound -e POSTGRES_USER=lostfound -e POSTGRES_PASSWORD=classroom-only \
    -v lostfound-data:/var/lib/postgresql/data postgres:17 > /dev/null
  echo "$(date '+%F %T') DB 컨테이너 시작"
fi

docker pull -q "$IMAGE" > /dev/null || { echo "$(date '+%F %T') pull 실패: $IMAGE"; exit 1; }
NEW=$(docker image inspect -f '{{.Id}}' "$IMAGE")
RUNNING=$(docker inspect -f '{{.Image}}' app 2> /dev/null)
if [ "$NEW" != "$RUNNING" ]; then
  docker rm -f app > /dev/null 2>&1
  docker run -d --name app --network lostfound --restart unless-stopped \
    --env-file .env -e DB_HOST=db -e DB_PASSWORD=classroom-only \
    -p 8080:8000 "$IMAGE" > /dev/null
  docker image prune -f > /dev/null
  echo "$(date '+%F %T') 앱을 새 이미지로 교체: ${NEW:7:12}"
fi
