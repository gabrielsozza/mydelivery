# MyDelivery API

Backend Spring Boot do MyDelivery — plataforma multi-tenant de delivery para restaurantes.

## Stack

- **Java 21** + **Spring Boot 3.5**
- **MySQL** (JPA/Hibernate, ddl-auto=update)
- **Spring Security + JWT**
- **Cloudinary** (upload de imagens)
- **Mercado Pago** (PIX + cartão)
- **Evolution API** (WhatsApp)

## Rodando localmente

### 1. Pré-requisitos

- Java 21
- MySQL rodando em `localhost:3306`
- (Opcional) Evolution API em `:8081` para WhatsApp

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
```

## Deploy no Railway

1. Conectar este repositório
2. Adicionar plugin **MySQL**
3. Configurar variáveis no painel do Railway:
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (vêm do plugin MySQL)
   - `JWT_SECRET`
   - `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`
   - `MAIL_*` (se quiser email)
   - `APP_URL`, `API_BASE_URL`, `CORS_ORIGINS` com os domínios reais

`PORT` é injetada automaticamente pelo Railway — já tratada em `server.port=${PORT:8080}`.

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
