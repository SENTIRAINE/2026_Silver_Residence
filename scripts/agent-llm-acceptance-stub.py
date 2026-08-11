from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class AcceptanceHandler(BaseHTTPRequestHandler):
    server_version = "AgentAcceptanceStub/1.0"

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/chat/completions":
            self._send(404, {"error": "not_found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            schema_name = request["response_format"]["json_schema"]["name"]
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            self._send(400, {"error": "invalid_request"})
            return

        if schema_name == "grounded_answer":
            result = {
                "supported": True,
                "answer": "Map results are ready.",
                "citationOrdinals": [],
            }
        elif schema_name == "housing_search_plan":
            try:
                planner_input = json.loads(request["messages"][1]["content"])
                query = planner_input["query"]
            except (KeyError, TypeError, ValueError, json.JSONDecodeError):
                self._send(400, {"error": "invalid_planner_input"})
                return
            result = self._housing_plan(query)
            if result is None:
                self._send(422, {"error": "unexpected_planner_query"})
                return
        else:
            self._send(422, {"error": "unexpected_schema", "schema": schema_name})
            return

        content = json.dumps(result, ensure_ascii=True, separators=(",", ":"))
        self._send(
            200,
            {
                "id": "agent-acceptance-stub",
                "object": "chat.completion",
                "model": request.get("model", "acceptance-stub"),
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": content},
                        "finish_reason": "stop",
                    }
                ],
            },
        )

    def log_message(self, format: str, *args: object) -> None:
        return

    @staticmethod
    def _housing_plan(query: str) -> dict[str, object] | None:
        base: dict[str, object] = {
            "mode": "RANK",
            "districts": [],
            "hardFilters": {"priceMin": None, "priceMax": None},
            "preferences": {
                "price": {"enabled": False, "level": "PREFER_LOW", "weight": 0},
                "convenience": {
                    "enabled": False,
                    "level": "PREFER_HIGH",
                    "weight": 0,
                },
                "roadWalkability": {
                    "enabled": False,
                    "level": "PREFER_HIGH",
                    "weight": 0,
                },
            },
            "roadCriteria": {"wsMin": None, "gviMin": None, "noiMax": None},
            "spatial": {"relation": "WITHIN_ROAD_BUFFER", "bufferMeters": None},
            "display": {"includeRoads": False, "includeBuffers": False},
            "limit": 20,
        }
        preferences = base["preferences"]
        assert isinstance(preferences, dict)

        if "300" in query and "75" in query:
            base["mode"] = "BUFFER_FILTER"
            preferences["roadWalkability"] = {
                "enabled": True,
                "level": "HIGH",
                "weight": 1,
            }
            base["roadCriteria"] = {"wsMin": 75, "gviMin": None, "noiMax": None}
            base["spatial"] = {"relation": "WITHIN_ROAD_BUFFER", "bufferMeters": 300}
            base["display"] = {"includeRoads": True, "includeBuffers": True}
            return base

        if "100%" in query:
            base["mode"] = "BUFFER_FILTER"
            preferences["convenience"] = {
                "enabled": True,
                "level": "PREFER_HIGH",
                "weight": 1,
            }
            return base

        if "\u516b\u6210" in query and "\u4e24\u6210" in query:
            base["districts"] = ["\u4e2d\u5c71\u533a"]
            preferences["convenience"] = {
                "enabled": True,
                "level": "PREFER_HIGH",
                "weight": 0.8,
            }
            preferences["roadWalkability"] = {
                "enabled": True,
                "level": "PREFER_HIGH",
                "weight": 0.2,
            }
            base["display"] = {"includeRoads": True, "includeBuffers": True}
            return base

        return None

    def _send(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18088)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), AcceptanceHandler).serve_forever()


if __name__ == "__main__":
    main()
