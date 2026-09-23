"""Public, test-only key material. Never used by Android or exported packages."""
import base64
import hashlib
from pathlib import Path
import ssl

FIXTURES = Path(__file__).parent / "fixtures" / "tls"
CERT_DER = ssl.PEM_cert_to_DER_cert((FIXTURES / "server.pem").read_text())
PUBLIC_DER = base64.b64decode("".join((FIXTURES / "server-public.pem").read_text().splitlines()[1:-1]))
PIN = hashlib.sha256(PUBLIC_DER).hexdigest()


def wrap_server(server):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(FIXTURES / "server.pem", FIXTURES / "server-key.pem")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    return server
