# Evolution API — Deploy no Railway

A Evolution API é um **serviço separado** do backend MyDelivery. Você precisa criar
um **segundo serviço no Railway** para ela (não vai junto com o backend).

## Subindo no Railway

### 1. Criar novo serviço

No mesmo projeto Railway:

1. **New → Empty Service** (não conectar repo — vamos usar a imagem Docker oficial)
2. **Settings → Source → Image** → `atendai/evolution-api:v2.1.1`
3. **Settings → Networking → Generate Domain** (gera URL pública tipo `evolution-production-xxxx.up.railway.app`)

### 2. Variáveis de ambiente (Settings → Variables)

Mínimo necessário para a v2.x rodar:

```
SERVER_TYPE=http
SERVER_PORT=8080
SERVER_URL=https://evolution-production-xxxx.up.railway.app

AUTHENTICATION_API_KEY=<gere com: openssl rand -hex 32>
AUTHENTICATION_EXPOSE_IN_FETCH_INSTANCES=true

DATABASE_ENABLED=true
DATABASE_PROVIDER=postgresql
DATABASE_CONNECTION_URI=${{Postgres.DATABASE_URL}}
DATABASE_CONNECTION_CLIENT_NAME=evolution_railway
DATABASE_SAVE_DATA_INSTANCE=true
DATABASE_SAVE_DATA_NEW_MESSAGE=true
DATABASE_SAVE_MESSAGE_UPDATE=true
DATABASE_SAVE_DATA_CONTACTS=true
DATABASE_SAVE_DATA_CHATS=true

CACHE_REDIS_ENABLED=false
CACHE_LOCAL_ENABLED=true

LANGUAGE=pt-BR
CONFIG_SESSION_PHONE_CLIENT=MyDelivery
CONFIG_SESSION_PHONE_NAME=Chrome
DEL_INSTANCE=false
QRCODE_LIMIT=30

WEBHOOK_GLOBAL_ENABLED=false
```

> Importante: a Evolution v2 usa **Postgres** (não MySQL). Adicione um plugin **Postgres**
> no Railway (não reaproveite o MySQL do backend) e a `DATABASE_URL` é injetada via referência
> `${{Postgres.DATABASE_URL}}`.

### 3. Conectar com o backend MyDelivery

No serviço do **backend MyDelivery**, configure:

```
EVOLUTION_BASE_URL=https://evolution-production-xxxx.up.railway.app
EVOLUTION_API_KEY=<mesma chave AUTHENTICATION_API_KEY acima>
EVOLUTION_WEBHOOK_BASE_URL=https://<dominio-do-backend>.up.railway.app
```

## Rodando localmente (dev)

O `docker-compose.yml` desta pasta é só para uso local. Em produção use Railway diretamente
com a imagem oficial.

```bash
cd evolution
cp .env.example .env  # criar e preencher
docker compose up -d
```

Acesse: `http://localhost:8081`
