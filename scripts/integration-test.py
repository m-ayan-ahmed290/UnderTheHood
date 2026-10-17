"""Deterministic end-to-end companion + real Rclone local-backend test."""
import hashlib,json,os,pathlib,shutil,subprocess,tempfile,time,urllib.request,urllib.error
root=pathlib.Path(__file__).resolve().parent.parent
binary=os.environ.get('UTH_RCLONE') or shutil.which('rclone')
if not binary:
 source=json.loads(subprocess.check_output(['go','mod','download','-json','github.com/rclone/rclone@v1.75.2']))['Dir']
 binary=str(root/'target'/'rclone-test')
 subprocess.run(['go','build','-trimpath','-o',binary,'.'],cwd=source,check=True)
subprocess.run(['cargo','build','--locked','-p','hood-companion'],cwd=root,check=True)
with tempfile.TemporaryDirectory() as temp:
 data=pathlib.Path(temp);cloud=data/'cloud';cloud.mkdir();(cloud/'existing.txt').write_text('existing cloud file')
 subprocess.run([binary,'config','create','fixture','local','--config',str(data/'rclone.conf'),'--non-interactive'],check=True,stdout=subprocess.DEVNULL)
 env={**os.environ,'UTH_DATA':temp,'UTH_RCLONE':binary,'UTH_WEB_ASSETS':str(root/'apps/web/dist'),'UTH_NO_BROWSER':'1'}
 process=subprocess.Popen([str(root/'target/debug/hood-companion')],env=env,stdout=subprocess.PIPE,text=True)
 try:
  line=process.stdout.readline();token=line.split('#',1)[1].split(' ',1)[0]
  def request(path,body=None,headers=None,raw=False,expect=200):
   h={'Authorization':'Bearer '+token,**(headers or {})};payload=body
   if body is not None and not isinstance(body,bytes):payload=json.dumps(body).encode();h['Content-Type']='application/json'
   req=urllib.request.Request('http://127.0.0.1:47651/v1/'+path,data=payload,headers=h)
   try:
    with urllib.request.urlopen(req,timeout=60) as response: assert response.status==expect;return response.read() if raw else json.load(response)
   except urllib.error.HTTPError as error: assert error.code==expect,(error.code,error.read());return None
  for _ in range(100):
   try:request('health');break
   except (ConnectionError,urllib.error.URLError):time.sleep(.05)
  request('nodes',headers={'Origin':'https://evil.example'},expect=403)
  request('nodes',headers={'Host':'evil.example:47651'},expect=403)
  request('nodes',headers={'Authorization':'Bearer invalid'},expect=403)
  assert request('remotes')['remotes']==['fixture']
  folder={'id':'test-folder','parent':'root','name':'大学 Projects','folder':True,'remote':None,'path':None,'size':0,'mime':'inode/directory','starred':False,'trash':False,'modified':1}
  request('nodes',folder);request('nodes',{**folder,'id':'duplicate'},expect=400)
  request('nodes',{**folder,'parent':'test-folder'},expect=400)
  request('nodes',{**folder,'id':'bad','name':'../unsafe'},expect=400)
  # The Rclone local backend test destination is the temporary account root.
  # Use a configured alias remote to expose a bounded folder, avoiding app CWD writes.
  subprocess.run([binary,'config','create','bounded','alias','remote',str(cloud),'--config',str(data/'rclone.conf'),'--non-interactive'],check=True,stdout=subprocess.DEVNULL)
  listed=request('files?remote=bounded&path=');assert listed[0]['Name']=='existing.txt'
  fixture=b'UnderTheHood streamed integration fixture\x00'*10000
  node=request('upload?remote=bounded&parent=test-folder&name=fixture.bin',fixture)
  assert hashlib.sha256(request('download?id='+node['id'],raw=True)).digest()==hashlib.sha256(fixture).digest()
  nodes=request('nodes');assert len(nodes)==2 and nodes[1 if nodes[0]['folder'] else 0]['folder'] is False
  snapshot=request('snapshot');assert snapshot['version']==1 and len(snapshot['nodes'])==2
  request('restore',snapshot,expect=400)
  request('files?remote=bounded&path=..%2Fsecret',expect=400)
  def wait_setup(predicate):
   for _ in range(300):
    state=request('setup')
    if predicate(state):return state
    if state['status']=='failed':raise AssertionError(state['message'])
    time.sleep(.05)
   raise AssertionError('Provider setup did not advance')
  request('connect',{'name':'  Local Account.v2  ','provider':'local','options':{}})
  wait_setup(lambda s:s['status']=='connected')
  assert 'Local Account.v2' in request('remotes')['remotes']
  request('connect',{'name':'Local Account.v2','provider':'local','options':{}},expect=400)
  request('connect',{'name':'invalid:name','provider':'drive','options':{}},expect=400)
  for provider,name in [('drive','M. Ayan Drive'),('onedrive','A.A OneDrive'),('drive','M. Ayan Drive')]:
   request('connect',{'name':name,'provider':provider,'options':{}})
   state=wait_setup(lambda s:bool(s.get('auth_url')) or s['status']=='question')
   while state['status']=='question':
    q=state['question'];answer=q.get('DefaultStr','')
    if not answer and q.get('Examples'):answer=q['Examples'][0]['Value']
    request('setup/answer',{'answer':answer})
    state=wait_setup(lambda s:bool(s.get('auth_url')) or s['status']=='question')
   assert state['status']=='authorizing',state
   assert state['auth_url'].startswith('http://127.0.0.1:53682/auth?state=')
   request('setup/cancel',{})
   wait_setup(lambda s:s['status']=='cancelled')
  print('PASS: reported account names, local connection completion, Google Drive/OneDrive OAuth initiation, cancellation and retry without false connected status')
  print('PASS: real Rclone listing/upload/download, SHA-256 round trip, VFS constraints, snapshot and browser security boundaries')
 finally:
  process.terminate();process.wait(timeout=10)
