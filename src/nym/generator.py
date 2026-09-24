from __future__ import annotations

import itertools
import random
import string
from collections.abc import Iterable, Iterator

DEFAULT_CHARSET = string.ascii_lowercase + string.digits + "_."


def validate_length(length: int) -> None:
    if not 2 <= length <= 32:
        raise ValueError("username length must be between 2 and 32")


def sequential(length: int, charset: str = DEFAULT_CHARSET) -> Iterator[str]:
    validate_length(length)
    if not charset:
        raise ValueError("charset must not be empty")
    for chars in itertools.product(charset, repeat=length):
        yield "".join(chars)


def random_names(
    length: int,
    charset: str = DEFAULT_CHARSET,
    *,
    seed: int | None = None,
) -> Iterator[str]:
    validate_length(length)
    if not charset:
        raise ValueError("charset must not be empty")
    rng = random.Random(seed)
    while True:
        yield "".join(rng.choice(charset) for _ in range(length))


def pattern(
    template: str,
    *,
    letters: str = string.ascii_lowercase,
    digits: str = string.digits,
    custom: str = DEFAULT_CHARSET,
) -> Iterator[str]:
    if not 2 <= len(template) <= 32:
        raise ValueError("pattern length must be between 2 and 32")

    alphabets: list[str] = []
    for token in template:
        if token == "@":
            alphabet = letters
        elif token == "#":
            alphabet = digits
        elif token == "*":
            alphabet = custom
        else:
            alphabet = token

        if not alphabet:
            raise ValueError("pattern alphabet must not be empty")
        alphabets.append(alphabet)

    for chars in itertools.product(*alphabets):
        yield "".join(chars)


def dictionary(
    words: Iterable[str],
    *,
    lowercase: bool = True,
    strip: bool = True,
) -> Iterator[str]:
    for raw in words:
        value = raw.strip() if strip else raw
        value = value.lower() if lowercase else value
        if 2 <= len(value) <= 32:
            yield value
