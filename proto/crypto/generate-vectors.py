#!/usr/bin/env python3
"""Regenerate public TEST-ONLY vectors with an explicitly supplied libsodium dylib.

Usage: python3 proto/crypto/generate-vectors.py /path/to/libsodium.dylib
The library must support the standard libsodium C ABI. No random inputs are used.
"""
import ctypes
import hashlib
import json
import pathlib
import sys

sodium = ctypes.CDLL(sys.argv[1])
assert sodium.sodium_init() >= 0


def output(size):
    return ctypes.create_string_buffer(size)


def box(seed):
    pk, sk = output(32), output(32)
    assert sodium.crypto_box_seed_keypair(pk, sk, seed) == 0
    return pk.raw, sk.raw


def signing(seed):
    pk, sk = output(32), output(64)
    assert sodium.crypto_sign_seed_keypair(pk, sk, seed) == 0
    return pk.raw, sk.raw


def sign(message, secret):
    sig = output(64)
    assert sodium.crypto_sign_detached(sig, None, message, ctypes.c_ulonglong(len(message)), secret) == 0
    return sig.raw


a_box, a_secret = box(bytes(range(32)))
b_box, b_secret = box(bytes(range(32, 64)))
a_sign, a_sign_secret = signing(bytes(range(64, 96)))
b_sign, b_sign_secret = signing(bytes(range(96, 128)))
prefix = bytes(range(16))
nonce = prefix + (1).to_bytes(8, 'big')
plain = 'Twinotify test vector: hello 👋'.encode()
cipher = output(len(plain) + 16)
assert sodium.crypto_box_easy(cipher, plain, ctypes.c_ulonglong(len(plain)), nonce, b_box, a_secret) == 0
token = 'test-pair-token-0123456789'
a_message = token.encode() + a_box + a_sign + b_box + b_sign
a_sig = sign(a_message, a_sign_secret)
b_message = b'twinotify-pair-confirm-b-v1\n' + a_message + a_sig
notify = ('twinotify-pair-notify-v1\n' + token + '\nB\ndev-b').encode()
hex_digest = hashlib.sha256(a_box + a_sign).hexdigest().upper()
vector = dict(
    description='PUBLIC TEST KEYS ONLY. Generated using the libsodium C ABI; never install these identities.',
    a_box_public=a_box.hex(), a_box_secret=a_secret.hex(),
    b_box_public=b_box.hex(), b_box_secret=b_secret.hex(),
    a_sign_public=a_sign.hex(), a_sign_secret=a_sign_secret.hex(),
    b_sign_public=b_sign.hex(), b_sign_secret=b_sign_secret.hex(),
    nonce_prefix=prefix.hex(), nonce_counter='1', nonce=nonce.hex(),
    plaintext=plain.hex(), ciphertext=cipher.raw.hex(),
    message_signature=sign(plain, a_sign_secret).hex(), token=token,
    initiator_message=a_message.hex(), initiator_signature=a_sig.hex(),
    responder_message=b_message.hex(), responder_signature=sign(b_message, b_sign_secret).hex(),
    notify_message=notify.hex(), notify_signature=sign(notify, b_sign_secret).hex(),
    fingerprint='-'.join(hex_digest[i:i + 4] for i in range(0, 64, 4)),
)
pathlib.Path(__file__).with_name('known-answer-v1.json').write_text(json.dumps(vector, indent=2) + '\n')
