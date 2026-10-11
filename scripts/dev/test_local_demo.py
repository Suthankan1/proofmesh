import importlib.util, json, os, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('demo',Path(__file__).with_name('local-demo.py'))
demo=importlib.util.module_from_spec(spec); spec.loader.exec_module(demo)
class DemoSafetyTests(unittest.TestCase):
    def test_private_isolated_config_is_stable_and_loopback_only(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(demo,'STATE',Path(tmp)/'state'), patch.object(demo,'run',return_value=''):
            prior=os.umask(0o077)
            try:
                first=demo.config(); second=demo.config()
                self.assertEqual(first,second)
                self.assertEqual((demo.STATE/'secrets.json').stat().st_mode & 0o777,0o600)
                self.assertEqual(demo.STATE.stat().st_mode & 0o777,0o700)
                compose=json.loads((demo.STATE/'compose.json').read_text())
                for service in compose['services'].values():
                    for port in service.get('ports',[]): self.assertTrue(port.startswith('127.0.0.1:'))
                url=compose['services']['gateway']['environment']['PROOFMESH_DATABASE_URL']
                self.assertIn('search_path=public',url)
                realm=json.loads((demo.STATE/'proofmesh-realm.json').read_text())
                self.assertTrue(all(not client['directAccessGrantsEnabled'] for client in realm['clients']))
                self.assertEqual(realm['users'][1]['realmRoles'],['VIEWER'])
                self.assertNotIn('ports',compose['services']['tool'])
                self.assertNotIn('volumes',compose['services']['gateway'])
            finally: os.umask(prior)
    def test_incomplete_existing_state_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(demo,'STATE',Path(tmp)):
            file=Path(tmp)/'keep'; file.write_text('user data')
            with self.assertRaises(RuntimeError): demo.config()
            self.assertEqual(file.read_text(),'user data')
    def test_execute_rejects_path_injection_before_file_access(self):
        with self.assertRaises(ValueError): demo.execute({},'../../secret')
if __name__=='__main__': unittest.main()
