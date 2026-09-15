"""Run with python -m unittest discover -s tools -p 'test_*.py'."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

import phoneuse_client as client


class PairingClientTest(unittest.TestCase):
    def test_202_pairing_body_is_preserved_and_empty_mcp_notification_is_none(self):
        connection = MagicMock()
        response = connection.getresponse.return_value
        response.status = 202
        response.read.return_value = b'{"status":"pending"}'
        with patch.object(client.http.client, 'HTTPConnection', return_value=connection):
            config = {'url': 'http://192.168.1.20:8443'}
            self.assertEqual({'status': 'pending'}, client.post(config, '/pair/request', {}, False))
            response.read.return_value = b''
            self.assertIsNone(client.post(config, '/mcp', {}, False))

    def run_pair(self, directory, responses):
        config = Path(directory) / 'phoneuse.json'
        args = ['phoneuse_client.py', '--config', str(config), 'pair', '--url',
                'http://192.168.1.20:8443', '--name', 'laptop']
        with patch('sys.argv', args), patch.object(client, 'post', side_effect=responses) as post, \
                patch.object(client.time, 'sleep'), patch('builtins.print'):
            client.main()
        return config, post

    def pending(self):
        return {'pairing_id': 'id', 'pairing_secret': 'secret', 'verification_code': '123456',
                'expires_in': 120, 'poll_interval': 2}

    def test_waits_for_phone_and_saves_shared_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            path, post = self.run_pair(directory, [self.pending(), {'status': 'pending'},
                {'status': 'approved', 'token': 'test-token', 'client_id': 'client'}])
            self.assertEqual('test-token', json.loads(path.read_text())['token'])
            self.assertEqual('/pair/request', post.call_args_list[0].args[1])
            self.assertEqual({'pairing_id': 'id', 'pairing_secret': 'secret'}, post.call_args.args[2])

    def test_denial_does_not_write_config(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'denied'):
                self.run_pair(directory, [self.pending(), {'status': 'denied'}])
            self.assertFalse((Path(directory) / 'phoneuse.json').exists())

    def test_expiry_does_not_write_config(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(client.time, 'monotonic', side_effect=[0, 121]):
                with self.assertRaisesRegex(RuntimeError, 'expired'):
                    self.run_pair(directory, [self.pending()])
            self.assertFalse((Path(directory) / 'phoneuse.json').exists())


if __name__ == '__main__':
    unittest.main()
