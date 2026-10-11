#!/usr/bin/env python3
"""Isolated LOCAL demo. No cloud actions, destructive cleanup, or existing env edits."""
import argparse, hashlib, json, os, secrets, signal, subprocess, time, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
STATE=ROOT/'.local-demo'
PROJECT='proofmesh-local-demo'
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def run(args, cwd=ROOT, env=None, input=None):
    result=subprocess.run(args,cwd=cwd,env=env,input=input,text=True,capture_output=True,timeout=900)
    if result.returncode:
        if STATE.exists():
            path=STATE/'last-command-error.log'; path.write_text(result.stdout+'\n'+result.stderr); path.chmod(0o600)
        raise subprocess.CalledProcessError(result.returncode,args)
    return result.stdout
def compose(*args): return run(['docker','compose','-p',PROJECT,'-f',str(STATE/'compose.json'),*args])
def sql(text): return run(['docker','compose','-p',PROJECT,'-f',str(STATE/'compose.json'),'exec','-T','postgres','psql','-U','proofmesh','-d','proofmesh','-v','ON_ERROR_STOP=1'],input=text)
def private(path,content):
    with open(path,'x') as file: file.write(content)
    path.chmod(0o600)
def wait(url,timeout=180):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            with OPENER.open(url,timeout=2) as response:
                if response.status==200: return
        except (OSError,urllib.error.URLError): pass
        time.sleep(1)
    raise RuntimeError('Local service did not become ready: '+url)
