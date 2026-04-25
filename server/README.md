# Proxy Anthropic (SafeguardAssistant)

O APK **não** deve levar `ANTHROPIC_API_KEY`. Este servidor recebe o POST do [`AiClient`](../app/src/main/java/com/example/safeguardassistant/AiClient.kt) e chama a Claude API.

O código do proxy usa **tipos compatíveis com Python 3.9** (no Mac costuma ser a versão do `python3` do sistema). Se tiveres Python 3.10+, também funciona.

## 1. Configurar

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
# Edita SÓ o ficheiro .env (não o .env.example) e cola ANTHROPIC_API_KEY
# de https://console.anthropic.com/ — o Python só lê .env, não .env.example
```

Teste rápido (com o servidor a correr): o endpoint **exige** um corpo JSON. Usa **uma linha** (em zsh/bash copia tudo duma vez; erros 422 = corpo em falta):

```bash
curl -s -X POST 'http://127.0.0.1:8787/inspect' -H 'Content-Type: application/json' -d '{"profile":"FI_OCCUPIED","visibleTexts":["Yes","No"],"rulesJson":{},"systemPrompt":"Return JSON: {\"answer\":\"No\"} only, one line."}'
```

Se aparecer `422` com `"loc":["body"]` → o `curl` não enviou o `-d '...'`. Se aparecer `502` → a Anthropic devolveu erro (chave, modelo, rede). Mensagem com `not_found_error` e `model:` → o `ANTHROPIC_MODEL` no `.env` é um ID antigo/inválido; o default do proxy é `claude-haiku-4-5` (ver [modelos](https://docs.anthropic.com/en/docs/about-claude/models)). Se `500` com `ANTHROPIC_API_KEY missing` → o `.env` não tem a variável no diretório `server` ou não reiniciaste o uvicorn.

## 2. Arrancar

```bash
uvicorn main:app --host 0.0.0.0 --port 8787
```

## 3. URL no Android

- **Debug / emulador:** o projeto já define em  
  [`app/src/debug/res/values/config.xml`](../app/src/debug/res/values/config.xml)  
  `http://10.0.2.2:8787/inspect` (alias do teu Mac no emulador).  
  [`app/src/debug/AndroidManifest.xml`](../app/src/debug/AndroidManifest.xml) permite HTTP só em debug.

- **Telemóvel físico (mesma Wi‑Fi):** edita `app/src/debug/res/values/config.xml` com o IP LAN do PC, ex.  
  `http://192.168.1.23:8787/inspect`

- **Release:** `app/src/main/res/values/config.xml` fica vazio por defeito — usa HTTPS (ex. ngrok ou Cloud Run) e cola a URL antes de gerar release.

## 4. Testar

```bash
curl -s http://127.0.0.1:8787/health
```

## 5. Inspeção em campo (5G, Wi‑Fi, muitos dispositivos)

Cada inspetor **não** pode depender do teu Mac. O fluxo tem de ser:

1. **Proxy na internet** — o mesmo `main.py` num serviço com **URL HTTPS pública** (ex. [Cloud Run](https://cloud.google.com/run), [Railway](https://railway.app), [Fly.io](https://fly.io), [Render](https://render.com), [Azure Container Apps](https://azure.microsoft.com/products/container-apps), etc.).
2. **Secrets no servidor** — `ANTHROPIC_API_KEY` (e `ANTHROPIC_MODEL` se quiseres) só nas **variáveis de ambiente / secret manager** do hosting, nunca no Git.
3. **Uma URL em todos os APKs** — em `app/src/main/res/values/config.xml`, preenche `ai_inspection_endpoint` com `https://teu-dominio/inspect` (build de **release** assinado e distribuído via Play / MDM / link interno).
4. **Proteger o endpoint** (recomendado) — no servidor, define `PROXY_BEARER_TOKEN` a um token longo (gera com `openssl rand -hex 32`). O proxy passa a exigir `Authorization: Bearer <token>`. No Android, o mesmo valor vai em `ai_inspection_bearer` **só** no teu processo de build (CI com secret, ou ficheiro local de overrides não comitado). Em dev local, deixa ambos vazios.
5. **Limitar custo e abuso** — além do Bearer, no cloud costuma haver **rate limiting** / **API gateway**; monitoriza a consola Anthropic. Para auditoria fina, o passo seguinte costuma ser **login (OIDC) + short-lived token** em vez de um segredo fixo no APK.
6. **Criptografia** — o transporte é **TLS** (HTTPS). O tráfego entre inspetor e o teu proxy está protegido; o ecrã continua a ser enviado em texto ao teu serviço — define política de retenção e, se a empresa exigir, **BAA / DPA** com a Anthropic.

Teste o Bearer com o proxy em `127.0.0.1` (`.env` com `PROXY_BEARER_TOKEN=olá` — só exemplo):

```bash
curl -s -X POST 'http://127.0.0.1:8787/inspect' \
  -H 'Authorization: Bearer olá' -H 'Content-Type: application/json' \
  -d '{"profile":"FI_OCCUPIED","visibleTexts":["Yes","No"],"rulesJson":{},"systemPrompt":"Return JSON: {\"answer\":\"No\"} only, one line."}'
```

## 6. Checklist mínima de produção

- HTTPS, `ANTHROPIC_API_KEY` e (recomendado) **login** com `JWT_SECRET` + `ADMIN_API_KEY` no hosting.
- `ai_inspection_endpoint` no release; o app guarda o JWT após “Entrar” (não comitar secrets no Git).
- O APK em 4G/5G precisa de URL pública; não depende do Mac do desenvolvedor.

## 7. Login, cadastro e aprovação (pt-BR)

1. No `.env` do servidor: `JWT_SECRET` (ex.: `openssl rand -base64 48`) e `ADMIN_API_KEY` (chave **diferente** do JWT, para aprovar usuários).
2. Com `JWT_SECRET` definido, o `/inspect` exige `Authorization: Bearer <JWT>` de usuário **aprovado** (ou use só `PROXY_BEARER_TOKEN` *sem* `JWT_SECRET` para teste legado, sem login).
3. **Cadastro** (app: “Criar conta”): `POST /auth/register` — usuário fica _pendente_.
4. **Aprovar** (só admin, com a chave em segredo):
   - `GET /admin/pending` com header `X-Admin-Key: <ADMIN_API_KEY>`
   - `POST /admin/approve` com `{"email":"fulano@empresa.com"}` e o mesmo header.
5. **Entrar** no app: o Android chama `POST /auth/login` e salva o token; o `AiClient` envia esse token em toda requisição ao `/inspect`.

Exemplo (Mac, servidor em 8787, substitua e-mails e chaves):

```bash
export A=seu_admin_key
curl -s -H "X-Admin-Key: $A" http://127.0.0.1:8787/admin/pending
curl -s -X POST -H "X-Admin-Key: $A" -H "Content-Type: application/json" \
  -d '{"email":"inspetor@empresa.com"}' http://127.0.0.1:8787/admin/approve
```

O ficheiro `auth.db` (SQLite) fica no servidor; faça **backup** ou guarde o volume no deploy em nuvem.
