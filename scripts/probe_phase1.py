"""Read-only configuration; DH probe only, no session creation or routing changes."""
import base64
import hashlib
import os
import socket
import sys
import tomllib
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

def varint(n):
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])

def field(n, data):
    return varint(n * 8 + 2) + varint(len(data)) + data

def fields(data):
    pos = 0
    def integer():
        nonlocal pos
        value = shift = 0
        while True:
            b = data[pos]
            pos += 1
            value |= (b & 127) << shift
            if b < 128:
                return value
            shift += 7
            if shift > 63:
                raise ValueError('invalid varint')
    result = []
    while pos < len(data):
        tag = integer()
        if tag & 7 != 2:
            raise ValueError(f'field={tag >> 3} wire={tag & 7} offset={pos - 1}')
        size = integer()
        value = data[pos:pos + size]
        if len(value) != size:
            raise ValueError('truncated field')
        pos += size
        result.append((tag >> 3, value))
    return result

cfg = tomllib.load(open(sys.argv[1], 'rb'))
sign = ed25519.Ed25519PrivateKey.from_private_bytes(base64.b64decode(cfg['keys']['private_key']))
server_key = base64.b64decode(cfg['keys']['server_pub_key'])
cipher = ChaCha20Poly1305(hashlib.sha256(server_key).digest())
ephemeral = x25519.X25519PrivateKey.generate().public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
pub = sign.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
msg = field(1, field(1, ephemeral) + field(2, sign.sign(ephemeral)) + field(3, pub))
nonce = os.urandom(12)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(5)
    sock.connect(('144.31.85.160', 443))
    sock.send(nonce + cipher.encrypt(nonce, msg, None))
    response = sock.recv(65535)
    plain = cipher.decrypt(response[:12], response[12:], None)
    print('Authenticated decryption OK; plaintext bytes:', len(plain))
    try:
        parsed = fields(plain)
        print('Outer fields:', [(n, len(v)) for n, v in parsed])
        for n, value in parsed:
            if n == 2:
                dh = dict(fields(value))
                ed25519.Ed25519PublicKey.from_public_bytes(server_key).verify(dh[2], dh[1])
                print('Server DH signature verified')
    except ValueError as err:
        print('Protobuf error:', err)
        print('Plaintext first 16 bytes (no private keys):', plain[:16].hex())
