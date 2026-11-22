import importlib.util
import json
import pathlib
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any, Dict, List


RUNNER_PATH = (
    pathlib.Path(__file__).resolve().parent.parent
    / "skills"
    / "android-use"
    / "scripts"
    / "jev-runner.py"
)
spec = importlib.util.spec_from_file_location("jev_runner", RUNNER_PATH)
assert spec and spec.loader
jev_runner = importlib.util.module_from_spec(spec)
sys.modules["jev_runner"] = jev_runner
spec.loader.exec_module(jev_runner)


class MockServer:
    """Reusable local HTTP server recording requests and returning queued/scripted JSON responses."""

    def __init__(self, handler_fn):
        self.requests: List[Dict[str, Any]] = []
        outer = self

        class _Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):  # noqa: A002
                pass

            def _handle(self, method: str):
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length).decode("utf-8") if length > 0 else ""
                body = json.loads(raw) if raw else None
                rec = {
                    "method": method,
                    "path": self.path,
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": body,
                }
                outer.requests.append(rec)
                status, resp_body = handler_fn(rec)
                encoded = json.dumps(resp_body).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

        self._httpd = HTTPServer(("127.0.0.1", 0), _Handler)
        self.url = f"http://127.0.0.1:{self._httpd.server_port}"
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, exc_type, exc, tb):
        self._httpd.shutdown()
        self._httpd.server_close()


class TestJevConfig(unittest.TestCase):
    def test_auto_prefers_typesafe_then_openrouter(self):
        cfg = jev_runner.resolve_jev_config(
            env={"TYPESAFE_API_KEY": "ts-key", "OPENROUTER_API_KEY": "or-key"}
        )
        self.assertEqual(cfg.provider, "typesafe")
        self.assertEqual(cfg.endpoint, jev_runner.TYPESAFE_ENDPOINT)
        self.assertEqual(cfg.model, jev_runner.DEFAULT_TYPESAFE_MODEL)

        cfg_or = jev_runner.resolve_jev_config(env={"OPENROUTER_API_KEY": "or-key"})
        self.assertEqual(cfg_or.provider, "openrouter")
        self.assertEqual(cfg_or.endpoint, jev_runner.OPENROUTER_ENDPOINT)
        self.assertEqual(cfg_or.model, jev_runner.DEFAULT_OPENROUTER_MODEL)

    def test_missing_keys_raises_not_configured(self):
        with self.assertRaises(jev_runner.RunnerError) as ctx:
            jev_runner.resolve_jev_config(env={})
        self.assertEqual(ctx.exception.code, "JEV_NOT_CONFIGURED")
        self.assertEqual(ctx.exception.exit_code, 2)


