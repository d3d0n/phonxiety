from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from phonxiety.settings import TOKEN_KEYS, _tokens


class TokensTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.state = Path(self._dir.name)

    def tearDown(self) -> None:
        self._dir.cleanup()

    def _write(self, data: object) -> Path:
        path = self.state / "tokens.json"
        path.write_text(json.dumps(data))
        return path

    def test_a_missing_file_generates_every_key(self) -> None:
        data = _tokens(self.state)
        self.assertEqual(set(data), set(TOKEN_KEYS))
        self.assertTrue(all(data[key] for key in TOKEN_KEYS))
        self.assertEqual(json.loads((self.state / "tokens.json").read_text()), data)

    def test_a_file_without_an_app_token_is_invalid(self) -> None:
        self._write({"host_token": "h", "device_token": "d"})
        with self.assertRaisesRegex(ValueError, "Invalid connection credential file"):
            _tokens(self.state)

    def test_a_file_with_an_empty_app_token_is_invalid(self) -> None:
        self._write({"host_token": "h", "device_token": "d", "app_token": ""})
        with self.assertRaisesRegex(ValueError, "Invalid connection credential file"):
            _tokens(self.state)

    def test_a_complete_file_is_returned_unchanged(self) -> None:
        expected = {"host_token": "h", "device_token": "d", "app_token": "a"}
        self._write(expected)
        self.assertEqual(_tokens(self.state), expected)


if __name__ == "__main__":
    unittest.main()
