#!/usr/bin/env python3
"""Optional single-shot Android UI element picker & tapper powered by TypeSafe Jev.

Strictly 1 tap per invocation (no multi-step loop) so the main agent retains full
control over workflow planning, scrolling, state verification, and recovery.

Reads live foreground app + compact interactive marks (`GET /v1/screen/marks?refresh=true`),
asks Jev to select the single matching mark (or `none`), optionally falls back to a
Set-of-Mark annotated screenshot when unlabeled icons or low confidence require vision,
executes `tap-element` (+ optional `--text` typing), and returns fresh post-action state
(`after_foreground_package`, `screen_changed`, `after_screen_marks`, and `recovery_hint`).
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
                    "or OPENROUTER_API_KEY (OpenRouter), or use standard "
                    "`android-use.sh check` / `tap-mark` / `tap-text` commands."
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
    except OSError as exc:
        raise RunnerError(
            code="NETWORK_ERROR",
            message=f"{method} {url} failed: {exc}",
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

    def start_session(self, label: str = "jev-tap") -> Dict[str, Any]:
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

    def wait_for_change(
        self,
        timeout_ms: int = 1500,
        quiet_period_ms: int = 150,
        include_marks: bool = True,
        fail_on_timeout: bool = False,
    ) -> Dict[str, Any]:
        return self.post(
            "/v1/wait",
            {
                "waitForChange": True,
                "timeoutMs": timeout_ms,
                "quietPeriodMs": quiet_period_ms,
                "includeMarks": include_marks,
                "failOnTimeout": fail_on_timeout,
            },
        )


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
    return sum(
        1 for m in marks if not (m.get("text") or m.get("contentDescription") or m.get("hintText"))
    )


def compute_screen_fingerprint(foreground_pkg: Optional[str], marks: List[Dict[str, Any]]) -> str:
    parts = [foreground_pkg or ""]
    for m in marks:
        parts.append(
            f"{m.get('markId')}|{m.get('text') or ''}|{m.get('contentDescription') or ''}|"
            f"{m.get('checked')}|{m.get('editable')}"
        )
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:16]


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


def build_single_tap_questions(
    marks: List[Dict[str, Any]],
    include_need_vision: bool,
) -> Dict[str, Any]:
    capped_marks = marks[:MAX_CHOICE_MARKS]
    target_criteria: Dict[str, str] = {
        str(m["markId"]): format_mark_label(m) for m in capped_marks
    }
    target_criteria["none"] = (
        "No visible element on the current screen matches the target description, or it violates a negative constraint."
    )
    questions: Dict[str, Any] = {
        "target_mark": {
            "type": "choice",
            "instructions": (
                "Select the SINGLE numbered UI element from `screen_marks` that best matches `target_description` "
                "(strictly obeying any 'do not tap' constraints). Choose 'none' if no element matches."
            ),
            "criteria": target_criteria,
        }
    }
    if include_need_vision:
        questions["need_vision"] = {
            "type": "noul",
            "instructions": (
                "Is a visual screenshot required to reliably identify the requested element "
                "(for example because candidate buttons are unlabeled icons or require seeing images/colors)?"
            ),
            "criteria": {
                "true": "Text labels in screen_marks are insufficient; visual inspection is needed.",
                "false": "Text labels in screen_marks clearly identify the target element or confirm its absence.",
            },
        }
    return questions


def observe_after_action(
    device: AndroidUseClient,
    before_fp: str,
    settle_ms: int,
    wait_timeout_ms: int = 1500,
    quiet_period_ms: int = 150,
) -> Tuple[Optional[str], List[Dict[str, Any]], bool, Optional[int]]:
    """Wait smartly for UI transition via server-side AccessibilityEvent `waitForChange`, falling back cleanly."""
    if wait_timeout_ms > 0:
        try:
            wait_res = device.wait_for_change(
                timeout_ms=wait_timeout_ms,
                quiet_period_ms=quiet_period_ms,
                include_marks=True,
                fail_on_timeout=False,
            )
            if isinstance(wait_res.get("marks"), list) and wait_res.get("screenChanged") is not None:
                return (
                    wait_res.get("foregroundPackage"),
                    wait_res.get("marks") or [],
                    bool(wait_res.get("screenChanged")),
                    int(wait_res.get("elapsedMs") or 0),
                )
        except RunnerError:
            # Older APK or minimal mock without waitForChange support: fall back to local settle poll.
            pass

    if settle_ms > 0:
        time.sleep(settle_ms / 1000.0)

    info = device.get_device_info()
    after_pkg = info.get("foregroundPackage")
    after_marks: List[Dict[str, Any]] = device.get_marks(refresh=True).get("marks") or []
    after_fp = compute_screen_fingerprint(after_pkg, after_marks)

    if after_fp == before_fp and settle_ms > 0:
        extra_ms = min(max(settle_ms, 300), 500)
        time.sleep(extra_ms / 1000.0)
        info = device.get_device_info()
        after_pkg = info.get("foregroundPackage")
        after_marks = device.get_marks(refresh=True).get("marks") or []
        after_fp = compute_screen_fingerprint(after_pkg, after_marks)

    return after_pkg, after_marks, (after_fp != before_fp), None


def tap_with_jev(
    target_description: str,
    device: AndroidUseClient,
    jev: JevClient,
    text_to_type: Optional[str] = None,
    clear_first: bool = False,
    submit_text: bool = False,
    vision_fallback: bool = True,
    min_target_confidence: float = 0.45,
    settle_ms: int = 250,
    wait_timeout_ms: int = 1500,
    quiet_period_ms: int = 150,
    dry_run: bool = False,
) -> Dict[str, Any]:
    """Single-shot, loop-proof element picker & tapper powered by Jev."""
    started_at = time.monotonic()
    started_own_session = False

    session_status = device.get_session()
    if not session_status.get("active"):
        if dry_run:
            raise RunnerError("SESSION_NOT_ACTIVE", "Active session required before dry-run observation.")
        device.start_session(label=f"jev-tap:{target_description[:24]}")
        started_own_session = True

    total_input_tokens = 0
    total_cost_usd = 0.0
    resolved_model = jev.config.model

    try:
        info = device.get_device_info()
        if info.get("deviceLocked"):
            return {
                "status": "escalate",
                "reason": "device_locked",
                "recovery_hint": "Device is locked; ask the user to unlock the phone.",
                "target_description": target_description,
                "provider": jev.config.provider,
                "model": resolved_model,
            }

        before_pkg = info.get("foregroundPackage")
        marks_resp = device.get_marks(refresh=True)
        marks: List[Dict[str, Any]] = marks_resp.get("marks") or []
        unlabeled = count_unlabeled_marks(marks)
        before_fp = compute_screen_fingerprint(before_pkg, marks)
        before_summary = [format_mark_label(m) for m in marks[:40]]

        if not marks:
            return {
                "status": "not_found",
                "reason": "no_interactive_marks",
                "recovery_hint": (
                    "No interactive accessibility marks found (loading, Canvas, or custom WebView). "
                    "Wait briefly and run `tree`, or fall back to `screenshot` + `tap-image`."
                ),
                "target_description": target_description,
                "before_foreground_package": before_pkg,
                "provider": jev.config.provider,
                "model": resolved_model,
                "screen_marks": [],
            }

        state: Dict[str, Any] = {
            "target_description": target_description,
            "foreground_package": before_pkg,
            "unlabeled_count": unlabeled,
            "screen_marks": [format_mark_label(m) for m in marks[:MAX_CHOICE_MARKS]],
        }
        questions = build_single_tap_questions(marks=marks, include_need_vision=vision_fallback)

        t0 = time.monotonic()
        resp = jev.evaluate(state=state, questions=questions)
        decision_ms = int((time.monotonic() - t0) * 1000)

        resolved_model = str(resp.get("model") or resolved_model)
        usage = resp.get("usage") or {}
        total_input_tokens += int(usage.get("input_tokens") or 0)
        total_cost_usd += float(usage.get("cost") or 0.0)

        answers = resp.get("answers") or {}
        target_choice, target_prob, target_conf = parse_choice_answer(answers, "target_mark")
        need_vision_prob = parse_noul_answer(answers, "need_vision") if vision_fallback else 0.0
        used_vision = False

        needs_visual_retry = vision_fallback and (
            need_vision_prob >= 0.65
            or (unlabeled > 0 and (target_choice == "none" or target_conf < min_target_confidence))
        )

        if needs_visual_retry:
            try:
                shot = device.get_annotated_screenshot_base64()
                shot_marks = shot.get("marks") or marks
                if shot_marks:
                    marks = shot_marks
                    before_fp = compute_screen_fingerprint(before_pkg, marks)
                    before_summary = [format_mark_label(m) for m in marks[:40]]
                    state["screen_marks"] = [format_mark_label(m) for m in marks[:MAX_CHOICE_MARKS]]
                questions_v = build_single_tap_questions(marks=marks, include_need_vision=False)
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
                target_choice, target_prob, target_conf = parse_choice_answer(
                    resp_v.get("answers") or {},
                    "target_mark",
                )
                used_vision = True
            except RunnerError as exc:
                return {
                    "status": "escalate",
                    "reason": f"vision_fallback_failed:{exc.code}",
                    "recovery_hint": "Use `screenshot-marked` + `tap-mark` directly.",
                    "target_description": target_description,
                    "before_foreground_package": before_pkg,
                    "provider": jev.config.provider,
                    "model": resolved_model,
                    "decision_ms": decision_ms,
                    "screen_marks": before_summary,
                }

        base_out: Dict[str, Any] = {
            "target_description": target_description,
            "before_foreground_package": before_pkg,
            "provider": jev.config.provider,
            "model": resolved_model,
            "selected_mark": target_choice,
            "probability": round(target_prob, 3),
            "confidence": round(target_conf, 3),
            "used_vision": used_vision,
            "decision_ms": decision_ms,
            "usage": {
                "input_tokens": total_input_tokens,
                "cost_usd": round(total_cost_usd, 6),
            },
        }

        if target_choice == "none" or target_conf < min_target_confidence:
            base_out["status"] = "not_found" if target_choice == "none" else "escalate"
            base_out["reason"] = (
                "no_matching_element" if target_choice == "none" else f"low_confidence:{target_conf:.2f}"
            )
            base_out["recovery_hint"] = (
                "Target not matched cleanly on current screen. Check `screen_marks`, "
                "inspect `tree`, scroll once with `swipe`, or use `screenshot-marked`."
            )
            base_out["screen_marks"] = before_summary
            base_out["total_elapsed_ms"] = int((time.monotonic() - started_at) * 1000)
            return base_out

        matched_label = next(
            (format_mark_label(m) for m in marks if str(m.get("markId")) == target_choice),
            f"[{target_choice}]",
        )
        base_out["selected_label"] = matched_label

        if dry_run:
            base_out["status"] = "dry_run"
            base_out["screen_marks"] = before_summary
            base_out["total_elapsed_ms"] = int((time.monotonic() - started_at) * 1000)
            return base_out

        mark_id = int(target_choice)
        tap_res = device.tap_mark(mark_id)
        base_out["resolved_point"] = tap_res.get("resolvedPoint")

        if text_to_type:
            if settle_ms > 0:
                time.sleep(min(settle_ms, 200) / 1000.0)
            device.type_text(text=text_to_type, clear_first=clear_first, submit=submit_text)
            base_out["typed_text"] = text_to_type

        after_pkg, after_marks, screen_changed, transition_ms = observe_after_action(
            device=device,
            before_fp=before_fp,
            settle_ms=settle_ms,
            wait_timeout_ms=wait_timeout_ms,
            quiet_period_ms=quiet_period_ms,
        )
        base_out["status"] = "tapped"
        base_out["after_foreground_package"] = after_pkg
        base_out["screen_changed"] = screen_changed
        if transition_ms is not None:
            base_out["transition_wait_ms"] = transition_ms
        base_out["after_screen_marks"] = [format_mark_label(m) for m in after_marks[:40]]
        if not screen_changed:
            base_out["recovery_hint"] = (
                "Screen marks did not visibly change after tap. If this was not a focus-only tap, "
                "run `wait-change 2500` or `tree`, dismiss any overlay/dialog, or fall back to `screenshot-marked`."
            )
        base_out["total_elapsed_ms"] = int((time.monotonic() - started_at) * 1000)
        return base_out
    finally:
        if started_own_session:
            try:
                device.end_session()
            except Exception:
                pass


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Optional single-shot Android UI element picker & tapper using TypeSafe Jev (no loop).",
    )
    parser.add_argument(
        "target",
        help="Concise description of the ONE element to tap on the current screen (include 'do not tap ...' if needed)",
    )
    parser.add_argument("--provider", default=None, choices=["auto", "typesafe", "openrouter"], help="Jev API provider")
    parser.add_argument("--model", default=None, help="Override model ID (default: jev-latest or typesafe/jev-1.14)")
    parser.add_argument("--endpoint", default=None, help="Override Jev API endpoint URL")
    parser.add_argument("--text", default=None, help="Optional text string to type after tapping an editable field")
    parser.add_argument("--clear-first", action="store_true", help="Clear field before typing --text")
    parser.add_argument("--submit", action="store_true", help="Submit IME Enter after typing --text")
    parser.add_argument("--no-vision", action="store_true", help="Disable Set-of-Mark screenshot fallback")
    parser.add_argument(
        "--min-target-confidence", type=float, default=0.45, help="Minimum target_mark confidence (default: 0.45)"
    )
    parser.add_argument("--settle-ms", type=int, default=250, help="Fallback settle pause in ms (default: 250)")
    parser.add_argument(
        "--wait-timeout-ms",
        type=int,
        default=1500,
        help="Server-side event-driven waitForChange timeout in ms (default: 1500; 0 to disable)",
    )
    parser.add_argument(
        "--quiet-ms",
        type=int,
        default=150,
        help="UI quiet settle period in ms after screen change (default: 150)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Select target element without tapping on device")
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

        result = tap_with_jev(
            target_description=args.target,
            device=device,
            jev=jev,
            text_to_type=args.text,
            clear_first=args.clear_first,
            submit_text=args.submit,
            vision_fallback=not args.no_vision,
            min_target_confidence=args.min_target_confidence,
            settle_ms=max(0, args.settle_ms),
            wait_timeout_ms=max(0, args.wait_timeout_ms),
            quiet_period_ms=max(0, args.quiet_ms),
            dry_run=args.dry_run,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["status"] in ("tapped", "dry_run") else 10
    except RunnerError as exc:
        print(
            json.dumps({"status": "error", "code": exc.code, "message": str(exc)}, ensure_ascii=False, indent=2),
            file=sys.stderr,
        )
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
