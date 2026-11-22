#!/usr/bin/env python3
"""Optional fast UI automation runner powered by TypeSafe Jev (direct or OpenRouter).

Text-first by default: reads compact live interactive marks from `android-use`
(`GET /v1/screen/marks?refresh=true`), asks Jev to choose the next operation and
target mark in a single parallel request, and executes via `tap-element`.

Falls back to a Set-of-Mark annotated screenshot (`screenshot?annotate=true`)
only when Jev signals visual ambiguity (`need_vision`) or low confidence on
unlabeled controls, and escalates cleanly back to the calling System-2 agent
whenever free-form text, sensitive confirmation, or stuck states arise.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple


TYPESAFE_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
OPENROUTER_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
DEFAULT_TYPESAFE_MODEL = "jev-latest"
DEFAULT_OPENROUTER_MODEL = "typesafe/jev-1.14"
MAX_CHOICE_MARKS = 254  # Jev Choice max is 255 options; reserve 1 for "none".


class RunnerError(RuntimeError):
    """Structured error surfaced cleanly to calling agents."""

    def __init__(self, code: str, message: str, exit_code: int = 1) -> None:
        super().__init__(message)
        self.code = code
        self.exit_code = exit_code


@dataclass
class JevConfig:
    provider: str  # "typesafe" | "openrouter"
    api_key: str
    endpoint: str
    model: str
    timeout_sec: float = 20.0


def resolve_jev_config(
    provider_arg: Optional[str] = None,
    model_arg: Optional[str] = None,
    endpoint_arg: Optional[str] = None,
    env: Optional[Dict[str, str]] = None,
) -> JevConfig:
    e = env if env is not None else os.environ
    provider = (provider_arg or e.get("JEV_PROVIDER") or "auto").strip().lower()

    typesafe_key = (e.get("TYPESAFE_API_KEY") or "").strip()
    openrouter_key = (e.get("OPENROUTER_API_KEY") or "").strip()

    if provider == "auto":
        if typesafe_key:
            provider = "typesafe"
        elif openrouter_key:
            provider = "openrouter"
        else:
            raise RunnerError(
                code="JEV_NOT_CONFIGURED",
                message=(
                    "No Jev API key found. Set TYPESAFE_API_KEY (direct TypeSafe) "
                    "or OPENROUTER_API_KEY (OpenRouter), or fall back to standard "
                    "`android-use.sh marks` / `tap-mark` commands."
                ),
                exit_code=2,
            )

    if provider == "typesafe":
        if not typesafe_key:
            raise RunnerError(
                code="JEV_NOT_CONFIGURED",
                message="JEV_PROVIDER=typesafe requires TYPESAFE_API_KEY.",
                exit_code=2,
            )
        return JevConfig(
            provider="typesafe",
            api_key=typesafe_key,
            endpoint=(endpoint_arg or e.get("JEV_ENDPOINT") or TYPESAFE_ENDPOINT).strip(),
            model=(model_arg or e.get("JEV_MODEL") or DEFAULT_TYPESAFE_MODEL).strip(),
        )

    if provider == "openrouter":
        if not openrouter_key:
            raise RunnerError(
                code="JEV_NOT_CONFIGURED",
                message="JEV_PROVIDER=openrouter requires OPENROUTER_API_KEY.",
                exit_code=2,
            )
        return JevConfig(
            provider="openrouter",
            api_key=openrouter_key,
            endpoint=(endpoint_arg or e.get("JEV_ENDPOINT") or OPENROUTER_ENDPOINT).strip(),
            model=(model_arg or e.get("JEV_MODEL") or DEFAULT_OPENROUTER_MODEL).strip(),
        )

    raise RunnerError(
        code="INVALID_PROVIDER",
        message=f"Unsupported Jev provider '{provider}'. Expected 'auto', 'typesafe', or 'openrouter'.",
        exit_code=2,
    )


def http_json(
    method: str,
    url: str,
    headers: Optional[Dict[str, str]] = None,
    payload: Optional[Dict[str, Any]] = None,
    timeout_sec: float = 20.0,
) -> Dict[str, Any]:
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)

    data: Optional[bytes] = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req_headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        raise RunnerError(
            code=f"HTTP_{exc.code}",
            message=f"{method} {url} failed with HTTP {exc.code}: {body[:400]}",
        ) from exc
    except urllib.error.URLError as exc:
        raise RunnerError(
            code="NETWORK_ERROR",
            message=f"{method} {url} failed: {exc.reason}",
        ) from exc


class AndroidUseClient:
    """Minimal stdlib HTTP client for the android-use device server."""

    def __init__(self, base_url: str, token: str = "", timeout_sec: float = 20.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token.strip()
        self.timeout_sec = timeout_sec

    def _headers(self) -> Dict[str, str]:
        if self.token:
            return {"Authorization": f"Bearer {self.token}"}
        return {}

    def get(self, path: str) -> Dict[str, Any]:
        return http_json("GET", f"{self.base_url}{path}", headers=self._headers(), timeout_sec=self.timeout_sec)

    def post(self, path: str, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        return http_json(
            "POST",
            f"{self.base_url}{path}",
            headers=self._headers(),
            payload=payload if payload is not None else {},
            timeout_sec=self.timeout_sec,
        )

    def get_session(self) -> Dict[str, Any]:
        return self.get("/v1/session")

    def start_session(self, label: str = "jev-runner") -> Dict[str, Any]:
        return self.post("/v1/session/start", {"label": label, "idleTimeoutMs": 600000})

    def end_session(self) -> Dict[str, Any]:
        return self.post("/v1/session/end")

    def get_device_info(self) -> Dict[str, Any]:
        return self.get("/v1/device/info")

    def get_marks(self, refresh: bool = True) -> Dict[str, Any]:
        flag = "true" if refresh else "false"
        return self.get(f"/v1/screen/marks?refresh={flag}")

    def get_annotated_screenshot_base64(self, max_dimension: int = 1024) -> Dict[str, Any]:
        return self.get(
            f"/v1/screen/screenshot?encoding=base64&format=jpeg&quality=75&maxDimension={max_dimension}&annotate=true"
        )

    def tap_mark(self, mark_id: int) -> Dict[str, Any]:
        return self.post("/v1/actions/tap-element", {"markId": mark_id})

    def type_text(self, text: str, clear_first: bool = False, submit: bool = False) -> Dict[str, Any]:
        return self.post(
            "/v1/actions/type",
            {"text": text, "clearFirst": clear_first, "submit": submit},
        )

    def swipe(self, sx: float, sy: float, ex: float, ey: float, duration_ms: int = 300) -> Dict[str, Any]:
        return self.post(
            "/v1/actions/swipe",
            {
                "startX": sx,
                "startY": sy,
                "endX": ex,
                "endY": ey,
                "coordinateSpace": "normalized",
                "durationMs": duration_ms,
            },
        )

    def press_key(self, key: str) -> Dict[str, Any]:
        return self.post("/v1/actions/key", {"key": key})

    def wait_sleep(self, duration_ms: int = 800) -> Dict[str, Any]:
        return self.post("/v1/wait", {"durationMs": duration_ms})


class JevClient:
    """Thin stdlib client for TypeSafe Jev (direct `/v1/systemone` or OpenRouter `/api/alpha/decisions`)."""

    def __init__(self, config: JevConfig) -> None:
        self.config = config

    def evaluate(
        self,
        state: Dict[str, Any],
        questions: Dict[str, Any],
        image_base64: Optional[str] = None,
        image_format: str = "jpeg",
    ) -> Dict[str, Any]:
        headers: Dict[str, str] = {
            "Authorization": f"Bearer {self.config.api_key}",
        }
        body: Dict[str, Any] = {
            "model": self.config.model,
            "questions": questions,
        }

        if image_base64:
            if self.config.provider == "typesafe":
                placeholder = f"img_{uuid.uuid4().hex}"
                state_with_ref = dict(state)
                state_with_ref["annotated_screenshot"] = (
                    f"Annotated Android screenshot with numbered Set-of-Mark boxes: {placeholder}"
                )
                body["state"] = state_with_ref
                body["assets"] = {
                    placeholder: {
                        "type": "image",
                        "format": image_format,
                        "data_base64": image_base64,
                    }
                }
                headers["TypeSafe-Danger-Zone-Images"] = "true"
            else:
                # OpenRouter multimodal Decisions API uses a state content array.
                body["state"] = [
                    json.dumps(state, ensure_ascii=False),
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/{image_format};base64,{image_base64}",
                        },
                    },
                ]
        else:
            body["state"] = state

        return http_json(
            "POST",
            self.config.endpoint,
            headers=headers,
            payload=body,
            timeout_sec=self.config.timeout_sec,
        )


def format_mark_label(mark: Dict[str, Any]) -> str:
    label = (
        mark.get("text")
        or mark.get("contentDescription")
        or mark.get("hintText")
        or mark.get("resourceId")
        or mark.get("className")
        or "(unlabeled)"
    )
    suffix_parts: List[str] = []
    if mark.get("editable"):
        suffix_parts.append("editable")
    elif mark.get("checkable"):
        suffix_parts.append("checked" if mark.get("checked") else "unchecked")
    suffix = f" ({', '.join(suffix_parts)})" if suffix_parts else ""
    return f"[{mark['markId']}] {label}{suffix}"


def count_unlabeled_marks(marks: List[Dict[str, Any]]) -> int:
    count = 0
    for m in marks:
        if not (m.get("text") or m.get("contentDescription") or m.get("hintText")):
            count += 1
    return count


def compute_screen_fingerprint(foreground_pkg: Optional[str], marks: List[Dict[str, Any]]) -> str:
    parts = [foreground_pkg or ""]
    for m in marks:
        parts.append(
            f"{m.get('markId')}|{m.get('text') or ''}|{m.get('contentDescription') or ''}|"
            f"{m.get('checked')}|{m.get('editable')}"
        )
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:16]


def build_decision_questions(
    marks: List[Dict[str, Any]],
    can_type_text: bool,
    include_need_vision: bool,
) -> Dict[str, Any]:
    op_criteria: Dict[str, str] = {
        "tap_mark": "Tap one of the visible numbered UI elements to make progress toward the goal.",
        "scroll_down": "Scroll down (swipe up) because the desired item is likely further down the screen.",
        "scroll_up": "Scroll up (swipe down) because the desired item is likely higher up the screen.",
        "back": "Press Android BACK to leave a wrong screen, dialog, or submenu.",
        "wait": "The screen is loading or transitioning; wait briefly before acting.",
        "done": (
            "ALL parts of the goal and any stated completion criteria are clearly satisfied and visible "
            "on the current screen (do NOT choose done on an intermediate screen)."
        ),
        "escalate": (
            "Stop and hand off to a full LLM/human because: free-form text not in provided_text is needed, "
            "a sensitive/irreversible action (payment, sending message, deleting data, password/2FA) is next, "
            "or the required control is missing/unclear."
        ),
    }
    if can_type_text:
        op_criteria["type_text"] = (
            "Tap an editable text input field (target_mark) and type the provided_text into it."
        )

    questions: Dict[str, Any] = {
        "operation": {
            "type": "choice",
            "instructions": (
                "Choose the single best next UI operation on Android to accomplish `goal` "
                "given `foreground_package`, `screen_marks`, and `recent_actions`."
            ),
            "criteria": op_criteria,
        }
    }

    capped_marks = marks[:MAX_CHOICE_MARKS]
    if capped_marks:
        target_criteria: Dict[str, str] = {
            str(m["markId"]): format_mark_label(m) for m in capped_marks
        }
        target_criteria["none"] = "None of the listed elements should be tapped."
        questions["target_mark"] = {
            "type": "choice",
            "instructions": (
                "If tapping or typing into a UI element on this screen, which numbered mark ID "
                "from `screen_marks` is the exact target? Choose 'none' if no element matches."
            ),
            "criteria": target_criteria,
        }

    if include_need_vision:
        questions["need_vision"] = {
            "type": "noul",
            "instructions": (
                "Is a visual screenshot required to accurately pick the next element "
                "(for example because candidate buttons are unlabeled icons or require seeing images/colors)?"
            ),
            "criteria": {
                "true": "Text labels in screen_marks are insufficient; visual inspection is needed.",
                "false": "Text labels in screen_marks clearly identify the target element or action.",
            },
        }

    return questions


def parse_choice_answer(answers: Dict[str, Any], key: str) -> Tuple[str, float, float]:
    raw = answers.get(key)
    if not isinstance(raw, dict) or raw.get("type") != "choice":
        raise RunnerError("INVALID_JEV_RESPONSE", f"Missing or invalid choice answer for '{key}': {raw}")
    choice = str(raw.get("choice", ""))
    probs = raw.get("probabilities") or {}
    prob = float(probs.get(choice, 0.0)) if isinstance(probs, dict) else 0.0
    confidence = float(raw.get("confidence", prob))
    return choice, prob, confidence


def parse_noul_answer(answers: Dict[str, Any], key: str) -> float:
    raw = answers.get(key)
    if not isinstance(raw, dict) or raw.get("type") != "noul":
        return 0.0
    return float(raw.get("noul", 0.0))


def run_jev_loop(
    goal: str,
    device: AndroidUseClient,
    jev: JevClient,
    max_steps: int = 12,
    text_to_type: Optional[str] = None,
    clear_first: bool = False,
    submit_text: bool = False,
    vision_fallback: bool = True,
    min_op_confidence: float = 0.50,
    min_target_confidence: float = 0.40,
    settle_ms: int = 250,
    dry_run: bool = False,
) -> Dict[str, Any]:
    started_at = time.monotonic()
    started_own_session = False

    session_status = device.get_session()
    if not session_status.get("active"):
        if dry_run:
            raise RunnerError("SESSION_NOT_ACTIVE", "Active session required before dry-run observation.")
        device.start_session(label=f"jev:{goal[:28]}")
        started_own_session = True

    steps_log: List[Dict[str, Any]] = []
    recent_actions: List[Dict[str, Any]] = []
    total_input_tokens = 0
    total_cost_usd = 0.0
    resolved_model = jev.config.model
    typed_already = False
    last_sig: Optional[Tuple[str, str]] = None
    final_status = "max_steps"
    final_reason = f"Reached max_steps={max_steps} without completing goal."
    last_marks_summary: List[str] = []

    try:
        for step in range(1, max_steps + 1):
            info = device.get_device_info()
            if info.get("deviceLocked"):
                final_status = "escalate"
                final_reason = "device_locked"
                break

            fg_pkg = info.get("foregroundPackage")
            marks_resp = device.get_marks(refresh=True)
            marks: List[Dict[str, Any]] = marks_resp.get("marks") or []
            unlabeled = count_unlabeled_marks(marks)
            last_marks_summary = [format_mark_label(m) for m in marks[:40]]

            if not marks:
                final_status = "escalate"
                final_reason = "no_interactive_marks"
                break

            fingerprint = compute_screen_fingerprint(fg_pkg, marks)
            if recent_actions:
                prev_fp = recent_actions[-1].pop("_fingerprint", None)
                if prev_fp is not None:
                    recent_actions[-1]["screen_changed"] = prev_fp != fingerprint

            can_type_now = bool(text_to_type) and not typed_already
            state: Dict[str, Any] = {
                "goal": goal,
                "foreground_package": fg_pkg,
                "unlabeled_count": unlabeled,
                "screen_marks": [format_mark_label(m) for m in marks[:MAX_CHOICE_MARKS]],
                "recent_actions": recent_actions[-5:],
            }
            if can_type_now:
                state["provided_text"] = text_to_type

            questions = build_decision_questions(
                marks=marks,
                can_type_text=can_type_now,
                include_need_vision=vision_fallback,
            )

            t0 = time.monotonic()
            resp = jev.evaluate(state=state, questions=questions)
            decision_ms = int((time.monotonic() - t0) * 1000)

            resolved_model = str(resp.get("model") or resolved_model)
            usage = resp.get("usage") or {}
            total_input_tokens += int(usage.get("input_tokens") or 0)
            total_cost_usd += float(usage.get("cost") or 0.0)

            answers = resp.get("answers") or {}
            op, op_prob, op_conf = parse_choice_answer(answers, "operation")
            target_choice, target_prob, target_conf = (
                parse_choice_answer(answers, "target_mark")
                if "target_mark" in answers
                else ("none", 0.0, 0.0)
            )
            need_vision_prob = parse_noul_answer(answers, "need_vision") if vision_fallback else 0.0
            used_vision = False

            needs_visual_retry = vision_fallback and (
                need_vision_prob >= 0.65
                or (
                    unlabeled > 0
                    and (
                        op_conf < min_op_confidence
                        or (
                            op in ("tap_mark", "type_text")
                            and (target_choice == "none" or target_conf < min_target_confidence)
                        )
                    )
                )
            )

            if needs_visual_retry:
                try:
                    shot = device.get_annotated_screenshot_base64()
                    shot_marks = shot.get("marks") or marks
                    if shot_marks:
                        marks = shot_marks
                        last_marks_summary = [format_mark_label(m) for m in marks[:40]]
                        state["screen_marks"] = [format_mark_label(m) for m in marks[:MAX_CHOICE_MARKS]]
                        questions_v = build_decision_questions(
                            marks=marks,
                            can_type_text=can_type_now,
                            include_need_vision=False,
                        )
                    else:
                        questions_v = build_decision_questions(
                            marks=marks,
                            can_type_text=can_type_now,
                            include_need_vision=False,
                        )

                    t1 = time.monotonic()
                    resp_v = jev.evaluate(
                        state=state,
                        questions=questions_v,
                        image_base64=shot.get("dataBase64"),
                        image_format="jpeg",
                    )
                    decision_ms += int((time.monotonic() - t1) * 1000)
                    usage_v = resp_v.get("usage") or {}
                    total_input_tokens += int(usage_v.get("input_tokens") or 0)
                    total_cost_usd += float(usage_v.get("cost") or 0.0)
                    answers_v = resp_v.get("answers") or {}
                    op, op_prob, op_conf = parse_choice_answer(answers_v, "operation")
                    target_choice, target_prob, target_conf = (
                        parse_choice_answer(answers_v, "target_mark")
                        if "target_mark" in answers_v
                        else ("none", 0.0, 0.0)
                    )
                    used_vision = True
                except RunnerError as exc:
                    steps_log.append(
                        {
                            "step": step,
                            "operation": "escalate",
                            "reason": f"vision_fallback_failed:{exc.code}",
                            "decision_ms": decision_ms,
                        }
                    )
                    final_status = "escalate"
                    final_reason = f"vision_fallback_failed: {exc}"
                    break

            step_entry: Dict[str, Any] = {
                "step": step,
                "foreground_package": fg_pkg,
                "operation": op,
                "op_confidence": round(op_conf, 3),
                "op_probability": round(op_prob, 3),
                "target_mark": target_choice if op in ("tap_mark", "type_text") else None,
                "target_confidence": round(target_conf, 3) if op in ("tap_mark", "type_text") else None,
                "used_vision": used_vision,
                "decision_ms": decision_ms,
            }

            if op == "done" and op_conf >= min_op_confidence:
                steps_log.append(step_entry)
                final_status = "done"
                final_reason = "goal_completed"
                break

            if op == "escalate" or op_conf < min_op_confidence:
                steps_log.append(step_entry)
                final_status = "escalate"
                final_reason = "jev_requested_escalation" if op == "escalate" else f"low_op_confidence:{op_conf:.2f}"
                break

            if op in ("tap_mark", "type_text"):
                if target_choice == "none" or target_conf < min_target_confidence:
                    steps_log.append(step_entry)
                    final_status = "escalate"
                    final_reason = (
                        "no_matching_target_mark"
                        if target_choice == "none"
                        else f"low_target_confidence:{target_conf:.2f}"
                    )
                    break

            action_sig = f"{op}:{target_choice if op in ('tap_mark', 'type_text') else ''}"
            if last_sig == (fingerprint, action_sig):
                steps_log.append(step_entry)
                final_status = "escalate"
                final_reason = f"stuck_repeat_action:{action_sig}"
                break
            last_sig = (fingerprint, action_sig)

            if dry_run:
                step_entry["dry_run"] = True
                steps_log.append(step_entry)
                final_status = "dry_run"
                final_reason = "stopped_after_dry_run_step"
                break

            # Execute selected action on Android.
            if op == "tap_mark":
                mark_id = int(target_choice)
                act_res = device.tap_mark(mark_id)
                step_entry["resolved_point"] = act_res.get("resolvedPoint")
            elif op == "type_text":
                mark_id = int(target_choice)
                act_res = device.tap_mark(mark_id)
                step_entry["resolved_point"] = act_res.get("resolvedPoint")
                if settle_ms > 0:
                    time.sleep(min(settle_ms, 200) / 1000.0)
                device.type_text(text=text_to_type or "", clear_first=clear_first, submit=submit_text)
                typed_already = True
                step_entry["typed_text"] = text_to_type
            elif op == "scroll_down":
                device.swipe(0.5, 0.78, 0.5, 0.22, 300)
            elif op == "scroll_up":
                device.swipe(0.5, 0.25, 0.5, 0.78, 300)
            elif op == "back":
                device.press_key("BACK")
            elif op == "wait":
                device.wait_sleep(800)

            steps_log.append(step_entry)
            recent_actions.append(
                {
                    "step": step,
                    "operation": op,
                    "target": target_choice if op in ("tap_mark", "type_text") else None,
                    "_fingerprint": fingerprint,
                }
            )

            if settle_ms > 0 and op != "wait":
                time.sleep(settle_ms / 1000.0)
    finally:
        if started_own_session:
            try:
                device.end_session()
            except Exception:
                pass

    # Remove internal fingerprint scratch key before emitting JSON.
    for item in recent_actions:
        item.pop("_fingerprint", None)

    return {
        "status": final_status,
        "reason": final_reason,
        "goal": goal,
        "provider": jev.config.provider,
        "model": resolved_model,
        "step_count": len(steps_log),
        "total_elapsed_ms": int((time.monotonic() - started_at) * 1000),
        "usage": {
            "input_tokens": total_input_tokens,
            "cost_usd": round(total_cost_usd, 6),
        },
        "steps": steps_log,
        "last_screen_marks": last_marks_summary,
    }


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Optional fast Android UI subtask runner using TypeSafe Jev (direct or OpenRouter).",
    )
    parser.add_argument("goal", help="Concise UI subtask goal, e.g. 'Open Wi-Fi settings and turn Wi-Fi on'")
    parser.add_argument("--provider", default=None, choices=["auto", "typesafe", "openrouter"], help="Jev API provider")
    parser.add_argument("--model", default=None, help="Override model ID (default: jev-latest or typesafe/jev-1.14)")
    parser.add_argument("--endpoint", default=None, help="Override Jev API endpoint URL")
    parser.add_argument("--max-steps", type=int, default=12, help="Maximum UI actions before stopping (default: 12)")
    parser.add_argument("--text", default=None, help="Pre-supplied text string if a step needs typing")
    parser.add_argument("--clear-first", action="store_true", help="Clear field before typing --text")
    parser.add_argument("--submit", action="store_true", help="Submit IME Enter after typing --text")
    parser.add_argument("--no-vision", action="store_true", help="Disable Set-of-Mark screenshot fallback")
    parser.add_argument("--min-confidence", type=float, default=0.50, help="Minimum operation confidence (default: 0.50)")
    parser.add_argument(
        "--min-target-confidence", type=float, default=0.40, help="Minimum target_mark confidence (default: 0.40)"
    )
    parser.add_argument("--settle-ms", type=int, default=250, help="Post-action UI settle pause in ms (default: 250)")
    parser.add_argument("--dry-run", action="store_true", help="Run 1 decision step without executing on device")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    try:
        jev_cfg = resolve_jev_config(
            provider_arg=args.provider,
            model_arg=args.model,
            endpoint_arg=args.endpoint,
        )
        android_url = os.environ.get("ANDROID_USE_URL") or os.environ.get("URL") or "http://127.0.0.1:8765"
        android_token = os.environ.get("ANDROID_USE_TOKEN") or os.environ.get("TOKEN") or ""
        device = AndroidUseClient(base_url=android_url, token=android_token)
        jev = JevClient(config=jev_cfg)

        result = run_jev_loop(
            goal=args.goal,
            device=device,
            jev=jev,
            max_steps=max(1, args.max_steps),
            text_to_type=args.text,
            clear_first=args.clear_first,
            submit_text=args.submit,
            vision_fallback=not args.no_vision,
            min_op_confidence=args.min_confidence,
            min_target_confidence=args.min_target_confidence,
            settle_ms=max(0, args.settle_ms),
            dry_run=args.dry_run,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["status"] in ("done", "dry_run") else 10
    except RunnerError as exc:
        print(
            json.dumps({"status": "error", "code": exc.code, "message": str(exc)}, ensure_ascii=False, indent=2),
            file=sys.stderr,
        )
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
