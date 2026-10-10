"""Check built gateway/migration images without requiring a shell in either image."""
import argparse
import io
import json
import subprocess
import tarfile


def docker(*args, check=True):
    return subprocess.run(['docker', *args], capture_output=True, check=check)


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def verify(image, binary, excluded):
    config = json.loads(docker('image', 'inspect', image).stdout)[0]['Config']
    require(config['User'] == 'nonroot:nonroot', 'runtime must declare nonroot user')
    require(config['Entrypoint'] == ['/' + binary], 'runtime must directly invoke its binary')
    require(not any(value.startswith('PROOFMESH_') for value in config.get('Env', [])), 'baked runtime configuration')
    container = docker('create', image).stdout.decode().strip()
    try:
        with tarfile.open(fileobj=io.BytesIO(docker('export', container).stdout)) as archive:
            names = set(archive.getnames())
            require(binary in names and excluded not in names, 'runtime artifacts are not isolated')
            require(not any(path in names for path in ['bin/sh', 'usr/bin/go', 'src']), 'unexpected runtime tooling/source')
            certificates = archive.extractfile('etc/ssl/certs/ca-certificates.crt').read()
            require(certificates.count(b'BEGIN CERTIFICATE') > 10, 'CA bundle unavailable')
    finally:
        docker('rm', container)
    smoke = docker('run', '--rm', '--read-only', '--cap-drop=ALL',
                   '--security-opt=no-new-privileges:true', image, check=False)
    output = (smoke.stdout + smoke.stderr).decode()
    require(smoke.returncode == 1, 'missing configuration must fail closed')
    expected = 'gateway startup failed' if binary == 'gateway' else 'gateway migration failed'
    require(expected in output, 'smoke did not reach application configuration validation')
    require(not any(value in output for value in ['panic:', 'shared libraries', 'no such file']), 'unexpected runtime failure')
    history = docker('history', '--no-trunc', image).stdout.decode()
    require(not any(value in history for value in ['PROOFMESH_DATABASE_URL', 'PRIVATE KEY', 'COPY .env']), 'sensitive image history')
    print(binary + ': image isolation, nonroot, exec entrypoint, CA trust and restricted missing-config smoke passed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gateway_image')
    parser.add_argument('migrate_image')
    args = parser.parse_args()
    verify(args.gateway_image, 'gateway', 'migrate')
    verify(args.migrate_image, 'migrate', 'gateway')


if __name__ == '__main__':
    main()