class TestJevRunnerLoop(unittest.TestCase):
    def test_text_first_tap_and_type_loop_takes_zero_screenshots(self):
        screen_stage = {"step": 0}

        def android_handler(req):
            path = req["path"]
            if path == "/v1/session":
                return 200, {"active": True}
            if path == "/v1/device/info":
                return 200, {"foregroundPackage": "com.example.search", "deviceLocked": False}
            if path.startswith("/v1/screen/marks"):
                if screen_stage["step"] == 0:
                    return 200, {
                        "count": 2,
                        "displayWidth": 1080,
                        "displayHeight": 2400,
                        "marks": [
                            {"markId": 1, "nodeId": "n1", "text": "Search", "editable": True, "center": {"x": 500, "y": 150}},
                            {"markId": 2, "nodeId": "n2", "text": "Settings", "clickable": True, "center": {"x": 950, "y": 150}},
                        ],
                    }
                return 200, {
                    "count": 1,
                    "displayWidth": 1080,
                    "displayHeight": 2400,
                    "marks": [
                        {"markId": 1, "nodeId": "n1", "text": "Tokyo weather: 22C", "clickable": True, "center": {"x": 500, "y": 400}},
                    ],
                }
            if path == "/v1/actions/tap-element":
                return 200, {"ok": True, "resolvedPoint": {"x": 500, "y": 150}}
            if path == "/v1/actions/type":
                screen_stage["step"] = 1
                return 200, {"ok": True, "submitted": True}
            raise AssertionError(f"Unexpected Android path: {path}")

        jev_calls = {"n": 0}

        def jev_handler(req):
            jev_calls["n"] += 1
            if jev_calls["n"] == 1:
                self.assertIn("type_text", req["body"]["questions"]["operation"]["criteria"])
                return 200, {
                    "model": "jev-1.14.0",
                    "answers": {
                        "operation": {"type": "choice", "choice": "type_text", "probabilities": {"type_text": 0.94}, "confidence": 0.92},
                        "target_mark": {"type": "choice", "choice": "1", "probabilities": {"1": 0.96}, "confidence": 0.95},
                        "need_vision": {"type": "noul", "noul": 0.05},
                    },
                    "usage": {"input_tokens": 210, "output_tokens": 0, "cost": 0.000009},
                }
            return 200, {
                "model": "jev-1.14.0",
                "answers": {
                    "operation": {"type": "choice", "choice": "done", "probabilities": {"done": 0.98}, "confidence": 0.97},
                    "target_mark": {"type": "choice", "choice": "none", "probabilities": {"none": 0.95}, "confidence": 0.94},
                    "need_vision": {"type": "noul", "noul": 0.01},
                },
                "usage": {"input_tokens": 180, "output_tokens": 0, "cost": 0.000008},
            }

        with MockServer(android_handler) as android_srv, MockServer(jev_handler) as jev_srv:
            device = jev_runner.AndroidUseClient(android_srv.url, token="tok")
            jev = jev_runner.JevClient(
                jev_runner.JevConfig(
                    provider="typesafe",
                    api_key="ts-secret",
                    endpoint=f"{jev_srv.url}/v1/systemone",
                    model="jev-latest",
                )
            )
            result = jev_runner.run_jev_loop(
                goal="Search Tokyo weather",
                device=device,
                jev=jev,
                text_to_type="Tokyo weather",
                submit_text=True,
                settle_ms=0,
            )

            self.assertEqual(result["status"], "done")
            self.assertEqual(result["step_count"], 2)
            self.assertEqual(result["usage"]["input_tokens"], 390)
            screenshot_calls = [r for r in android_srv.requests if "/screenshot" in r["path"]]
            self.assertEqual(len(screenshot_calls), 0)

    def test_vision_fallback_triggers_on_need_vision_for_typesafe_and_openrouter(self):
        for provider in ("typesafe", "openrouter"):
            with self.subTest(provider=provider):
                stage = {"done": False}

                def android_handler(req):
                    path = req["path"]
                    if path == "/v1/session":
                        return 200, {"active": True}
                    if path == "/v1/device/info":
                        return 200, {"foregroundPackage": "com.example.camera", "deviceLocked": False}
                    if path.startswith("/v1/screen/marks"):
                        if not stage["done"]:
                            return 200, {
                                "count": 2,
                                "displayWidth": 1080,
                                "displayHeight": 2400,
                                "marks": [
                                    {"markId": 1, "nodeId": "n1", "className": "android.widget.ImageButton", "clickable": True, "center": {"x": 100, "y": 100}},
                                    {"markId": 2, "nodeId": "n2", "className": "android.widget.ImageButton", "clickable": True, "center": {"x": 980, "y": 100}},
                                ],
                            }
                        return 200, {
                            "count": 1,
                            "displayWidth": 1080,
                            "displayHeight": 2400,
                            "marks": [
                                {"markId": 1, "nodeId": "n1", "text": "Flash ON", "clickable": True, "center": {"x": 500, "y": 100}},
                            ],
                        }
                    if path.startswith("/v1/screen/screenshot"):
                        return 200, {
                            "dataBase64": "ZmFrZWpwZWc=",
                            "marks": [
                                {"markId": 1, "nodeId": "n1", "className": "android.widget.ImageButton", "clickable": True, "center": {"x": 100, "y": 100}},
                                {"markId": 2, "nodeId": "n2", "className": "android.widget.ImageButton", "clickable": True, "center": {"x": 980, "y": 100}},
                            ],
                        }
                    if path == "/v1/actions/tap-element":
                        stage["done"] = True
                        return 200, {"ok": True, "resolvedPoint": {"x": 980, "y": 100}}
                    raise AssertionError(f"Unexpected path: {path}")

                calls = {"n": 0}

                def jev_handler(req):
                    calls["n"] += 1
                    if calls["n"] == 1:
                        # Text-only pass requests vision fallback.
                        return 200, {
                            "model": "jev-1.14.0",
                            "answers": {
                                "operation": {"type": "choice", "choice": "tap_mark", "probabilities": {"tap_mark": 0.55}, "confidence": 0.45},
                                "target_mark": {"type": "choice", "choice": "1", "probabilities": {"1": 0.50}, "confidence": 0.30},
                                "need_vision": {"type": "noul", "noul": 0.88},
                            },
                            "usage": {"input_tokens": 120},
                        }
                    if calls["n"] == 2:
                        # Verify multimodal payload shape per provider.
                        if provider == "typesafe":
                            self.assertEqual(req["headers"].get("typesafe-danger-zone-images"), "true")
                            self.assertTrue(req["body"].get("assets"))
                        else:
                            self.assertIsInstance(req["body"]["state"], list)
                            self.assertEqual(req["body"]["state"][1]["type"], "image_url")
                        return 200, {
                            "model": "jev-1.14.0",
                            "answers": {
                                "operation": {"type": "choice", "choice": "tap_mark", "probabilities": {"tap_mark": 0.95}, "confidence": 0.94},
                                "target_mark": {"type": "choice", "choice": "2", "probabilities": {"2": 0.93}, "confidence": 0.91},
                            },
                            "usage": {"input_tokens": 500},
                        }
                    return 200, {
                        "model": "jev-1.14.0",
                        "answers": {
                            "operation": {"type": "choice", "choice": "done", "probabilities": {"done": 0.99}, "confidence": 0.99},
                            "target_mark": {"type": "choice", "choice": "none", "probabilities": {"none": 0.99}, "confidence": 0.99},
                            "need_vision": {"type": "noul", "noul": 0.02},
                        },
                        "usage": {"input_tokens": 110},
                    }

                with MockServer(android_handler) as android_srv, MockServer(jev_handler) as jev_srv:
                    device = jev_runner.AndroidUseClient(android_srv.url)
                    jev = jev_runner.JevClient(
                        jev_runner.JevConfig(
                            provider=provider,
                            api_key="test-key",
                            endpoint=f"{jev_srv.url}/decide",
                            model="jev-latest" if provider == "typesafe" else "typesafe/jev-1.14",
                        )
                    )
                    res = jev_runner.run_jev_loop(
                        goal="Turn flash on",
                        device=device,
                        jev=jev,
                        settle_ms=0,
                    )
                    self.assertEqual(res["status"], "done")
                    self.assertTrue(res["steps"][0]["used_vision"])
                    self.assertEqual(res["steps"][0]["target_mark"], "2")

    def test_stuck_repeated_action_escalates_cleanly(self):
        def android_handler(req):
            path = req["path"]
            if path == "/v1/session":
                return 200, {"active": True}
            if path == "/v1/device/info":
                return 200, {"foregroundPackage": "com.example.app", "deviceLocked": False}
            if path.startswith("/v1/screen/marks"):
                return 200, {
                    "count": 1,
                    "displayWidth": 1080,
                    "displayHeight": 2400,
                    "marks": [
                        {"markId": 1, "nodeId": "n1", "text": "Retry", "clickable": True, "center": {"x": 540, "y": 1200}},
                    ],
                }
            if path == "/v1/actions/tap-element":
                return 200, {"ok": True, "resolvedPoint": {"x": 540, "y": 1200}}
            raise AssertionError(f"Unexpected path: {path}")

        def jev_handler(req):
            return 200, {
                "model": "jev-1.14.0",
                "answers": {
                    "operation": {"type": "choice", "choice": "tap_mark", "probabilities": {"tap_mark": 0.9}, "confidence": 0.9},
                    "target_mark": {"type": "choice", "choice": "1", "probabilities": {"1": 0.9}, "confidence": 0.9},
                    "need_vision": {"type": "noul", "noul": 0.05},
                },
                "usage": {"input_tokens": 100},
            }

        with MockServer(android_handler) as android_srv, MockServer(jev_handler) as jev_srv:
            device = jev_runner.AndroidUseClient(android_srv.url)
            jev = jev_runner.JevClient(
                jev_runner.JevConfig(
                    provider="typesafe",
                    api_key="test-key",
                    endpoint=f"{jev_srv.url}/v1/systemone",
                    model="jev-latest",
                )
            )
            res = jev_runner.run_jev_loop(
                goal="Tap Retry until loaded",
                device=device,
                jev=jev,
                max_steps=5,
                settle_ms=0,
            )
            self.assertEqual(res["status"], "escalate")
            self.assertEqual(res["reason"], "stuck_repeat_action:tap_mark:1")
            self.assertEqual(res["step_count"], 2)


if __name__ == "__main__":
    unittest.main()
