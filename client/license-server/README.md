# SMC Chart License Server

Мини-сервер лицензий для VPS Ubuntu 24. Он проверяет `HWID + пароль + срок доступа`.
База: SQLite. Зависимости: только стандартный Python 3.

## Что делает

- `POST /api/license/check` принимает `hwid` и `password`.
- Сервер ищет HWID в SQLite, проверяет PBKDF2-хэш пароля и `expires_at`.
- Если срок истек или HWID не найден, приложение не запускается.
- Пароли в базе не хранятся открытым текстом.

## Установка на VPS

```bash
sudo adduser --system --group --home /opt/smc-license smclic
sudo mkdir -p /opt/smc-license /var/lib/smc-license
sudo cp license_common.py license_server.py license_admin.py /opt/smc-license/
sudo cp systemd/smc-license.service /etc/systemd/system/smc-license.service
sudo chown -R smclic:smclic /opt/smc-license /var/lib/smc-license
sudo chmod 750 /opt/smc-license

sudo -u smclic python3 /opt/smc-license/license_admin.py init
sudo systemctl daemon-reload
sudo systemctl enable --now smc-license
curl http://127.0.0.1:8787/health
```

## Выдать доступ на месяц

1. Пользователь нажимает в программе `Скопировать HWID`.
2. На VPS добавляешь этот HWID:

```bash
sudo -u smclic python3 /opt/smc-license/license_admin.py add \
  --hwid "SMC-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" \
  --password "client-password" \
  --days 30 \
  --note "client name"
```

Продлить еще на месяц:

```bash
sudo -u smclic python3 /opt/smc-license/license_admin.py extend \
  --hwid "SMC-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" \
  --days 30
```

Посмотреть базу:

```bash
sudo -u smclic python3 /opt/smc-license/license_admin.py list
```

Отключить доступ:

```bash
sudo -u smclic python3 /opt/smc-license/license_admin.py disable --hwid "SMC-..."
```

## HTTPS через Caddy

Пароль нельзя гонять по обычному HTTP. Самый простой вариант - Caddy как reverse proxy:

```bash
sudo apt update
sudo apt install -y caddy
sudo tee /etc/caddy/Caddyfile >/dev/null <<'EOF'
license.example.com {
    reverse_proxy 127.0.0.1:8787
}
EOF
sudo systemctl reload caddy
```

После HTTPS Wireshark уже не увидит `HWID`, пароль и ответ сервера. Он увидит только IP/домен и сам факт соединения.

Потом на Windows в проекте укажи:

```properties
# settings/license.properties
enabled=true
server.url=https://license.example.com
timeout.seconds=8
```

## Дополнительная защита: TLS public-key pin

Чтобы приложение не доверяло подмененному сертификату, можно закрепить публичный ключ твоего домена.
На VPS или любом Linux-компьютере получи SHA-256 fingerprint публичного ключа:

```bash
echo | openssl s_client -connect license.example.com:443 -servername license.example.com 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256
```

Вставь полученный hex в настройки Windows:

```properties
enabled=true
server.url=https://license.example.com
server.publicKeySha256=PASTE_HEX_HERE
allow.insecure.local=true
timeout.seconds=8
```

Если сертификат/ключ на сервере поменяется, pin нужно обновить, иначе программа честно заблокирует вход.

Для разработки можно временно отключить:

```properties
enabled=false
```

## Temporary HTTP without domain

This is simpler, but not private: Wireshark can read HWID/password. Use it only when you accept that risk.

Keep Python license server on `127.0.0.1:8787`, expose only Caddy on port 80:

```bash
sudo tee /etc/caddy/Caddyfile >/dev/null <<'EOF'
http://license.example.com {
    reverse_proxy 127.0.0.1:8787
}
EOF

sudo ufw allow 80/tcp
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl http://license.example.com/health
```

Windows settings:

```properties
enabled=true
server.url=http://license.example.com
allow.insecure.remote=true
timeout.seconds=8
```
