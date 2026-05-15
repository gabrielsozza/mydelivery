# MyDelivery API

Backend Spring Boot do MyDelivery — plataforma multi-tenant de delivery para restaurantes.

## Stack

- **Java 21** + **Spring Boot 3.5** (Maven)
- **MySQL** (JPA/Hibernate, ddl-auto=update)
- **Spring Security + JWT**
- **Cloudinary** (upload de imagens)
- **Mercado Pago** (PIX + cartão)
- **Evolution API v2** (WhatsApp — serviço Docker separado)

## Rodando localmente

### 1. Pré-requisitos

- Java 21
- MySQL rodando em `localhost:3306`
- (Opcional) Evolution API em `:8081` via `docker compose up` na pasta `evolution/`

### 2. Configurar variáveis de ambiente

```bash
cp .env.example .env
# editar .env com suas credenciais
```

Mínimo necessário para o backend subir:

- `DB_PASSWORD` — senha do MySQL local
- `JWT_SECRET` — gere com `openssl rand -base64 64`
- `CLOUDINARY_*` — credenciais do [Cloudinary](https://cloudinary.com/console) (uploads)

### 3. Subir

```bash
./mvnw spring-boot:run
```

Backend sobe em `http://localhost:8080`.

## Estrutura

```
src/main/java/com/mydelivery/
├── config/          # Properties, beans (Cloudinary, Mercado Pago, etc.)
├── controller/      # REST endpoints
├── dto/             # DTOs de request/response
├── exception/       # GlobalExceptionHandler
├── job/             # Tarefas agendadas (@Scheduled)
├── model/           # Entidades JPA
├── repository/      # Spring Data
├── security/        # JWT filter, SecurityConfig
└── service/         # Lógica de negócio

evolution/           # Docker-compose da Evolution API (serviço separado — ver evolution/README.md)
```

## Deploy no Railway

> O Railway detecta automaticamente o projeto Maven via **Nixpacks** e usa Java 21
> (configurado via `system.properties`, `nixpacks.toml` e `railway.json`).

### Arquitetura no Railway

Este projeto precisa de **3 serviços** no mesmo projeto Railway:

| Serviço | Tipo | Origem |
|---------|------|--------|
| `mydelivery-api` | App | Este repo (GitHub integration) |
| `mysql` | Database | Plugin **MySQL** do Railway |
| `evolution-api` | App | Imagem Docker `atendai/evolution-api:v2.1.1` (+ plugin **Postgres**) — ver `evolution/README.md` |

### Passo a passo — Backend MyDelivery

1. **New Project → Deploy from GitHub** → selecionar este repo
2. Railway detecta Maven, builda com `./mvnw clean package`, sobe o jar
3. **Add Plugin → MySQL** no mesmo projeto
4. **Settings → Networking → Generate Domain** (gera URL pública)
5. **Variables** — colar as variáveis abaixo

### Variáveis de ambiente (Railway → Variables)

Cole todas no painel do serviço **mydelivery-api**:

```bash
# ── Banco (referência ao plugin MySQL) ──
DB_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?useSSL=false&serverTimezone=America/Sao_Paulo&allowPublicKeyRetrieval=true
DB_USERNAME=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}

# ── JWT (gere: openssl rand -base64 64) ──
JWT_SECRET=COLAR_AQUI_O_RESULTADO_DO_OPENSSL_RAND
JWT_EXPIRATION=28800000
JWT_REFRESH_EXPIRATION=604800000

# ── Email (App Password do Gmail) ──
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=seu-email@gmail.com
MAIL_PASSWORD=app-password-do-gmail
MAIL_FROM=noreply@mydelivery.app
MAIL_FROM_NAME=MyDelivery

# ── URLs públicas (TROCAR pelos domínios reais do Railway / frontend) ──
APP_URL=https://app.mydelivery.com
API_BASE_URL=https://${{RAILWAY_PUBLIC_DOMAIN}}
CORS_ORIGINS=https://app.mydelivery.com

# ── Cloudinary ──
CLOUDINARY_CLOUD_NAME=seu_cloud_name
CLOUDINARY_API_KEY=sua_api_key
CLOUDINARY_API_SECRET=seu_api_secret

# ── Evolution API (URL pública do segundo serviço Railway) ──
EVOLUTION_BASE_URL=https://evolution-production-xxxx.up.railway.app
EVOLUTION_API_KEY=mesma_AUTHENTICATION_API_KEY_da_evolution
EVOLUTION_WEBHOOK_BASE_URL=https://${{RAILWAY_PUBLIC_DOMAIN}}

# ── Mercado Pago ──
MP_WEBHOOK_URL=https://${{RAILWAY_PUBLIC_DOMAIN}}/api/webhooks/mercadopago
```

> `PORT` é injetada automaticamente pelo Railway — já tratada em `server.port=${PORT:8080}`.
> Não defina `PORT` manualmente.

### Variáveis do MySQL Plugin

O plugin MySQL do Railway expõe automaticamente:
- `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`

A sintaxe `${{MySQL.MYSQLHOST}}` cria uma **referência viva** — se o plugin trocar de host,
o backend pega o novo valor sem redeploy manual.

### Evolution API (segundo serviço)

Ver instruções detalhadas em [`evolution/README.md`](./evolution/README.md).
Resumo: criar **Empty Service** → setar imagem `atendai/evolution-api:v2.1.1` → adicionar
plugin **Postgres** → configurar variáveis listadas no README da pasta.

### Arquivos de deploy neste repo

- `system.properties` — força Java 21
- `nixpacks.toml` — config explícita do builder Nixpacks
- `Procfile` — fallback do start command
- `railway.json` — config nativa do Railway

## Recursos principais

- **Multi-tenant** por restaurante (slug único)
- **Trial 32 dias** com bloqueio gradual após vencimento
- **Planos** mensal / semestral / anual com cancelamento e renovação automática
- **Pagamentos** PIX e cartão via Mercado Pago (multi-tenant)
- **WhatsApp** opcional via Evolution API com bot anti-spam
- **Cardápio público** com cache busting e CDN do Cloudinary
- **Suporte** integrado com upload de anexos (Cloudinary)

## Endpoints públicos

| Path | Função |
|------|--------|
| `POST /api/auth/login` | Login (retorna JWT) |
| `POST /api/auth/cadastro` | Cadastro de restaurante |
| `GET /api/restaurante/publico/{slug}` | Dados públicos do restaurante |
| `GET /api/cardapio/{slug}` | Cardápio público |
| `POST /api/pedidos/novo` | Criação de pedido pelo cliente final |
| `POST /api/webhooks/mercadopago` | Webhook MP |
| `POST /api/webhooks/whatsapp/{instance}` | Webhook Evolution |

Demais endpoints exigem `Authorization: Bearer <jwt>` e role `RESTAURANTE` ou `ADMIN`.
