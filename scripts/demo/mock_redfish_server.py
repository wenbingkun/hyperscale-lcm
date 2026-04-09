#!/usr/bin/env python3
import argparse
import base64
import json
import ssl
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def parse_args():
    parser = argparse.ArgumentParser(description="Mock Redfish server for Hyperscale LCM demo")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--bootstrap-user", default="admin")
    parser.add_argument("--bootstrap-password", default="admin123")
    return parser.parse_args()


def make_handler(state, bootstrap_user, bootstrap_password):
    expected_auth = "Basic " + base64.b64encode(
        f"{bootstrap_user}:{bootstrap_password}".encode("utf-8")
    ).decode("ascii")

    class RedfishHandler(BaseHTTPRequestHandler):
        server_version = "HyperscaleLCMDemo/1.0"

        def do_GET(self):
            if not self._authorized():
                return

            if self.path == "/redfish/v1/Systems":
                return self._json(
                    {
                        "Members": [
                            {"@odata.id": "/redfish/v1/Systems/System.Embedded.1"}
                        ]
                    }
                )
            if self.path == "/redfish/v1/Systems/System.Embedded.1":
                return self._json(
                    {
                        "Id": "System.Embedded.1",
                        "Manufacturer": "OpenBMC",
                        "Model": "DemoBMC-9000",
                    }
                )
            if self.path == "/redfish/v1/AccountService":
                return self._json(
                    {
                        "Accounts": {
                            "@odata.id": "/redfish/v1/AccountService/Accounts"
                        }
                    }
                )
            if self.path == "/redfish/v1/AccountService/Accounts":
                members = [
                    {
                        "@odata.id": f"/redfish/v1/AccountService/Accounts/{account['Id']}"
                    }
                    for account in state["accounts"]
                ]
                return self._json({"Members": members})
            if self.path.startswith("/redfish/v1/AccountService/Accounts/"):
                account_id = self.path.rsplit("/", 1)[-1]
                account = next(
                    (
                        item
                        for item in state["accounts"]
                        if item["Id"] == account_id
                    ),
                    None,
                )
                if account is None:
                    return self._json(
                        {"error": "account not found"}, status=HTTPStatus.NOT_FOUND
                    )
                return self._json(account)

            return self._json({"error": "not found"}, status=HTTPStatus.NOT_FOUND)

        def do_POST(self):
            if not self._authorized():
                return
            if self.path != "/redfish/v1/AccountService/Accounts":
                return self._json({"error": "not found"}, status=HTTPStatus.NOT_FOUND)

            payload = self._read_json()
            account = {
                "Id": str(state["next_account_id"]),
                "UserName": payload.get("UserName"),
                "Password": payload.get("Password"),
                "RoleId": payload.get("RoleId", "Administrator"),
                "Enabled": payload.get("Enabled", True),
            }
            state["next_account_id"] += 1
            state["accounts"].append(account)
            return self._json(account, status=HTTPStatus.CREATED)

        def do_PATCH(self):
            if not self._authorized():
                return
            if not self.path.startswith("/redfish/v1/AccountService/Accounts/"):
                return self._json({"error": "not found"}, status=HTTPStatus.NOT_FOUND)

            account_id = self.path.rsplit("/", 1)[-1]
            account = next(
                (
                    item
                    for item in state["accounts"]
                    if item["Id"] == account_id
                ),
                None,
            )
            if account is None:
                return self._json({"error": "account not found"}, status=HTTPStatus.NOT_FOUND)

            payload = self._read_json()
            account.update(payload)
            return self._json(account, status=HTTPStatus.OK)

        def log_message(self, format, *args):
            print(format % args)

        def _authorized(self):
            if self.headers.get("Authorization") == expected_auth:
                return True

            self.send_response(HTTPStatus.UNAUTHORIZED)
            self.send_header("WWW-Authenticate", 'Basic realm="redfish-demo"')
            self.end_headers()
            return False

        def _read_json(self):
            content_length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(content_length) if content_length else b"{}"
            return json.loads(raw.decode("utf-8"))

        def _json(self, payload, status=HTTPStatus.OK):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return RedfishHandler


def main():
    args = parse_args()
    state = {
        "accounts": [
            {
                "Id": "1",
                "UserName": args.bootstrap_user,
                "Password": args.bootstrap_password,
                "RoleId": "Administrator",
                "Enabled": True,
            }
        ],
        "next_account_id": 2,
    }

    server = ThreadingHTTPServer((args.bind, args.port), make_handler(state, args.bootstrap_user, args.bootstrap_password))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=args.cert, keyfile=args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)

    print(f"mock redfish server listening on https://{args.bind}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
