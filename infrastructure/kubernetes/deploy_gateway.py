"""Render or deploy gateway artifacts with a fail-closed pre-deploy migration gate."""
import argparse
from dataclasses import dataclass
import json
import re
import subprocess
import sys


@dataclass(frozen=True)
class Release:
    context: str
    namespace: str
    release: str
    revision: str
    gateway_image: str
    migrate_image: str

    def __post_init__(self):
        if not self.context.strip() or self.context != self.context.strip():
            raise ValueError('an explicit Kubernetes context is required')
        for value, limit in [(self.namespace, 63), (self.release, 40)]:
            if len(value) > limit or not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]*[a-z0-9])?', value):
                raise ValueError('invalid namespace or release identifier')
        if not re.fullmatch(r'[0-9a-f]{40}', self.revision):
            raise ValueError('revision must be a full Git commit hash')
        for image in [self.gateway_image, self.migrate_image]:
            if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[0-9a-f]{64}', image):
                raise ValueError('both images must use immutable SHA-256 digests')


def reference(variable, kind, name, key):
    return {'name': variable, 'valueFrom': {kind: {'name': name, 'key': key}}}


def pod(image, name, env):
    return {
        'automountServiceAccountToken': False,
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 65532,
                            'runAsGroup': 65532, 'seccompProfile': {'type': 'RuntimeDefault'}},
        'terminationGracePeriodSeconds': 30,
        'containers': [{
            'name': name, 'image': image, 'imagePullPolicy': 'IfNotPresent', 'env': env,
            'securityContext': {'allowPrivilegeEscalation': False,
                                'readOnlyRootFilesystem': True,
                                'capabilities': {'drop': ['ALL']}},
            'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'},
                          'limits': {'cpu': '1', 'memory': '256Mi'}},
        }],
    }


def resources(release):
    annotations = {'proofmesh.io/revision': release.revision,
                   'proofmesh.io/release': release.release}
    migration = pod(release.migrate_image, 'migrate', [reference(
        'PROOFMESH_DATABASE_URL', 'secretKeyRef', 'proofmesh-migration-db', 'database-url')])
    migration['restartPolicy'] = 'Never'
    job = {
        'apiVersion': 'batch/v1', 'kind': 'Job',
        'metadata': {'name': 'proofmesh-migrate-' + release.release,
                     'namespace': release.namespace, 'annotations': annotations},
        'spec': {'backoffLimit': 0, 'activeDeadlineSeconds': 90,
                 'ttlSecondsAfterFinished': 86400,
                 'template': {'metadata': {'annotations': annotations}, 'spec': migration}},
    }
    env = [reference('PROOFMESH_DATABASE_URL', 'secretKeyRef',
                     'proofmesh-gateway-db', 'database-url')]
    for key in ['PROOFMESH_EXECUTION_GRANT_ISSUER', 'PROOFMESH_EXECUTION_GRANT_AUDIENCE',
                'PROOFMESH_JWKS_URL', 'PROOFMESH_TOOL_TARGETS_JSON']:
        env.append(reference(key, 'configMapKeyRef', 'proofmesh-gateway-config', key))
    gateway = pod(release.gateway_image, 'gateway', env)
    container = gateway['containers'][0]
    container['ports'] = [{'name': 'http', 'containerPort': 8080}]
    container['readinessProbe'] = {'httpGet': {'path': '/readyz', 'port': 'http'},
                                   'periodSeconds': 5, 'timeoutSeconds': 3}
    container['livenessProbe'] = {'httpGet': {'path': '/healthz', 'port': 'http'},
                                  'periodSeconds': 10, 'timeoutSeconds': 3}
    container['startupProbe'] = {'httpGet': {'path': '/healthz', 'port': 'http'},
                                 'periodSeconds': 2, 'failureThreshold': 30}
    labels = {'app.kubernetes.io/name': 'proofmesh-gateway'}
    deployment = {
        'apiVersion': 'apps/v1', 'kind': 'Deployment',
        'metadata': {'name': 'proofmesh-gateway', 'namespace': release.namespace,
                     'annotations': annotations},
        'spec': {'replicas': 2, 'progressDeadlineSeconds': 180,
                 'strategy': {'type': 'RollingUpdate', 'rollingUpdate': {'maxUnavailable': 0, 'maxSurge': 1}},
                 'selector': {'matchLabels': labels},
                 'template': {'metadata': {'labels': labels, 'annotations': annotations}, 'spec': gateway}},
    }
    service = {
        'apiVersion': 'v1', 'kind': 'Service',
        'metadata': {'name': 'proofmesh-gateway', 'namespace': release.namespace},
        'spec': {'type': 'ClusterIP', 'selector': labels,
                 'ports': [{'port': 8080, 'targetPort': 'http', 'name': 'http'}]},
    }
    return job, deployment, service


def kubectl(release):
    def run(args, document=None):
        subprocess.run(
            ['kubectl', '--context=' + release.context, '--namespace=' + release.namespace,
             '--request-timeout=15s', *args],
            input=json.dumps(document) if document is not None else None,
            text=True, check=True, timeout=180,
        )
    return run


def execute(release, run):
    job, deployment, service = resources(release)
    # Create rather than apply: an old successful Job cannot authorize this release.
    run(['create', '-f', '-'], job)
    run(['wait', '--for=condition=complete', 'job/' + job['metadata']['name'], '--timeout=120s'])
    run(['apply', '-f', '-'], {'apiVersion': 'v1', 'kind': 'List', 'items': [deployment, service]})
    run(['rollout', 'status', 'deployment/proofmesh-gateway', '--timeout=120s'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ['context', 'namespace', 'release', 'revision', 'gateway-image', 'migrate-image']:
        parser.add_argument('--' + field, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--render', action='store_true', help='print resources without contacting Kubernetes')
    mode.add_argument('--deploy', action='store_true', help='create migration Job, then gate gateway rollout')
    args = parser.parse_args()
    try:
        release = Release(args.context, args.namespace, args.release, args.revision,
                          args.gateway_image, args.migrate_image)
    except ValueError as error:
        parser.error(str(error))
    if args.render:
        print(json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': resources(release)}, indent=2))
        return 0
    try:
        execute(release, kubectl(release))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
        print('gateway deployment stopped; inspect the migration Job and rollout status', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