def config():
    if (STATE/'secrets.json').exists(): return json.loads((STATE/'secrets.json').read_text())
    if STATE.exists(): raise RuntimeError('Existing incomplete demo state preserved; inspect it before continuing')
    STATE.mkdir(mode=0o700); os.umask(0o077)
    c={key:secrets.token_urlsafe(32) for key in ['dbPassword','webSecret','authSecret','adminPassword','operatorPassword','viewerPassword']}
    c['organizationId']=str(uuid.uuid4()); c['agentId']=str(uuid.uuid4()); c['agentSecret']='pm_agent_'+secrets.token_urlsafe(32)
    c['subjects']={name:str(uuid.uuid4()) for name in ['operator','viewer']}
    private(STATE/'secrets.json',json.dumps(c,indent=2)+'\n')
    run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256','-out',str(STATE/'private.pem')])
    run(['openssl','pkey','-in',str(STATE/'private.pem'),'-pubout','-out',str(STATE/'public.pem')])
    realm=json.loads((ROOT/'infrastructure/keycloak/import/proofmesh-realm.json').read_text())
    web=next(x for x in realm['clients'] if x['clientId']=='proofmesh-web')
    web['secret']=c['webSecret']; web['redirectUris']=['http://localhost:13000/api/auth/callback/keycloak']; web['webOrigins']=['http://localhost:13000']; web['attributes']['post.logout.redirect.uris']='http://localhost:13000/*'
    realm['users']=[{'id':c['subjects'][name],'username':name+'@proofmesh.local','email':name+'@proofmesh.local','firstName':'Demo','lastName':name.title(),'enabled':True,'emailVerified':True,'credentials':[{'type':'password','value':c[name+'Password'],'temporary':False}],'realmRoles':(['PLATFORM_ADMIN','APPROVER','SECURITY_OPERATOR'] if name=='operator' else ['VIEWER'])} for name in ['operator','viewer']]
    private(STATE/'proofmesh-realm.json',json.dumps(realm))
    db={'image':'postgres:18.6-alpine','environment':{'POSTGRES_DB':'proofmesh','POSTGRES_USER':'proofmesh','POSTGRES_PASSWORD':c['dbPassword']},'ports':['127.0.0.1:55440:5432'],'volumes':['demo-db:/var/lib/postgresql'],'healthcheck':{'test':['CMD-SHELL','pg_isready -U proofmesh -d proofmesh'],'interval':'2s','timeout':'2s','retries':60}}
    kc={'image':'quay.io/keycloak/keycloak:26.7.2','command':['start-dev','--import-realm'],'environment':{'KC_BOOTSTRAP_ADMIN_USERNAME':'admin','KC_BOOTSTRAP_ADMIN_PASSWORD':c['adminPassword']},'ports':['127.0.0.1:18081:8080'],'volumes':[str(STATE/'proofmesh-realm.json')+':/opt/keycloak/data/import/proofmesh-realm.json:ro','demo-keycloak:/opt/keycloak/data'],'depends_on':{'postgres':{'condition':'service_healthy'}}}
    gateway={'build':{'context':str(ROOT/'apps/gateway'),'target':'gateway-runtime'},'read_only':True,'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'ports':['127.0.0.1:19090:8080'],'environment':{'PROOFMESH_DATABASE_URL':'postgres://proofmesh:'+c['dbPassword']+'@postgres:5432/proofmesh?sslmode=disable&search_path=public','PROOFMESH_EXECUTION_GRANT_ISSUER':'proofmesh-control-plane','PROOFMESH_EXECUTION_GRANT_AUDIENCE':'proofmesh-gateway','PROOFMESH_JWKS_URL':'http://host.docker.internal:18080/.well-known/jwks.json','PROOFMESH_TOOL_TARGETS_JSON':json.dumps([{'tool_name':'payments','operation_name':'refund','url':'http://tool:9000/refund'}])},'depends_on':['postgres','tool']}
    tool={'image':'python:3.14-alpine','command':['python','/tool.py'],'read_only':True,'user':'65532:65532','cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'volumes':[str(ROOT/'scripts/dev/mock-tool.py')+':/tool.py:ro']}
    private(STATE/'compose.json',json.dumps({'services':{'postgres':db,'keycloak':kc,'gateway':gateway,'tool':tool},'volumes':{'demo-db':{},'demo-keycloak':{}}},indent=2))
    return c

def environment(c):
    env=os.environ.copy(); env.update({'BETTER_AUTH_URL':'http://localhost:13000','BETTER_AUTH_SECRET':c['authSecret'],'PROOFMESH_AUTH_DB_HOST':'127.0.0.1','PROOFMESH_AUTH_DB_PORT':'55440','PROOFMESH_AUTH_DB_NAME':'proofmesh','PROOFMESH_AUTH_DB_USER':'proofmesh','PROOFMESH_AUTH_DB_PASSWORD':c['dbPassword'],'KEYCLOAK_ISSUER':'http://localhost:18081/realms/proofmesh','KEYCLOAK_WEB_CLIENT_ID':'proofmesh-web','KEYCLOAK_WEB_CLIENT_SECRET':c['webSecret'],'CONTROL_PLANE_BASE_URL':'http://localhost:18080','SPRING_DATASOURCE_URL':'jdbc:postgresql://127.0.0.1:55440/proofmesh','SPRING_DATASOURCE_USERNAME':'proofmesh','SPRING_DATASOURCE_PASSWORD':c['dbPassword'],'OIDC_ISSUER_URI':'http://localhost:18081/realms/proofmesh','OIDC_JWK_SET_URI':'http://localhost:18081/realms/proofmesh/protocol/openid-connect/certs','PROOFMESH_EXECUTION_GRANT_SIGNING_ENABLED':'true','PROOFMESH_EXECUTION_GRANT_SIGNING_KEY_ID':'local-demo','PROOFMESH_EXECUTION_GRANT_SIGNING_PRIVATE_KEY_PATH':str(STATE/'private.pem'),'PROOFMESH_EXECUTION_GRANT_SIGNING_PUBLIC_KEY_PATH':str(STATE/'public.pem'),'PROOFMESH_DATABASE_URL':'postgres://proofmesh:'+c['dbPassword']+'@127.0.0.1:55440/proofmesh?sslmode=disable&search_path=public'})
    return env

def seed(c):
    org,agent=c['organizationId'],c['agentId']
    statements=[f"INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES ('{org}','local-demo','ProofMesh Local Demo','ACTIVE') ON CONFLICT DO NOTHING;",f"INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES ('{agent}','{org}','Demo refund agent','ACTIVE') ON CONFLICT DO NOTHING;"]
    for name,subject in c['subjects'].items():
        user=str(uuid.uuid5(uuid.NAMESPACE_URL,'proofmesh-demo:'+subject))
        statements.extend([f"INSERT INTO proofmesh.operator_users(id,oidc_subject,display_name,email,status) VALUES ('{user}','{subject}','Demo {name}','{name}@proofmesh.local','ACTIVE') ON CONFLICT DO NOTHING;",f"INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES ('{org}','{user}','ACTIVE') ON CONFLICT DO NOTHING;"])
    credential=str(uuid.uuid5(uuid.NAMESPACE_URL,agent)); digest=hashlib.sha256(c['agentSecret'].encode()).hexdigest()
    statements.append(f"INSERT INTO proofmesh.agent_credentials(id,organization_id,agent_id,credential_hash,created_at,expires_at) VALUES ('{credential}','{org}','{agent}','{digest}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '30 days') ON CONFLICT DO NOTHING;")
    policy=str(uuid.uuid5(uuid.NAMESPACE_URL,org)); version=str(uuid.uuid5(uuid.NAMESPACE_URL,policy)); rule=str(uuid.uuid5(uuid.NAMESPACE_URL,version)); binding=str(uuid.uuid5(uuid.NAMESPACE_URL,version+'binding'))
    definition=json.dumps({'rules':[{'id':rule,'priority':100,'target':{'tool':'payments','operation':'refund'},'risk':{'minimum':0},'effect':'REQUIRE_APPROVAL','reasonCode':'LOCAL_DEMO'}]},sort_keys=True,separators=(',',':'))
    digest=hashlib.sha256(definition.encode()).hexdigest()
    statements.extend([f"INSERT INTO proofmesh.policies(id,organization_id,name) VALUES ('{policy}','{org}','Demo approval policy') ON CONFLICT DO NOTHING;",f"INSERT INTO proofmesh.policy_versions(id,policy_id,organization_id,version_number,state,definition,definition_hash,published_at) VALUES ('{version}','{policy}','{org}',1,'PUBLISHED','{definition}'::jsonb,'{digest}',CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;",f"INSERT INTO proofmesh.agent_policy_bindings(id,organization_id,agent_id,policy_version_id,activated_at) VALUES ('{binding}','{org}','{agent}','{version}',CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;"])
    sql('BEGIN;\n'+'\n'.join(statements)+'\nCOMMIT;')

def up(c,dev=False):
    import socket
    for port in (55440,18081,18080,13000,19090):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port)) == 0: raise RuntimeError('Demo port already occupied: '+str(port)+'; existing services preserved')
    if run(['node','--version']).strip().split('.')[0]!='v24': raise RuntimeError('Select Node 24 on PATH before starting the demo')
    env=environment(c); children=[]
    def start(name,args,cwd=ROOT):
        log=open(STATE/(name+'.log'),'a'); os.chmod(STATE/(name+'.log'),0o600)
        proc=subprocess.Popen(args,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT); children.append((proc,log)); return proc
    def stop(*_): raise KeyboardInterrupt
    signal.signal(signal.SIGTERM,stop)
    try:
        print('Starting isolated local infrastructure...',flush=True); compose('up','-d','postgres','keycloak')
        wait('http://localhost:18081/realms/proofmesh/.well-known/openid-configuration')
        sql('CREATE SCHEMA IF NOT EXISTS proofmesh_web_auth;')
        print('Building control plane and gateway...',flush=True)
        run(['./mvnw','--batch-mode','--no-transfer-progress','-DskipTests','package'],ROOT/'apps/control-plane',env)
        run(['go','build','-o',str(STATE/'migrate'),'./cmd/migrate'],ROOT/'apps/gateway',env); run([str(STATE/'migrate')],env=env)
        start('control-plane',['java','-jar',str(ROOT/'apps/control-plane/target/control-plane-0.0.1-SNAPSHOT.jar'),'--server.port=18080','--server.address=127.0.0.1'])
        wait('http://localhost:18080/actuator/health'); seed(c)
        run(['node','scripts/migrate-auth.mjs'],ROOT/'apps/web',env)
        if not dev: run(['node','node_modules/next/dist/bin/next','build'],ROOT/'apps/web',env)
        start('web',['node','node_modules/next/dist/bin/next','dev' if dev else 'start','--hostname','127.0.0.1','--port','13000'],ROOT/'apps/web')
        compose('up','-d','--build','gateway','tool'); wait('http://localhost:19090/readyz'); wait('http://localhost:13000/sign-in')
        print('Demo ready: http://localhost:13000 — credentials: python3 scripts/dev/local-demo.py credentials',flush=True)
        while all(p.poll() is None for p,_ in children): time.sleep(1)
        raise RuntimeError('A local process exited; inspect private .local-demo logs')
    finally:
        for proc,log in reversed(children):
            if proc.poll() is None: proc.terminate()
            try: proc.wait(timeout=15)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
            log.close()
        compose('stop')

