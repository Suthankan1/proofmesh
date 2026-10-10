import json
import subprocess
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import deploy_gateway as deploy

DIGEST = 'sha256:' + 'a' * 64


def options(**changes):
    values = dict(context='test-context', namespace='proofmesh', release='r1',
                  revision='b' * 40, gateway_image='registry/gateway@' + DIGEST,
                  migrate_image='registry/migrate@' + DIGEST)
    values.update(changes)
    return deploy.Release(**values)


class ReleaseTests(unittest.TestCase):
    def test_rejects_mutable_images_and_invalid_release_before_kubectl(self):
        for changes in [dict(gateway_image='gateway:latest'), dict(migrate_image='migrate:v1'),
                        dict(release='../r1'), dict(namespace='bad namespace'),
                        dict(revision='main'), dict(context='')]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                options(**changes)

    def test_gate_precedes_rollout_and_uses_explicit_context_namespace(self):
        calls = []
        deploy.execute(options(), lambda args, document=None: calls.append((args, document)))
        self.assertEqual([c[0][0] for c in calls], ['create', 'wait', 'apply', 'rollout'])
        self.assertEqual(calls[0][1]['kind'], 'Job')
        self.assertIn('job/proofmesh-migrate-r1', calls[1][0])
        self.assertIn('--for=condition=complete', calls[1][0])
        self.assertEqual([x['kind'] for x in calls[2][1]['items']], ['Deployment', 'Service'])
        with patch('subprocess.run') as run:
            deploy.kubectl(options())(['wait', 'job/test'])
        self.assertEqual(run.call_args.args[0][:5],
                         ['kubectl', '--context=test-context', '--namespace=proofmesh',
                          '--request-timeout=15s', 'wait'])
        self.assertTrue(run.call_args.kwargs['check'])

    def test_any_failure_stops_before_next_step(self):
        for failure in range(4):
            calls = []
            def run(args, document=None):
                calls.append(args)
                if len(calls) == failure + 1:
                    raise subprocess.CalledProcessError(1, 'kubectl')
            with self.subTest(failure=failure), self.assertRaises(subprocess.CalledProcessError):
                deploy.execute(options(), run)
            self.assertEqual(len(calls), failure + 1)
            if failure < 2:
                self.assertFalse(any(args[0] == 'apply' for args in calls))

    def test_job_and_runtime_least_privilege_and_credential_isolation(self):
        job, deployment, service = deploy.resources(options())
        self.assertEqual(job['spec']['backoffLimit'], 0)
        self.assertEqual(job['spec']['activeDeadlineSeconds'], 90)
        self.assertEqual(job['spec']['template']['spec']['restartPolicy'], 'Never')
        for obj in [job, deployment]:
            pod = obj['spec']['template']['spec']
            self.assertFalse(pod['automountServiceAccountToken'])
            self.assertEqual(pod['securityContext']['runAsUser'], 65532)
            self.assertTrue(pod['securityContext']['runAsNonRoot'])
            container = pod['containers'][0]
            self.assertTrue(container['securityContext']['readOnlyRootFilesystem'])
            self.assertFalse(container['securityContext']['allowPrivilegeEscalation'])
            self.assertEqual(container['securityContext']['capabilities']['drop'], ['ALL'])
            self.assertNotIn('command', container)
            self.assertNotIn('initContainers', pod)
        migration_env = job['spec']['template']['spec']['containers'][0]['env']
        runtime_env = deployment['spec']['template']['spec']['containers'][0]['env']
        self.assertEqual(len(migration_env), 1)
        self.assertEqual(migration_env[0]['valueFrom']['secretKeyRef']['name'], 'proofmesh-migration-db')
        self.assertEqual(runtime_env[0]['valueFrom']['secretKeyRef']['name'], 'proofmesh-gateway-db')
        self.assertTrue(all('valueFrom' in env for env in runtime_env))
        gateway = deployment['spec']['template']['spec']['containers'][0]
        self.assertEqual(gateway['readinessProbe']['httpGet']['path'], '/readyz')
        self.assertEqual(gateway['livenessProbe']['httpGet']['path'], '/healthz')
        self.assertEqual(service['spec']['type'], 'ClusterIP')
        self.assertEqual(deployment['spec']['template']['metadata']['annotations']['proofmesh.io/revision'], 'b' * 40)

    def test_cli_render_and_real_subprocess_failure_gate(self):
        script = str(Path(__file__).with_name('deploy_gateway.py'))
        arguments = [sys.executable, script, '--context', 'test-context',
                     '--namespace', 'proofmesh', '--release', 'r1', '--revision', 'b' * 40,
                     '--gateway-image', 'registry/gateway@' + DIGEST,
                     '--migrate-image', 'registry/migrate@' + DIGEST]
        rendered = subprocess.run(arguments + ['--render'], capture_output=True, text=True, check=True)
        self.assertEqual(len(json.loads(rendered.stdout)['items']), 3)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fake = root / 'kubectl'
            fake.write_text('#!' + sys.executable + "\n" +
                            "import json,os,sys\n" +
                            "with open(os.environ['CALL_LOG'],'a') as log: log.write(json.dumps(sys.argv[1:])+'\\n')\n" +
                            "sys.exit(1 if 'wait' in sys.argv and os.environ.get('FAIL_WAIT')=='1' else 0)\n")
            fake.chmod(0o700)
            log = root / 'calls.jsonl'
            env = dict(os.environ, PATH=temp + os.pathsep + os.environ['PATH'],
                       CALL_LOG=str(log), FAIL_WAIT='1')
            failed = subprocess.run(arguments + ['--deploy'], env=env, capture_output=True, text=True)
            self.assertEqual(failed.returncode, 1)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([call[3] for call in calls], ['create', 'wait'])
            self.assertIn('deployment stopped', failed.stderr)
            log.unlink()
            env['FAIL_WAIT'] = '0'
            subprocess.run(arguments + ['--deploy'], env=env, capture_output=True, text=True, check=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([call[3] for call in calls], ['create', 'wait', 'apply', 'rollout'])

    def test_subprocess_receives_json_without_shell_or_secrets_on_command_line(self):
        job = deploy.resources(options())[0]
        with patch('subprocess.run') as run:
            deploy.kubectl(options())(['create', '-f', '-'], job)
        self.assertEqual(json.loads(run.call_args.kwargs['input']), job)
        self.assertNotIn('shell', run.call_args.kwargs)
        self.assertEqual(run.call_args.kwargs['timeout'], 180)


if __name__ == '__main__':
    unittest.main()
