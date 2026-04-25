"""
Proxy: recebe o JSON do app Android e chama a API Anthropic.
Autenticacao: SQLite + JWT + aprovacao admin. Compativel com Python 3.9.
"""
import contextlib
import json
import os
import re
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

import anthropic
import jwt
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from jwt.exceptions import ExpiredSignatureError, InvalidTokenError
from passlib.context import CryptContext
from pydantic import BaseModel, field_validator
from starlette import status
from starlette.responses import JSONResponse

load_dotenv()

# --- Config ---
DB_PATH = os.environ.get("AUTH_DB_PATH", os.path.join(os.path.dirname(__file__), "auth.db"))
JWT_SECRET = os.environ.get("JWT_SECRET", "").strip()
JWT_EXPIRE_DAYS = int(os.environ.get("JWT_EXPIRE_DAYS", "7"))
ADMIN_API_KEY = os.environ.get("ADMIN_API_KEY", "").strip()
ANTHROPIC_KEY = "ANTHROPIC_API_KEY"

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# --- DB ---


@contextlib.contextmanager
def get_conn() -> Any:
    conn = sqlite3.connect(DB_PATH)
    try:
        conn.row_factory = sqlite3.Row
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    d = os.path.dirname(DB_PATH)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    with get_conn() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                is_approved INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            """
        )


def get_inspect_auth_mode() -> str:
    if JWT_SECRET:
        return "jwt"
    if os.environ.get("PROXY_BEARER_TOKEN", "").strip():
        return "bearer"
    return "none"


# --- Models ---


class InspectRequest(BaseModel):
    profile: str
    visibleTexts: List[str]
    rulesJson: Optional[Dict[str, Any]] = None
    systemPrompt: str


class InspectVisionRequest(BaseModel):
    profile: str
    visibleTexts: List[str]
    screenshotBase64: str
    step: Optional[int] = 0


class UserRegister(BaseModel):
    email: str
    password: str

    @field_validator("email")
    @classmethod
    def email_ok(cls, v: str) -> str:
        v = v.strip().lower()
        if "@" not in v or len(v) < 3:
            raise ValueError("email invalido")
        return v

    @field_validator("password")
    @classmethod
    def pass_ok(cls, v: str) -> str:
        if len(v) < 6:
            raise ValueError("senha minimo 6 caracteres")
        return v


class UserLogin(BaseModel):
    email: str
    password: str


class ApproveBody(BaseModel):
    email: str

    @field_validator("email")
    @classmethod
    def ap_email_ok(cls, v: str) -> str:
        return v.strip().lower()


# --- Auth helpers ---


def _hash_pwd(plain: str) -> str:
    return pwd_context.hash(plain[:72])


def _verify_pwd(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain[:72], hashed)


def _create_jwt(sub_email: str) -> str:
    now = datetime.now(timezone.utc)
    exp = now + timedelta(days=JWT_EXPIRE_DAYS)
    payload: Dict[str, Any] = {
        "sub": sub_email,
        "exp": exp,
        "iat": now,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


def _decode_jwt(token: str) -> str:
    try:
        p = jwt.decode(token, JWT_SECRET, algorithms=["HS256"])
        sub = p.get("sub")
        if not isinstance(sub, str) or not sub:
            raise HTTPException(status_code=401, detail="Token invalido")
        return sub.lower()
    except ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Sessao expirada, faca login de novo")
    except InvalidTokenError:
        raise HTTPException(status_code=401, detail="Token invalido")


def _get_user_by_email(conn: Any, email: str) -> Optional[sqlite3.Row]:
    r = conn.execute("SELECT * FROM users WHERE email = ? COLLATE NOCASE", (email.lower().strip(),)).fetchone()
    return r


def _require_jwt_approved(authorization: Optional[str] = Header(default=None)) -> str:
    """Retorna o email aprovado ou levanta HTTPException."""
    if not authorization or authorization[:7].lower() != "bearer ":
        raise HTTPException(
            status_code=401,
            detail="Nao autenticado. Faca login no app.",
        )
    token = authorization[7:].strip()
    email = _decode_jwt(token)
    with get_conn() as conn:
        u = _get_user_by_email(conn, email)
        if not u:
            raise HTTPException(status_code=401, detail="Usuario inexistente")
        if not int(u["is_approved"]):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="pending_approval",
            )
    return email


def _require_admin(x_admin_key: Optional[str] = Header(default=None, alias="X-Admin-Key")) -> None:
    if not ADMIN_API_KEY:
        raise HTTPException(status_code=503, detail="ADMIN_API_KEY nao configurada no servidor")
    if not x_admin_key or not secrets.compare_digest(x_admin_key.strip(), ADMIN_API_KEY):
        raise HTTPException(status_code=403, detail="Admin recusado")


def _require_proxy_bearer(authorization: Optional[str] = Header(default=None)) -> None:
    expected = os.environ.get("PROXY_BEARER_TOKEN", "").strip()
    if not expected:
        return
    if not authorization or authorization[:7].lower() != "bearer ":
        raise HTTPException(status_code=401, detail="Unauthorized")
    got = authorization[7:].strip()
    if not secrets.compare_digest(got, expected):
        raise HTTPException(status_code=401, detail="Unauthorized")


def verify_inspect_auth(authorization: Optional[str] = Header(default=None)) -> None:
    mode = get_inspect_auth_mode()
    if mode == "none":
        return
    if mode == "jwt":
        _require_jwt_approved(authorization=authorization)
    else:
        _require_proxy_bearer(authorization=authorization)


def _extract_answer_json(text: str) -> Dict[str, str]:
    text = text.strip()
    if not text:
        return {"answer": "MANUAL_REVIEW"}
    # Modelo por vezes envolve em ```json ... ```
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE).strip()
        text = re.sub(r"\s*```\s*$", "", text).strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict) and "answer" in obj:
            return {"answer": str(obj["answer"]).strip()}
    except json.JSONDecodeError:
        pass
    brace = text.find("{")
    if brace != -1:
        depth = 0
        for i in range(brace, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    try:
                        obj = json.loads(text[brace : i + 1])
                        if isinstance(obj, dict) and "answer" in obj:
                            return {"answer": str(obj["answer"]).strip()}
                    except json.JSONDecodeError:
                        pass
                    break
    m = re.search(r"\{[^{}]*\"answer\"[^{}]*\}", text, re.DOTALL)
    if m:
        try:
            obj = json.loads(m.group())
            if isinstance(obj, dict) and "answer" in obj:
                return {"answer": str(obj["answer"]).strip()}
        except json.JSONDecodeError:
            pass
    return {"answer": "MANUAL_REVIEW"}


def _vision_fallback(reason: str) -> Dict[str, Any]:
    return {
        "action": "manual_review",
        "answer": "",
        "xPercent": 0.0,
        "yPercent": 0.0,
        "confidence": 0.0,
        "reason": reason,
    }


def _coerce_vision_dict(d: Dict[str, Any]) -> Dict[str, Any]:
    act = str(d.get("action", "manual_review")).strip().lower()
    if act in ("tap", "scroll", "stop", "manual_review"):
        pass
    elif act == "manual":
        act = "manual_review"
    else:
        act = "manual_review"
    try:
        conf = float(d.get("confidence", 0) or 0)
    except (TypeError, ValueError):
        conf = 0.0
    conf = max(0.0, min(1.0, conf))
    try:
        xp = float(d.get("xPercent", 0) or 0)
    except (TypeError, ValueError):
        xp = 0.0
    try:
        yp = float(d.get("yPercent", 0) or 0)
    except (TypeError, ValueError):
        yp = 0.0
    return {
        "action": act,
        "answer": str(d.get("answer", "")).strip(),
        "xPercent": max(0.0, min(100.0, xp)),
        "yPercent": max(0.0, min(100.0, yp)),
        "confidence": conf,
        "reason": str(d.get("reason", "")).strip(),
    }


def _extract_vision_json(text: str) -> Dict[str, Any]:
    text = text.strip()
    if not text:
        return _vision_fallback("empty model output")
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE).strip()
        text = re.sub(r"\s*```\s*$", "", text).strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict) and "action" in obj:
            return _coerce_vision_dict(obj)
    except json.JSONDecodeError:
        pass
    brace = text.find("{")
    if brace != -1:
        depth = 0
        for i in range(brace, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    try:
                        obj = json.loads(text[brace : i + 1])
                        if isinstance(obj, dict) and "action" in obj:
                            return _coerce_vision_dict(obj)
                    except json.JSONDecodeError:
                        pass
                    break
    return _vision_fallback("could not parse vision JSON")


def _vision_system_prompt(profile: str) -> str:
    p = (profile or "").strip().upper()
    manual_text = ""
    try:
        base = os.path.join(os.path.dirname(__file__), "manuals")
        path = os.path.join(base, f"{p}.md")
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as f:
                manual_text = f.read().strip()
    except Exception:
        manual_text = ""

    header = (
        "You are controlling an Android app assistant that fills Safeguard property inspection forms.\n"
        f"Inspection profile: {profile}\n"
        "You will receive:\n"
        "1) A screenshot of the current Safeguard form screen\n"
        "2) Accessibility visibleTexts, which may be incomplete or misleading\n\n"
        "Your job:\n"
        "- Identify the current question being asked\n"
        "- Identify the available answer options\n"
        "- Choose the safest correct answer based on the inspection profile\n"
        "- Return a tap location using percentage coordinates (0-100) of screen width/height "
        "for the center of the correct option/control\n"
        "- If you need to reveal more options, return scroll\n"
    )
    fi_rules = """
