import itertools

import pytest

from nym.generator import dictionary, pattern, random_names, sequential


def test_sequential_order() -> None:
    assert list(sequential(2, "ab")) == ["aa", "ab", "ba", "bb"]


def test_random_is_seeded() -> None:
    a = list(itertools.islice(random_names(4, "ab", seed=42), 8))
    b = list(itertools.islice(random_names(4, "ab", seed=42), 8))
    assert a == b


def test_pattern_expands_tokens() -> None:
    values = list(pattern("@#", letters="ab", digits="12"))
    assert values == ["a1", "a2", "b1", "b2"]


def test_dictionary_normalizes() -> None:
    assert list(dictionary(iter(["  Alpha  ", "x", "Beta"]))) == ["alpha", "beta"]


@pytest.mark.parametrize("length", [0, 1, 33])
def test_invalid_length(length: int) -> None:
    with pytest.raises(ValueError):
        next(sequential(length, "a"))