def execute(c,action_id):
    action_id=str(uuid.UUID(action_id))
    saved=json.loads((STATE/('action-'+action_id+'.json')).read_text())
    def submit():
        req=urllib.request.Request('http://localhost:18080/api/v1/runtime/actions',data=json.dumps(saved['request']).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+c['agentSecret']})
        with OPENER.open(req,timeout=10) as response: return json.load(response)
    before=json.loads(compose('exec','-T','tool','python','-c',"import urllib.request; print(urllib.request.urlopen('http://localhost:9000').read().decode())"))['calls']
    result=submit()
    if not result.get('executionGrant'): raise RuntimeError('No live grant: '+result['authorityStatus']+'. Authority cannot be reminted; submit a fresh action only when appropriate.')
    envelope={key:result[value] for key,value in [('organization_id','organizationId'),('agent_id','agentId'),('governed_action_id','governedActionId'),('governance_decision_id','governanceDecisionId')]}
    envelope.update(tool_name=saved['request']['toolName'],operation_name=saved['request']['operationName'],payload=json.loads(saved['request']['requestPayloadJson']))
    req=urllib.request.Request('http://localhost:19090/v1/executions',data=json.dumps(envelope).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+result['executionGrant']})
    with OPENER.open(req,timeout=15) as response: receipt=json.load(response)
    if receipt['calls']!=before+1: raise RuntimeError('Tool invocation count was not exactly one')
    try: OPENER.open(req,timeout=10)
    except urllib.error.HTTPError as error:
        if error.code!=409: raise
    else: raise RuntimeError('Replay was not rejected')
    after=json.loads(compose('exec','-T','tool','python','-c',"import urllib.request; print(urllib.request.urlopen('http://localhost:9000').read().decode())"))['calls']
    if after!=receipt['calls']: raise RuntimeError('Replay invoked the tool again')
    remint=submit()
    if remint.get('executionGrant') or remint['authorityStatus']!='ALREADY_ISSUED': raise RuntimeError('Unexpected reminted authority')
    print(json.dumps({'actionId':action_id,'receipt':receipt,'replayStatus':409,'remintStatus':remint['authorityStatus']},indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('command',choices=['up','credentials','submit','execute']); parser.add_argument('action_id',nargs='?'); parser.add_argument('--dev',action='store_true',help='Use the development web server instead of build/start'); args=parser.parse_args(); os.umask(0o077)
    try:
        if args.command != 'up' and not (STATE/'secrets.json').exists(): raise RuntimeError('Start the demo first')
        c=config()
        if args.command=='up': up(c,args.dev)
        elif args.command=='credentials': print('Operator: operator@proofmesh.local / '+c['operatorPassword']+'\nViewer: viewer@proofmesh.local / '+c['viewerPassword'])
        elif args.command=='execute':
            if not args.action_id: parser.error('execute requires an action UUID')
            execute(c,args.action_id)
        elif args.command=='submit':
            request={'idempotencyKey':str(uuid.uuid4()),'toolName':'payments','operationName':'refund','requestPayloadJson':'{"amount":25,"currency":"USD","reference":"local-demo"}'}
            req=urllib.request.Request('http://localhost:18080/api/v1/runtime/actions',data=json.dumps(request).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+c['agentSecret']})
            with OPENER.open(req,timeout=10) as response: result=json.load(response)
            private(STATE/('action-'+result['governedActionId']+'.json'),json.dumps({'request':request,'result':{k:v for k,v in result.items() if k!='executionGrant'}}))
            print(json.dumps({k:v for k,v in result.items() if k!='executionGrant'},indent=2))
    except KeyboardInterrupt: print('Demo stopped; all local volumes and evidence retained.')
    except subprocess.CalledProcessError as error:
        # Do not echo command/env/SQL containing credentials.
        print('Local demo command failed; exit '+str(error.returncode),flush=True); raise SystemExit(1)
