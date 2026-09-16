"""Run with python -m unittest discover -s tools -p 'test_*.py'."""
import json
import io
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
            saved = json.loads((Path(directory) / 'phoneuse.json').read_text())
            self.assertNotIn('token', saved)
            self.assertNotIn('pending', saved)

    def test_expiry_does_not_write_config(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(client.time, 'time', side_effect=[0, 121]):
                with self.assertRaisesRegex(RuntimeError, 'expired'):
                    self.run_pair(directory, [self.pending()])
            saved = json.loads((Path(directory) / 'phoneuse.json').read_text())
            self.assertNotIn('token', saved)
            self.assertNotIn('pending', saved)

    def test_pair_reuses_saved_credential_after_process_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path, _ = self.run_pair(directory, [self.pending(),
                {'status': 'approved', 'token': 'test-token', 'client_id': 'client'}])
            before = path.read_text()
            _, post = self.run_pair(directory, [{}])
            self.assertEqual(before, path.read_text())
            self.assertEqual('/api', post.call_args.args[1])
            self.assertEqual('test-token', post.call_args.args[0]['token'])
            self.assertEqual(1, post.call_count)

    def test_pending_survives_bridge_restart_without_duplicate_request(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            with patch.object(client, 'post', side_effect=[self.pending(),
                    {'status': 'approved', 'token': 'hidden-token', 'client_id': 'client'}]) as post:
                pending = client.Connection(path, 'http://127.0.0.1:8443').connect()
                paired = client.Connection(path).connect()
            self.assertEqual('pending', pending['status'])
            self.assertEqual('paired', paired['status'])
            self.assertNotIn('secret', json.dumps(pending))
            self.assertNotIn('hidden-token', json.dumps(paired))
            self.assertEqual(['/pair/request', '/pair/status'], [c.args[1] for c in post.call_args_list])

    def test_disconnect_keeps_pairing_and_never_replays_action(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            path.write_text(json.dumps({'url': 'http://127.0.0.1:8443', 'token': 'hidden-token'}))
            connection = client.Connection(path)
            before = path.read_text()
            with patch.object(client, 'post', side_effect=ConnectionRefusedError('offline')) as post:
                with self.assertRaises(ConnectionRefusedError):
                    connection.send('/api', {'tool': 'home', 'arguments': {'request_id': 'one'}})
                self.assertEqual(1, post.call_count)
            self.assertEqual(before, path.read_text())

    def test_revocation_clears_credential_but_does_not_request_pairing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            path.write_text(json.dumps({'url': 'http://127.0.0.1:8443', 'token': 'hidden-token'}))
            with patch.object(client, 'post', side_effect=client.HttpError(401, {'error': 'revoked'})) as post:
                with self.assertRaisesRegex(RuntimeError, 'revoked'):
                    client.Connection(path).connect()
                self.assertEqual(1, post.call_count)
            self.assertNotIn('token', json.loads(path.read_text()))

    def test_url_mismatch_never_sends_saved_token(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            path.write_text(json.dumps({'url': 'http://127.0.0.1:8443', 'token': 'hidden-token'}))
            with patch.object(client, 'post') as post, self.assertRaisesRegex(ValueError, 'another URL'):
                client.Connection(path, 'http://192.168.1.2:8443')
            post.assert_not_called()

    def test_mcp_pairs_and_calls_without_exposing_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            requests = [
                {'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-11-25'}},
                {'method': 'notifications/initialized'},
                {'id': 2, 'method': 'tools/list'},
                {'id': 3, 'method': 'tools/call', 'params': {'name': 'phoneuse_connect'}},
                {'id': 4, 'method': 'tools/call', 'params': {'name': 'phoneuse_connect'}},
                {'id': 5, 'method': 'tools/list'},
                {'id': 6, 'method': 'tools/call', 'params': {'name': 'home', 'arguments': {'request_id': 'one'}}}]
            output, errors = io.StringIO(), io.StringIO()
            incoming = io.StringIO('\n'.join(json.dumps({'jsonrpc': '2.0', **r}) for r in requests))
            responses = [self.pending(), {'status': 'approved', 'token': 'hidden-token', 'client_id': 'client'},
                         {'result': {'tools': [{'name': 'home'}]}},
                         {'jsonrpc': '2.0', 'id': 6, 'result': client.tool_result({'state': 'executed'})}]
            with patch('sys.stdin', incoming), patch('sys.stdout', output), patch('sys.stderr', errors), \
                    patch.object(client, 'post', side_effect=responses) as post:
                client.bridge(client.Connection(path, 'http://127.0.0.1:8443'))
            messages = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(7, len(messages))  # Six responses plus tools/list_changed.
            self.assertTrue(any(m.get('method') == 'notifications/tools/list_changed' for m in messages))
            self.assertNotIn('hidden-token', output.getvalue() + errors.getvalue())
            self.assertNotIn('pairing_secret', output.getvalue() + errors.getvalue())
            self.assertEqual('home', post.call_args.args[2]['params']['name'])
            self.assertEqual('hidden-token', json.loads(path.read_text())['token'])

    def test_mcp_initializes_offline_with_saved_pairing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            path.write_text(json.dumps({'url': 'http://127.0.0.1:8443', 'token': 'hidden-token'}))
            incoming = io.StringIO('\n'.join(json.dumps({'jsonrpc': '2.0', 'id': i, 'method': method})
                                            for i, method in enumerate(['initialize', 'tools/list'])))
            output = io.StringIO()
            with patch('sys.stdin', incoming), patch('sys.stdout', output), \
                    patch.object(client, 'post', side_effect=ConnectionRefusedError('offline')) as post:
                client.bridge(client.Connection(path))
            self.assertEqual(1, post.call_count)
            messages = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertIn('capabilities', messages[0]['result'])
            self.assertEqual('phoneuse_connect', messages[1]['result']['tools'][0]['name'])
            self.assertEqual('hidden-token', json.loads(path.read_text())['token'])

    def test_mcp_reconnect_refreshes_tools_even_with_existing_pairing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'phoneuse.json'
            path.write_text(json.dumps({'url': 'http://127.0.0.1:8443', 'token': 'hidden-token'}))
            incoming = io.StringIO(json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                                               'params': {'name': 'phoneuse_connect'}}))
            output = io.StringIO()
            with patch('sys.stdin', incoming), patch('sys.stdout', output), patch.object(client, 'post', return_value={}):
                client.bridge(client.Connection(path))
            messages = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual('notifications/tools/list_changed', messages[1]['method'])


if __name__ == '__main__':
    unittest.main()