Rules for FI_OCCUPIED:
- Property is occupied
- Vacancy = No
- Occupied = Yes
- Contact made = No unless explicitly stated
- Spoke with occupant = No unless explicitly stated
- Damages = No unless clearly visible or stated
- Grass over 6 inches = No unless clearly visible or stated
- Property condition = Fair unless a better specific option is clearly required
- Utilities on = Yes
- Property secure = Yes
- Access gained = No
- Do not continue into Comments section
- If the screen is Comments or asks for notes/comments, return action stop
- If confidence is low, return manual_review

Return JSON only on one line or a single JSON object:
{
  "action": "tap" | "scroll" | "stop" | "manual_review",
  "answer": "exact answer if applicable",
  "xPercent": 0-100,
  "yPercent": 0-100,
  "confidence": 0-1,
  "reason": "short reason"
}
"""
    if p == "FI_OCCUPIED":
        system = header + fi_rules
    else:
        system = (
            header
            + "Apply the inspection profile name conservatively. If unsure, return manual_review. "
            + "Same JSON schema as above.\n"
        )

    if manual_text:
        system += "\n\nPROFILE MANUAL (follow these rules when relevant):\n" + manual_text + "\n"
    return system


# --- App ---

init_db()
app = FastAPI(title="SafeguardAssistant proxy")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.on_event("startup")
def _startup() -> None:
    init_db()
    mode = get_inspect_auth_mode()
    if mode == "none" and ANTHROPIC_KEY in os.environ and os.environ.get(ANTHROPIC_KEY, "").strip():
        print("AVISO: /inspect publico (sem JWT nem PROXY_BEARER) — so para dev local!")


# --- Auth routes (precisam JWT_SECRET) ---


def _auth_available() -> bool:
    return bool(JWT_SECRET)


@app.post("/auth/register")
def auth_register(body: UserRegister) -> JSONResponse:
    if not _auth_available():
        raise HTTPException(
            status_code=503,
            detail="Registro desabilitado: defina JWT_SECRET no servidor",
        )
    with get_conn() as conn:
        ex = _get_user_by_email(conn, body.email)
        if ex:
            raise HTTPException(status_code=409, detail="Email ja cadastrado")
        conn.execute(
            "INSERT INTO users (email, password_hash, is_approved, created_at) VALUES (?,?,?,?)",
            (body.email.lower(), _hash_pwd(body.password), 0, datetime.now(timezone.utc).isoformat()),
        )
    return JSONResponse(
        {"ok": True, "message": "Cadastro recebido. Aguarde aprovacao de um administrador."},
        status_code=201,
    )


@app.post("/auth/login")
def auth_login(body: UserLogin) -> Dict[str, Any]:
    if not _auth_available():
        raise HTTPException(status_code=503, detail="Login desabilitado: defina JWT_SECRET no servidor")
    with get_conn() as conn:
        u = _get_user_by_email(conn, body.email.strip().lower())
        if not u or not _verify_pwd(body.password, u["password_hash"]):
            raise HTTPException(status_code=401, detail="Email ou senha incorretos")
        if not int(u["is_approved"]):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="pending_approval",
            )
    token = _create_jwt(u["email"].lower() if isinstance(u["email"], str) else u["email"])
    if isinstance(token, bytes):
        token = token.decode("utf-8")
    return {"access_token": token, "token_type": "bearer"}


# --- Admin ---


@app.get("/admin/pending")
def admin_list_pending(_: None = Depends(_require_admin)) -> Dict[str, Any]:
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT email, created_at FROM users WHERE is_approved = 0 ORDER BY id ASC",
        ).fetchall()
    return {"emails": [{"email": r["email"], "created_at": r["created_at"]} for r in rows]}


@app.post("/admin/approve")
def admin_approve(body: ApproveBody, _: None = Depends(_require_admin)) -> Dict[str, Any]:
    with get_conn() as conn:
        e = body.email.strip().lower()
        cur = conn.execute("UPDATE users SET is_approved = 1 WHERE email = ? COLLATE NOCASE", (e,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Email nao encontrado")
    return {"ok": True, "message": f"Aprovado: {e}"}


# --- Inspect (auth: JWT, bearer estatico, ou nenhum em dev) ---


@app.post("/inspect", dependencies=[Depends(verify_inspect_auth)])
def inspect(body: InspectRequest) -> Dict[str, str]:
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=500, detail="ANTHROPIC_API_KEY missing")

    model = os.environ.get("ANTHROPIC_MODEL", "claude-haiku-4-5").strip()

    lines = body.visibleTexts or []
    numbered = "\n".join(f"{i + 1:3d}. {line}" for i, line in enumerate(lines))

    user_block = (
        "You are reading a mobile property inspection form. Each line below is one string "
        "from the Android accessibility tree (order is not guaranteed; duplicates may appear).\n\n"
        "TASK — do this mentally, then output JSON only:\n"
        "1) Find the ONE primary question or field that most needs an answer now "
        "(look for question marks, labels like 'Required', incomplete 'Select', "
        "or yes/no groups that look unanswered next to a prompt).\n"
        "2) List which candidate ANSWER strings from the SAME screen appear as separate lines "
        "and could be tapped (e.g. Yes, No, Occupied, Not For Sale). Ignore toolbar: Camera, Gallery, "
        "Label, Badge, QUEUE, Stations, photo slot names.\n"
        "3) Pick exactly ONE answer that is a substring or exact match of a visible line, "
        "following the system rules JSON when it applies.\n"
        "4) If you cannot name the question you are answering and a safe option, return MANUAL_REVIEW.\n\n"
        "Return a single JSON object, one line, with this shape:\n"
        '{"question_focus":"short quote of the question you chose or empty",'
        '"answer":"Yes"} or {"question_focus":"","answer":"MANUAL_REVIEW"}\n\n'
        "NUMBERED LINES:\n"
        + numbered
    )

    client = anthropic.Anthropic(api_key=api_key)
    try:
        msg = client.messages.create(
            model=model,
            max_tokens=512,
            system=body.systemPrompt.strip(),
            messages=[{"role": "user", "content": user_block}],
        )
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e)) from e

    parts: List[str] = []
    for block in msg.content:
        btype = getattr(block, "type", None)
        if btype == "text":
            parts.append(getattr(block, "text", ""))
    combined = "\n".join(parts)
    out = _extract_answer_json(combined)
    if os.environ.get("INSPECT_LOG_MODEL", "").strip() in ("1", "true", "yes"):
        qm = re.search(r'"question_focus"\s*:\s*"((?:[^"\\]|\\.)*)"', combined)
        qf = (qm.group(1) if qm else "")[:120]
        print(f"[inspect] model={model} answer={out.get('answer')!r} question_focus={qf!r}")
    return out


@app.post("/inspect-vision", dependencies=[Depends(verify_inspect_auth)])
def inspect_vision(body: InspectVisionRequest) -> Dict[str, Any]:
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=500, detail="ANTHROPIC_API_KEY missing")

    raw_b64 = (body.screenshotBase64 or "").strip()
    if len(raw_b64) < 64:
        raise HTTPException(status_code=400, detail="screenshotBase64 too small")

    model = os.environ.get("ANTHROPIC_VISION_MODEL", "").strip()
    if not model:
        model = os.environ.get("ANTHROPIC_MODEL", "claude-haiku-4-5").strip()

    lines = body.visibleTexts or []
    numbered = "\n".join(f"{i + 1:3d}. {line}" for i, line in enumerate(lines))
    step = body.step if body.step is not None else 0

    user_text = (
        f"Form step hint (may be inaccurate): {step}\n\n"
        "NUMBERED visibleTexts lines from Android accessibility (order not guaranteed):\n"
        f"{numbered}\n\n"
        "Use the screenshot as the primary source of truth for layout and labels. "
        "Pick the safest next action for this inspection profile."
    )

    user_content: List[Dict[str, Any]] = [
        {
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": "image/jpeg",
                "data": raw_b64,
            },
        },
        {"type": "text", "text": user_text},
    ]

    client = anthropic.Anthropic(api_key=api_key)
    try:
        msg = client.messages.create(
            model=model,
            max_tokens=1024,
            system=_vision_system_prompt(body.profile),
            messages=[{"role": "user", "content": user_content}],
        )
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e)) from e

    parts: List[str] = []
    for block in msg.content:
        btype = getattr(block, "type", None)
        if btype == "text":
            parts.append(getattr(block, "text", ""))
    combined = "\n".join(parts)
    out = _extract_vision_json(combined)
    if os.environ.get("INSPECT_LOG_MODEL", "").strip() in ("1", "true", "yes"):
        print(
            f"[inspect-vision] model={model} action={out.get('action')!r} "
            f"conf={out.get('confidence')!r} reason={(out.get('reason') or '')[:120]!r}"
        )
    return out
